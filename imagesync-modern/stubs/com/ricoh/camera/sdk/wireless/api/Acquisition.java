package com.ricoh.camera.sdk.wireless.api;

/* Generated compile-time stub - not packaged. */
import com.ricoh.camera.sdk.wireless.api.response.Error;
import java.io.OutputStream;
import java.util.List;

public interface Acquisition {
    void cancel();
    long getCurrentSize();
    List<Error> getErrors();
    OutputStream getOutputStream();
    AcquisitionState getState();
    long getTotalSize();
}
