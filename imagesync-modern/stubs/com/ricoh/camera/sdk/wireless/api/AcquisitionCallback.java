package com.ricoh.camera.sdk.wireless.api;

/* Generated compile-time stub - not packaged. */
public interface AcquisitionCallback {
    void error(CameraImage cameraImage, Acquisition acquisition);
    void progress(CameraImage cameraImage, Acquisition acquisition);
    void success(CameraImage cameraImage, Acquisition acquisition);
}
