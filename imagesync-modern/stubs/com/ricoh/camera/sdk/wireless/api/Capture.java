package com.ricoh.camera.sdk.wireless.api;

/* Generated compile-time stub - not packaged. */
import com.ricoh.camera.sdk.wireless.api.setting.capture.CaptureMethod;

public interface Capture {
    String getId();
    CaptureMethod getMethod();
    int getNumCurrentShots();
    SelfTimer getSelfTimer();
    CaptureState getState();
}
