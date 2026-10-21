package com.ricoh.camera.sdk.wireless.api;

/* Generated compile-time stub - not packaged. */
import com.ricoh.camera.sdk.wireless.api.response.Response;

public abstract class CameraTransferImage implements CameraImage {
    public abstract String getStorageID();
    public abstract TransferQuality getTransferQuality();
    public abstract Response setTransferStatus(TransferStatus transferStatus);
}
