package com.ricohimaging.imagesync.modern;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;

import com.ricoh.camera.sdk.wireless.api.Acquisition;
import com.ricoh.camera.sdk.wireless.api.AcquisitionCallback;
import com.ricoh.camera.sdk.wireless.api.CameraDevice;
import com.ricoh.camera.sdk.wireless.api.CameraDeviceDetector;
import com.ricoh.camera.sdk.wireless.api.CameraEventListener;
import com.ricoh.camera.sdk.wireless.api.CameraImage;
import com.ricoh.camera.sdk.wireless.api.CameraStorage;
import com.ricoh.camera.sdk.wireless.api.CameraStatus;
import com.ricoh.camera.sdk.wireless.api.Capture;
import com.ricoh.camera.sdk.wireless.api.CaptureState;
import com.ricoh.camera.sdk.wireless.api.DeviceInterface;
import com.ricoh.camera.sdk.wireless.api.Point;
import com.ricoh.camera.sdk.wireless.api.response.Response;
import com.ricoh.camera.sdk.wireless.api.response.Result;
import com.ricoh.camera.sdk.wireless.api.response.StartCaptureResponse;
import com.ricoh.camera.sdk.wireless.api.setting.camera.CameraDeviceSetting;
import com.ricoh.camera.sdk.wireless.api.setting.camera.Key;
import com.ricoh.camera.sdk.wireless.api.setting.camera.SSID;
import com.ricoh.camera.sdk.wireless.api.setting.capture.CaptureMethod;
import com.ricoh.camera.sdk.wireless.api.setting.capture.CaptureSetting;
import com.ricohimaging.imagesync.SvApplication;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thin, defensive facade over the ORIGINAL Ricoh Camera SDK.
 *
 * <p>The rebuilt UI never re-implements the camera protocol: every network
 * operation here funnels into the same classes the official app uses. All SDK
 * calls run on worker threads (the SDK does blocking HTTP with retries) and
 * results are published on the main thread.</p>
 */
public final class CameraBridge {

    public enum State { DISCONNECTED, BLE_ONLY, CONNECTED }

    public interface StateListener {
        void onStateChanged(State state);
    }

    public interface ImageListCallback {
        void onImages(List<CameraImage> images);
        void onError(String message);
    }

    public interface BitmapCallback {
        void onBitmap(Bitmap bitmap);
        void onError(String message);
    }

    public interface ByteCallback {
        void onBytes(byte[] data);
        void onError(String message);
    }

    public interface ProgressCallback {
        void onProgress(long current, long total);
    }

    public interface SaveCallback {
        void onSaved(String displayPath);
        void onError(String message);
    }

    public interface CaptureCallback {
        void onStarted();
        void onComplete();
        void onError(String message);
    }

    public interface FocusCallback {
        void onFocused(boolean success);
    }

    public interface LiveViewCallback {
        void onFrame(Bitmap bitmap);
        void onError(String message);
    }

    public interface ConnectCallback {
        void onConnected(CameraDevice device);
        void onError(String message);
    }

    public interface SettingsCallback {
        void onSettings(LinkedHashMap<String, SettingInfo> settings);
        void onError(String message);
    }

    public interface WifiInfoCallback {
        void onInfo(String ssid, String key, String message);
    }

    public static final int QUALITY_ORIGINAL = 0;
    public static final int QUALITY_COMPRESSED = 1;

    public interface SimpleCallback {
        void onDone(boolean success, String message);
    }

    /** Read-only snapshot of one capture setting. */
    public static final class SettingInfo {
        public final String name;
        public final String value;
        public final List<String> choices;

        SettingInfo(String name, String value, List<String> choices) {
            this.name = name;
            this.value = value;
            this.choices = choices;
        }

        public int indexOfValue() {
            for (int i = 0; i < choices.size(); i++) {
                if (choices.get(i).equals(value)) return i;
            }
            return -1;
        }
    }

    /** Setting names as exposed by the original ricoh SDK classes. */
    public static final String S_EXPOSURE_PROGRAM = "ExposureProgram";
    public static final String S_ISO = "ISO";
    public static final String S_F_NUMBER = "FNumber";
    public static final String S_SHUTTER_SPEED = "ShutterSpeed";
    public static final String S_EXPOSURE_COMPENSATION = "ExposureCompensation";
    public static final String S_WHITE_BALANCE = "WhiteBalance";
    public static final String S_STILL_QUALITY = "StillImageQuality";
    public static final String S_STILL_FORMAT = "StillImageCaptureFormat";
    public static final String S_FLASH_MODE = "FlashMode";
    public static final String S_METERING = "ExposureMeteringMode";
    public static final String S_DIGITAL_FILTER = "DigitalFilter";
    public static final String S_ZOOM = "ZoomLevel";
    public static final String S_CAPTURE_METHOD = "CaptureMethod";

    private static final String PACKAGE = "com.ricohimaging.imagesync";
    private static final String SETTING_PACKAGE =
            "com.ricoh.camera.sdk.wireless.api.setting.capture.";
    private static final String PREF_TERMS = "SHARED_PREFERENCE_KEY_TERMS_VERSION_NAME";

    private static volatile CameraBridge instance;

    private final SvApplication application;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final ExecutorService frameDecoder = Executors.newSingleThreadExecutor();
    private final List<StateListener> listeners = new ArrayList<>();

    private final AtomicBoolean detecting = new AtomicBoolean(false);
    private final AtomicBoolean liveViewOn = new AtomicBoolean(false);
    private final AtomicLong frameSequence = new AtomicLong();

    private CameraEventListener sdkListener;
    private CameraDevice watchedDevice;
    private LiveViewCallback liveViewCallback;
    private CaptureCallback pendingCapture;
    private FocusCallback pendingFocus;

    private CameraBridge(SvApplication application) {
        this.application = application;
    }

    public static CameraBridge get(Context context) {
        if (instance == null) {
            synchronized (CameraBridge.class) {
                if (instance == null) {
                    instance = new CameraBridge((SvApplication) context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    // ------------------------------------------------------------------ state
    public State state() {
        CameraDevice device = device();
        if (device == null) return State.DISCONNECTED;
        try {
            if (device.isConnected(DeviceInterface.WLAN)) return State.CONNECTED;
            if (device.isConnected(DeviceInterface.BLE)) return State.BLE_ONLY;
        } catch (Throwable ignored) {
            // Fall through: treat unexpected SDK failure as disconnected.
        }
        return State.DISCONNECTED;
    }

    public CameraDevice device() {
        try {
            return application.a;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public String model() {
        CameraDevice device = device();
        if (device == null) return null;
        try {
            return device.getModel();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public String firmware() {
        CameraDevice device = device();
        if (device == null) return null;
        try {
            return device.getFirmwareVersion();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Camera firmware as the body UI shows it ("02.50" -> "2.50"). */
    public static String prettyFirmware(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        StringBuilder out = new StringBuilder(raw.length());
        int start = 0;
        for (int i = 0; i <= raw.length(); i++) {
            if (i == raw.length() || raw.charAt(i) == '.') {
                int begin = start;
                while (begin < i - 1 && raw.charAt(begin) == '0'
                        && Character.isDigit(raw.charAt(begin + 1))) {
                    begin++;
                }
                out.append(raw, begin, i);
                if (i < raw.length()) out.append('.');
                start = i + 1;
            }
        }
        return out.toString();
    }

    /** Normalised firmware string, or null when disconnected. */
    public String displayFirmware() {
        return prettyFirmware(firmware());
    }

    public int batteryLevel() {
        CameraDevice device = device();
        if (device == null) return -1;
        try {
            CameraStatus status = device.getStatus();
            return status == null ? -1 : status.getBatteryLevel();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public boolean charging() {
        CameraDevice device = device();
        if (device == null) return false;
        try {
            CameraStatus status = device.getStatus();
            if (status == null || status.getBatteryUsed() == null) return false;
            return "ACADAPTER".equals(status.getBatteryUsed().name());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean termsAccepted(Activity activity) {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
            String accepted = prefs.getString(PREF_TERMS, "");
            int id = activity.getResources().getIdentifier("terms_version", "string", PACKAGE);
            String required = id == 0 ? "" : activity.getString(id);
            return required.isEmpty() || required.equals(accepted);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public void addStateListener(StateListener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
        attachSdkListener();
        listener.onStateChanged(state());
    }

    public void removeStateListener(StateListener listener) {
        listeners.remove(listener);
        if (listeners.isEmpty()) detachSdkListener();
    }

    private void notifyState() {
        State current = state();
        for (StateListener listener : new ArrayList<>(listeners)) {
            listener.onStateChanged(current);
        }
    }

    private void attachSdkListener() {
        CameraDevice device = device();
        if (device == null || device == watchedDevice) return;
        detachSdkListener();
        watchedDevice = device;
        try {
            sdkListener = new CameraEventListener() {
                @Override
                public void deviceDisconnected(CameraDevice cameraDevice) {
                    main.post(() -> {
                        notifyState();
                        stopLiveViewInternal();
                    });
                }

                @Override
                public void imageStored(CameraDevice cameraDevice, CameraImage cameraImage) {
                    main.post(() -> {
                        if (liveViewCallback == null) notifyState();
                    });
                }

                @Override
                public void captureComplete(CameraDevice cameraDevice, Capture capture) {
                    final CaptureCallback callback = pendingCapture;
                    pendingCapture = null;
                    if (callback != null) main.post(callback::onComplete);
                }

                @Override
                public void focusComplete(CameraDevice cameraDevice, Response response,
                                          Capture capture) {
                    final FocusCallback callback = pendingFocus;
                    pendingFocus = null;
                    boolean success = response != null && response.getResult() == Result.OK;
                    if (callback != null) main.post(() -> callback.onFocused(success));
                }

                @Override
                public void liveViewFrameUpdated(CameraDevice cameraDevice, byte[] frame) {
                    LiveViewCallback callback = liveViewCallback;
                    if (callback == null || frame == null || frame.length == 0) return;
                    final long sequence = frameSequence.incrementAndGet();
                    frameDecoder.execute(() -> {
                        if (sequence + 2 < frameSequence.get()) return; // drop stale frames
                        final Bitmap bitmap = ImageLoader.decodeFrame(frame);
                        if (bitmap == null) return;
                        main.post(() -> callback.onFrame(bitmap));
                    });
                }
            };
            device.addEventListener(sdkListener);
        } catch (Throwable ignored) {
            watchedDevice = null;
        }
    }

    private void detachSdkListener() {
        if (sdkListener != null && watchedDevice != null) {
            try {
                watchedDevice.removeEventListener(sdkListener);
            } catch (Throwable ignored) {
                // Best effort: the device may already be gone.
            }
        }
        sdkListener = null;
        watchedDevice = null;
    }

    // ---------------------------------------------------------------- connect
    /** Direct Wi-Fi detect+connect, only works when the phone is on the camera AP. */
    public void connectOverWifi(final ConnectCallback callback) {
        ensureBootstrap();
        if (!detecting.compareAndSet(false, true)) {
            callback.onError("检测已在进行中");
            return;
        }
        io.execute(() -> {
            CameraDevice connected = null;
            String error = null;
            try {
                List<CameraDevice> found = CameraDeviceDetector.detect(DeviceInterface.WLAN);
                if (found == null || found.isEmpty()) {
                    error = "未检测到相机。请确认手机已连接到相机的 Wi-Fi";
                } else {
                    CameraDevice candidate = found.get(0);
                    try {
                        candidate.setBackgroundSyncImages(false);
                        candidate.setSortMode(false);
                    } catch (Throwable ignored) {
                        // Optional tuning; keep going.
                    }
                    Response response = candidate.connect(DeviceInterface.WLAN);
                    if (response != null && response.getResult() == Result.OK) {
                        connected = candidate;
                    } else {
                        error = "连接相机失败，请重试";
                    }
                }
            } catch (Throwable failure) {
                error = "连接失败：" + failure.getClass().getSimpleName();
            } finally {
                detecting.set(false);
            }
            final CameraDevice result = connected;
            final String failure = error;
            main.post(() -> {
                if (result != null) {
                    application.a = result;
                    attachSdkListener();
                    notifyState();
                    callback.onConnected(result);
                } else {
                    callback.onError(failure == null ? "未连接" : failure);
                }
            });
        });
    }

    public void disconnect(Activity activity) {
        final CameraDevice device = device();
        if (device == null) return;
        stopLiveView();
        io.execute(() -> {
            try {
                if (device.isConnected(DeviceInterface.WLAN)) {
                    device.disconnect(DeviceInterface.WLAN);
                }
                if (device.isConnected(DeviceInterface.BLE)) {
                    device.disconnect(DeviceInterface.BLE);
                }
            } catch (Throwable ignored) {
                // Clearing the holder below still returns the UI to disconnected.
            }
            main.post(() -> {
                application.a = null;
                detachSdkListener();
                notifyState();
            });
        });
    }

    public void ensureBootstrap() {
        try {
            CameraDeviceDetector.setContext(application);
        } catch (Throwable ignored) {
            // Older SDK builds tolerate a later setContext.
        }
    }

    private static void toast(Activity activity, String message) {
        android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    // --------------------------------------------------------- camera Wi-Fi
    /** Reads the camera WLAN SSID / key as reported by the original SDK. */
    public void readCameraWifi(final WifiInfoCallback callback) {
        final CameraDevice device = device();
        if (device == null || state() != State.CONNECTED) {
            callback.onInfo(null, null, "相机未连接");
            return;
        }
        io.execute(() -> {
            try {
                List<CameraDeviceSetting> request = new ArrayList<>();
                request.add(new SSID());
                request.add(new Key());
                Response response = device.getCameraDeviceSettings(request);
                if (response == null || response.getResult() != Result.OK) {
                    throw new java.io.IOException("camera rejected");
                }
                final String ssid = valueOf(request.get(0));
                final String key = valueOf(request.get(1));
                main.post(() -> callback.onInfo(ssid, key, null));
            } catch (Throwable error) {
                main.post(() -> callback.onInfo(null, null, "无法读取相机 Wi-Fi 设置"));
            }
        });
    }

    private static String valueOf(CameraDeviceSetting setting) {
        if (setting == null || setting.getValue() == null) return null;
        Object value = setting.getValue().get();
        return value == null ? setting.getValue().toString() : value.toString();
    }

    /** Writes SSID/key; the camera may reject this on firmware without WLAN setting write. */
    public void writeCameraWifi(final String ssid, final String key,
                                final SimpleCallback callback) {
        final CameraDevice device = device();
        if (device == null || state() != State.CONNECTED) {
            callback.onDone(false, "相机未连接");
            return;
        }
        io.execute(() -> {
            try {
                List<CameraDeviceSetting> payload = new ArrayList<>();
                payload.add(new SSID(ssid));
                payload.add(new Key(key));
                Response response = device.setCameraDeviceSettings(payload);
                boolean ok = response != null && response.getResult() == Result.OK;
                if (!ok) {
                    // Some firmwares expose the values read-only through the SDK
                    // but accept the same fields on the REST device endpoint.
                    ok = writeWifiOverHttp(ssid, key);
                }
                // A 200 response is not proof: read the values back and only
                // report success when the camera really reports the new values.
                String[] applied = readWifiBlocking(device);
                boolean verified = applied != null
                        && ssid.equals(applied[0]) && key.equals(applied[1]);
                final boolean httpOk = ok;
                final boolean success = verified;
                main.post(() -> callback.onDone(success, success ? ""
                        : (httpOk ? "相机接受了请求但设置未生效（固件限制），请在相机菜单的 WLAN 设置中修改"
                        : "此相机固件不允许在 App 内修改 Wi-Fi 名称/密码，请在相机菜单的 WLAN 设置中修改")));
            } catch (Throwable error) {
                boolean ok = writeWifiOverHttp(ssid, key);
                final boolean success = ok && false;
                main.post(() -> callback.onDone(success, "此相机固件不允许在 App 内修改 Wi-Fi 名称/密码，请在相机菜单的 WLAN 设置中修改"));
            }
        });
    }

    private String[] readWifiBlocking(CameraDevice device) {
        try {
            List<CameraDeviceSetting> request = new ArrayList<>();
            request.add(new SSID());
            request.add(new Key());
            Response response = device.getCameraDeviceSettings(request);
            if (response == null || response.getResult() != Result.OK) return null;
            return new String[]{valueOf(request.get(0)), valueOf(request.get(1))};
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean deleteViaHttp(String folder, String name) {
        String[][] candidates = {
                {"http://192.168.0.1/v1/photos/" + enc(folder) + "/" + enc(name), "DELETE"},
                {"http://192.168.0.1/v1/photos/" + folder + "/" + name, "DELETE"},
                {"http://192.168.0.1/v1/photos/" + enc(folder) + "/" + enc(name), "POST"},
        };
        for (String[] candidate : candidates) {
            java.net.HttpURLConnection connection = null;
            try {
                java.net.URL url = new java.net.URL(candidate[0]);
                connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setRequestMethod(candidate[1]);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("Content-Length", "0");
                if ("POST".equals(candidate[1])) {
                    connection.setRequestProperty("X-HTTP-Method-Override", "DELETE");
                }
                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) return true;
            } catch (Throwable ignored) {
                // Try the next accepted form.
            } finally {
                if (connection != null) {
                    try {
                        connection.disconnect();
                    } catch (Throwable ignored) {
                        // Nothing to release.
                    }
                }
            }
        }
        return false;
    }

    private static String enc(String value) {
        try {
            return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8")
                    .replace("+", "%20");
        } catch (Throwable ignored) {
            return value == null ? "" : value;
        }
    }

    /** Direct REST fallback for firmwares that accept PUT /v1/params/device. */
    private static boolean writeWifiOverHttp(String ssid, String key) {
        java.net.HttpURLConnection connection = null;
        try {
            java.net.URL url = new java.net.URL("http://192.168.0.1/v1/params/device");
            connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PUT");
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(5000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            String body = "{\"ssid\":\"" + ssid.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\",\"key\":\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
            byte[] bytes = body.getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            connection.getOutputStream().write(bytes);
            int code = connection.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                    // Nothing else to release.
                }
            }
        }
    }

    /** Deletes one camera image through the standard Ricoh REST endpoint. */
    public void deleteCameraImage(final CameraImage image, final SimpleCallback callback) {
        if (image == null) {
            callback.onDone(false, "图片不存在");
            return;
        }
        io.execute(() -> {
            java.net.HttpURLConnection connection = null;
            try {
                String folder = image.getFolderName();
                String name = image.getName();
                final boolean ok = deleteViaHttp(folder, name);
                main.post(() -> callback.onDone(ok, ok ? ""
                        : "相机拒绝了删除请求（部分机型不支持 App 内删除）"));
            } catch (Throwable error) {
                main.post(() -> callback.onDone(false, "删除失败，请重试"));
            } finally {
                if (connection != null) {
                    try {
                        connection.disconnect();
                    } catch (Throwable ignored) {
                        // Nothing to release.
                    }
                }
            }
        });
    }

    // ------------------------------------------------------- download quality
    public int downloadQuality() {
        try {
            return application.getSharedPreferences("modern_ui", 0)
                    .getInt("download_quality", QUALITY_ORIGINAL);
        } catch (Throwable ignored) {
            return QUALITY_ORIGINAL;
        }
    }

    public void setDownloadQuality(int quality) {
        try {
            application.getSharedPreferences("modern_ui", 0).edit()
                    .putInt("download_quality", quality == QUALITY_COMPRESSED
                            ? QUALITY_COMPRESSED : QUALITY_ORIGINAL).apply();
        } catch (Throwable ignored) {
            // Keep the previous preference.
        }
    }

    public interface PreviewCallback {
        void onPreview(Bitmap bitmap, String message);
    }

    /** Fast, camera-generated small JPEG preview used by grids and viewers. */
    public void loadPreview(final CameraImage image, final PreviewCallback callback) {
        if (image == null || state() != State.CONNECTED) {
            callback.onPreview(null, "相机未连接");
            return;
        }
        io.execute(() -> {
            Bitmap bitmap = null;
            String message = null;
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                if (image.hasExtraSmallData()) {
                    Response response = image.getExtraSmallData(buffer);
                    if (response != null && response.getResult() == Result.OK) {
                        bitmap = BitmapFactory.decodeByteArray(buffer.toByteArray(), 0,
                                buffer.size());
                    }
                }
                if (bitmap == null && image.hasThumbnail()) {
                    buffer.reset();
                    Response response = image.getThumbnail(buffer);
                    if (response != null && response.getResult() == Result.OK) {
                        bitmap = BitmapFactory.decodeByteArray(buffer.toByteArray(), 0,
                                buffer.size());
                    }
                }
                if (bitmap == null) message = "无法读取预览";
            } catch (Throwable error) {
                message = "无法读取预览";
            }
            final Bitmap result = bitmap;
            final String failure = message;
            main.post(() -> callback.onPreview(result, failure));
        });
    }

    // ---------------------------------------------------------------- gallery
    public void loadCameraImages(final ImageListCallback callback) {
        final CameraDevice device = device();
        if (device == null || state() != State.CONNECTED) {
            callback.onError("相机未连接");
            return;
        }
        io.execute(() -> {
            try {
                Response update = device.updateImages();
                if (update != null && update.getResult() != Result.OK) {
                    throw new java.io.IOException("image update rejected");
                }
                List<CameraStorage> storages = device.getStorages();
                if (storages != null) {
                    // The SDK lists images asynchronously; the original app polls for
                    // up to 60 s. Ten seconds is plenty for a foreground refresh.
                    boolean pending = true;
                    for (int attempt = 0; attempt < 100 && pending; attempt++) {
                        pending = false;
                        for (CameraStorage storage : storages) {
                            if (storage == null || !storage.isAvailable()) continue;
                            String listState = String.valueOf(storage.getListImagesState());
                            if (!"LISTED".equals(listState)) pending = true;
                        }
                        if (pending) Thread.sleep(100L);
                    }
                }
                List<CameraImage> images = new ArrayList<>();
                if (storages != null) {
                    for (CameraStorage storage : storages) {
                        if (storage == null || !storage.isAvailable()) continue;
                        String permission = String.valueOf(storage.getPermission());
                        if ("UNKNOWN".equals(permission)) continue;
                        List<CameraImage> stored = storage.getImages();
                        if (stored != null) images.addAll(stored);
                    }
                }
                if (images.isEmpty()) {
                    List<CameraImage> direct = device.getImages();
                    if (direct != null) images.addAll(direct);
                }
                Collections.sort(images, new Comparator<CameraImage>() {
                    @Override
                    public int compare(CameraImage left, CameraImage right) {
                        int folder = safeFolder(right).compareTo(safeFolder(left));
                        if (folder != 0) return folder;
                        return safeNameOf(right).compareTo(safeNameOf(left));
                    }
                });
                final List<CameraImage> result = images;
                main.post(() -> callback.onImages(result));
            } catch (Throwable error) {
                main.post(() -> callback.onError("读取相机照片失败"));
            }
        });
    }

    private static String safeFolder(CameraImage image) {
        try {
            String folder = image.getFolderName();
            return folder == null ? "" : folder;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safeNameOf(CameraImage image) {
        try {
            String name = image.getName();
            return name == null ? "" : name;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static java.util.Date safeDate(CameraImage image) {
        try {
            return image.getDateTime();
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void loadThumbnail(final CameraImage image, final int targetPx,
                              final BitmapCallback callback) {
        loadImageBytes(image, true, targetPx, callback);
    }

    public void loadPreview(final CameraImage image, final int targetPx,
                            final BitmapCallback callback) {
        loadImageBytes(image, false, targetPx, callback);
    }

    private void loadImageBytes(final CameraImage image, final boolean thumbnail,
                                final int targetPx, final BitmapCallback callback) {
        if (image == null) {
            callback.onError("图片不可用");
            return;
        }
        io.execute(() -> {
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                boolean ok;
                if (thumbnail) {
                    ok = image.hasThumbnail();
                    if (ok) image.getThumbnail(buffer);
                    if (!ok || buffer.size() == 0) {
                        buffer.reset();
                        if (image.hasViewData()) image.getViewData(buffer);
                    }
                } else {
                    if (image.hasViewData()) {
                        image.getViewData(buffer);
                    } else if (image.hasExtraSmallData()) {
                        image.getExtraSmallData(buffer);
                    }
                }
                final byte[] data = buffer.toByteArray();
                Bitmap decoded = ImageLoader.decode(data, targetPx);
                if (decoded == null) throw new java.io.IOException("decode failed");
                final Bitmap bitmap = orient(decoded, image);
                main.post(() -> callback.onBitmap(bitmap));
            } catch (Throwable error) {
                main.post(() -> callback.onError("加载图片失败"));
            }
        });
    }

    /** Downloads full-resolution bytes through the SDK's asynchronous acquisition. */
    public void downloadImage(final CameraImage image, final String displayName,
                              final ProgressCallback progress, final SaveCallback callback) {
        if (image == null) {
            callback.onError("图片不可用");
            return;
        }
        final java.io.File temp;
        try {
            temp = new java.io.File(application.getCacheDir(),
                    "download-" + System.currentTimeMillis() + ".bin");
        } catch (Throwable error) {
            callback.onError("无法创建临时文件");
            return;
        }
        final FileOutputStream stream;
        try {
            stream = new FileOutputStream(temp);
        } catch (Throwable error) {
            callback.onError("无法创建临时文件");
            return;
        }
        final AtomicLong lastProgress = new AtomicLong();
        final boolean compressed = downloadQuality() == QUALITY_COMPRESSED
                && isJpeg(image) && image.hasExtraSmallData();
        try {
            AcquisitionCallback callbackSink = new AcquisitionCallback() {
                @Override
                public void error(CameraImage cameraImage, Acquisition acquisition) {
                    closeQuietly(stream);
                    if (temp.exists()) temp.delete();
                    main.post(() -> callback.onError("下载失败"));
                }

                @Override
                public void progress(CameraImage cameraImage, Acquisition acquisition) {
                    if (progress == null || acquisition == null) return;
                    long now = System.currentTimeMillis();
                    if (now - lastProgress.get() < 120) return;
                    lastProgress.set(now);
                    long current = acquisition.getCurrentSize();
                    long total = acquisition.getTotalSize();
                    main.post(() -> progress.onProgress(current, total));
                }

                @Override
                public void success(CameraImage cameraImage, Acquisition acquisition) {
                    closeQuietly(stream);
                    saveDownloaded(temp, displayName, image, callback);
                }
            };
            if (compressed) {
                // The compressed JPEG path is blocking; this already runs on the
                // bridge worker thread, so no extra executor is needed.
                Response small = image.getExtraSmallData(stream);
                closeQuietly(stream);
                if (small != null && small.getResult() == Result.OK) {
                    saveDownloaded(temp, displayName, image, callback);
                } else {
                    if (temp.exists()) temp.delete();
                    callback.onError("下载失败");
                }
                return;
            }
            image.getDataAsync(stream, callbackSink);
        } catch (Throwable error) {
            closeQuietly(stream);
            callback.onError("下载失败");
        }
    }

    /** Downloads via the synchronous getData() when an OutputStream is unavailable. */
    public void downloadImageSync(final CameraImage image, final String displayName,
                                  final SaveCallback callback) {
        if (image == null) {
            callback.onError("图片不可用");
            return;
        }
        io.execute(() -> {
            java.io.File temp = null;
            try {
                temp = new java.io.File(application.getCacheDir(),
                        "download-" + System.currentTimeMillis() + ".bin");
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    Response response = image.getData(out);
                    if (response != null && response.getResult() != Result.OK) {
                        throw new java.io.IOException("camera returned error");
                    }
                }
                saveDownloaded(temp, displayName, image, callback);
            } catch (Throwable error) {
                if (temp != null && temp.exists()) temp.delete();
                main.post(() -> callback.onError("下载失败"));
            }
        });
    }

    private void saveDownloaded(java.io.File temp, String displayName, CameraImage image,
                                final SaveCallback callback) {
        String name = displayName != null ? displayName : safeName(image);
        String mime = mimeOf(image);
        MediaStoreSaver.save(application, temp, name, mime,
                new MediaStoreSaver.ResultCallback() {
                    @Override
                    public void onSaved(String displayPath) {
                        temp.delete();
                        main.post(() -> callback.onSaved(displayPath));
                    }

                    @Override
                    public void onError(Throwable error) {
                        temp.delete();
                        main.post(() -> callback.onError("保存到相册失败"));
                    }
                });
    }

    private static String safeName(CameraImage image) {
        try {
            String name = image.getName();
            if (name != null && !name.isEmpty()) return name;
        } catch (Throwable ignored) {
            // fall through
        }
        return "IMG_" + System.currentTimeMillis() + ".jpg";
    }

    private static boolean isJpeg(CameraImage image) {
        try {
            return image.getFormat() != null && "JPEG".equals(image.getFormat().name());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String mimeOf(CameraImage image) {
        try {
            if (image.getFormat() != null) {
                switch (image.getFormat().name()) {
                    case "DNG":
                    case "PEF":
                    case "TIFF":
                        return "image/x-" + image.getFormat().name().toLowerCase();
                    case "MP4":
                    case "MOV":
                    case "AVI":
                        return "video/*";
                    default:
                        break;
                }
            }
        } catch (Throwable ignored) {
            // default below
        }
        return "image/jpeg";
    }

    private static void closeQuietly(OutputStream stream) {
        try {
            stream.close();
        } catch (Throwable ignored) {
            // ignore
        }
    }

    public void loadLatestCaptureImage(final BitmapCallback callback) {
        final CameraDevice device = device();
        if (device == null) {
            callback.onError("相机未连接");
            return;
        }
        io.execute(() -> {
            try {
                CameraImage latest = device.getLatestCaptureImage();
                if (latest == null) throw new java.io.IOException("no capture");
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                if (latest.hasThumbnail()) {
                    latest.getThumbnail(buffer);
                } else if (latest.hasViewData()) {
                    latest.getViewData(buffer);
                }
                final Bitmap bitmap = ImageLoader.decode(buffer.toByteArray(), 0);
                if (bitmap == null) throw new java.io.IOException("decode failed");
                main.post(() -> callback.onBitmap(bitmap));
            } catch (Throwable error) {
                main.post(() -> callback.onError("暂无拍摄"));
            }
        });
    }

    // -------------------------------------------------------------- live view
    public void startLiveView(final LiveViewCallback callback) {
        final CameraDevice device = device();
        if (device == null) {
            callback.onError("相机未连接");
            return;
        }
        liveViewCallback = callback;
        io.execute(() -> {
            try {
                Response response = device.startLiveView();
                if (response != null && response.getResult() != Result.OK) {
                    throw new java.io.IOException("live view rejected");
                }
                liveViewOn.set(true);
            } catch (Throwable error) {
                main.post(() -> {
                    if (liveViewCallback == callback) {
                        liveViewCallback = null;
                        callback.onError("无法启动实时取景");
                    }
                });
            }
        });
    }

    public boolean liveViewRunning() {
        return liveViewOn.get();
    }

    public void stopLiveView() {
        stopLiveViewInternal();
    }

    private void stopLiveViewInternal() {
        if (!liveViewOn.getAndSet(false)) {
            liveViewCallback = null;
            return;
        }
        liveViewCallback = null;
        final CameraDevice device = device();
        if (device == null) return;
        io.execute(() -> {
            try {
                device.stopLiveView();
            } catch (Throwable ignored) {
                // Best effort shutdown.
            }
        });
    }

    // ----------------------------------------------------------------- remote
    public void capture(final boolean selfTimer, final CaptureCallback callback) {
        final CameraDevice device = device();
        if (device == null || state() != State.CONNECTED) {
            callback.onError("相机未连接");
            return;
        }
        pendingCapture = callback;
        io.execute(() -> {
            try {
                if (selfTimer) {
                    boolean armed = applyCanonicalSetting(device, S_CAPTURE_METHOD, "SelfTimer2s");
                    if (!armed) throw new java.io.IOException("self timer unsupported");
                }
                // false = do not transfer the shot to the phone automatically; the
                // user downloads explicitly from the gallery.
                StartCaptureResponse response = device.startCapture(false);
                if (response == null || response.getResult() != Result.OK) {
                    throw new java.io.IOException("capture rejected");
                }
                main.post(callback::onStarted);
                armCaptureTimeout();
            } catch (Throwable error) {
                pendingCapture = null;
                main.post(() -> callback.onError("拍摄失败"));
            }
        });
    }

    public void captureAt(final float normalizedX, final float normalizedY,
                          final CaptureCallback callback) {
        final CameraDevice device = device();
        if (device == null || state() != State.CONNECTED) {
            callback.onError("相机未连接");
            return;
        }
        pendingCapture = callback;
        io.execute(() -> {
            try {
                StartCaptureResponse response = device.startCapture(
                        new Point(clamp01(normalizedX), clamp01(normalizedY)));
                if (response == null || response.getResult() != Result.OK) {
                    throw new java.io.IOException("capture rejected");
                }
                main.post(callback::onStarted);
                armCaptureTimeout();
            } catch (Throwable error) {
                pendingCapture = null;
                main.post(() -> callback.onError("拍摄失败"));
            }
        });
    }

    /** captureComplete is the authoritative signal; timeout keeps the UI unstuck. */
    private void armCaptureTimeout() {
        main.postDelayed(() -> {
            CaptureCallback pending = pendingCapture;
            if (pending != null) {
                pendingCapture = null;
                pending.onComplete();
            }
        }, 9000);
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0.5f;
        return Math.max(0.02f, Math.min(0.98f, value));
    }

    public void stopCapture(final CaptureCallback callback) {
        final CameraDevice device = device();
        if (device == null) {
            callback.onError("相机未连接");
            return;
        }
        io.execute(() -> {
            try {
                device.stopCapture();
                main.post(callback::onComplete);
            } catch (Throwable error) {
                main.post(() -> callback.onError("停止拍摄失败"));
            }
        });
    }

    public void focusAt(final float normalizedX, final float normalizedY,
                        final FocusCallback callback) {
        final CameraDevice device = device();
        if (device == null) {
            callback.onFocused(false);
            return;
        }
        io.execute(() -> {
            try {
                Response response = device.focus(new Point(
                        clamp01(normalizedX), clamp01(normalizedY)));
                boolean ok = response != null && response.getResult() == Result.OK;
                main.post(() -> callback.onFocused(ok));
            } catch (Throwable error) {
                main.post(() -> callback.onFocused(false));
            }
        });
    }

    /** Half-press AF using the camera's own focus area. */
    public void focusCenter(final FocusCallback callback) {
        final CameraDevice device = device();
        if (device == null) {
            callback.onFocused(false);
            return;
        }
        io.execute(() -> {
            try {
                Response response = device.focus();
                boolean ok = response != null && response.getResult() == Result.OK;
                main.post(() -> callback.onFocused(ok));
            } catch (Throwable error) {
                main.post(() -> callback.onFocused(false));
            }
        });
    }

    // --------------------------------------------------------------- settings
    /** Builds fresh, value-less setting objects for the requested names. */
    private List<CaptureSetting> settingInstances(List<String> names) {
        List<CaptureSetting> settings = new ArrayList<>();
        for (String name : names) {
            try {
                Class<?> type = Class.forName(SETTING_PACKAGE + name);
                settings.add((CaptureSetting) type.getDeclaredConstructor().newInstance());
            } catch (Throwable ignored) {
                // Unknown on this camera/firmware: skip silently.
            }
        }
        return settings;
    }

    public void readSettings(final List<String> names, final SettingsCallback callback) {
        final CameraDevice device = device();
        if (device == null) {
            callback.onError("相机未连接");
            return;
        }
        io.execute(() -> {
            try {
                LinkedHashMap<String, SettingInfo> result = null;
                // The SDK refreshes capture settings from /v1/props every 500 ms.
                // Right after connecting the cache can still be empty, so retry
                // briefly before telling the UI that a value is unsupported.
                for (int attempt = 0; attempt < 3; attempt++) {
                    List<CaptureSetting> requested = settingInstances(names);
                    if (requested.isEmpty()) throw new java.io.IOException("no settings");
                    Response response = device.getCaptureSettings(requested);
                    if (response != null && response.getResult() != Result.OK) {
                        throw new java.io.IOException("settings rejected");
                    }
                    result = new LinkedHashMap<>();
                    boolean incomplete = false;
                    for (CaptureSetting setting : requested) {
                        String value = setting.getValue() == null ? null
                                : setting.getValue().toString();
                        List<String> choices = new ArrayList<>();
                        List<CaptureSetting> available = setting.getAvailableSettings();
                        if (available != null) {
                            for (CaptureSetting option : available) {
                                if (option.getValue() != null) {
                                    choices.add(option.getValue().toString());
                                }
                            }
                        }
                        if (choices.isEmpty()) incomplete = true;
                        result.put(setting.getName(),
                                new SettingInfo(setting.getName(), value, choices));
                    }
                    if (!incomplete) break;
                    Thread.sleep(350L);
                }
                final LinkedHashMap<String, SettingInfo> snapshot = result;
                main.post(() -> callback.onSettings(snapshot));
            } catch (Throwable error) {
                main.post(() -> callback.onError("读取相机参数失败"));
            }
        });
    }

    public void writeSetting(final String name, final String value,
                             final SimpleCallback callback) {
        final CameraDevice device = device();
        if (device == null) {
            callback.onDone(false, "相机未连接");
            return;
        }
        io.execute(() -> {
            try {
                boolean ok = applyCanonicalSetting(device, name, value);
                if (!ok) {
                    // One retry covers the 500 ms settings cache refresh window.
                    Thread.sleep(450L);
                    ok = applyCanonicalSetting(device, name, value);
                }
                final boolean success = ok;
                main.post(() -> callback.onDone(success,
                        success ? "" : "该机型不支持此设置值"));
            } catch (Throwable error) {
                main.post(() -> callback.onDone(false, "设置失败"));
            }
        });
    }

    /**
     * The SDK converts a setting to a REST value by matching the instance against
     * the class' public static constants, so only values reported by the camera
     * (getAvailableSettings) or the SDK constants are valid payloads.
     */
    private static boolean applyCanonicalSetting(CameraDevice device, String name, String value)
            throws Exception {
        CaptureSetting target = canonicalSetting(device, name, value);
        if (target == null) return false;
        List<CaptureSetting> payload = new ArrayList<>();
        payload.add(target);
        Response response = device.setCaptureSettings(payload);
        return response != null && response.getResult() == Result.OK;
    }

    private static CaptureSetting canonicalSetting(CameraDevice device, String name, String value)
            throws Exception {
        Class<?> type = Class.forName(SETTING_PACKAGE + name);
        CaptureSetting template = (CaptureSetting) type.getDeclaredConstructor().newInstance();
        List<CaptureSetting> request = new ArrayList<>();
        request.add(template);
        device.getCaptureSettings(request);
        List<CaptureSetting> available = template.getAvailableSettings();
        if (available != null) {
            for (CaptureSetting option : available) {
                if (option != null && option.getValue() != null
                        && value.equals(option.getValue().toString())) {
                    return option;
                }
            }
        }
        for (java.lang.reflect.Field field : type.getFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            if (!CaptureSetting.class.isAssignableFrom(field.getType())) continue;
            CaptureSetting constant = (CaptureSetting) field.get(null);
            if (constant != null && constant.getValue() != null
                    && value.equalsIgnoreCase(constant.getValue().toString())) {
                return constant;
            }
        }
        return null;
    }

    /** Switches the camera capture method between stills and movie recording. */
    public void setCaptureMode(final boolean movie, final SimpleCallback callback) {
        final CameraDevice device = device();
        if (device == null) {
            callback.onDone(false, "相机未连接");
            return;
        }
        io.execute(() -> {
            boolean ok;
            try {
                ok = applyCanonicalSetting(device, S_CAPTURE_METHOD,
                        movie ? "Movie" : "StillImage");
            } catch (Throwable error) {
                ok = false;
            }
            final boolean success = ok;
            main.post(() -> callback.onDone(success,
                    success ? "" : "相机未接受拍摄模式切换"));
        });
    }

    /** Exposure shortcuts used by the remote screen chips. */
    public void stepExposureCompensation(final int direction,
                                         final SimpleCallback callback) {
        io.execute(() -> {
            CameraDevice device = device();
            if (device == null) {
                main.post(() -> callback.onDone(false, "相机未连接"));
                return;
            }
            try {
                Class<?> type = Class.forName(SETTING_PACKAGE + S_EXPOSURE_COMPENSATION);
                CaptureSetting template =
                        (CaptureSetting) type.getDeclaredConstructor().newInstance();
                List<CaptureSetting> request = new ArrayList<>();
                request.add(template);
                device.getCaptureSettings(request);
                List<CaptureSetting> available = template.getAvailableSettings();
                if (available == null || available.isEmpty()) {
                    main.post(() -> callback.onDone(false, "不支持曝光补偿"));
                    return;
                }
                String current = template.getValue() == null ? null
                        : template.getValue().toString();
                int index = 0;
                for (int i = 0; i < available.size(); i++) {
                    CaptureSetting option = available.get(i);
                    if (option.getValue() != null
                            && option.getValue().toString().equals(current)) {
                        index = i;
                        break;
                    }
                }
                int next = Math.max(0, Math.min(available.size() - 1, index + direction));
                if (next == index) {
                    main.post(() -> callback.onDone(true, ""));
                    return;
                }
                List<CaptureSetting> payload = new ArrayList<>();
                payload.add(available.get(next));
                Response response = device.setCaptureSettings(payload);
                boolean ok = response != null && response.getResult() == Result.OK;
                main.post(() -> callback.onDone(ok, ok ? "" : "调整失败"));
            } catch (Throwable error) {
                main.post(() -> callback.onDone(false, "调整失败"));
            }
        });
    }

    /**
     * Applies the camera-reported EXIF orientation to a decoded still image.
     * Thumbnails are small, so the rotated copy is cheap; live-view frames use
     * view-level rotation instead.
     */
    public static Bitmap orient(Bitmap bitmap, CameraImage image) {
        if (bitmap == null || image == null) return bitmap;
        int degrees;
        try {
            if (image.getOrientation() == null) return bitmap;
            switch (image.getOrientation().name()) {
                case "ROTATE90CW":
                    degrees = 90;
                    break;
                case "ROTATE180":
                    degrees = 180;
                    break;
                case "ROTATE270CW":
                    degrees = 270;
                    break;
                default:
                    return bitmap;
            }
        } catch (Throwable ignored) {
            return bitmap;
        }
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.postRotate(degrees);
        try {
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle();
            return rotated;
        } catch (Throwable error) {
            return bitmap;
        }
    }

    // ---------------------------------------------------------------- helpers
    public List<CaptureSetting> newSettingList(String... names) {
        List<String> list = new ArrayList<>();
        Collections.addAll(list, names);
        return settingInstances(list);
    }

    public static String prettySettingValue(String raw) {
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return "—";
        return raw;
    }
}
