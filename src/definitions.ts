export interface CameraLayersPlugin {
  /**
   * Start camera preview with optional configuration
   */
  start(options: CameraStartOptions): Promise<void>;

  /**
   * Stop camera preview and release resources
   */
  stop(): Promise<void>;

  /**
   * Capture photo with all layers composited
   */
  capture(options?: CaptureOptions): Promise<CaptureResult>;

  /**
   * Add a new layer to the camera preview
   */
  addLayer(layer: CameraLayer): Promise<{ layerId: string }>;

  /**
   * Update an existing layer
   */
  updateLayer(options: { layerId: string; layer: Partial<CameraLayer> }): Promise<void>;

  /**
   * Remove a specific layer
   */
  removeLayer(options: { layerId: string }): Promise<void>;

  /**
   * Remove all layers
   */
  clearLayers(): Promise<void>;

  /**
   * Switch between front and back camera
   */
  switchCamera(): Promise<void>;

  /**
   * Set flash mode
   */
  setFlashMode(options: { mode: FlashMode }): Promise<void>;

  /**
   * Set zoom level (1.0 = no zoom)
   */
  setZoom(options: { zoom: number }): Promise<void>;

  /**
   * Set focus point (normalized coordinates 0-1)
   */
  setFocus(options: { x: number; y: number }): Promise<void>;
}

export interface CameraStartOptions {
  /**
   * Camera position
   * @default 'back'
   */
  position?: 'front' | 'back';

  /**
   * Preview width in pixels
   */
  width?: number;

  /**
   * Preview height in pixels
   */
  height?: number;

  /**
   * Preview position x in pixels
   */
  x?: number;

  /**
   * Preview position y in pixels
   */
  y?: number;

  /**
   * Render camera behind webview
   * @default false
   */
  toBack?: boolean;

  /**
   * Padding from bottom in pixels
   */
  paddingBottom?: number;

  /**
   * Enable pinch-to-zoom gesture
   * @default true
   */
  enableZoom?: boolean;

  /**
   * Enable tap-to-focus gesture
   * @default true
   */
  enableFocus?: boolean;
}

export interface CaptureOptions {
  /**
   * JPEG quality (0-100)
   * @default 85
   */
  quality?: number;

  /**
   * Output format
   * @default 'uri'
   */
  outputType?: 'base64' | 'uri' | 'both';

  /**
   * Save captured photo to device gallery
   * @default false
   */
  saveToGallery?: boolean;
}

export interface CaptureResult {
  /**
   * Base64-encoded image data (if outputType is 'base64' or 'both')
   */
  base64?: string;

  /**
   * File URI path (if outputType is 'uri' or 'both')
   */
  uri?: string;

  /**
   * Image width in pixels
   */
  width: number;

  /**
   * Image height in pixels
   */
  height: number;
}

export interface CameraLayer {
  /**
   * Unique layer identifier (auto-generated if not provided)
   */
  id?: string;

  /**
   * Layer type
   */
  type: 'image' | 'text' | 'shape';

  /**
   * Layer stacking order (higher values appear on top)
   * @default 0
   */
  zIndex?: number;

  /**
   * X position (normalized 0-1 or pixels)
   */
  x: number;

  /**
   * Y position (normalized 0-1 or pixels)
   */
  y: number;

  /**
   * Layer width (normalized 0-1 or pixels)
   */
  width?: number;

  /**
   * Layer height (normalized 0-1 or pixels)
   */
  height?: number;

  // Image layer properties
  /**
   * Local file path or base64 data URI for image layer
   */
  imagePath?: string;

  /**
   * Remote image URL for image layer
   */
  imageUrl?: string;

  // Text layer properties
  /**
   * Text content for text layer
   */
  text?: string;

  /**
   * Font size in points for text layer
   */
  fontSize?: number;

  /**
   * Font color (hex format) for text layer
   */
  fontColor?: string;

  /**
   * Font family name for text layer
   */
  fontFamily?: string;

  /**
   * Text alignment for text layer
   */
  textAlign?: 'left' | 'center' | 'right';

  /**
   * Background color (hex format, supports alpha) for text layer
   */
  backgroundColor?: string;

  /**
   * Padding around text in pixels
   */
  padding?: number;

  // Shape layer properties
  /**
   * Shape type for shape layer
   */
  shapeType?: 'rectangle' | 'circle' | 'line';

  /**
   * Stroke color (hex format) for shape layer
   */
  strokeColor?: string;

  /**
   * Stroke width in pixels for shape layer
   */
  strokeWidth?: number;

  /**
   * Fill color (hex format or 'transparent') for shape layer
   */
  fillColor?: string;

  // Common properties
  /**
   * Layer opacity (0-1)
   * @default 1
   */
  opacity?: number;

  /**
   * Rotation angle in degrees
   * @default 0
   */
  rotation?: number;
}

export type FlashMode = 'off' | 'on' | 'auto' | 'torch';
