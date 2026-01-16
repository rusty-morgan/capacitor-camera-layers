package com.kaelmo.cameralayers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class LayeredCameraView(context: Context) : FrameLayout(context) {
    
    companion object {
        const val CAMERA_FACING_BACK = 0
        const val CAMERA_FACING_FRONT = 1
        private const val TAG = "LayeredCameraView"
    }

    private var textureView: TextureView
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraManager: CameraManager
    private var cameraId: String? = null
    private var currentFacing = CAMERA_FACING_BACK
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var flashMode = "off"
    private var zoomLevel = 1.0f
    private var layers: List<CameraLayer> = emptyList()
    private var captureCallback: ((CaptureResult?, Exception?) -> Unit)? = null
    private var currentQuality: Int = 85

    init {
        textureView = TextureView(context)
        addView(textureView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    fun startCamera(facing: Int, enableZoom: Boolean, enableFocus: Boolean, callback: (Exception?) -> Unit) {
        currentFacing = facing
        
        startBackgroundThread()
        
        if (textureView.isAvailable) {
            openCamera(callback)
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openCamera(callback)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }

    fun stopCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        stopBackgroundThread()
    }

    fun switchCamera(callback: (Exception?) -> Unit) {
        currentFacing = if (currentFacing == CAMERA_FACING_BACK) CAMERA_FACING_FRONT else CAMERA_FACING_BACK
        stopCamera()
        
        post {
            startCamera(currentFacing, true, true, callback)
        }
    }

    fun setFlashMode(mode: String) {
        flashMode = mode
        updatePreview()
    }

    fun setZoom(zoom: Float) {
        zoomLevel = zoom.coerceIn(1.0f, 10.0f)
        updatePreview()
    }

    fun setFocus(x: Float, y: Float) {
        // Implement tap-to-focus using CONTROL_AF_REGIONS
        // This requires calculating MeteringRectangle based on normalized coordinates
    }

    fun updateLayers(layers: List<CameraLayer>) {
        this.layers = layers
    }

    fun capturePhoto(quality: Int, layers: List<CameraLayer>, callback: (CaptureResult?, Exception?) -> Unit) {
        this.layers = layers
        this.currentQuality = quality
        this.captureCallback = callback
        
        val reader = imageReader ?: run {
            callback(null, Exception("Camera not ready"))
            return
        }

        try {
            val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE) ?: run {
                callback(null, Exception("Failed to create capture request"))
                return
            }

            captureBuilder.addTarget(reader.surface)
            
            // Set flash mode
            when (flashMode) {
                "on" -> captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                "auto" -> captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                else -> captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            // Apply zoom
            applyCropRegion(captureBuilder)

            captureSession?.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    Log.d(TAG, "Capture completed")
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    super.onCaptureFailed(session, request, failure)
                    callback(null, Exception("Capture failed"))
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            callback(null, e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(callback: (Exception?) -> Unit) {
        try {
            cameraId = getCameraId(currentFacing)
            val id = cameraId ?: run {
                callback(Exception("No camera found"))
                return
            }

            val characteristics = cameraManager.getCameraCharacteristics(id)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val largestSize = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height }
                ?: Size(1920, 1080)

            imageReader = ImageReader.newInstance(largestSize.width, largestSize.height, ImageFormat.JPEG, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    processImage(image)
                    image.close()
                }
            }, backgroundHandler)

            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(callback)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    callback(Exception("Camera error: $error"))
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            callback(e)
        }
    }

    private fun createCaptureSession(callback: (Exception?) -> Unit) {
        try {
            val texture = textureView.surfaceTexture ?: run {
                callback(Exception("Surface texture not available"))
                return
            }

            texture.setDefaultBufferSize(textureView.width, textureView.height)
            val surface = Surface(texture)

            val previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) ?: run {
                callback(Exception("Failed to create preview request"))
                return
            }

            previewRequestBuilder.addTarget(surface)

            val outputs = mutableListOf(surface)
            imageReader?.surface?.let { outputs.add(it) }

            cameraDevice?.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) {
                        callback(Exception("Camera closed"))
                        return
                    }

                    captureSession = session

                    try {
                        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)

                        session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
                        callback(null)
                    } catch (e: Exception) {
                        callback(e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    callback(Exception("Failed to configure camera"))
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            callback(e)
        }
    }

    private fun updatePreview() {
        if (cameraDevice == null || captureSession == null) return

        try {
            val texture = textureView.surfaceTexture ?: return
            val surface = Surface(texture)

            val previewRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return
            previewRequestBuilder.addTarget(surface)

            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            
            // Apply zoom
            applyCropRegion(previewRequestBuilder)

            captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update preview", e)
        }
    }

    private fun applyCropRegion(builder: CaptureRequest.Builder) {
        val cameraId = this.cameraId ?: return
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val sensorRect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val cropW = sensorRect.width() / zoomLevel
        val cropH = sensorRect.height() / zoomLevel
        val cropX = (sensorRect.width() - cropW) / 2
        val cropY = (sensorRect.height() - cropH) / 2

        val cropRect = Rect(cropX.toInt(), cropY.toInt(), (cropX + cropW).toInt(), (cropY + cropH).toInt())
        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRect)
    }

    private fun processImage(image: Image) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            
            // Rotate bitmap if necessary (front camera is often mirrored)
            if (currentFacing == CAMERA_FACING_FRONT) {
                val matrix = Matrix()
                matrix.preScale(-1.0f, 1.0f)
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
            }

            // Composite layers
            val compositedBitmap = compositeImage(bitmap, layers)

            // Generate base64
            val byteArrayOutputStream = ByteArrayOutputStream()
            compositedBitmap.compress(Bitmap.CompressFormat.JPEG, currentQuality, byteArrayOutputStream)
            val base64 = android.util.Base64.encodeToString(byteArrayOutputStream.toByteArray(), android.util.Base64.NO_WRAP)

            // Save to temp file for URI
            val tempFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
            FileOutputStream(tempFile).use { out ->
                compositedBitmap.compress(Bitmap.CompressFormat.JPEG, currentQuality, out)
            }

            val result = CaptureResult(
                bitmap = compositedBitmap,
                base64 = base64,
                uri = tempFile.absolutePath,
                width = compositedBitmap.width,
                height = compositedBitmap.height
            )

            captureCallback?.invoke(result, null)
            captureCallback = null

        } catch (e: Exception) {
            captureCallback?.invoke(null, e)
            captureCallback = null
        }
    }

    private fun compositeImage(cameraImage: Bitmap, layers: List<CameraLayer>): Bitmap {
        val result = Bitmap.createBitmap(cameraImage.width, cameraImage.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // Draw camera image
        canvas.drawBitmap(cameraImage, 0f, 0f, null)

        // Draw layers sorted by zIndex
        layers.sortedBy { it.zIndex }.forEach { layer ->
            canvas.save()

            // Calculate position and size
            val x = if (layer.x < 1) layer.x * cameraImage.width else layer.x
            val y = if (layer.y < 1) layer.y * cameraImage.height else layer.y
            val width = layer.width?.let { if (it < 1) it * cameraImage.width else it } ?: cameraImage.width.toFloat()
            val height = layer.height?.let { if (it < 1) it * cameraImage.height else it } ?: cameraImage.height.toFloat()

            // Apply rotation
            if (layer.rotation != 0f) {
                canvas.rotate(layer.rotation, x + width / 2, y + height / 2)
            }

            // Apply opacity
            val paint = Paint().apply {
                alpha = (layer.opacity * 255).toInt()
            }

            when (layer.type) {
                "text" -> drawTextLayer(canvas, layer, x, y, width, height)
                "shape" -> drawShapeLayer(canvas, layer, x, y, width, height)
                "image" -> drawImageLayer(canvas, layer, x, y, width, height)
            }

            canvas.restore()
        }

        return result
    }

    private fun drawTextLayer(canvas: Canvas, layer: CameraLayer, x: Float, y: Float, width: Float, height: Float) {
        val text = layer.text ?: return
        val fontSize = layer.fontSize ?: 16f
        val fontColor = parseColor(layer.fontColor ?: "#FFFFFF")
        val padding = layer.padding ?: 0f

        val paint = Paint().apply {
            color = fontColor
            textSize = fontSize * context.resources.displayMetrics.scaledDensity
            isAntiAlias = true
        }

        // Draw background if specified
        layer.backgroundColor?.let { bgColor ->
            val bgPaint = Paint().apply {
                color = parseColor(bgColor)
                style = Paint.Style.FILL
            }
            canvas.drawRect(x, y, x + width, y + height, bgPaint)
        }

        // Set text alignment
        paint.textAlign = when (layer.textAlign) {
            "center" -> Paint.Align.CENTER
            "right" -> Paint.Align.RIGHT
            else -> Paint.Align.LEFT
        }

        val textX = when (layer.textAlign) {
            "center" -> x + width / 2
            "right" -> x + width - padding
            else -> x + padding
        }

        val textY = y + padding - paint.ascent()
        canvas.drawText(text, textX, textY, paint)
    }

    private fun drawShapeLayer(canvas: Canvas, layer: CameraLayer, x: Float, y: Float, width: Float, height: Float) {
        val strokeColor = parseColor(layer.strokeColor ?: "#000000")
        val strokeWidth = layer.strokeWidth ?: 1f
        val fillColor = parseColor(layer.fillColor ?: "transparent")

        when (layer.shapeType) {
            "rectangle" -> {
                if (layer.fillColor != null && layer.fillColor != "transparent") {
                    val fillPaint = Paint().apply {
                        color = fillColor
                        style = Paint.Style.FILL
                    }
                    canvas.drawRect(x, y, x + width, y + height, fillPaint)
                }

                val strokePaint = Paint().apply {
                    color = strokeColor
                    style = Paint.Style.STROKE
                    this.strokeWidth = strokeWidth
                }
                canvas.drawRect(x, y, x + width, y + height, strokePaint)
            }

            "circle" -> {
                val cx = x + width / 2
                val cy = y + height / 2
                val radius = minOf(width, height) / 2

                if (layer.fillColor != null && layer.fillColor != "transparent") {
                    val fillPaint = Paint().apply {
                        color = fillColor
                        style = Paint.Style.FILL
                    }
                    canvas.drawCircle(cx, cy, radius, fillPaint)
                }

                val strokePaint = Paint().apply {
                    color = strokeColor
                    style = Paint.Style.STROKE
                    this.strokeWidth = strokeWidth
                }
                canvas.drawCircle(cx, cy, radius, strokePaint)
            }

            "line" -> {
                val strokePaint = Paint().apply {
                    color = strokeColor
                    style = Paint.Style.STROKE
                    this.strokeWidth = strokeWidth
                }
                canvas.drawLine(x, y, x + width, y + height, strokePaint)
            }
        }
    }

    private fun drawImageLayer(canvas: Canvas, layer: CameraLayer, x: Float, y: Float, width: Float, height: Float) {
        var bitmap: Bitmap? = null

        layer.imagePath?.let { path ->
            bitmap = if (path.startsWith("data:image")) {
                // Base64 data URI
                val base64Data = path.substringAfter(",")
                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                // File path
                BitmapFactory.decodeFile(path)
            }
        }

        layer.imageUrl?.let { url ->
            // For remote URLs, we would need async loading
            // This is simplified - production would cache images
        }

        bitmap?.let {
            val destRect = RectF(x, y, x + width, y + height)
            canvas.drawBitmap(it, null, destRect, Paint().apply { alpha = (layer.opacity * 255).toInt() })
        }
    }

    private fun parseColor(colorString: String): Int {
        if (colorString == "transparent") {
            return Color.TRANSPARENT
        }

        var hex = colorString.trim()
        if (hex.startsWith("#")) {
            hex = hex.substring(1)
        }

        return when (hex.length) {
            6 -> Color.parseColor("#$hex")
            8 -> Color.parseColor("#$hex")
            else -> Color.BLACK
        }
    }

    private fun getCameraId(facing: Int): String? {
        val cameraFacing = if (facing == CAMERA_FACING_FRONT) 
            CameraCharacteristics.LENS_FACING_FRONT 
        else 
            CameraCharacteristics.LENS_FACING_BACK

        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == cameraFacing) {
                return id
            }
        }
        return null
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }
}
