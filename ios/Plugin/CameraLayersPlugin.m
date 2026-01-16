#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

CAP_PLUGIN(CameraLayersPlugin, "CameraLayers",
    CAP_PLUGIN_METHOD(start, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(stop, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(capture, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(addLayer, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(updateLayer, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(removeLayer, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(clearLayers, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(switchCamera, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(setFlashMode, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(setZoom, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(setFocus, CAPPluginReturnPromise);
)
