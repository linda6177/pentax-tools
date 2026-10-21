# NOTES-REMOTE — reimplementing remote shooting/live view on RICOH Image Sync

Target: `com.ricohimaging.imagesync` 2.1.32 (APK sha256 `00756f0c…3942`). Evidence: JADX 1.5.3 output under
`analysis/jadx/sources` — `ShootingActivity.java`, `m/e4.java` (only `CameraEventListener` handling live view),
`m/c4.java` (focus listener), `m/t3.java`, `m/q3.java`, `m/p3.java`, `m/k1.java`, `m/h3.java`, and SDK
`com/ricoh/camera/sdk/wireless/{api,impl}`. Tags: **[O]** observed in code, **[I]** inference/recommendation.

## 0. API surface

`com.ricoh.camera.sdk.wireless.api.CameraDevice` — every method is blocking HTTP, never call from main:
`addEventListener/removeEventListener`, `connect(DeviceInterface)`, `disconnect(DeviceInterface)`,
`startLiveView()/stopLiveView()`, `startCapture(boolean withFocus)`, `startCapture(Point)` (== `startCapture(true,pos)`
[O WifiAdapter:947]), `finishCapture()` (NO-OP, returns OK [O ImplCameraDevice:271]), `stopCapture()`,
`focus()/focus(Point)`, `getCaptureSettings(List)/setCaptureSettings(List)`,
`getCameraDeviceSettings(List)`, `getStatus()`, `isConnected(DeviceInterface)`, `isCapturingMovie()`.

Device acquisition: `ImplCameraDevice` is package-private; public entry points are `CameraDeviceDetector`
(BLE only — `startDetect/getDetectedCameraDevice` `throw UnsupportedOperationException` for `WLAN`
[O CameraDeviceDetector:152-189]) and `public WiFiCameraDeviceDetector.detect(Context, List<CameraDevice>)`
[O WiFiCameraDeviceDetector:19]. The latter GETs `/v1/props` (`http://192.168.0.1`; `192.168.1.1` for `G900 SE`),
validates against `ImplCameraDeviceWiFiAdapterFactory.SUPPORT_CAMERA` (16 models: GR III/IIIx/HDF, K-1/K-70/KF/KP/
K-S2/K-3 III(+Mono), WG-M2, G900 SE) and builds an `ImplCameraDevice`. `connect(WLAN)` re-checks identity and starts
`SyncTrigger` polling [O ImplCameraDevice:132-178, WifiAdapter:434-456]. Camera is the Wi-Fi peer; no discovery.

## 1. Live view

**Call chain [O].** `startLiveView()` → `ImplCameraDeviceWifiAdapter.startLiveView()`: if `!isConnected` →
`ERROR/DEVICE_NOT_FOUND`; if already running → `OK`; else `liveViewAction.start()` → `OK` (no handshake; frames may
never come). `stopLiveView()` is idempotent and interrupts the reader thread. `ActionLiveview` starts a
`Thread`, GETs `baseURL + "/v1/liveview"`, wraps it in `MjpegInputStream`, then loops
`int n = mjpeg.getMjpegFrame(buff); if (n == 0) break; callback.process(Arrays.copyOf(buff, n));` — `buff` is a
reused 200 000-byte array (`FRAME_MAX_LENGTH=200000`).

**Frame contract [O].** `CameraEventListener.liveViewFrameUpdated(CameraDevice, byte[] jpeg)` receives exactly one
complete JPEG per callback (SOI `FFD8` … EOI `FFD9`/`Content-Length` part); multipart MJPEG parsing is inside
`MjpegInputStream.getMjpegFrame` (parses Content-Length, else scans for `FFD9`; returns 0 on skip/parse failure).
Do not re-parse as MJPEG. Each array is a fresh copy (`Arrays.copyOf`), safe to retain; the SDK buffer is reused.
No resolution is exposed — decode the JPEG to learn width/height. Callback thread = `ActionLiveview` worker
(`Thread-N`), not main; post to UI thread.

**Original display [O].** Plain `ImageView` + `Bitmap` (no Surface/TextureView). `m.e4.liveViewFrameUpdated`:
`ArrayDeque` cap 4 (`if (size>3) pollLast(); push(frame); if (size>1) return;`) → `runOnUiThread` →
`BitmapFactory.decodeByteArray(getFirst())` → `Matrix.setRotate(±90/0)` → `Bitmap.createBitmap` →
`ImageView.setImageBitmap` → `clear()`. So it coalesces to the newest frame, **decodes on main**, and allocates a
rotated copy per frame. `ShootingActivity` has two ImageViews (`f732j0` landscape, `f734k0` portrait), chosen from
device orientation `f773z1` (SensorManager) and the camera-orientation setting; rotation math in
`ShootingActivity.M()` / `m.a` (0/90/180/270). Aspect is layout-based (ImageView scaleType), not computed.

**Lifecycle / pause-resume ordering [O].** Listener registered once in `onCreate` (`l.addEventListener(new e4(this))`),
removed in `onDestroy` (`removeEventListener`, lines 1483-1491). `onPause` does **not** stop live view: it
unregisters the sensor and posts a 60 s delayed runnable (`new b2.e(this,7)`, lines 1522-1524); if still backgrounded
it calls `W()` (stop movie) + `V()` (stop live view via `q3` case 2, clear ImageViews). `onResume` → `U()`, which
`connect(WLAN)` if needed then `new q3(this,1)` → `startLiveView()` on a single-thread executor (`s.a` =
`Executors.newSingleThreadExecutor()` + main-`Handler` post). **No automatic restart after stream error**: the SDK
thread just exits and emits no event; the app restarts only via resume/toggle [O]. A modern screen needs a watchdog
(no frame ~3 s while expected → `stopLiveView(); startLiveView()`, capped retries).

Skeleton (framework-only, decode off main) [I]:
```java
final CameraEventListener lv = new CameraEventListener() {
  public void liveViewFrameUpdated(CameraDevice d, final byte[] jpeg) {
    if (!decoding.compareAndSet(false, true)) return;            // drop while busy
    decodeExec.execute(() -> {
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inPreferredConfig = Bitmap.Config.RGB_565;
      Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
      main.post(() -> { if (b != null) iv.setImageBitmap(b); decoding.set(false); });
    });
  }
};
// start: ctrl.execute(() -> cam.startLiveView());  stop: ctrl.execute(() -> cam.stopLiveView());
// onDestroy: if (cam != null) cam.removeEventListener(lv);
```
Rotate with `iv.setRotation()` / TextureView transform, not `Bitmap.createBitmap` per frame.

## 2. Shutter, focus, capture events

**Start capture [O].** `startCapture(Point)` == `startCapture(true, point)` → `ActionCapture.start(...)`:
movie (`CaptureMethod.MOVIE`) → `POST /v1/camera/shoot/start`; still → `POST /v1/camera/shoot` (or
`/v1/camera/shoot/start` when BULB/TIME on eligible models) with `af=on` when withFocus and
`&pos=<round(x*100)>,<round(y*100)>` when a Point is given (`createBodyForFocusPoint:204`). Response
`StartCaptureResponse.getCapture()` yields a `Capture` (`ImplCapture`), stored as
`getStatus().getCurrentCapture()` with state `EXECUTING` [O WifiAdapter:376-393]. Returns immediately — the image is
not stored yet. Errors are `Result.ERROR` + `ErrorCode` (`DEVICE_NOT_FOUND`, `FORBIDDEN` "Device Busy", `NO_SPACE`,
`NETWORK_ERROR`, `INVALID_ARGUMENT`); "Device Busy" comes from `canStartCaptureWhenExecuting()` (movie running, bulb
exposing, continuous shooting, mirror-up…).

**Focus [O].** `focus(Point)` → `POST /v1/lens/focus` body `pos=x,y` → `BeanFocus{errCode,focused,captureId}`. If
`captureId` is non-empty it blocks in `waitActionResult(captureId, focusActionResultMap)` until the async "lens"
result arrives, **timeout 40 000 ms** (`ActionCapture:463`). `focusComplete(CameraDevice, Response, Capture)` is
emitted by `ActionCapture.startStill` for `G900 SE` synchronously after the shoot POST, and by
`UpdateCaptureIdForLens.handle` on the websocket thread for `SYNC_MODE.WEBSOCKET` models (`G900 SE`, `WG-M2` only
[O Factory:324]). POLLING models (all GR III / PENTAX in 2.1.32) get the result from the POST response
(`beanFocus.focused`) [I, consistent with code]. The one-shot listener `m.c4` removes itself inside
`focusComplete`; keep that discipline (CopyOnWriteArrayList snapshot iteration).

**Completion / state machine [O].** `captureComplete` / `imageStored` are emitted only from
`UpdateCameraImage.sendCaptureCompleteEvent/sendImageStoredEvent`, i.e. after a storage/image-list refresh on the
single `imagesSyncService` thread. Refresh is driven by the initial sync plus the `"storage"` websocket change or the
polling timer (`SyncTrigger` schedules every 500 ms). Expect **1–2 s+ between the HTTP 200 and `captureComplete`**;
don't block the shutter UI. `Capture.getState()`: `UNKNOWN/EXECUTING/COMPLETE/CANCELLED`; plus `getMethod()`,
`getSelfTimer()`, `getNumCurrentShots()`.

**Stop [O].** `finishCapture()` is a no-op — use `stopCapture()`: movie → `/v1/camera/shoot/finish`; still →
`/v1/camera/shoot` or `/v1/camera/shoot/cancel` by `CaptureMethod` (`canDoStop`: continuous H/M/L, MIRROR_UP;
`canDoCancel`: self-timer/bracket/motion/DOF variants). Bulb/TIME/mirror-up/continuous stay `EXECUTING` until stopped.

**Self-timer [O].** Arm as a capture method: `setCaptureSettings(Arrays.asList(CaptureMethod.SELFTIMER_2S))`
(also `SELFTIMER_10S/12S`, per-model `*_SELFTIMER_*`); allowed set = `getAvailableSettings()` of a `CaptureMethod`
template. Progress: `getStatus().getCurrentCapture().getSelfTimer().getCountDownState()` = `IDLE, BUSY2S, BUSY10S,
BUSY12S, TIME` (`SelfTimer.State`). `ActionCapture.createCaptureResponse` fills `ImplSelfTimer` from the cached
`CountDown` setting; `UpdateChanged` maps websocket `busy2s/busy10s/busy12s/time` and cancels 12 s countdowns.
`CountDown` is status — read, don't write [I]. Movie: `startCapture` with `MOVIE`, poll `isCapturingMovie()`, then
`stopCapture()`.

Skeleton [I]:
```java
CaptureMethod method = new CaptureMethod(); cam.getCaptureSettings(Arrays.asList(method)); // cache (~500 ms stale)
StartCaptureResponse r = (point != null) ? cam.startCapture(point) : cam.startCapture(true);
if (r.getResult() != Result.OK) { showError(r.getError().getCode()); return; }
Capture c = r.getCapture();            // EXECUTING; COMPLETE arrives via callback later
Response f = (point != null) ? cam.focus(point) : cam.focus(); // optional half-press pre-focus
cam.stopCapture();                     // bulb / movie / mirror-up / continuous end
```

## 3. Tap-to-focus coordinate space

- `api.Point(float x, float y)` is only a pair; the SDK writes `pos=round(x*100),round(y*100)`
  [O ActionCapture:204] → wire format is **integer percent (0…100) of the live-view image, origin top-left**.
- `Point` is expected **normalized [0,1]**, not pixels: every producer uses 0..1 (`LiveViewSpecification.focusArea`,
  `m.t3`) and `createBodyForFocusPoint` multiplies by 100.
- Original view→Point (`ShootingActivity.M(MotionEvent)` lines 750-950 + `m.t3`):
  `Point(motionEvent.getX()/viewWidth, motionEvent.getY()/viewHeight)` after orientation transform
  (portrait rotates/mirrors x/y); taps outside the focusable area are rejected; then `m.t3.b()` re-reads
  `FocusMethod` and calls `ShootingActivity.l(point, captureMethod)` → `focus(point)` (or `focus()` for `G900 SE` /
  `STAR_AF`) then `startCapture(point)` (or `startCapture(true)`). Reticle is placed by offsetting ImageView pixels.
- Focusable area: camera setting `LiveViewSpecification` (`setting.camera`), `LiveViewImage.getFocusArea():
  List<Point>` (4 normalized corners). `RestCameraSettingConverter.createLiveViewSpecification` builds a centered
  rect from two `/v1/props` ints: `w=p0/200, h=p1/200, corners (0.5-w,0.5-h)…(0.5+w,0.5+h)` [O]. Fallback if empty:
  `(0.111,0.125),(0.889,0.125),(0.889,0.875),(0.111,0.875)` [O ShootingActivity:780-785].
- Valid values: [0,1]; clamp to the focus area before sending (outside may return `FORBIDDEN`); never send NaN.
  Map view coords to the *displayed image rect* (letterboxing/rotation), not the raw screen.

## 4. Settings

**Read [O].** `getCaptureSettings(List<CaptureSetting> templates)` does **no network I/O** on the WLAN adapter: it
walks `camera.getCaptureSettings()` (cached `CopyOnWriteArrayList`), copies value + `availableSettings` by class
equality, returns `OK` (WifiAdapter:606-623). Cache is refreshed by `UpdateCaptureSetting` (`GET /v1/props` every
500 ms while connected; also on websocket `"camera"` change) [O SyncTrigger:23,178, UpdateCaptureSetting:80-93].
Values are up to ~0.5 s stale; re-read after writes (the push response re-parses the cache
[O ItemCaptureParams.push:390]). App shortcut: `m.a.g(new CaptureSetting[]{...}, device)`.
Camera-level settings (`LiveViewSpecification`, `CardSlot`, `DualCardSlotsMode`) use
`get/setCameraDeviceSettings(...)`.

**Write [O].** `setCaptureSettings(list)` → `ItemCaptureParams.push` splits by type: `StillImageCaptureFormat` →
`PUT /v1/params/device`; `CompositionAdjustment(+Enable)` → `PUT /v1/params/camera/compAdjust`; `FocusSetting` →
`PUT /v1/params/lens`; `ZoomLevel` → `POST /v1/lens/zoom`; the rest → `PUT /v1/params/camera`. Values are converted
by reflection (`SupportSettingReader.toRestValue`): the instance must **equal one of the class's
`public static final` constants**, otherwise conversion yields null (parameter skipped/malformed) [O, effect [I]].
=> Use objects from `template.getAvailableSettings()` (camera-reported) or SDK constants; avoid `new ISO("640")`.
Empty/all-null list → `INVALID_SETTING`. Mode/exposure writes rewrite related settings internally — re-read after.

**Classes** (`api.setting.capture`, string values) [O]:

| class | values |
|---|---|
| `ExposureProgram` | Auto, Program, AperturePriority, ShutterSpeedPriority, Manual, Bulb, Time, ISOPriority, ProgramShift… |
| `ISO` | Auto, Auto-Hi, "100"…"1600000" |
| `ShutterSpeed` | Auto, Bulb, Time, "1/250", "0.3"…"30" |
| `FNumber` | Auto, "2.8", "8.0"… |
| `ExposureCompensation` | "-2.0","-0.3","0.0","0.3","2.0" (1/3 steps, ±8) |
| `WhiteBalance` | Auto, Daylight, Cloudy, Shade, Tungsten Light, Manual, Color Temperature Configuration |
| `FocusSetting` | Auto-area AF, Select AF, Pinpoint AF, Continuous AF, Star AF, MF |
| `FocusMethod` | Auto, Manual |
| `CaptureMethod` | StillImage, Movie, SelfTimer2s/10S/12S, Continuous…, Bracket…, MirrorUp… (97 constants) |
| status | `CountDown`: idle/busy2s/busy10s/busy12s/time; `CameraOrientation`: none/positive/reverse/vertical_left/vertical_right |

Minimum viable UI: `CaptureMethod` (photo/movie), `ExposureProgram`, `ISO`, `ShutterSpeed`, `FNumber`,
`ExposureCompensation`, `WhiteBalance`; display `template.getValue().toString()`, offer
`template.getAvailableSettings()` to the picker. Safe defaults: read what the camera has; if forcing, `ISO.AUTO`,
`WhiteBalance.AUTO`, `ExposureCompensation.EC0_0`, `FocusMethod.AUTO`, `CaptureMethod.STILL_IMAGE`, self-timer off.

Skeleton [I]:
```java
ISO iso = new ISO(); cam.getCaptureSettings(Arrays.asList(iso));          // background
for (CaptureSetting v : iso.getAvailableSettings()) addItem(v.getValue().toString());
Response r = cam.setCaptureSettings(Arrays.asList(pickedValue));         // pickedValue from availableSettings
if (r.getResult() == Result.OK) cam.getCaptureSettings(Arrays.asList(iso)); // refresh UI
```

## 5. Status, events, threading

| callback | thread | notes |
|---|---|---|
| `liveViewFrameUpdated` | `ActionLiveview` worker | one JPEG/frame; post to main |
| `captureComplete` / `imageStored` | SDK `imagesSyncService` single thread | after image-list refresh, latency ≫ shoot response |
| `focusComplete` | G900 SE: caller of `startCapture`; websocket models: WS pool | not emitted for POLLING-only models [I] |
| `deviceDisconnected` | `pollingService` (ConnectionChecker every 500 ms; after 2 failed checks and >40 s without success) [O ConnectionChecker:36-53] | also 2 s disconnect timeout |
| settings/status refresh | `pollingService` (500 ms) | no callback — poll UI or re-read on demand |

`CameraStatus`: `getBatteryLevel(): int`, `getBatteryUsed(): BatteryUsed`, `getCurrentCapture(): Capture`. Battery and
capture state are **polled into the cache** by `UpdateCameraStatus` — no battery event; refresh UI on a 1 s Handler
tick while foreground [O/I]. `deviceDisconnected` handling in the app: null app camera, navigate to `MainActivity`,
`finish()` (`m.e4`), posted to main.

## 6. Pitfalls / rules

1. **ANR — blocking SDK calls on main.** All control calls are synchronous HTTP (`focus()` can wait 40 s). The
   original calls `getCameraDeviceSettings(...)` from `ShootingActivity.M(MotionEvent)` and settings reads in UI
   paths [O], routing most work through one executor + main Handler (`s.a`, `k4`). Use one control
   `ExecutorService`; post results to main.
2. **Decode on main.** Original does `decodeByteArray` + rotated `createBitmap` inside `runOnUiThread` [O]. Decode on
   a worker (`RGB_565`, optional `inBitmap` reuse), drop frames while busy, swap on main, recycle the old bitmap.
3. **Unbounded frame queue.** SDK arrays are fresh, but a slow consumer grows the queue; original caps at 4 and drops
   the oldest [O]. Clear on stop; keep only the newest.
4. **No stream error/retry in SDK.** Closed socket or 0-byte part silently ends the thread, no event. Add watchdog;
   stop live view in `onStop/onDestroy` (original keeps it up to 60 s in background [O]), restart on resume (with a
   fresh `startLiveView()`), remove listener in `onDestroy`.
5. **Capture success ≠ stored.** `startCapture` returns `EXECUTING`; completion is `captureComplete` or
   `getStatus().getCurrentCapture().getState()==COMPLETE`. Drive the shutter UI with a local state machine + timeout.
6. **`finishCapture()` is a no-op.** Use `stopCapture()`; movie/bulb/mirror-up/continuous stay `EXECUTING` until
   stopped (press/release for bulb).
7. **Settings need canonical value objects** from `getAvailableSettings()`/constants, non-empty list; mode/exposure
   writes can reset dependent settings — re-read after.
8. **Coordinates:** Point = normalized [0,1] of the live-view image (→ 0..100 on the wire), not pixels or 0..1000;
   account for letterboxing, sensor rotation and `LiveViewSpecification` focus area.
9. **Half-press vs full-press:** `startCapture(point)` already sends `af=on&pos=…`; don't fire `focus()` and
   `startCapture(true)` concurrently ("Device Busy"). Serialize all control calls.
10. **Listener hygiene:** `CopyOnWriteArrayList`; self-removal works (standard here); never mutate the camera inside a
    callback without hopping to the control executor; guard post-`onDestroy` callbacks with a `volatile` flag.
11. **Lifecycle races:** `connect(WLAN)` starts polling; `disconnect(WLAN)` + `stopLiveView()` before leaving.
12. **Per-frame Bitmap alloc/GC jank:** prefer view-level rotation (`setRotation`) over `Bitmap.createBitmap`; JPEGs
    are capped at 200 000 bytes by the SDK parser, decode still costs ~2–8 ms at 1–2 MP [I].

## 7. Open questions (inference boundaries)

- `focusComplete` delivery for pure-POLLING models is undecidable from this decompile (no non-websocket producer
  found); plan on the `focus()`/`startCapture()` response + local timeout.
- Per-model supported settings/values come from camera YAML (`res/raw/capture_<model>.yaml`) not materialised by
  JADX; enumerate at runtime via `getAvailableSettings()`.
- Bulb exposing state: only `Capture.getState()` is public; `isExposing()` lives on package-private `ImplCapture`,
  so implement bulb as press → `startCapture`, release → `stopCapture()`.
