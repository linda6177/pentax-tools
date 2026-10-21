# Modern UI — internal API contract (for screen implementers)

Read this before writing any screen. Do not modify files outside your assigned scope;
message the Lead (`send_message target=lead`) if a shared API must change.

## Build / verify

```
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
```
It compiles `stubs/` (compile-time API of the ORIGINAL Ricoh SDK) then `src/**` and produces
`dist/modern-shell.dex`. Two-stage compile exists because the modern UI ships as an auxiliary DEX
(`classes3.dex`) and must not package the original classes — **never** add SDK/AndroidX classes by
copying source; only reference them via the existing stubs.

Constraints: Java 8 source level, **no AndroidX / Material classes**, **no new resource XML**
(resources.arsc stays byte-identical), no Kotlin, no external libraries, all SDK calls off the main
thread. Chinese UI copy is expected (existing screens are Chinese).

## Architecture

- `ShellActivity` (launcher `ModernHomeAct extends ShellActivity`) is a **single activity** hosting
  4 `Screen`s in a `FrameLayout` (`contentArea()`); tab switches cross-fade/slide in place.
- `Screen` (package `modern`): `protected View onCreateView()`, `onShow()`, `onHide()`,
  `onHostResume()`, `onHostPause()`, `boolean onBackPressed()`, `onDestroyView()`.
  Fields available: `host` (ShellActivity), `t` (Theme), `dp(float)`, `activity()`, `alive()`.
- Full-screen overlays: `host.openOverlay(Screen)`, `host.openViewer(List<CameraImage>, index, View sharedFrom)`,
  `host.closeOverlay()`, `host.hasOverlay()`. Overlay back is routed by shell.
- Insets are already applied to the content area (status bar top). Do not add status-bar padding again.

## Theme `t` (see Theme.java)

`dark, background, surface, surfaceAlt, surfaceHigh, text, muted, accent, onAccent, accentSoft,
danger, success, hairline, scrim, radiusCard(26), radiusInner(18), radiusPill(100)`,
`t.typeface(0|1|2)`, `t.display()`, `t.accentWithAlpha(int alpha 0..255)`.
Night mode + Android 12 system accent are resolved automatically.

## UiKit (see UiKit.java)

- `rounded(ctx,color,radiusDp)`, `stroke(...)`, `gradient(...)`, `pressable(fill,radius,ripple)`,
  `circularRipple(color)`, `roundClip(view,radiusDp)`
- text: `label(a,t,text,sizeSp,color,weight 0..2)`, `title(a,t,text,sizeSp)`, `overline(a,t,text,color)`,
  `body(a,t,text,sizeSp,color)`
- layout: `column(a)`, `row(a)`, `spacer(a,heightDp)`, `hspacer(a,widthDp)`, `card(a,t,radiusDp)`,
  `hairline(a,t)`
- buttons: `primaryButton`, `tonalButton`, `textButton`, `iconButton(a,t,Icon,tint,Runnable)`
- motion: `pressFeedback(view)`, `enterFromBottom(view,delay)`, `enterFade(view,delay)`,
  `staggerChildren(group,start,step)`, `setVisibleAnimated(view,visible,duration)`,
  `haptic(view)`, `hapticTick(view)`, `DECELERATE`, `DECELERATE_SOFT`, `OVERSHOOT`
- misc: `roundBitmap(bitmap,radiusPx)`, `drawFocusReticle(canvas,paint,cx,cy,size)`

## Icons `new Glyph(activity, Glyph.Icon.X)` + `.setTint(color)` + `.setFilled(bool)`

HOME, GALLERY, CAMERA, SETTINGS, CLOSE, CHEVRON_RIGHT, CHEVRON_LEFT, BACK, DOWNLOAD, SHARE, DELETE,
INFO, REFRESH, WIFI, BLUETOOTH, BATTERY, FLASH, TIMER, FOCUS, SHUTTER, GRID, CHECK, MORE, SUN,
SLIDERS, PLAY, RECORD, ALBUM, LINK, SYNC, PHONE, TELE, FOLDER, UPLOAD, EXPAND, SPARKLE, WARNING,
CAMERA_FLIP. (24x24 grid, Canvas paths.)

## ShellActivity helpers

`t`, `host.bridge()`, `host.imageLoader()`, `host.toast(String)`, `host.showSnack(String)`,
`host.openLegacy("ShootingSettingActivity"|"MainActivity"|"TitleActivity"|...)`,
`host.launchConnectionWizard()`, `host.selectTab(ShellActivity.TAB_*, boolean animate)`,
`host.openViewer(images,index,sharedView)`, `host.openOverlay(screen)`, `host.closeOverlay()`,
`host.saveToGallery(byte[] data, String name, MediaStoreSaver.ResultCallback)`,
`host.contentArea()`.

## CameraBridge (all callbacks arrive on the main thread)

State:
`State { DISCONNECTED, BLE_ONLY, CONNECTED }`, `state()`, `device()`, `model()`, `firmware()`,
`batteryLevel()`, `charging()`, `termsAccepted(Activity)`, `addStateListener(StateListener)`,
`removeStateListener(...)`, `ensureBootstrap()`, `connectOverWifi(ConnectCallback)`,
`openConnectionWizard(Activity)`, `disconnect(Activity)`.

Gallery:
`loadCameraImages(ImageListCallback)` (blocking refresh + list state polling on worker; returns sorted
`List<CameraImage>` newest-first),
`loadThumbnail(CameraImage,int targetPx,BitmapCallback)`,
`loadPreview(CameraImage,int targetPx,BitmapCallback)`,
`downloadImage(CameraImage,String displayName,ProgressCallback,SaveCallback)` (async acquisition, temp
file + MediaStore publish; progress may be coarse),
`downloadImageSync(CameraImage,String,SaveCallback)` (fallback path),
`loadLatestCaptureImage(BitmapCallback)`.
`CameraImage`: `getName()`, `getFolderName()`, `getDateTime()` (may be null until info retrieval),
`getFormat()`, `getFileSize()`, `hasThumbnail()`, `hasViewData()`, `getOrientation()`.
Image identity changes after `updateImages()`; key caches with folder/name, never with the object.

Live view / remote:
`startLiveView(LiveViewCallback)` → `onFrame(Bitmap)` (decoded RGB_565, ~1 per SDK frame, coalesced),
`stopLiveView()`, `liveViewRunning()`,
`capture(boolean selfTimer, CaptureCallback)` (`onStarted` then `onComplete`, `onError`),
`captureAt(float nx,float ny,CaptureCallback)` (nx/ny normalized 0..1 of live-view image),
`stopCapture(CaptureCallback)`, `focusAt(float nx,float ny,FocusCallback)`, `focusCenter(FocusCallback)`.

Settings (values are strings exactly as the camera reports):
`readSettings(List<String> names, SettingsCallback)` → `SettingInfo{name,value,choices}`,
`writeSetting(String name,String value,SimpleCallback)` (uses canonical camera value objects),
`stepExposureCompensation(int direction,SimpleCallback)`,
constants `S_EXPOSURE_PROGRAM, S_ISO, S_F_NUMBER, S_SHUTTER_SPEED, S_EXPOSURE_COMPENSATION,
S_WHITE_BALANCE, S_STILL_QUALITY, S_STILL_FORMAT, S_FLASH_MODE, S_METERING, S_DIGITAL_FILTER, S_ZOOM,
S_CAPTURE_METHOD`.

`ImageLoader host.imageLoader()`:
`into(ImageView, String key, Source source, int targetPx, int fadeMs)`
`Source = boolean load(OutputStream sink)`; `load(key,source,targetPx,Callback)`, `cached(key)`,
`put(key,bitmap)`, static `decode(bytes,targetPx)`, `decodeFrame(bytes)`.

`MediaStoreSaver` is used by the bridge; only call `host.saveToGallery` for in-memory bitmaps.

## Visual language

- Surfaces: `t.surface` cards on `t.background`; 26dp cards, 18dp inner rows, hairline stroke 0.7dp.
- Accent: soft accent circles (`t.accentSoft`) behind icons; primary buttons full-width 52dp.
- Motion: 200–360 ms, `DECELERATE_SOFT`; entrance stagger 24–48 ms per item; haptics on tap
  (`UiKit.hapticTick`); no abrupt visibility toggles — use `setVisibleAnimated` or alpha/translation.
- Typography: 27–30sp bold titles with -0.015 letter spacing, 11.5sp uppercase overline with 0.14
  letter spacing, 13–14.5sp body, 11.5–12.5sp secondary.

## Ground truth notes (read before coding)

`analysis/NOTES-GALLERY.md`, `analysis/NOTES-REMOTE.md`, `analysis/NOTES-CONNECT.md`
(JADX sources at `analysis/jadx/sources`; the APK is `D:\Download\OPPO\Image Sync.apk`).
