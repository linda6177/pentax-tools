package com.ricoh.camera.sdk.wireless.api;

/* Generated compile-time stub - not packaged. */
import com.ricoh.camera.sdk.wireless.api.response.Response;
import com.ricoh.camera.sdk.wireless.api.response.StartCaptureResponse;
import com.ricoh.camera.sdk.wireless.api.setting.camera.CameraDeviceSetting;
import com.ricoh.camera.sdk.wireless.api.setting.capture.CaptureSetting;
import java.util.List;

public interface CameraDevice {
    void addEventListener(CameraEventListener cameraEventListener);
    void removeEventListener(CameraEventListener cameraEventListener);
    CameraEventListener[] getEventListeners();
    Response connect(DeviceInterface deviceInterface);
    Response disconnect(DeviceInterface deviceInterface);
    boolean isConnected(DeviceInterface deviceInterface);
    String getModel();
    String getManufacturer();
    String getSerialNumber();
    String getFirmwareVersion();
    boolean isPower();
    boolean isCapturingMovie();
    CameraStatus getStatus();
    List<CameraStorage> getStorages();
    List<CameraImage> getImages();
    Response updateImages();
    CameraImage getLatestCaptureImage();
    Response startLiveView();
    Response stopLiveView();
    StartCaptureResponse startCapture(boolean continuous);
    StartCaptureResponse startCapture(Point point);
    Response finishCapture();
    Response stopCapture();
    Response focus();
    Response focus(Point point);
    Response applyGreenButton();
    Response getCaptureSettings(List<CaptureSetting> settings);
    Response setCaptureSettings(List<CaptureSetting> settings);
    Response getCameraDeviceSettings(List<CameraDeviceSetting> settings);
    Response setCameraDeviceSettings(List<CameraDeviceSetting> settings);
    Response sendCameraMemoList(String memo);
    boolean setBackgroundSyncImages(boolean enabled);
    boolean setSortMode(boolean descending);
    List<CameraTransferImage> getTransferImages();
}
