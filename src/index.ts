import { registerPlugin } from '@capacitor/core';
import type { CameraLayersPlugin } from './definitions';

const CameraLayers = registerPlugin<CameraLayersPlugin>('CameraLayers', {
  web: () => import('./web').then(m => new m.CameraLayersWeb()),
});

export * from './definitions';
export { CameraLayers };
