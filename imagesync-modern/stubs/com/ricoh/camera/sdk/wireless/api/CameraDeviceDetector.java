package com.ricoh.camera.sdk.wireless.api;

/* Generated compile-time stub - not packaged. */
import android.content.Context;
import com.ricoh.camera.sdk.wireless.api.response.Response;
import java.util.List;

public class CameraDeviceDetector {
    public static void clearCache() { }
    public static List<CameraDevice> detect(DeviceInterface deviceInterface) { return null; }
    public static void disconnectAlreadyConnectedDevice() { }
    public static String getBleDeviceName(CameraDevice cameraDevice) { return null; }
    public static List<String> getBondedBleDeviceNames() { return null; }
    public static DetectState getDetectState(DeviceInterface deviceInterface) { return null; }
    public static List<CameraDevice> getDetectedCameraDevice(DeviceInterface deviceInterface) { return null; }
    public static void setContext(Context context) { }
    public static Response startDetect(DeviceInterface deviceInterface, int timeoutMillis) { return null; }
    public static Response stopDetect(DeviceInterface deviceInterface) { return null; }
}
