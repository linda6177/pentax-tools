package com.ricoh.camera.sdk.wireless.api;

/* Generated compile-time stub - not packaged. */
import com.ricoh.camera.sdk.wireless.api.response.Response;
import java.io.OutputStream;
import java.util.Date;

public interface CameraImage {
    Response getData(OutputStream outputStream);
    Acquisition getDataAsync(OutputStream outputStream, AcquisitionCallback acquisitionCallback);
    void getDataAsync(ImageDataStream imageDataStream);
    Date getDateTime();
    Response getExtraSmallData(OutputStream outputStream);
    void getExtraSmallDataAsync(ImageDataStream imageDataStream);
    long getFileSize();
    String getFolderName();
    ImageFormat getFormat();
    String getName();
    ImageOrientation getOrientation();
    CameraStorage getStorage();
    Response getThumbnail(OutputStream outputStream);
    Acquisition getThumbnailAsync(OutputStream outputStream, AcquisitionCallback acquisitionCallback);
    void getThumbnailAsync(ImageDataStream imageDataStream);
    ImageType getType();
    Response getViewData(OutputStream outputStream);
    Acquisition getViewDataAsync(OutputStream outputStream, AcquisitionCallback acquisitionCallback);
    void getViewDataAsync(ImageDataStream imageDataStream);
    boolean hasExtraSmallData();
    boolean hasThumbnail();
    boolean hasViewData();
    Response retrieveInfo();
}
