package com.ricoh.camera.sdk.wireless.api;

/* Generated compile-time stub - not packaged. */
import java.util.List;

public interface CameraStorage {
    String getId();
    List<CameraImage> getImages();
    StorageListImagesState getListImagesState();
    StoragePermission getPermission();
    int getRecordableTime();
    int getRemainingPictures();
    StorageType getType();
    boolean isAvailable();
    boolean isFormatted();
    boolean isWritingTarget();
}
