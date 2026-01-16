import Foundation
import Capacitor
import AVFoundation
import Photos
import UIKit

@objc(CameraLayersPlugin)
public class CameraLayersPlugin: CAPPlugin {
    private var cameraView: LayeredCameraView?
    private var layers: [String: CameraLayerData] = [:]
    
    @objc func start(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            // Request camera permissions
            AVCaptureDevice.requestAccess(for: .video) { granted in
                if !granted {
                    call.reject("Camera permission denied")
                    return
                }
                
                DispatchQueue.main.async {
                    // Parse options
                    let position = call.getString("position") ?? "back"
                    let width = call.getDouble("width") ?? Double(UIScreen.main.bounds.width)
                    let height = call.getDouble("height") ?? 480.0
                    let x = call.getDouble("x") ?? 0.0
                    let y = call.getDouble("y") ?? 0.0
                    let toBack = call.getBool("toBack") ?? false
                    let enableZoom = call.getBool("enableZoom") ?? true
                    let enableFocus = call.getBool("enableFocus") ?? true
                    
                    // Create camera view
                    let frame = CGRect(x: x, y: y, width: width, height: height)
                    self.cameraView = LayeredCameraView(frame: frame)
                    
                    guard let cameraView = self.cameraView else {
                        call.reject("Failed to create camera view")
                        return
                    }
                    
                    // Configure camera
                    let cameraPosition: AVCaptureDevice.Position = position == "front" ? .front : .back
                    
                    // Add to webView
                    if let webView = self.bridge?.webView {
                        if toBack {
                            webView.superview?.insertSubview(cameraView, belowSubview: webView)
                        } else {
                            webView.superview?.addSubview(cameraView)
                        }
                    }
                    
                    // Start camera session
                    cameraView.startCamera(position: cameraPosition, enableZoom: enableZoom, enableFocus: enableFocus) { error in
                        if let error = error {
                            call.reject("Failed to start camera: \(error.localizedDescription)")
                        } else {
                            call.resolve()
                        }
                    }
                }
            }
        }
    }
    
    @objc func stop(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.cameraView?.stopCamera()
            self.cameraView?.removeFromSuperview()
            self.cameraView = nil
            call.resolve()
        }
    }
    
    @objc func capture(_ call: CAPPluginCall) {
        guard let cameraView = self.cameraView else {
            call.reject("Camera not started")
            return
        }
        
        let quality = call.getDouble("quality") ?? 85.0
        let outputType = call.getString("outputType") ?? "uri"
        let saveToGallery = call.getBool("saveToGallery") ?? false
        
        cameraView.capturePhoto(quality: quality / 100.0, layers: Array(self.layers.values)) { result, error in
            if let error = error {
                call.reject("Failed to capture photo: \(error.localizedDescription)")
                return
            }
            
            guard let result = result else {
                call.reject("Failed to capture photo")
                return
            }
            
            var response: [String: Any] = [
                "width": result.width,
                "height": result.height
            ]
            
            if outputType == "base64" || outputType == "both" {
                if let base64 = result.base64 {
                    response["base64"] = base64
                }
            }
            
            if outputType == "uri" || outputType == "both" {
                if let uri = result.uri {
                    response["uri"] = uri
                }
            }
            
            // Save to gallery if requested
            if saveToGallery, let image = result.image {
                PHPhotoLibrary.requestAuthorization { status in
                    if status == .authorized {
                        PHPhotoLibrary.shared().performChanges({
                            PHAssetCreationRequest.creationRequestForAsset(from: image)
                        }, completionHandler: nil)
                    }
                }
            }
            
            call.resolve(response)
        }
    }
    
    @objc func addLayer(_ call: CAPPluginCall) {
        guard let layerDict = call.getObject("layer") ?? call.options else {
            call.reject("Layer data required")
            return
        }
        
        let layerId = (layerDict["id"] as? String) ?? UUID().uuidString
        
        guard let layerData = self.parseLayer(layerId: layerId, dict: layerDict) else {
            call.reject("Invalid layer data")
            return
        }
        
        self.layers[layerId] = layerData
        
        DispatchQueue.main.async {
            self.cameraView?.updateLayers(Array(self.layers.values))
        }
        
        call.resolve(["layerId": layerId])
    }
    
    @objc func updateLayer(_ call: CAPPluginCall) {
        guard let layerId = call.getString("layerId") else {
            call.reject("Layer ID required")
            return
        }
        
        guard var existingLayer = self.layers[layerId] else {
            call.reject("Layer not found")
            return
        }
        
        if let layerDict = call.getObject("layer") {
            existingLayer = self.mergeLayer(existing: existingLayer, updates: layerDict)
            self.layers[layerId] = existingLayer
            
            DispatchQueue.main.async {
                self.cameraView?.updateLayers(Array(self.layers.values))
            }
        }
        
        call.resolve()
    }
    
    @objc func removeLayer(_ call: CAPPluginCall) {
        guard let layerId = call.getString("layerId") else {
            call.reject("Layer ID required")
            return
        }
        
        self.layers.removeValue(forKey: layerId)
        
        DispatchQueue.main.async {
            self.cameraView?.updateLayers(Array(self.layers.values))
        }
        
        call.resolve()
    }
    
    @objc func clearLayers(_ call: CAPPluginCall) {
        self.layers.removeAll()
        
        DispatchQueue.main.async {
            self.cameraView?.updateLayers([])
        }
        
        call.resolve()
    }
    
    @objc func switchCamera(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.cameraView?.switchCamera { error in
                if let error = error {
                    call.reject("Failed to switch camera: \(error.localizedDescription)")
                } else {
                    call.resolve()
                }
            }
        }
    }
    
    @objc func setFlashMode(_ call: CAPPluginCall) {
        guard let modeString = call.getString("mode") else {
            call.reject("Flash mode required")
            return
        }
        
        let flashMode: AVCaptureDevice.FlashMode
        switch modeString {
        case "on":
            flashMode = .on
        case "auto":
            flashMode = .auto
        case "off":
            flashMode = .off
        default:
            flashMode = .off
        }
        
        DispatchQueue.main.async {
            self.cameraView?.setFlashMode(flashMode)
            call.resolve()
        }
    }
    
    @objc func setZoom(_ call: CAPPluginCall) {
        guard let zoom = call.getDouble("zoom") else {
            call.reject("Zoom level required")
            return
        }
        
        DispatchQueue.main.async {
            self.cameraView?.setZoom(CGFloat(zoom))
            call.resolve()
        }
    }
    
    @objc func setFocus(_ call: CAPPluginCall) {
        guard let x = call.getDouble("x"), let y = call.getDouble("y") else {
            call.reject("Focus coordinates required")
            return
        }
        
        DispatchQueue.main.async {
            self.cameraView?.setFocus(point: CGPoint(x: x, y: y))
            call.resolve()
        }
    }
    
    // MARK: - Helper Methods
    
    private func parseLayer(layerId: String, dict: [String: Any]) -> CameraLayerData? {
        guard let type = dict["type"] as? String else { return nil }
        
        let x = (dict["x"] as? Double) ?? 0
        let y = (dict["y"] as? Double) ?? 0
        let width = dict["width"] as? Double
        let height = dict["height"] as? Double
        let zIndex = (dict["zIndex"] as? Int) ?? 0
        let opacity = (dict["opacity"] as? Double) ?? 1.0
        let rotation = (dict["rotation"] as? Double) ?? 0.0
        
        return CameraLayerData(
            id: layerId,
            type: type,
            x: CGFloat(x),
            y: CGFloat(y),
            width: width != nil ? CGFloat(width!) : nil,
            height: height != nil ? CGFloat(height!) : nil,
            zIndex: zIndex,
            opacity: CGFloat(opacity),
            rotation: CGFloat(rotation),
            imagePath: dict["imagePath"] as? String,
            imageUrl: dict["imageUrl"] as? String,
            text: dict["text"] as? String,
            fontSize: (dict["fontSize"] as? Double).map { CGFloat($0) },
            fontColor: dict["fontColor"] as? String,
            fontFamily: dict["fontFamily"] as? String,
            textAlign: dict["textAlign"] as? String,
            backgroundColor: dict["backgroundColor"] as? String,
            padding: (dict["padding"] as? Double).map { CGFloat($0) },
            shapeType: dict["shapeType"] as? String,
            strokeColor: dict["strokeColor"] as? String,
            strokeWidth: (dict["strokeWidth"] as? Double).map { CGFloat($0) },
            fillColor: dict["fillColor"] as? String
        )
    }
    
    private func mergeLayer(existing: CameraLayerData, updates: [String: Any]) -> CameraLayerData {
        return CameraLayerData(
            id: existing.id,
            type: (updates["type"] as? String) ?? existing.type,
            x: (updates["x"] as? Double).map { CGFloat($0) } ?? existing.x,
            y: (updates["y"] as? Double).map { CGFloat($0) } ?? existing.y,
            width: (updates["width"] as? Double).map { CGFloat($0) } ?? existing.width,
            height: (updates["height"] as? Double).map { CGFloat($0) } ?? existing.height,
            zIndex: (updates["zIndex"] as? Int) ?? existing.zIndex,
            opacity: (updates["opacity"] as? Double).map { CGFloat($0) } ?? existing.opacity,
            rotation: (updates["rotation"] as? Double).map { CGFloat($0) } ?? existing.rotation,
            imagePath: (updates["imagePath"] as? String) ?? existing.imagePath,
            imageUrl: (updates["imageUrl"] as? String) ?? existing.imageUrl,
            text: (updates["text"] as? String) ?? existing.text,
            fontSize: (updates["fontSize"] as? Double).map { CGFloat($0) } ?? existing.fontSize,
            fontColor: (updates["fontColor"] as? String) ?? existing.fontColor,
            fontFamily: (updates["fontFamily"] as? String) ?? existing.fontFamily,
            textAlign: (updates["textAlign"] as? String) ?? existing.textAlign,
            backgroundColor: (updates["backgroundColor"] as? String) ?? existing.backgroundColor,
            padding: (updates["padding"] as? Double).map { CGFloat($0) } ?? existing.padding,
            shapeType: (updates["shapeType"] as? String) ?? existing.shapeType,
            strokeColor: (updates["strokeColor"] as? String) ?? existing.strokeColor,
            strokeWidth: (updates["strokeWidth"] as? Double).map { CGFloat($0) } ?? existing.strokeWidth,
            fillColor: (updates["fillColor"] as? String) ?? existing.fillColor
        )
    }
}

// MARK: - Data Structures

struct CameraLayerData {
    let id: String
    let type: String
    let x: CGFloat
    let y: CGFloat
    let width: CGFloat?
    let height: CGFloat?
    let zIndex: Int
    let opacity: CGFloat
    let rotation: CGFloat
    
    // Image layer
    let imagePath: String?
    let imageUrl: String?
    
    // Text layer
    let text: String?
    let fontSize: CGFloat?
    let fontColor: String?
    let fontFamily: String?
    let textAlign: String?
    let backgroundColor: String?
    let padding: CGFloat?
    
    // Shape layer
    let shapeType: String?
    let strokeColor: String?
    let strokeWidth: CGFloat?
    let fillColor: String?
}

struct CaptureResultData {
    let image: UIImage?
    let base64: String?
    let uri: String?
    let width: Int
    let height: Int
}
