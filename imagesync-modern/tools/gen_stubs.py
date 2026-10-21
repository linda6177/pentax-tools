"""Generate minimal compile-time stubs for ORIGINAL Image Sync classes.

The modern UI is compiled against these stubs and shipped as classes3.dex only.
The stub classes themselves are NEVER packaged: at runtime the real classes from
classes.dex / classes2.dex are loaded by the same PathClassLoader, so every
reference resolves to the original Ricoh Camera SDK implementation.

Signatures are copied verbatim from the JADX output of the shipped APK
(sha256 00756f0c0474c7f6ac0271b817effadf15e92362d49eab9d86cd0d6bba639442).
Keep descriptors exactly equal to the dex or the runtime link fails with
NoSuchMethodError / NoSuchFieldError.
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "stubs"

API = "com.ricoh.camera.sdk.wireless.api"
RESP = API + ".response"
CAP = API + ".setting.capture"

HEADER = "/* Generated compile-time stub - not packaged. */\n"

FILES: dict[str, str] = {}


def add(pkg: str, name: str, body: str) -> None:
    FILES[f"{pkg.replace('.', '/')}/{name}.java"] = (
        "package " + pkg + ";\n\n" + HEADER + body.strip() + "\n"
    )


# --------------------------------------------------------------------------
# enums
# --------------------------------------------------------------------------
add(API, "DeviceInterface", """
public enum DeviceInterface { WLAN, BLE }
""")
add(API, "DetectState", """
public enum DetectState { NOT_DETECTING, DETECTING }
""")
add(API, "ImageFormat", """
public enum ImageFormat { UNKNOWN, JPEG, TIFF, DPOF, PEF, DNG, MP4, MOV, AVI }
""")
add(API, "ImageType", """
public enum ImageType { UNKNOWN, STILL_IMAGE, MOVIE }
""")
add(API, "ImageOrientation", """
public enum ImageOrientation {
    HORIZONTAL, MIRROR_HORIZONTAL, ROTATE180, MIRROR_VERTICAL,
    MIRROR_HORIZONTAL_AND_ROTATE270CW, ROTATE90CW,
    MIRROR_HORIZONTAL_AND_ROTATE90CW, ROTATE270CW
}
""")
add(API, "StorageType", """
public enum StorageType { FIXED_RAM, REMOVABLE_RAM }
""")
add(API, "StorageListImagesState", """
public enum StorageListImagesState {
    NOT_LISTED, LISTING, PENDING, LISTED, INTERRUPTED
}
""")
add(API, "StoragePermission", """
public enum StoragePermission { UNKNOWN, READ_WRITE, READ }
""")
add(API, "BatteryUsed", """
public enum BatteryUsed { BATTERY, ACADAPTER }
""")
add(API, "TransferQuality", """
public enum TransferQuality { FULL, EXTRA_SMALL }
""")
add(API, "CaptureState", """
public enum CaptureState { UNKNOWN, EXECUTING, COMPLETE, CANCELLED }
""")
add(API, "AcquisitionState", """
public enum AcquisitionState { PROCESSING, SUCCEED, ERROR, CANCELLED, UNKNOWN }
""")
add(RESP, "Result", """
public enum Result { OK, ERROR }
""")
add(RESP, "ErrorCode", """
public enum ErrorCode {
    NETWORK_ERROR, DEVICE_NOT_FOUND, IMAGE_NOT_FOUND, INVALID_SETTING,
    INVALID_ARGUMENT, NO_SPACE, FORBIDDEN, UNKNOWN_VALUE, IO_ERROR
}
""")

# --------------------------------------------------------------------------
# simple value / response classes
# --------------------------------------------------------------------------
add(API, "Point", """
public class Point {
    public Point(float x, float y) { }
    public float getX() { return 0f; }
    public float getY() { return 0f; }
}
""")
add(RESP, "Error", """
public final class Error {
    public ErrorCode getCode() { return null; }
    public String getMessage() { return null; }
}
""")
add(RESP, "Response", """
import java.util.List;

public class Response {
    public Response(Result result) { }
    public List<Error> getErrors() { return null; }
    public Result getResult() { return null; }
}
""")
add(RESP, "StartCaptureResponse", """
import com.ricoh.camera.sdk.wireless.api.Capture;

public final class StartCaptureResponse extends Response {
    public StartCaptureResponse(Result result) { super(result); }
    public Capture getCapture() { return null; }
}
""")

# --------------------------------------------------------------------------
# callback / acquisition contracts
# --------------------------------------------------------------------------
add(API, "AcquisitionCallback", """
public interface AcquisitionCallback {
    void error(CameraImage cameraImage, Acquisition acquisition);
    void progress(CameraImage cameraImage, Acquisition acquisition);
    void success(CameraImage cameraImage, Acquisition acquisition);
}
""")
add(API, "Acquisition", """
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
""")
add(API, "ImageDataStream", """
import java.io.OutputStream;

public class ImageDataStream {
    public ImageDataStream(OutputStream outputStream) { }
    public void cancel() { }
    public Acquisition getAcquisition() { return null; }
    public AcquisitionCallback getAcquisitionCallback() { return null; }
    public long getCurrentSize() { return 0L; }
    public java.util.List<com.ricoh.camera.sdk.wireless.api.response.Error> getErrors() { return null; }
    public OutputStream getOutputStream() { return null; }
    public AcquisitionState getState() { return null; }
    public long getTotalSize() { return 0L; }
}
""")
add(API, "SelfTimer", """
public interface SelfTimer {
    enum State { IDLE, BUSY2S, BUSY10S, BUSY12S, TIME }
    State getCountDownState();
}
""")
add(API, "Capture", """
import com.ricoh.camera.sdk.wireless.api.setting.capture.CaptureMethod;

public interface Capture {
    String getId();
    CaptureMethod getMethod();
    int getNumCurrentShots();
    SelfTimer getSelfTimer();
    CaptureState getState();
}
""")

# --------------------------------------------------------------------------
# camera / image / storage contracts
# --------------------------------------------------------------------------
add(API, "CameraStatus", """
public interface CameraStatus {
    int getBatteryLevel();
    BatteryUsed getBatteryUsed();
    Capture getCurrentCapture();
}
""")
add(API, "CameraStorage", """
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
""")
add(API, "CameraImage", """
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
""")
add(API, "CameraEventListener", """
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
""")
add(API, "CameraDevice", """
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
""")
add(API, "CameraTransferImage", """
import com.ricoh.camera.sdk.wireless.api.response.Response;

public abstract class CameraTransferImage implements CameraImage {
    public abstract String getStorageID();
    public abstract TransferQuality getTransferQuality();
    public abstract Response setTransferStatus(TransferStatus transferStatus);
}
""")
add(API, "TransferStatus", """
public enum TransferStatus { UNKNOWN, WAITING, TRANSFERRING, COMPLETE, ERROR }
""")
add(API, "CameraDeviceDetector", """
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
""")

# --------------------------------------------------------------------------
# capture settings
# --------------------------------------------------------------------------
add(CAP, "CaptureSetting", """
import java.util.List;

public abstract class CaptureSetting {
    public static class Value {
        public Value(Object value) { }
        public Object get() { return null; }
        public String toString() { return null; }
    }

    public CaptureSetting(String name) { }
    public CaptureSetting(String name, Value value) { }
    public List<CaptureSetting> getAvailableSettings() { return null; }
    public String getName() { return null; }
    public Value getValue() { return null; }
    public String toString() { return null; }
}
""")
add(CAP, "CaptureMethod", """
public class CaptureMethod extends CaptureSetting {
    public CaptureMethod(String value) { super("CaptureMethod"); }
}
""")

SETTINGS = [
    "CameraOrientation", "CompositionAdjustmentEnable", "CountDown", "CustomImage",
    "DigitalFilter", "ExposureCompensation", "ExposureMeteringMode", "ExposureProgram",
    "FlashMode", "FNumber", "FocusMethod", "FocusSetting", "GradND", "ISO",
    "MovieCaptureFormat", "ShutterSpeed", "StillImageCaptureFormat",
    "StillImageCaptureFormatAndQuality", "StillImageQuality", "Type3CaptureState",
    "Type3PreCaptureResult", "UserExposureProgram", "WGM2Option", "WhiteBalance",
    "ZoomLevel",
]
for _setting in SETTINGS:
    add(CAP, _setting, f"""
public class {_setting} extends CaptureSetting {{
    public {_setting}() {{ super("{_setting}"); }}
    public {_setting}(String value) {{ super("{_setting}"); }}
}}
""")

# Bool/Integer based settings used by remote UI (supported-value probing reads
# getValue() text, so a String constructor is enough to request a value).
add(CAP, "AstroTrackingTime", """
public class AstroTrackingTime extends CaptureSetting {
    public AstroTrackingTime(Integer value) { super("AstroTrackingTime"); }
}
""")
add(CAP, "NumTotalShots", """
public class NumTotalShots extends CaptureSetting {
    public NumTotalShots(Integer value) { super("NumTotalShots"); }
}
""")
add(CAP, "AutoFocusRunning", """
public class AutoFocusRunning extends CaptureSetting {
    public AutoFocusRunning(boolean value) { super("AutoFocusRunning"); }
}
""")
add(CAP, "HyperExposureProgramEnable", """
public class HyperExposureProgramEnable extends CaptureSetting {
    public HyperExposureProgramEnable(boolean value) { super("HyperExposureProgramEnable"); }
}
""")
add(CAP, "OnePushBracketEnable", """
public class OnePushBracketEnable extends CaptureSetting {
    public OnePushBracketEnable(boolean value) { super("OnePushBracketEnable"); }
}
""")
add(CAP, "RemoconEnable", """
public class RemoconEnable extends CaptureSetting {
    public RemoconEnable(boolean value) { super("RemoconEnable"); }
}
""")

# --------------------------------------------------------------------------
# camera settings (only the ones the modern UI reads)
# --------------------------------------------------------------------------
add(API + ".setting.camera", "CameraDeviceSetting", """
import java.util.List;

public abstract class CameraDeviceSetting {
    public static class Value {
        public Value(Object value) { }
        public Object get() { return null; }
        public String toString() { return null; }
    }

    public CameraDeviceSetting(String name) { }
    public CameraDeviceSetting(String name, Value value) { }
    public List<CameraDeviceSetting> getAvailableSettings() { return null; }
    public String getName() { return null; }
    public Value getValue() { return null; }
}
""")
for _name in ("LiveViewSpecification", "WLANEnable", "WLANSetting", "CameraTime",
              "OperationMode", "CardSlot", "Key", "Channel", "SSID",
              "AutoResizeEnable", "GEOTaggingEnable"):
    add(API + ".setting.camera", _name, f"""
public class {_name} extends CameraDeviceSetting {{
    public {_name}() {{ super("{_name}"); }}
    public {_name}(String value) {{ super("{_name}"); }}
}}
""")

# --------------------------------------------------------------------------
# application holder that owns the connected CameraDevice (original field name)
# --------------------------------------------------------------------------
add("com.ricohimaging.imagesync", "SvApplication", """
import android.app.Application;
import com.ricoh.camera.sdk.wireless.api.CameraDevice;

public class SvApplication extends Application {
    /* Field names are the raw DEX names (JADX displays them renamed). */
    public CameraDevice a;
}
""")


def main() -> None:
    if OUT.exists():
        for path in OUT.rglob("*.java"):
            path.unlink()
    for relative, content in FILES.items():
        target = OUT / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
    print(f"generated {len(FILES)} stub sources in {OUT}")


if __name__ == "__main__":
    main()
