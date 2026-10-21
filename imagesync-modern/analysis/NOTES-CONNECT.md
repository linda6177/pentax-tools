# NOTES-CONNECT — connection lifecycle & modern home-screen integration contract

Target: `com.ricohimaging.imagesync` 2.1.32, APK sha256 `00756f0c…3942`.
Evidence: full JADX 1.5.3 tree at `analysis/jadx/sources` (8496 files). Paths below are relative to that
tree. Tags: **OBSERVED** = read directly in decompiled code; **INFERRED** = reasoned, not directly proven.

Obfuscation: app code is R8-renamed (`SvApplication.f783a`, `s.g`, `m.i6`, …); the camera SDK
(`com.ricoh.camera.sdk.wireless.*`) is **not** renamed and is the stable integration surface.

---

## 1. Connection state model

`SvApplication` (`com/ricohimaging/imagesync/SvApplication.java`) is the process-wide singleton
(`AndroidManifest` `android:name=".SvApplication"`) and is the **only** owner of connection state.

| field | type | meaning | status |
|---|---|---|---|
| `f783a` | `CameraDevice` | the single connected camera; `null` = not connected | OBSERVED |
| `f784c` | `DeviceInterface` | interface used for the *current/last* attempt; default `WLAN` | OBSERVED |
| `b` | `m.j` | transfer/image-list manager bound to the device (`j.a(device)` attaches an event listener) | OBSERVED |
| `f785g` | `n.c` | camera-settings snapshot holder; cleared together with `f783a` | OBSERVED |
| `f788m` | boolean | app is in background (`onStop` true / `onStart` false) | OBSERVED |
| `f789n` | boolean | one-shot "open MainActivity on the *setting* tab" flag | OBSERVED |
| `f790o` | boolean | background location-sync service running | OBSERVED |
| `f791p` | String | BLE device name whose WLAN must be re-enabled on foreground | OBSERVED |

**Live state to render** — use the SDK, not a cached flag:

```java
SvApplication app = (SvApplication) getApplication();
CameraDevice dev = app.f783a;                                  // may be non-null while only BLE-connected
boolean wlan = dev != null && dev.isConnected(DeviceInterface.WLAN);  // camera album usable
boolean ble  = dev != null && dev.isConnected(DeviceInterface.BLE);   // control channel only
String model = dev != null ? dev.getModel() : null;
String fw    = dev != null ? dev.getFirmwareVersion() : null;
int battery  = dev != null ? dev.getStatus().getBatteryLevel() : -1;  // fresh only after connect/polling
```
`ImplCameraDevice.isConnected(iface)` returns the adapter's `isConnected` flag
(`impl/ImplCameraDevice.java:436`), i.e. **what the SDK currently believes**, not a live HTTP probe.
Three UI states are therefore required: `DISCONNECTED / BLE_ONLY / WLAN_CONNECTED`. OBSERVED.
Battery/model: `getModel()`, `getFirmwareVersion()`, `getSerialNumber()`,
`getStatus().getBatteryLevel()` / `getBatteryUsed()` (`impl/ImplCameraStatus.java`); values are
refreshed by the SDK's polling/watchers after a WLAN connect (`ImplCameraDeviceWifiAdapter.startPolling()`).

### Who sets / clears `f783a`
* Set by `m/i6.java` (Title bootstrap, WLAN auto-detect **or** BLE auto-reconnect), `s/i.java`
  (`i.a(app,h)`, the common "detect→configure→connect" helper, called by WiFi/BLE wizard paths and
  by `s/s.java`/`s/e.java` when switching BLE→WLAN), `SelectBleDeviceActivity` (BLE success, via `m/k1`).
  OBSERVED.
* Cleared by the disconnect listener `m/f2.java` (`deviceDisconnected` → `f783a=null; f785g=null`,
  restarts MainActivity's 1 s poll if app not backgrounded), same in `m/e4.java` (ShootingActivity),
  `SelectBleDeviceActivity` error paths, and `SvApplication.e()`.
  `e()` disconnects BLE, clears handlers, stops the location service and **calls
  `Process.killProcess(myPid())`** — fired from `ProcessLifecycleOwner.onDestroy`. Do **not** call it.
* `deviceDisconnected` is delivered by the SDK's own `impl/ConnectionChecker` (2 retries, 40 s grace,
  `ConnectionChecker.java:66-107`) or synchronously by `CameraDevice.disconnect(iface)`. There is **no**
  `deviceConnected` callback (`api/CameraEventListener.java`): after connect, poll `isConnected`.

---

## 2. Mandatory bootstrap (do this before touching the SDK)

1. **`CameraDeviceDetector.setContext(context)`** (`api/CameraDeviceDetector.java:180`) — static,
   required before any `detect()`. Called by the Title bootstrap (`m/i6.java:70`) and by every wizard
   Activity that detects (e.g. `SelectBleDeviceActivity.java:198`, `m/n0.java:84`). Without it
   `WiFiCameraDeviceDetector.detect` constructs `ImplCameraDevice(..., CONTEXT=null, ...)` and BLE
   detection cannot start. OBSERVED (NPE/log failure is INFERRED from the null arg).
2. **Terms agreement** — pref `SHARED_PREFERENCE_KEY_TERMS_VERSION_NAME` (`p.a.f1730r`, also used as a
   raw literal) must equal `R.string.terms_version`. `TitleActivity.onCreate` checks it (`TitleActivity.java:140`);
   on mismatch it delays 1 s and starts `TermsAgreementActivity` (`b2/e.java:196-203`); the fragment
   `m/g6.java:134-139` writes the version on *Agree* and restarts `TitleActivity`.
3. **Permissions** requested by `TitleActivity` (`:146-175`): `WRITE_EXTERNAL_STORAGE` (no-op on API≥33),
   `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`, `BLUETOOTH_ADMIN` (if BLE hw). Plus, on API>30,
   `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` requested later by `CheckBluetoothPermissionActivity.java:120-140`
   (manifest declares `BLUETOOTH_SCAN` with `neverForLocation`). GPS provider **enabled** + fine-location
   granted is required for BLE auto-reconnect (`j2/g.java:96`, `B()`), and for Wi-Fi AP scanning
   (`SelectWifiApActivity`);
   `BLUETOOTH_AUTO_RECONNECTION` pref (`SHARED_PREFERENCE_KEY_BLUETOOTH_AUTO_RECONNECTION`) gates BLE reconnect.
4. **Application.onCreate** (`SvApplication.java:212-237`) already: cleans `/sdcard/Log/ImageSync`,
   `j2.g.K(ctx)` (Wi-Fi helper init), registers activity-lifecycle callbacks,
   **opens Room DB** `SvAppDatabase.h(ctx)` and registers a `ProcessLifecycleOwner` observer.
   `SvAppDatabase.h` = `Room.databaseBuilder(..., "photo-database")`, version 9, `allowMainThreadQueries()`,
   migration 8→9, single entity `q.a` (transferred images). Connection code only *opens* it (`TitleActivity.java:136`,
   `MainActivity` fragments) — it is **not** required by the connect path. Modern home inherits it. OBSERVED.
5. `SvApplication.onStart` (`:256-299`) re-enables camera WLAN over BLE if `f791p` matches and calls
   `c(ctx)` → may start `LocationUpdateService` (needs terms + fine location + POST_NOTIFICATIONS).
   `MainActivity` also calls `d.c(this)` (`MainActivity.java:196`). A new home screen should **not** call
   `c()` unless it wants background location sync. OBSERVED.
6. Saved-device migration `TitleActivity.l` (`:45-117`): if prefs `…USED_DEVICE_NAME1/2/3` hold
   `GR_*` / `PENTAX_*` names, it writes `files/BLEConnectionHistory/<model>/<bleDeviceName>` and clears
   the prefs. Idempotent; harmless to skip on a fresh install, but it is what makes the "reconnect to
   last camera" data available. OBSERVED.

---

## 3. Connection wizard: entry points, extras, result contract

Graph (all Activities are `launchMode="singleTop"`, `exported=false` except `TitleActivity`;
starting them from inside the same app with explicit `Intent` is always allowed — manifest OBSERVED):

```
TitleActivity (launcher, exported)
  └─(m/i6 auto-connect else MainActivity)
SelectConnectionModelActivity                       extras: MOVE_FROM_SHOOTING_ICON(bool)
  ├─ GR II, K-S2         → b2/d.g() → ConnectNfcActivity | SelectWifiApActivity | CheckWifiActivity
  ├─ GR III/IIIx/K-3 III → b2/d.f() → SelectConnectionProcessActivity (BLE-first)
  └─ other models        → CheckWifiActivity | SelectWifiApActivity | CheckLocationInformationActivity
SelectConnectionProcessActivity   extras: MOVE_FROM_SHOOTING_ICON, SUPPORT_BLE_MODEL, SELECTED_DEVICE_NAME
  ├─ "Connect via Bluetooth" → SelectBleDeviceActivity | CheckBluetoothPermissionActivity
  └─ "Select SSID"           → CheckWifiActivity | SelectWifiApActivity
CheckWifiActivity    extras: MOVE_FROM_SHOOTING_ICON, SUPPORT_NFC_MODEL, SUPPORT_BLE_MODEL   (1 s poller n.g)
SelectWifiApActivity extras: MOVE_FROM_SHOOTING_ICON, SELECT_NFC_MODEL   (scan; needs location+GPS)
  └─ item click → InputWifiPasswordActivity  extras: WIFI_SSID, WIFI_SECURITY, WIFI_BSSID, MOVE_FROM_SHOOTING_ICON
       └─ Connect → ConnectingWifiActivity   extras: + WIFI_PASSWORD (+ MOVE_FROM_NFC_CONNECTION, move_from_last_ap)
            └─ success → svApp.b(); then:
                 MOVE_FROM_SHOOTING_ICON=true  → s.u.e(activity) → ShootingActivity
                 else GR III fw<=1.0           → firmware dialog, then MainActivity
                 else                          → MainActivity  (FLAG_NEW_TASK|CLEAR_TOP, extra "cameraImages"="cameraImages")
SelectBleDeviceActivity  extras: MOVE_FROM_SHOOTING_ICON, SUPPORT_BLE_MODEL, SELECTED_DEVICE_NAME
  └─ success → MainActivity (same flags/extra) | ShootingActivity; failure → ConnectErrorActivity
       extras: CONNECT_ERROR_MSG, RETURN_SELECT_BLE, DELETE_CACHED_DEVICE, SUPPORT_BLE_MODEL,
               SELECTED_DEVICE_NAME, MOVE_FROM_SHOOTING_ICON
MainActivity (singleTop) extras: "cameraImages" ∈ {deviceImages, cameraImages, setting} (tab select);
                          "MOVE_TO_PREVIOUS_TAB"(bool) = return to previous tab (fallback rules in onNewIntent)
```
OBSERVED from `SelectConnectionModelActivity.java:42-81`, `b2/d.java:112-157` (partly raw bytecode),
`SelectConnectionProcessActivity.java:49-55,151-234`, `CheckBluetoothPermissionActivity.java:40-140`,
`SelectWifiApActivity.java`, `m/r2.java`, `m/s1.java`, `ConnectingWifiActivity.java:105-179`,
`SelectBleDeviceActivity.java:116-158`, `ConnectErrorActivity.java:33-42`, `MainActivity.java:225-353`.

**Routing rule for `b2.d.f(flag, nfc, model)`** (OBSERVED, bytecode-derived): `SelectConnectionProcessActivity`
is reached only for K-3 Mark III / Monochrome (with Bluetooth, Wi-Fi and location/GPS all good);
otherwise: BT off → `CheckBleActivity`; Wi-Fi off → `CheckWifiActivity`; location/GPS bad → `CheckLocationInformationActivity`.

`MOVE_FROM_SHOOTING_ICON` is the one cross-cutting extra: **it changes the post-connect destination**
(ShootingActivity instead of MainActivity). Pass `false` from a modern home screen.

**No Activity-result contract exists.** The wizard never `setResult`s; success is only observable as a
`SvApplication.f783a` change (plus the wizard launching `MainActivity` itself, usually with
`CLEAR_TOP|NEW_TASK`, so the back stack above it collapses). The only "return" extra is
`MOVE_TO_PREVIOUS_TAB` used when `MainActivity` is re-entered from the model screen/back presses.
A modern home screen must therefore observe `f783a` (see §5), not `startActivityForResult`.

---

## 4. Is there a Wi-Fi-only detect+connect path? — **YES** (no BLE needed)

Full chain, all OBSERVED:

`CameraDeviceDetector.detect(DeviceInterface.WLAN)` (`api/CameraDeviceDetector.java:105`):
1. throws for `BLE`; for `WLAN` returns `EMPTY_LIST` if a detect is already running (`WIFI_STATE`).
2. `WIFI_DETECTOR.detect(CONTEXT, ALREADY_DETECTED_DEVICES)` → `impl/WiFiCameraDeviceDetector.java`:
   * `new ItemCameraInfo(false, "")`; `pullWithShortTimeout()` →
     `GET http://192.168.0.1/v1/props` (connect timeout 1 s, read 10 s; `RestCameraClient.doGet(...,1,10)`).
     **G900 SE** uses `http://192.168.1.1` instead (`ItemCameraInfo.java:25-32`); if the first pull yields
     `model == null` the detector retries with `ItemCameraInfo(false, "G900 SE")`.
   * JSON → `impl/BeanProps`: `model, serialNo, firmwareVersion, macAddress, manufacturer, bdName,
     stateMovie, errCode, ssid, key, battery, …`.
   * model must be in `ImplCameraDeviceWiFiAdapterFactory.SUPPORT_CAMERA`:
     `GR II, PENTAX K-1, K-1 Mark II, K-3 Mark III, K-3 Mark III Monochrome, K-S2, K-70, KF, KP,
     RICOH GR III/III HDF/IIIx/IIIx HDF, RICOH WG-M2, G900 SE` (15 models). Otherwise `null`.
   * builds `ImplCameraDevice(manufacturer, model, serial, fw, context, deviceName)`, reuses a cached
     instance with the same device name/`equals`, appends to `ALREADY_DETECTED_DEVICES`.
3. `CameraDevice.connect(DeviceInterface.WLAN)` (`impl/ImplCameraDevice.java:132-158`):
   `wlanAdapter.connect()` → `impl/ConnectionChecker.isConnected()` → `ItemCameraInfo.pullWithShortTimeout()`
   + `isSameCameraInfo(...)` (one more GET `/v1/props`). On OK: `PowerState.ON`, BLE adapter disconnected
   if open, `currentAdapter = wlan`, `startPolling()`.
   Failure → `Response(ERROR, DEVICE_NOT_FOUND)`.
   `disconnect(WLAN)` is symmetric (`ImplCameraDeviceWifiAdapter.disconnect()`, stops live view/websocket/pollers).

Consequences for a modern screen: **if the phone is already joined to the camera's AP, detect+connect
works with no BLE, no NFC, no runtime permission** (plain HTTP only). There is no Wi-Fi *beacon scan*
in the SDK — detection is exactly "can I GET /v1/props from 192.168.0.1". Empty list means: phone not on
camera AP, camera off / AP not up, or model unsupported. INFERRED for the failure taxonomy, OBSERVED
for the request itself.
Note `CameraDeviceDetector.startDetect/getDetectState/stopDetect` throw `UnsupportedOperationException`
for `WLAN` — WLAN has **only** one-shot `detect()`.

Existing in-app helpers you can mimic or call from same-module code (they wrap the above):
* `s.i.a(SvApplication, s.h)` — `Boolean`; uses `svApp.f784c`, detects if `f783a` null/not connected,
  picks `detect(...).get(0)`, sets `f783a/f784c`, `setBackgroundSyncImages(false)`, `setSortMode(false)`,
  attaches `m.j`, `connect(iface)`, `setPower(true)` if needed, saves SSID/key into prefs (`s.i.c/e`).
* `j2.g.j(ctx, device)` — join saved AP via `ConnectivityManager.requestNetwork` specifier, then
  `connect(WLAN)` with 60 s retry; `j2.g.i(ctx,device)` is the legacy `WifiManager` variant;
  `j2.g.z(device)` = retry `connect(WLAN)` for 60 s. `s.e` = full BLE→WLAN switch.
* Saved AP prefs: `SHARED_PREFERENCE_KEY_LAST_AP_{SSID,PASSWORD,ACCESS_KEY,SECURITY,BSSID}`
  (`p.a.d/f/f1722g/f1723h/f1724i`; password AES-encrypted + Base64, key generated by `s.g.v()`).
* Per-device history: `files/BLEConnectionHistory/<model>/<bleDeviceName>` holding Gson `q.b`
  `{a:ssid, b:encPwd, c:accessKey, d:security, e:bssid}` (`q/b.java`, written by `s.i.d`).

---

## 5. Recommended minimum integration for a modern home/connect screen

Framework-only patterns; the camera SDK classes are app dependencies, the obfuscated helpers are optional.

**A. Show state, refresh on resume + on SDK event**

```java
private final CameraEventListener connListener = new CameraEventListener() {
    @Override public void deviceDisconnected(CameraDevice d) { runOnUiThread(Home::render); }
};
private void attach() {
    CameraDevice d = ((SvApplication) getApplication()).f783a;
    if (d != null) d.addEventListener(connListener);
}
private void detach() {
    CameraDevice d = ((SvApplication) getApplication()).f783a;
    if (d != null) d.removeEventListener(connListener);
}
@Override protected void onResume() { super.onResume(); attach(); render(); }   // re-check every resume
@Override protected void onPause()  { detach(); super.onPause(); }
// render(): DISCONNECTED if f783a==null; BLE_ONLY if only BLE; CONNECTED_WLAN if isConnected(WLAN).
```
Because the SDK fires `deviceDisconnected` but no connected event, also poll `render()` on
`ON_START`, after returning from the wizard, and (optionally) a 1–2 s handler while in `BLE_ONLY`.

**B. Start the original wizard (recommended: zero reimplementation risk)**

```java
startActivity(new Intent(this, SelectConnectionModelActivity.class)
        .putExtra("MOVE_FROM_SHOOTING_ICON", false));   // false => post-connect goes to legacy MainActivity
```

**C. Direct WLAN detect+connect (only when user is already on the camera AP) — worker thread**

```java
// Runnable on a background thread; both calls block on HTTP (up to ~11 s worst case).
CameraDeviceDetector.setContext(getApplicationContext());           // mandatory, idempotent
List<CameraDevice> found = CameraDeviceDetector.detect(DeviceInterface.WLAN);
if (found.isEmpty()) return null;                                   // not on camera AP / unsupported model
CameraDevice dev = found.get(0);
dev.setBackgroundSyncImages(false);
dev.setSortMode(false);
if (dev.connect(DeviceInterface.WLAN).getResult() != Result.OK) return null;
SvApplication app = (SvApplication) getApplication();
app.f784c = DeviceInterface.WLAN;
app.f783a = dev;             // MUST publish here; every other screen reads SvApplication.f783a
app.b();                     // optional: starts the auto date/time-sync timer (pref-gated)
return dev;
```
This replicates `s.i.a` / `m.i6` minus analytics, the G900 SE "stop movie" fix-up and the
`BLEConnectionHistory` SSID/key write (call `s.i.a(app, new s.h())` instead if those matter).

**D. Disconnect action (optional; the app itself has no user-facing disconnect)**

```java
CameraDevice dev = app.f783a;
if (dev != null) {
    if (dev.isConnected(DeviceInterface.BLE))  dev.disconnect(DeviceInterface.BLE);
    if (dev.isConnected(DeviceInterface.WLAN)) dev.disconnect(DeviceInterface.WLAN);
}
app.f783a = null; app.f785g = null;   // mirror m/f2.deviceDisconnected
```

**E. Post-connect navigation** — the wizard itself starts `MainActivity` with
`cameraImages="cameraImages"`, `FLAG_ACTIVITY_NEW_TASK|FLAG_ACTIVITY_CLEAR_TOP` (0x14000000).
A modern home screen will land **under** that Activity; if it must remain the destination, either
(a) let the legacy MainActivity be the "camera album" screen (least invasive), or
(b) start the wizard, detect `f783a != null` on resume, and finish yourself — the wizard will still push
`MainActivity` on top (cannot be suppressed without replacing the wizard Activities).

---

## 6. Pitfalls checklist

* Never touch the SDK on the main thread: `detect()` and `connect()` do blocking HTTP with retries.
* `CameraDeviceDetector.detect(WLAN)` returns `[]` (not an error) while a previous detect runs; cache-guard.
* `setContext` must be re-called after process death; it is static state in the SDK, not persisted.
* `f783a` may be a BLE-only connection — gate the gallery/shooting UI on `isConnected(WLAN)`.
* Publish `f783a`/`f784c` yourself after an out-of-band connect, otherwise MainActivity/transfer code
  (which reads `SvApplication.f783a`) stays disconnected.
* Do **not** call `SvApplication.e()` (kills the process) or `c()` (starts the foreground location service).
* `SvApplication.f789n` is a one-shot flag consumed by `MainActivity` (jump to *setting* tab on next start).
* Activities are `singleTop`; reuse of stale intents (`onNewIntent`) matters for `cameraImages`/
  `MOVE_TO_PREVIOUS_TAB`.
* Android 12+ Bluetooth needs `BLUETOOTH_CONNECT`/`SCAN` at runtime; Wi-Fi scanning + SSID need
  `ACCESS_FINE_LOCATION` and GPS on (Android 13+ `NEARBY_WIFI_DEVICES` is not requested by this app).
* `Result.OK` is the only success value; `Response.getErrors()` carries `ErrorCode`.
* Firmware quirks: GR III fw ≤ 1.0 forces a dialog before MainActivity; GR II/K-S2 old fw get the
  channel-change fix-up (`s.i.a`). Copying `s.i.a` keeps those behaviours.

---

## 7. Key evidence map

| Topic | File |
|---|---|
| State singleton, lifecycle, timers | `com/ricohimaging/imagesync/SvApplication.java` |
| Bootstrap (terms/perms/auto-connect) | `TitleActivity.java`, `m/i6.java`, `b2/e.java`, `m/g6.java` |
| Detect + connect helper | `s/i.java`, `s/e.java`, `s/s.java` |
| Wi-Fi join / reconnect | `j2/g.java` (`i`, `j`, `k`, `z`, `A`, `B`, `C`) |
| AP prefs / history files | `p/a.java`, `s/g.java` (`x`, `y`, `d`, `U`), `q/b.java`, `s/g.v()` |
| Wizard routing | `b2/d.java`, `SelectConnectionModelActivity.java`, `SelectConnectionProcessActivity.java` |
| Wizard screens/extras | `CheckWifiActivity`, `SelectWifiApActivity`, `InputWifiPasswordActivity`, `ConnectingWifiActivity`, `SelectBleDeviceActivity`, `Check*Activity`, `ConnectErrorActivity` |
| Post-connect nav | `ConnectingWifiActivity.java:57-87`, `SelectBleDeviceActivity.java:83-106`, `s/u.java:65-110`, `MainActivity.java` |
| SDK WLAN detect | `impl/WiFiCameraDeviceDetector.java`, `impl/ItemCameraInfo.java`, `api/CameraDeviceDetector.java` |
| SDK connect/state | `impl/ImplCameraDevice.java`, `impl/ImplCameraDeviceWifiAdapter.java`, `impl/ConnectionChecker.java`, `impl/ImplCameraStatus.java` |
| Model support lists | `impl/ImplCameraDeviceWiFiAdapterFactory.java` (`SUPPORT_CAMERA`), `m/b0.java` |
| Room DB | `db/SvAppDatabase.java` (entity `q.a`, version 9) |
