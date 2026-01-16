# @kaelmo/capacitor-camera-layers

Capacitor plugin for camera preview with customizable layers that composite onto captured photos. This plugin allows you to add image frames, text watermarks, and shape overlays that are perfectly composited onto the final captured photo, matching exactly what the user sees in the preview.

## Features

- 📷 **Full Camera Control** - Access front/back cameras with preview
- 🎨 **Customizable Layers** - Add images, text, and shapes as overlays
- 🖼️ **Perfect Composition** - Captured photos match the preview exactly
- ⚡ **Advanced Controls** - Flash, zoom, tap-to-focus support
- 📱 **Cross-Platform** - Works on iOS, Android, and Web
- 🎯 **Layer Management** - Add, update, remove, and clear layers dynamically
- 💾 **Flexible Output** - Get photos as base64 or file URI

## Supported Platforms

- ✅ iOS 13.0+
- ✅ Android 5.0+ (API 21+)
- ✅ Web (modern browsers with getUserMedia support)

## Installation

```bash
npm install @kaelmo/capacitor-camera-layers
npx cap sync
```

## iOS Setup

Add camera and photo library permissions to your `Info.plist`:

```xml
<key>NSCameraUsageDescription</key>
<string>We need camera access to take photos</string>
<key>NSPhotoLibraryAddUsageDescription</key>
<string>We need access to save photos to your gallery</string>
```

## Android Setup

The required permissions are automatically added from the plugin's `AndroidManifest.xml`:
- `android.permission.CAMERA`
- `android.permission.WRITE_EXTERNAL_STORAGE` (for Android 9 and below)

Make sure to request camera permissions at runtime before starting the camera:

```typescript
import { CameraLayers } from '@kaelmo/capacitor-camera-layers';

// Request permissions and start camera
await CameraLayers.start({ position: 'back' });
```

## API Documentation

### Methods

#### `start(options: CameraStartOptions): Promise<void>`

Start the camera preview with optional configuration.

**Parameters:**
- `options.position` - Camera position: `'front'` or `'back'` (default: `'back'`)
- `options.width` - Preview width in pixels
- `options.height` - Preview height in pixels
- `options.x` - Preview position x in pixels
- `options.y` - Preview position y in pixels
- `options.toBack` - Render camera behind webview (default: `false`)
- `options.paddingBottom` - Padding from bottom in pixels
- `options.enableZoom` - Enable pinch-to-zoom gesture (default: `true`)
- `options.enableFocus` - Enable tap-to-focus gesture (default: `true`)

#### `stop(): Promise<void>`

Stop the camera preview and release resources.

#### `capture(options?: CaptureOptions): Promise<CaptureResult>`

Capture a photo with all layers composited.

**Parameters:**
- `options.quality` - JPEG quality 0-100 (default: `85`)
- `options.outputType` - Output format: `'base64'`, `'uri'`, or `'both'` (default: `'uri'`)
- `options.saveToGallery` - Save photo to device gallery (default: `false`)

**Returns:**
- `CaptureResult` object with `base64`, `uri`, `width`, and `height` properties

#### `addLayer(layer: CameraLayer): Promise<{ layerId: string }>`

Add a new layer to the camera preview.

**Returns:** Object with generated `layerId`

#### `updateLayer(options: { layerId: string; layer: Partial<CameraLayer> }): Promise<void>`

Update an existing layer's properties.

#### `removeLayer(options: { layerId: string }): Promise<void>`

Remove a specific layer by ID.

#### `clearLayers(): Promise<void>`

Remove all layers from the preview.

#### `switchCamera(): Promise<void>`

Switch between front and back camera.

#### `setFlashMode(options: { mode: FlashMode }): Promise<void>`

Set the flash mode: `'off'`, `'on'`, `'auto'`, or `'torch'`.

#### `setZoom(options: { zoom: number }): Promise<void>`

Set the zoom level (1.0 = no zoom, 2.0 = 2x zoom).

#### `setFocus(options: { x: number; y: number }): Promise<void>`

Set focus point using normalized coordinates (0-1).

### Layer Types

#### Image Layer

Add an image overlay (e.g., decorative frame, logo):

```typescript
{
  type: 'image',
  imagePath: 'assets/frame.png', // or base64 data URI
  imageUrl: 'https://example.com/image.png', // or remote URL
  x: 0,
  y: 0,
  width: 1, // normalized (0-1) or pixels
  height: 1,
  zIndex: 10,
  opacity: 1.0,
  rotation: 0
}
```

#### Text Layer

Add text overlay (e.g., watermark, date stamp):

```typescript
{
  type: 'text',
  text: '© 2026 My Company',
  x: 0.05,
  y: 0.95,
  fontSize: 16,
  fontColor: '#FFFFFF',
  fontFamily: 'Arial',
  textAlign: 'left', // 'left', 'center', or 'right'
  backgroundColor: '#00000080', // supports alpha
  padding: 8,
  zIndex: 20,
  opacity: 1.0
}
```

#### Shape Layer

Add geometric shapes (e.g., focus guide, border):

```typescript
{
  type: 'shape',
  shapeType: 'rectangle', // 'rectangle', 'circle', or 'line'
  x: 0.1,
  y: 0.1,
  width: 0.8,
  height: 0.8,
  strokeColor: '#FF0000',
  strokeWidth: 3,
  fillColor: 'transparent',
  zIndex: 5,
  opacity: 1.0
}
```

## Usage Examples

### Basic Usage

```typescript
import { CameraLayers } from '@kaelmo/capacitor-camera-layers';

async function takePicture() {
  // Start camera preview
  await CameraLayers.start({
    position: 'back',
    width: window.innerWidth,
    height: window.innerHeight * 0.7,
    x: 0,
    y: 100
  });

  // Capture photo
  const result = await CameraLayers.capture({
    quality: 90,
    outputType: 'uri'
  });

  console.log('Photo captured:', result.uri);

  // Stop camera
  await CameraLayers.stop();
}
```

### Adding Image Frame Overlay

```typescript
// Add a decorative frame that covers the entire preview
const { layerId } = await CameraLayers.addLayer({
  type: 'image',
  imagePath: 'assets/polaroid-frame.png',
  x: 0,
  y: 0,
  width: 1, // full width (normalized)
  height: 1, // full height (normalized)
  zIndex: 10
});
```

### Adding Text Watermark

```typescript
// Add copyright text in bottom-left corner
await CameraLayers.addLayer({
  type: 'text',
  text: '© 2026 My Company',
  x: 0.05, // 5% from left
  y: 0.95, // 95% from top
  fontSize: 16,
  fontColor: '#FFFFFF',
  backgroundColor: '#00000080', // semi-transparent black
  padding: 8,
  zIndex: 20
});

// Add date stamp
const today = new Date().toLocaleDateString();
await CameraLayers.addLayer({
  type: 'text',
  text: today,
  x: 0.5, // centered
  y: 0.92,
  fontSize: 14,
  fontColor: '#333333',
  textAlign: 'center',
  zIndex: 15
});
```

### Adding Shape Overlay

```typescript
// Add a rectangular focus guide
await CameraLayers.addLayer({
  type: 'shape',
  shapeType: 'rectangle',
  x: 0.1, // 10% from left
  y: 0.1, // 10% from top
  width: 0.8, // 80% width
  height: 0.8, // 80% height
  strokeColor: '#FF0000',
  strokeWidth: 3,
  fillColor: 'transparent',
  zIndex: 5
});

// Add a circular overlay
await CameraLayers.addLayer({
  type: 'shape',
  shapeType: 'circle',
  x: 0.25,
  y: 0.25,
  width: 0.5,
  height: 0.5,
  strokeColor: '#00FF00',
  strokeWidth: 2,
  fillColor: '#00FF0020', // semi-transparent green
  zIndex: 6
});
```

### Camera Controls

```typescript
// Switch between front and back camera
await CameraLayers.switchCamera();

// Set flash mode
await CameraLayers.setFlashMode({ mode: 'auto' });

// Set zoom (1.0 = no zoom, 2.0 = 2x zoom)
await CameraLayers.setZoom({ zoom: 2.0 });

// Tap to focus (normalized coordinates 0-1)
await CameraLayers.setFocus({ x: 0.5, y: 0.5 }); // center focus
```

### Dynamic Layer Management

```typescript
// Add a layer and get its ID
const { layerId } = await CameraLayers.addLayer({
  type: 'text',
  text: 'Draft',
  x: 0.5,
  y: 0.5,
  fontSize: 48,
  fontColor: '#FF0000',
  textAlign: 'center'
});

// Update the layer
await CameraLayers.updateLayer({
  layerId,
  layer: {
    text: 'Final',
    fontColor: '#00FF00'
  }
});

// Remove the layer
await CameraLayers.removeLayer({ layerId });

// Or clear all layers
await CameraLayers.clearLayers();
```

### Complete Example: Photo with Frame

```typescript
async function takePictureWithFrame() {
  try {
    // Start camera
    await CameraLayers.start({
      position: 'back',
      width: window.innerWidth,
      height: 600,
      x: 0,
      y: 100,
      enableZoom: true,
      enableFocus: true
    });

    // Add decorative frame
    const { layerId: frameId } = await CameraLayers.addLayer({
      type: 'image',
      imagePath: 'assets/polaroid-frame.png',
      x: 0,
      y: 0,
      width: 1,
      height: 1,
      zIndex: 10
    });

    // Add date text
    const today = new Date().toLocaleDateString();
    await CameraLayers.addLayer({
      type: 'text',
      text: today,
      x: 0.5,
      y: 0.92,
      fontSize: 14,
      fontColor: '#333333',
      textAlign: 'center',
      zIndex: 15
    });

    // Add location watermark
    await CameraLayers.addLayer({
      type: 'text',
      text: 'San Francisco, CA',
      x: 0.05,
      y: 0.95,
      fontSize: 12,
      fontColor: '#FFFFFF',
      backgroundColor: '#00000080',
      padding: 6,
      zIndex: 20
    });

    // Wait for user to position camera
    await new Promise(resolve => setTimeout(resolve, 3000));

    // Capture with all layers
    const result = await CameraLayers.capture({
      quality: 95,
      outputType: 'both',
      saveToGallery: true
    });

    console.log('Photo saved:', result.uri);
    console.log('Photo size:', result.width, 'x', result.height);

    // Clean up
    await CameraLayers.stop();

    return result;
  } catch (error) {
    console.error('Camera error:', error);
    throw error;
  }
}
```

## Troubleshooting

### Camera not starting

**Possible causes:**
- Camera permissions not granted
- Camera already in use by another app
- Invalid camera position specified

**Solutions:**
- Check that permissions are granted before calling `start()`
- Close other apps using the camera
- Verify the `position` parameter is either `'front'` or `'back'`

### Layers not appearing

**Possible causes:**
- Incorrect zIndex (layer hidden behind others)
- Coordinates out of bounds
- Invalid image path or URL
- Layer opacity set to 0

**Solutions:**
- Ensure zIndex is set appropriately (higher values appear on top)
- Use normalized coordinates (0-1) or valid pixel values
- Verify image paths are correct and accessible
- Check opacity is between 0 and 1

### Photo quality issues

**Possible causes:**
- Low quality setting
- Insufficient device storage
- Poor lighting conditions

**Solutions:**
- Increase the `quality` parameter (0-100)
- Free up device storage space
- Ensure adequate lighting for the camera

### Performance issues

**Possible causes:**
- Too many layers
- Large image files in layers
- Frequent layer updates

**Solutions:**
- Reduce the number of layers
- Optimize image sizes before adding as layers
- Batch layer updates when possible
- Use appropriate image resolutions

### Web platform limitations

**Note:** The web implementation has some limitations compared to native platforms:

- Flash/torch control may not be available
- Zoom control depends on browser support
- Focus control has limited support
- Image saving to gallery triggers download instead

## Advanced Topics

### Normalized vs Pixel Coordinates

Layer positions and sizes can be specified using either:

1. **Normalized coordinates (0-1)**: Values between 0 and 1 are treated as percentages
   - `x: 0.5` = 50% from left
   - `width: 1` = 100% width

2. **Pixel coordinates (>1)**: Values greater than 1 are treated as pixels
   - `x: 100` = 100 pixels from left
   - `width: 300` = 300 pixels wide

### Layer Composition Order

Layers are drawn in order of their `zIndex` value (ascending):
- Lower zIndex values appear behind
- Higher zIndex values appear on top
- Layers with the same zIndex are drawn in the order they were added

### Memory Management

The plugin automatically manages resources, but you should:
- Call `stop()` when done with the camera
- Remove unused layers with `removeLayer()` or `clearLayers()`
- Avoid keeping large images in memory unnecessarily

## License

MIT

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Issues

If you encounter any issues, please file them at:
https://github.com/rusty-morgan/capacitor-camera-layers/issues