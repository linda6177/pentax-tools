package com.ricoh.camera.sdk.wireless.api;

/* Generated compile-time stub - not packaged. */
import com.ricoh.camera.sdk.wireless.api.response.Response;

public abstract class CameraEventListener {
    public void captureComplete(CameraDevice cameraDevice, Capture capture) { }
    public void deviceDisconnected(CameraDevice cameraDevice) { }
    public void focusComplete(CameraDevice cameraDevice, Response response, Capture capture) { }
    public void imageStored(CameraDevice cameraDevice, CameraImage cameraImage) { }
    public void latestCaptureImageChanged(CameraDevice cameraDevice) { }
    public void liveViewFrameUpdated(CameraDevice cameraDevice, byte[] frame) { }
    public void transferImagesNotEmptied(CameraDevice cameraDevice) { }
}
