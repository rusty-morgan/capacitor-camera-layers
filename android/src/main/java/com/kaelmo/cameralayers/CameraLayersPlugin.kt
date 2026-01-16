package com.kaelmo.cameralayers

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*

@CapacitorPlugin(
    name = "CameraLayers",
    permissions = [
        Permission(strings = [Manifest.permission.CAMERA], alias = "camera"),
        Permission(strings = [Manifest.permission.WRITE_EXTERNAL_STORAGE], alias = "storage")
    ]
)
class CameraLayersPlugin : Plugin() {
    private var cameraView: LayeredCameraView? = null
    private val layers = mutableMapOf<String, CameraLayer>()

    @PluginMethod
    fun start(call: PluginCall) {
        if (!hasRequiredPermissions()) {
            requestAllPermissions(call, "cameraPermissionsCallback")
            return
        }

        startCameraInternal(call)
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        bridge.executeOnMainThread {
            cameraView?.stopCamera()
            
            val parent = cameraView?.parent as? ViewGroup
            parent?.removeView(cameraView)
            
            cameraView = null
            call.resolve()
        }
    }

    @PluginMethod
    fun capture(call: PluginCall) {
        val cameraView = this.cameraView
        if (cameraView == null) {
            call.reject("Camera not started")
            return
        }

        val quality = call.getInt("quality", 85)
        val outputType = call.getString("outputType", "uri")
        val saveToGallery = call.getBoolean("saveToGallery", false)!!

        cameraView.capturePhoto(quality, layers.values.toList()) { result, error ->
            if (error != null) {
                call.reject("Failed to capture photo: ${error.message}")
                return@capturePhoto
            }

            if (result == null) {
                call.reject("Failed to capture photo")
                return@capturePhoto
            }

            val response = JSObject()
            response.put("width", result.width)
            response.put("height", result.height)

            when (outputType) {
                "base64" -> response.put("base64", result.base64)
                "uri" -> response.put("uri", result.uri)
                "both" -> {
                    response.put("base64", result.base64)
                    response.put("uri", result.uri)
                }
            }

            if (saveToGallery && result.bitmap != null) {
                // Save to gallery implementation
                saveToGallery(result.bitmap)
            }

            call.resolve(response)
        }
    }

    @PluginMethod
    fun addLayer(call: PluginCall) {
        val layerData = call.data
        val layerId = layerData.optString("id", UUID.randomUUID().toString())

        val layer = parseLayer(layerId, layerData) ?: run {
            call.reject("Invalid layer data")
            return
        }

        layers[layerId] = layer

        bridge.executeOnMainThread {
            cameraView?.updateLayers(layers.values.toList())
        }

        val result = JSObject()
        result.put("layerId", layerId)
        call.resolve(result)
    }

    @PluginMethod
    fun updateLayer(call: PluginCall) {
        val layerId = call.getString("layerId")
        if (layerId == null) {
            call.reject("Layer ID required")
            return
        }

        val existingLayer = layers[layerId]
        if (existingLayer == null) {
            call.reject("Layer not found")
            return
        }

        val updates = call.getObject("layer")
        val updatedLayer = mergeLayer(existingLayer, updates)
        layers[layerId] = updatedLayer

        bridge.executeOnMainThread {
            cameraView?.updateLayers(layers.values.toList())
        }

        call.resolve()
    }

    @PluginMethod
    fun removeLayer(call: PluginCall) {
        val layerId = call.getString("layerId")
        if (layerId == null) {
            call.reject("Layer ID required")
            return
        }

        layers.remove(layerId)

        bridge.executeOnMainThread {
            cameraView?.updateLayers(layers.values.toList())
        }

        call.resolve()
    }

    @PluginMethod
    fun clearLayers(call: PluginCall) {
        layers.clear()

        bridge.executeOnMainThread {
            cameraView?.updateLayers(emptyList())
        }

        call.resolve()
    }

    @PluginMethod
    fun switchCamera(call: PluginCall) {
        bridge.executeOnMainThread {
            cameraView?.switchCamera { error ->
                if (error != null) {
                    call.reject("Failed to switch camera: ${error.message}")
                } else {
                    call.resolve()
                }
            }
        }
    }

    @PluginMethod
    fun setFlashMode(call: PluginCall) {
        val mode = call.getString("mode", "off")

        bridge.executeOnMainThread {
            cameraView?.setFlashMode(mode)
            call.resolve()
        }
    }

    @PluginMethod
    fun setZoom(call: PluginCall) {
        val zoom = call.getFloat("zoom")
        if (zoom == null) {
            call.reject("Zoom level required")
            return
        }

        bridge.executeOnMainThread {
            cameraView?.setZoom(zoom)
            call.resolve()
        }
    }

    @PluginMethod
    fun setFocus(call: PluginCall) {
        val x = call.getFloat("x")
        val y = call.getFloat("y")

        if (x == null || y == null) {
            call.reject("Focus coordinates required")
            return
        }

        bridge.executeOnMainThread {
            cameraView?.setFocus(x, y)
            call.resolve()
        }
    }

    // Helper methods

    private fun startCameraInternal(call: PluginCall) {
        bridge.executeOnMainThread {
            val position = call.getString("position", "back")
            val width = call.getInt("width", bridge.webView.width)
            val height = call.getInt("height", 480)
            val x = call.getInt("x", 0)
            val y = call.getInt("y", 0)
            val toBack = call.getBoolean("toBack", false)!!
            val enableZoom = call.getBoolean("enableZoom", true)!!
            val enableFocus = call.getBoolean("enableFocus", true)!!

            try {
                val view = LayeredCameraView(context)
                cameraView = view

                val layoutParams = ViewGroup.LayoutParams(width, height)
                view.layoutParams = layoutParams
                view.x = x.toFloat()
                view.y = y.toFloat()

                val parent = bridge.webView.parent as? ViewGroup
                if (parent != null) {
                    if (toBack) {
                        parent.addView(view, 0)
                    } else {
                        parent.addView(view)
                    }
                }

                val cameraFacing = if (position == "front") 
                    LayeredCameraView.CAMERA_FACING_FRONT 
                else 
                    LayeredCameraView.CAMERA_FACING_BACK

                view.startCamera(cameraFacing, enableZoom, enableFocus) { error ->
                    if (error != null) {
                        call.reject("Failed to start camera: ${error.message}")
                    } else {
                        call.resolve()
                    }
                }
            } catch (e: Exception) {
                call.reject("Failed to create camera view: ${e.message}")
            }
        }
    }

    private fun parseLayer(layerId: String, data: JSObject): CameraLayer? {
        val type = data.optString("type") ?: return null

        return CameraLayer(
            id = layerId,
            type = type,
            x = data.optDouble("x", 0.0).toFloat(),
            y = data.optDouble("y", 0.0).toFloat(),
            width = if (data.has("width")) data.optDouble("width").toFloat() else null,
            height = if (data.has("height")) data.optDouble("height").toFloat() else null,
            zIndex = data.optInt("zIndex", 0),
            opacity = data.optDouble("opacity", 1.0).toFloat(),
            rotation = data.optDouble("rotation", 0.0).toFloat(),
            imagePath = data.optString("imagePath"),
            imageUrl = data.optString("imageUrl"),
            text = data.optString("text"),
            fontSize = if (data.has("fontSize")) data.optDouble("fontSize").toFloat() else null,
            fontColor = data.optString("fontColor"),
            fontFamily = data.optString("fontFamily"),
            textAlign = data.optString("textAlign"),
            backgroundColor = data.optString("backgroundColor"),
            padding = if (data.has("padding")) data.optDouble("padding").toFloat() else null,
            shapeType = data.optString("shapeType"),
            strokeColor = data.optString("strokeColor"),
            strokeWidth = if (data.has("strokeWidth")) data.optDouble("strokeWidth").toFloat() else null,
            fillColor = data.optString("fillColor")
        )
    }

    private fun mergeLayer(existing: CameraLayer, updates: JSObject): CameraLayer {
        return CameraLayer(
            id = existing.id,
            type = updates.optString("type", existing.type),
            x = updates.optDouble("x", existing.x.toDouble()).toFloat(),
            y = updates.optDouble("y", existing.y.toDouble()).toFloat(),
            width = if (updates.has("width")) updates.optDouble("width").toFloat() else existing.width,
            height = if (updates.has("height")) updates.optDouble("height").toFloat() else existing.height,
            zIndex = updates.optInt("zIndex", existing.zIndex),
            opacity = updates.optDouble("opacity", existing.opacity.toDouble()).toFloat(),
            rotation = updates.optDouble("rotation", existing.rotation.toDouble()).toFloat(),
            imagePath = updates.optString("imagePath", existing.imagePath),
            imageUrl = updates.optString("imageUrl", existing.imageUrl),
            text = updates.optString("text", existing.text),
            fontSize = if (updates.has("fontSize")) updates.optDouble("fontSize").toFloat() else existing.fontSize,
            fontColor = updates.optString("fontColor", existing.fontColor),
            fontFamily = updates.optString("fontFamily", existing.fontFamily),
            textAlign = updates.optString("textAlign", existing.textAlign),
            backgroundColor = updates.optString("backgroundColor", existing.backgroundColor),
            padding = if (updates.has("padding")) updates.optDouble("padding").toFloat() else existing.padding,
            shapeType = updates.optString("shapeType", existing.shapeType),
            strokeColor = updates.optString("strokeColor", existing.strokeColor),
            strokeWidth = if (updates.has("strokeWidth")) updates.optDouble("strokeWidth").toFloat() else existing.strokeWidth,
            fillColor = updates.optString("fillColor", existing.fillColor)
        )
    }

    private fun saveToGallery(bitmap: Bitmap) {
        // Implementation to save bitmap to gallery
        // This would use MediaStore on Android
    }

    private fun hasRequiredPermissions(): Boolean {
        return hasPermission(Manifest.permission.CAMERA)
    }
}

// Data classes

data class CameraLayer(
    val id: String,
    val type: String,
    val x: Float,
    val y: Float,
    val width: Float?,
    val height: Float?,
    val zIndex: Int,
    val opacity: Float,
    val rotation: Float,
    val imagePath: String?,
    val imageUrl: String?,
    val text: String?,
    val fontSize: Float?,
    val fontColor: String?,
    val fontFamily: String?,
    val textAlign: String?,
    val backgroundColor: String?,
    val padding: Float?,
    val shapeType: String?,
    val strokeColor: String?,
    val strokeWidth: Float?,
    val fillColor: String?
)

data class CaptureResult(
    val bitmap: Bitmap?,
    val base64: String?,
    val uri: String?,
    val width: Int,
    val height: Int
)
