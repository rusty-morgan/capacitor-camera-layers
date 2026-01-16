import { WebPlugin } from '@capacitor/core';
import type {
  CameraLayersPlugin,
  CameraStartOptions,
  CaptureOptions,
  CaptureResult,
  CameraLayer,
  FlashMode,
} from './definitions';

export class CameraLayersWeb extends WebPlugin implements CameraLayersPlugin {
  private videoElement: HTMLVideoElement | null = null;
  private canvasElement: HTMLCanvasElement | null = null;
  private overlayCanvas: HTMLCanvasElement | null = null;
  private stream: MediaStream | null = null;
  private layers: Map<string, CameraLayer> = new Map();
  private currentCamera: 'front' | 'back' = 'back';
  private animationFrameId: number | null = null;
  private containerElement: HTMLDivElement | null = null;

  async start(options: CameraStartOptions): Promise<void> {
    const position = options.position || 'back';
    const width = options.width || window.innerWidth;
    const height = options.height || 480;
    const x = options.x || 0;
    const y = options.y || 0;

    try {
      // Request camera access
      this.stream = await navigator.mediaDevices.getUserMedia({
        video: {
          facingMode: position === 'front' ? 'user' : 'environment',
          width: { ideal: width },
          height: { ideal: height },
        },
      });

      // Create container
      this.containerElement = document.createElement('div');
      this.containerElement.style.position = 'absolute';
      this.containerElement.style.left = `${x}px`;
      this.containerElement.style.top = `${y}px`;
      this.containerElement.style.width = `${width}px`;
      this.containerElement.style.height = `${height}px`;
      this.containerElement.style.overflow = 'hidden';
      if (options.toBack) {
        this.containerElement.style.zIndex = '-1';
      }

      // Create video element
      this.videoElement = document.createElement('video');
      this.videoElement.srcObject = this.stream;
      this.videoElement.autoplay = true;
      this.videoElement.playsInline = true;
      this.videoElement.style.width = '100%';
      this.videoElement.style.height = '100%';
      this.videoElement.style.objectFit = 'cover';

      // Create overlay canvas for layers
      this.overlayCanvas = document.createElement('canvas');
      this.overlayCanvas.width = width;
      this.overlayCanvas.height = height;
      this.overlayCanvas.style.position = 'absolute';
      this.overlayCanvas.style.top = '0';
      this.overlayCanvas.style.left = '0';
      this.overlayCanvas.style.pointerEvents = 'none';

      this.containerElement.appendChild(this.videoElement);
      this.containerElement.appendChild(this.overlayCanvas);
      document.body.appendChild(this.containerElement);

      this.currentCamera = position;

      // Start rendering layers
      this.renderLayers();

      // Add zoom gesture support if enabled
      if (options.enableZoom !== false) {
        this.setupZoomGesture();
      }

      // Add focus gesture support if enabled
      if (options.enableFocus !== false) {
        this.setupFocusGesture();
      }
    } catch (error) {
      throw new Error(`Failed to start camera: ${error}`);
    }
  }

  async stop(): Promise<void> {
    // Stop rendering
    if (this.animationFrameId !== null) {
      cancelAnimationFrame(this.animationFrameId);
      this.animationFrameId = null;
    }

    // Stop media stream
    if (this.stream) {
      this.stream.getTracks().forEach(track => track.stop());
      this.stream = null;
    }

    // Remove elements
    if (this.containerElement && this.containerElement.parentNode) {
      this.containerElement.parentNode.removeChild(this.containerElement);
    }

    this.videoElement = null;
    this.canvasElement = null;
    this.overlayCanvas = null;
    this.containerElement = null;
  }

  async capture(options?: CaptureOptions): Promise<CaptureResult> {
    if (!this.videoElement || !this.overlayCanvas) {
      throw new Error('Camera not started');
    }

    const quality = (options?.quality || 85) / 100;
    const outputType = options?.outputType || 'uri';

    // Create capture canvas
    const captureCanvas = document.createElement('canvas');
    captureCanvas.width = this.videoElement.videoWidth || this.overlayCanvas.width;
    captureCanvas.height = this.videoElement.videoHeight || this.overlayCanvas.height;

    const ctx = captureCanvas.getContext('2d');
    if (!ctx) {
      throw new Error('Failed to create canvas context');
    }

    // Draw video frame
    ctx.drawImage(this.videoElement, 0, 0, captureCanvas.width, captureCanvas.height);

    // Composite all layers
    this.compositeLayers(ctx, captureCanvas.width, captureCanvas.height);

    // Convert to output format
    const result: CaptureResult = {
      width: captureCanvas.width,
      height: captureCanvas.height,
    };

    if (outputType === 'base64' || outputType === 'both') {
      const dataUrl = captureCanvas.toDataURL('image/jpeg', quality);
      result.base64 = dataUrl.split(',')[1];
    }

    if (outputType === 'uri' || outputType === 'both') {
      result.uri = captureCanvas.toDataURL('image/jpeg', quality);
    }

    // Save to gallery if requested (limited on web)
    if (options?.saveToGallery) {
      const link = document.createElement('a');
      link.download = `camera-${Date.now()}.jpg`;
      link.href = captureCanvas.toDataURL('image/jpeg', quality);
      link.click();
    }

    return result;
  }

  async addLayer(layer: CameraLayer): Promise<{ layerId: string }> {
    const id = layer.id || this.generateId();
    this.layers.set(id, { ...layer, id });
    return { layerId: id };
  }

  async updateLayer(options: {
    layerId: string;
    layer: Partial<CameraLayer>;
  }): Promise<void> {
    const existing = this.layers.get(options.layerId);
    if (existing) {
      this.layers.set(options.layerId, { ...existing, ...options.layer });
    }
  }

  async removeLayer(options: { layerId: string }): Promise<void> {
    this.layers.delete(options.layerId);
  }

  async clearLayers(): Promise<void> {
    this.layers.clear();
  }

  async switchCamera(): Promise<void> {
    const newCamera = this.currentCamera === 'back' ? 'front' : 'back';
    
    // Store current options
    const currentOptions: CameraStartOptions = {
      position: newCamera,
      width: this.containerElement?.offsetWidth,
      height: this.containerElement?.offsetHeight,
      x: this.containerElement ? parseInt(this.containerElement.style.left) : 0,
      y: this.containerElement ? parseInt(this.containerElement.style.top) : 0,
    };

    // Stop current camera
    await this.stop();

    // Start with new camera
    await this.start(currentOptions);
  }

  async setFlashMode(options: { mode: FlashMode }): Promise<void> {
    // Limited support on web - attempt to use torch if supported
    if (this.stream) {
      const track = this.stream.getVideoTracks()[0];
      const capabilities = track.getCapabilities() as any;

      if (capabilities.torch) {
        try {
          await track.applyConstraints({
            advanced: [{ torch: options.mode === 'torch' || options.mode === 'on' } as any],
          } as any);
        } catch (error) {
          // Torch not supported, silently fail
        }
      }
    }
  }

  async setZoom(options: { zoom: number }): Promise<void> {
    if (this.stream) {
      const track = this.stream.getVideoTracks()[0];
      const capabilities = track.getCapabilities() as any;

      if (capabilities.zoom) {
        try {
          await track.applyConstraints({
            advanced: [{ zoom: options.zoom } as any],
          } as any);
        } catch (error) {
          // Zoom not supported, silently fail
        }
      }
    }
  }

  async setFocus(options: { x: number; y: number }): Promise<void> {
    // Limited support on web - focus controls not widely supported in browsers
    // This is a placeholder for future implementation when browser support improves
  }

  private generateId(): string {
    return `layer_${Date.now()}_${Math.random().toString(36).slice(2, 11)}`;
  }

  private renderLayers(): void {
    if (!this.overlayCanvas) {
      return;
    }

    const ctx = this.overlayCanvas.getContext('2d');
    if (!ctx) {
      return;
    }

    // Clear canvas
    ctx.clearRect(0, 0, this.overlayCanvas.width, this.overlayCanvas.height);

    // Draw layers
    this.compositeLayers(ctx, this.overlayCanvas.width, this.overlayCanvas.height);

    // Continue rendering
    this.animationFrameId = requestAnimationFrame(() => this.renderLayers());
  }

  private compositeLayers(
    ctx: CanvasRenderingContext2D,
    canvasWidth: number,
    canvasHeight: number
  ): void {
    // Sort layers by zIndex
    const sortedLayers = Array.from(this.layers.values()).sort(
      (a, b) => (a.zIndex || 0) - (b.zIndex || 0)
    );

    for (const layer of sortedLayers) {
      ctx.save();

      // Set opacity
      ctx.globalAlpha = layer.opacity !== undefined ? layer.opacity : 1;

      // Calculate position and size
      const x = layer.x < 1 ? layer.x * canvasWidth : layer.x;
      const y = layer.y < 1 ? layer.y * canvasHeight : layer.y;
      const width = layer.width
        ? layer.width < 1
          ? layer.width * canvasWidth
          : layer.width
        : canvasWidth;
      const height = layer.height
        ? layer.height < 1
          ? layer.height * canvasHeight
          : layer.height
        : canvasHeight;

      // Apply rotation if specified
      if (layer.rotation) {
        ctx.translate(x + width / 2, y + height / 2);
        ctx.rotate((layer.rotation * Math.PI) / 180);
        ctx.translate(-(x + width / 2), -(y + height / 2));
      }

      // Draw based on layer type
      if (layer.type === 'text' && layer.text) {
        this.drawTextLayer(ctx, layer, x, y, width, height);
      } else if (layer.type === 'shape') {
        this.drawShapeLayer(ctx, layer, x, y, width, height);
      } else if (layer.type === 'image' && (layer.imagePath || layer.imageUrl)) {
        this.drawImageLayer(ctx, layer, x, y, width, height);
      }

      ctx.restore();
    }
  }

  private drawTextLayer(
    ctx: CanvasRenderingContext2D,
    layer: CameraLayer,
    x: number,
    y: number,
    width: number,
    height: number
  ): void {
    const fontSize = layer.fontSize || 16;
    const fontFamily = layer.fontFamily || 'Arial';
    const fontColor = layer.fontColor || '#FFFFFF';
    const textAlign = layer.textAlign || 'left';
    const padding = layer.padding || 0;

    ctx.font = `${fontSize}px ${fontFamily}`;
    ctx.fillStyle = fontColor;
    ctx.textAlign = textAlign;
    ctx.textBaseline = 'top';

    // Draw background if specified
    if (layer.backgroundColor) {
      ctx.fillStyle = layer.backgroundColor;
      ctx.fillRect(x, y, width, height);
      ctx.fillStyle = fontColor;
    }

    // Draw text
    const textX = textAlign === 'center' ? x + width / 2 : textAlign === 'right' ? x + width - padding : x + padding;
    const textY = y + padding;

    if (layer.text) {
      ctx.fillText(layer.text, textX, textY);
    }
  }

  private drawShapeLayer(
    ctx: CanvasRenderingContext2D,
    layer: CameraLayer,
    x: number,
    y: number,
    width: number,
    height: number
  ): void {
    const strokeColor = layer.strokeColor || '#000000';
    const strokeWidth = layer.strokeWidth || 1;
    const fillColor = layer.fillColor || 'transparent';

    ctx.strokeStyle = strokeColor;
    ctx.lineWidth = strokeWidth;
    ctx.fillStyle = fillColor;

    if (layer.shapeType === 'rectangle') {
      if (fillColor !== 'transparent') {
        ctx.fillRect(x, y, width, height);
      }
      ctx.strokeRect(x, y, width, height);
    } else if (layer.shapeType === 'circle') {
      ctx.beginPath();
      ctx.arc(x + width / 2, y + height / 2, Math.min(width, height) / 2, 0, 2 * Math.PI);
      if (fillColor !== 'transparent') {
        ctx.fill();
      }
      ctx.stroke();
    } else if (layer.shapeType === 'line') {
      ctx.beginPath();
      ctx.moveTo(x, y);
      ctx.lineTo(x + width, y + height);
      ctx.stroke();
    }
  }

  private drawImageLayer(
    ctx: CanvasRenderingContext2D,
    layer: CameraLayer,
    x: number,
    y: number,
    width: number,
    height: number
  ): void {
    // Note: Actual image loading would require async handling
    // This is a simplified version - production would cache loaded images
    const img = new Image();
    img.onload = () => {
      ctx.drawImage(img, x, y, width, height);
    };
    if (layer.imagePath) {
      img.src = layer.imagePath;
    } else if (layer.imageUrl) {
      img.src = layer.imageUrl;
    }
  }

  private setupZoomGesture(): void {
    // Implement pinch-to-zoom for web (limited support)
    // This is a simplified version
  }

  private setupFocusGesture(): void {
    // Implement tap-to-focus for web (limited support)
    // This is a simplified version
  }
}
