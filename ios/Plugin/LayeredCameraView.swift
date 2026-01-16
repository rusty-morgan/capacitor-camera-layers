import Foundation
import AVFoundation
import UIKit

class LayeredCameraView: UIView {
    private var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var photoOutput: AVCapturePhotoOutput?
    private var currentCamera: AVCaptureDevice?
    private var currentPosition: AVCaptureDevice.Position = .back
    private var currentFlashMode: AVCaptureDevice.FlashMode = .off
    private var layers: [CameraLayerData] = []
    private var captureCompletion: ((CaptureResultData?, Error?) -> Void)?
    
    override init(frame: CGRect) {
        super.init(frame: frame)
        setupView()
    }
    
    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupView()
    }
    
    private func setupView() {
        self.backgroundColor = .black
    }
    
    // MARK: - Camera Setup
    
    func startCamera(position: AVCaptureDevice.Position, enableZoom: Bool, enableFocus: Bool, completion: @escaping (Error?) -> Void) {
        currentPosition = position
        
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                // Create session
                let session = AVCaptureSession()
                session.sessionPreset = .photo
                
                // Get camera device
                guard let camera = self.getCameraDevice(for: position) else {
                    DispatchQueue.main.async {
                        completion(NSError(domain: "CameraLayers", code: -1, userInfo: [NSLocalizedDescriptionKey: "Camera not available"]))
                    }
                    return
                }
                
                self.currentCamera = camera
                
                // Add input
                let input = try AVCaptureDeviceInput(device: camera)
                if session.canAddInput(input) {
                    session.addInput(input)
                }
                
                // Add photo output
                let photoOutput = AVCapturePhotoOutput()
                if session.canAddOutput(photoOutput) {
                    session.addOutput(photoOutput)
                }
                self.photoOutput = photoOutput
                
                // Setup preview layer
                DispatchQueue.main.async {
                    let previewLayer = AVCaptureVideoPreviewLayer(session: session)
                    previewLayer.frame = self.bounds
                    previewLayer.videoGravity = .resizeAspectFill
                    self.layer.insertSublayer(previewLayer, at: 0)
                    self.previewLayer = previewLayer
                    
                    self.captureSession = session
                    
                    // Start session
                    DispatchQueue.global(qos: .userInitiated).async {
                        session.startRunning()
                        
                        DispatchQueue.main.async {
                            // Setup gestures
                            if enableZoom {
                                self.setupZoomGesture()
                            }
                            if enableFocus {
                                self.setupFocusGesture()
                            }
                            
                            completion(nil)
                        }
                    }
                }
            } catch {
                DispatchQueue.main.async {
                    completion(error)
                }
            }
        }
    }
    
    func stopCamera() {
        captureSession?.stopRunning()
        previewLayer?.removeFromSuperlayer()
        captureSession = nil
        previewLayer = nil
        photoOutput = nil
        currentCamera = nil
    }
    
    func switchCamera(completion: @escaping (Error?) -> Void) {
        let newPosition: AVCaptureDevice.Position = currentPosition == .back ? .front : .back
        
        stopCamera()
        
        // Small delay to ensure cleanup
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
            self.startCamera(position: newPosition, enableZoom: true, enableFocus: true, completion: completion)
        }
    }
    
    // MARK: - Camera Controls
    
    func setFlashMode(_ mode: AVCaptureDevice.FlashMode) {
        currentFlashMode = mode
    }
    
    func setZoom(_ zoom: CGFloat) {
        guard let device = currentCamera else { return }
        
        do {
            try device.lockForConfiguration()
            let maxZoom = device.activeFormat.videoMaxZoomFactor
            let clampedZoom = max(1.0, min(zoom, maxZoom))
            device.videoZoomFactor = clampedZoom
            device.unlockForConfiguration()
        } catch {
            print("Failed to set zoom: \(error)")
        }
    }
    
    func setFocus(point: CGPoint) {
        guard let device = currentCamera else { return }
        
        do {
            try device.lockForConfiguration()
            
            if device.isFocusPointOfInterestSupported {
                device.focusPointOfInterest = point
                device.focusMode = .autoFocus
            }
            
            if device.isExposurePointOfInterestSupported {
                device.exposurePointOfInterest = point
                device.exposureMode = .autoExpose
            }
            
            device.unlockForConfiguration()
        } catch {
            print("Failed to set focus: \(error)")
        }
    }
    
    // MARK: - Layers
    
    func updateLayers(_ layers: [CameraLayerData]) {
        self.layers = layers
        self.setNeedsDisplay()
    }
    
    // MARK: - Photo Capture
    
    func capturePhoto(quality: Double, layers: [CameraLayerData], completion: @escaping (CaptureResultData?, Error?) -> Void) {
        guard let photoOutput = self.photoOutput else {
            completion(nil, NSError(domain: "CameraLayers", code: -1, userInfo: [NSLocalizedDescriptionKey: "Photo output not available"]))
            return
        }
        
        self.captureCompletion = completion
        self.layers = layers
        
        let settings = AVCapturePhotoSettings()
        settings.flashMode = currentFlashMode
        
        photoOutput.capturePhoto(with: settings, delegate: self)
    }
    
    // MARK: - Compositing
    
    private func compositeImage(cameraImage: UIImage, layers: [CameraLayerData]) -> UIImage {
        let size = cameraImage.size
        
        UIGraphicsBeginImageContextWithOptions(size, false, 1.0)
        defer { UIGraphicsEndImageContext() }
        
        guard let context = UIGraphicsGetCurrentContext() else {
            return cameraImage
        }
        
        // Draw camera image
        cameraImage.draw(in: CGRect(origin: .zero, size: size))
        
        // Draw layers sorted by zIndex
        let sortedLayers = layers.sorted { $0.zIndex < $1.zIndex }
        for layer in sortedLayers {
            context.saveGState()
            
            // Set opacity
            context.setAlpha(layer.opacity)
            
            // Calculate position and size
            let x = layer.x < 1 ? layer.x * size.width : layer.x
            let y = layer.y < 1 ? layer.y * size.height : layer.y
            let width = layer.width ?? size.width
            let height = layer.height ?? size.height
            
            let finalWidth = width < 1 ? width * size.width : width
            let finalHeight = height < 1 ? height * size.height : height
            
            // Apply rotation if specified
            if layer.rotation != 0 {
                context.translateBy(x: x + finalWidth / 2, y: y + finalHeight / 2)
                context.rotate(by: layer.rotation * .pi / 180)
                context.translateBy(x: -(x + finalWidth / 2), y: -(y + finalHeight / 2))
            }
            
            // Draw based on layer type
            switch layer.type {
            case "text":
                drawTextLayer(context: context, layer: layer, x: x, y: y, width: finalWidth, height: finalHeight)
            case "shape":
                drawShapeLayer(context: context, layer: layer, x: x, y: y, width: finalWidth, height: finalHeight)
            case "image":
                drawImageLayer(context: context, layer: layer, x: x, y: y, width: finalWidth, height: finalHeight)
            default:
                break
            }
            
            context.restoreGState()
        }
        
        guard let result = UIGraphicsGetImageFromCurrentImageContext() else {
            return cameraImage
        }
        
        return result
    }
    
    private func drawTextLayer(context: CGContext, layer: CameraLayerData, x: CGFloat, y: CGFloat, width: CGFloat, height: CGFloat) {
        guard let text = layer.text else { return }
        
        let fontSize = layer.fontSize ?? 16
        let fontFamily = layer.fontFamily ?? "Helvetica"
        let font = UIFont(name: fontFamily, size: fontSize) ?? UIFont.systemFont(ofSize: fontSize)
        let fontColor = parseColor(layer.fontColor ?? "#FFFFFF")
        let padding = layer.padding ?? 0
        
        // Draw background if specified
        if let bgColorStr = layer.backgroundColor {
            let bgColor = parseColor(bgColorStr)
            context.setFillColor(bgColor)
            context.fill(CGRect(x: x, y: y, width: width, height: height))
        }
        
        // Draw text
        let textAlign: NSTextAlignment
        switch layer.textAlign {
        case "center":
            textAlign = .center
        case "right":
            textAlign = .right
        default:
            textAlign = .left
        }
        
        let paragraphStyle = NSMutableParagraphStyle()
        paragraphStyle.alignment = textAlign
        
        let attributes: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: UIColor(cgColor: fontColor),
            .paragraphStyle: paragraphStyle
        ]
        
        let textRect = CGRect(x: x + padding, y: y + padding, width: width - padding * 2, height: height - padding * 2)
        text.draw(in: textRect, withAttributes: attributes)
    }
    
    private func drawShapeLayer(context: CGContext, layer: CameraLayerData, x: CGFloat, y: CGFloat, width: CGFloat, height: CGFloat) {
        let strokeColor = parseColor(layer.strokeColor ?? "#000000")
        let strokeWidth = layer.strokeWidth ?? 1
        let fillColor = parseColor(layer.fillColor ?? "transparent")
        
        context.setStrokeColor(strokeColor)
        context.setLineWidth(strokeWidth)
        
        switch layer.shapeType {
        case "rectangle":
            let rect = CGRect(x: x, y: y, width: width, height: height)
            if layer.fillColor != "transparent" && layer.fillColor != nil {
                context.setFillColor(fillColor)
                context.fill(rect)
            }
            context.stroke(rect)
            
        case "circle":
            let rect = CGRect(x: x, y: y, width: width, height: height)
            if layer.fillColor != "transparent" && layer.fillColor != nil {
                context.setFillColor(fillColor)
                context.fillEllipse(in: rect)
            }
            context.strokeEllipse(in: rect)
            
        case "line":
            context.move(to: CGPoint(x: x, y: y))
            context.addLine(to: CGPoint(x: x + width, y: y + height))
            context.strokePath()
            
        default:
            break
        }
    }
    
    private func drawImageLayer(context: CGContext, layer: CameraLayerData, x: CGFloat, y: CGFloat, width: CGFloat, height: CGFloat) {
        var image: UIImage?
        
        if let imagePath = layer.imagePath {
            // Handle base64 or file path
            if imagePath.hasPrefix("data:image") {
                // Base64 data URI
                if let dataString = imagePath.split(separator: ",").last,
                   let data = Data(base64Encoded: String(dataString)) {
                    image = UIImage(data: data)
                }
            } else {
                // File path
                image = UIImage(contentsOfFile: imagePath)
            }
        } else if let imageUrl = layer.imageUrl {
            // For remote URLs, we would need async loading
            // This is a simplified version - production would cache images
            if let url = URL(string: imageUrl),
               let data = try? Data(contentsOf: url) {
                image = UIImage(data: data)
            }
        }
        
        if let image = image {
            let rect = CGRect(x: x, y: y, width: width, height: height)
            image.draw(in: rect)
        }
    }
    
    private func parseColor(_ hexString: String) -> CGColor {
        var hex = hexString.trimmingCharacters(in: .whitespacesAndNewlines)
        if hex.hasPrefix("#") {
            hex.removeFirst()
        }
        
        if hex == "transparent" {
            return UIColor.clear.cgColor
        }
        
        var rgba: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&rgba)
        
        let r, g, b, a: CGFloat
        if hex.count == 8 {
            r = CGFloat((rgba & 0xFF000000) >> 24) / 255.0
            g = CGFloat((rgba & 0x00FF0000) >> 16) / 255.0
            b = CGFloat((rgba & 0x0000FF00) >> 8) / 255.0
            a = CGFloat(rgba & 0x000000FF) / 255.0
        } else {
            r = CGFloat((rgba & 0xFF0000) >> 16) / 255.0
            g = CGFloat((rgba & 0x00FF00) >> 8) / 255.0
            b = CGFloat(rgba & 0x0000FF) / 255.0
            a = 1.0
        }
        
        return UIColor(red: r, green: g, blue: b, alpha: a).cgColor
    }
    
    // MARK: - Gestures
    
    private func setupZoomGesture() {
        let pinchGesture = UIPinchGestureRecognizer(target: self, action: #selector(handlePinch(_:)))
        addGestureRecognizer(pinchGesture)
    }
    
    private func setupFocusGesture() {
        let tapGesture = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        addGestureRecognizer(tapGesture)
    }
    
    @objc private func handlePinch(_ gesture: UIPinchGestureRecognizer) {
        guard let device = currentCamera else { return }
        
        if gesture.state == .changed {
            let maxZoom = device.activeFormat.videoMaxZoomFactor
            let currentZoom = device.videoZoomFactor
            let newZoom = currentZoom * gesture.scale
            let clampedZoom = max(1.0, min(newZoom, maxZoom))
            
            setZoom(clampedZoom)
            gesture.scale = 1.0
        }
    }
    
    @objc private func handleTap(_ gesture: UITapGestureRecognizer) {
        let location = gesture.location(in: self)
        let point = CGPoint(
            x: location.x / bounds.width,
            y: location.y / bounds.height
        )
        setFocus(point: point)
    }
    
    // MARK: - Helpers
    
    private func getCameraDevice(for position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        if let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position) {
            return device
        }
        return AVCaptureDevice.default(for: .video)
    }
    
    override func layoutSubviews() {
        super.layoutSubviews()
        previewLayer?.frame = bounds
    }
}

// MARK: - AVCapturePhotoCaptureDelegate

extension LayeredCameraView: AVCapturePhotoCaptureDelegate {
    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        if let error = error {
            captureCompletion?(nil, error)
            return
        }
        
        guard let imageData = photo.fileDataRepresentation(),
              let cameraImage = UIImage(data: imageData) else {
            captureCompletion?(nil, NSError(domain: "CameraLayers", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to process photo"]))
            return
        }
        
        // Composite layers onto image
        let compositedImage = compositeImage(cameraImage: cameraImage, layers: layers)
        
        // Generate base64
        let base64 = compositedImage.jpegData(compressionQuality: 0.85)?.base64EncodedString()
        
        // Save to temp file for URI
        var uri: String?
        if let jpegData = compositedImage.jpegData(compressionQuality: 0.85) {
            let tempDir = NSTemporaryDirectory()
            let fileName = "camera_\(Date().timeIntervalSince1970).jpg"
            let fileURL = URL(fileURLWithPath: tempDir).appendingPathComponent(fileName)
            
            try? jpegData.write(to: fileURL)
            uri = fileURL.path
        }
        
        let result = CaptureResultData(
            image: compositedImage,
            base64: base64,
            uri: uri,
            width: Int(compositedImage.size.width),
            height: Int(compositedImage.size.height)
        )
        
        captureCompletion?(result, nil)
    }
}
