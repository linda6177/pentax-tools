# NOTES-GALLERY — camera album, thumbnails, download, local media

Source: `analysis\jadx\sources` (JADX 1.5.3) of `Image Sync.apk`, sha256
`00756f0c...a639442`, `com.ricohimaging.imagesync` 2.1.32, minSdk 23, targetSdk 36.
Legend: **[OBS]** = read directly in decompiled code; **[INF]** = inferred from callers.

## 0. Class map (app side)

| class | role |
|---|---|
| `m.a0` | `CameraImagesFragment`: camera album, 3 tabs (not-transferred/transferred/all) |
| `m.a1` | `DeviceImagesFragment`: LOCAL phone gallery, Room-DB driven, per-day timeline |
| `m.j` | album helper: image-list fetch, Room cache upsert, download queue, event hooks |
| `m.i` | `ImageDataStream` subclass: full-size download + progress + save path |
| `m.i1` | same for `ImageViewActivity` single-image save (near-identical to `m.i`) |
| `m.d` | `ImageDataStream` used only to probe movie size via `getTotalSize()` |
| `m.g/m.f/m.s` | `CameraEventListener`s: latestCaptureImageChanged / transferImagesNotEmptied / deviceDisconnected |
| `m.t` | one-shot task rebuilding the album list (`a0.c`) and re-rendering |
| `m.y` | refresh/prefetch task: thumbnails for visible cells through `a.a.L/M` |
| `a.a` | static helpers: `L` thumbnail fetch, `M` preview fetch, `b` decode+orient, `f` unique name, `p` copy, `R` MediaScanner, `t` delete-tree, `v` delete file+MediaStore row |
| `q.a` | Room entity (uuid, camera name, date, size, paths, storage id, folder) |
| `SvAppDatabase` | Room DB `photo-database` v9, `allowMainThreadQueries()` |
| `m.u` | `TAB_NOT_TRANSFERRED / TAB_ALREADY_TRANSFERRED / TAB_ALL` |
| `s.a` | AsyncTask clone: `b(Object[])` on single-thread executor, `e(result)`/`d()` on main |
| `s.g` | model/firmware capability checks (`K`,`N`,`F`,`G`,`M`,`O`,`L`) |
| `s.i` | connect helper: `connect(iface)`, `setBackgroundSyncImages(false)`, `setSortMode(false)`, registers `m.j` listener **[OBS]** |
| `SvApplication` | `f783a` = current `CameraDevice` singleton; `b` = shared `m.j` |
| SDK | `com.ricoh.camera.sdk.wireless.api.*` unobfuscated interfaces, impl in `...wireless.impl` |

The album never connects the camera: `CameraDevice dev = ((SvApplication)getApplication()).f783a;` **[OBS]** (`m.a0.c`, `m.j.c`, `SvApplication.b`).

## 1. Listing camera images

### 1.1 Exact sequence (`m.j.f()`, copyable)

```java
// MUST run off the main thread (blocking HTTP).
CameraDevice dev = app.f783a;
if (dev == null || !dev.isConnected(DeviceInterface.WLAN)) abort();
List<CameraStorage> storages = new ArrayList<>(dev.getStorages());      // 0/1/2 entries
CameraStorage st;
if (s.g.N(dev)) {                       // dual-card models
    st = (storages.size() > 1 && storages.get(1).isAvailable()) ? storages.get(1) : storages.get(0);
} else {
    st = storages.get(0);
}
// multi-slot + dual-card: keep every storage whose getPermission() != UNKNOWN
if (s.g.K(dev.getModel()) && st.getPermission() == StoragePermission.UNKNOWN) return empty;
if (st.getPermission() == StoragePermission.UNKNOWN) return empty;      // card unavailable

Response r = dev.updateImages();        // starts camera-side listing; blocking
if (r.getResult() == Result.ERROR) return empty;                       // detail: r.getErrors()

List<CameraImage> images = dev.getImages();                            // cached adapter list
for (CameraStorage cs : usedStorages) {                                // listing is async
    int waited = 0;
    while (cs.getListImagesState() != StorageListImagesState.LISTED) {
        if (++waited > 60) return null;                                // gives up after 60 s
        Thread.sleep(1000);
    }
}
images = dev.getImages();               // re-read after listing completed
```

`StorageListImagesState = NOT_LISTED|LISTING|PENDING|LISTED|INTERRUPTED`. Original polls
1 s x up to 60 then returns `null`; callers wrap in `new ArrayList<>(...)` -> NPE swallowed
by the caller's `catch (Throwable)` (`m.a0.c`). **[OBS]**
`getImages()/getStorages()/getTransferImages()` are cheap adapter getters;
**`updateImages()` is the blocking network round-trip**.

### 1.2 Filtering, grouping, sorting (`m.a0.c`, `SvAppDatabase.g`, `q.a.compare`)

* Prefs: `SHARED_PREFERENCE_KEY_RAW_IMAGE_DISPLAY` (default false) hides `PEF`,`DNG`;
  `SHARED_PREFERENCE_KEY_VIDEO_DISPLAY` hides `AVI`,`MOV`,`MP4`.
* Group by `getFolderName()` (camera folder, e.g. `100RICOH`); folders in a `TreeMap`
  (comparator not recoverable, JADX artifact) - folder order unspecified **[INF]**.
* Images sorted **date DESC** (`q.a implements Comparator`).
* Tabs filter by storage id (`"sd1"` default, `"sd1_sd2"` = present on both cards).
* Re-render only via `Handler(Looper.getMainLooper()).post(...)` after worker finish
  (`m.t.e`, `m.y.e`, `m.w`).

### 1.3 Refresh / event listener contracts

Listeners (`CameraDevice.addEventListener`, deduplicated by scanning `getEventListeners()`):

| callback | original behaviour |
|---|---|
| `latestCaptureImageChanged(dev)` | `m.g`: if transfer idle -> `j.b(dev)` (re-check transfers + re-list) **[OBS]** |
| `transferImagesNotEmptied(dev)` | `m.f`: starts auto-transfer path (`j.b(dev)`) - camera has transfer-flagged images |
| `imageStored(dev,image)` | `m.g` override is **empty**; stills are picked up via `latestCaptureImageChanged` |
| `deviceDisconnected(dev)` | `m.s`: `removeEventListener(this)`; if WLAN not re-connected -> `runOnUiThread` -> connect screen |

Refresh: swipe/resume spawns `m.t` (`j.f()` -> `a0.c` -> `a0.l`); `m.y` loads thumbnails
for the visible viewport via `Executors.newFixedThreadPool(max(1,cpus/2))` (single thread
for model `G900 SE`). `a0.onDestroy()` cancels a running `m.i` (`ImageDataStream.cancel()`).
All listener callbacks arrive on SDK threads -> marshal to main before touching views.

## 2. Thumbnails / previews

### 2.1 API surface (`CameraImage`)

```java
Response getThumbnail(OutputStream out);                  // blocking, small JPEG
Acquisition getThumbnailAsync(OutputStream, AcquisitionCallback);
void getThumbnailAsync(ImageDataStream);
Response getViewData(OutputStream out);                   // blocking, VGA-ish preview JPEG
Acquisition getViewDataAsync(OutputStream, AcquisitionCallback);
void getViewDataAsync(ImageDataStream);
Response getExtraSmallData(OutputStream out);             // XS rendition, select models
void getExtraSmallDataAsync(ImageDataStream);
boolean hasThumbnail(); boolean hasViewData(); boolean hasExtraSmallData();
Response getData(OutputStream out);                       // blocking full file
Acquisition getDataAsync(OutputStream, AcquisitionCallback);
void getDataAsync(ImageDataStream);
Response retrieveInfo();                                  // blocking; fills getDateTime()
```

Predicates are computed **locally from the extension** at creation, no network
(`ImplCameraImage.toHasThumbnail/toHasViewData`):
`hasThumbnail` = `jpg|pef`; `hasViewData` = `jpg,tif,pef,dng,mp4,mov,avi`;
`hasExtraSmallData` = JPEG only on models in `SUPPORT_MODEL_XS_SIZE`.

### 2.2 Original thumbnail load (`a.a.L`, runs in `m.y` pool)

```java
File dir = new File(ctx.getFilesDir(), "Photo/Camera Thumbnail/"); dir.mkdirs();
String name = a.a.f(image.getName(), dir);        // unique: "IMG_0001_001.JPG" on clash
File out = new File(dir, name);
FileOutputStream fos = new FileOutputStream(out);
Response r = image.hasThumbnail() ? image.getThumbnail(fos)
           : image.hasViewData() ? image.getViewData(fos) : null;
if (r == null) { fos.close(); return null; }
fos.close();
if (r.getResult() == Result.OK) {
    Bitmap bmp = a.a.b(image, out.getAbsolutePath());  // decode 640x640 + orientation matrix
    // store path in Room (cameraThumbnailPath) and bind to the ImageView
} else { out.delete(); /* placeholder + error row */ }
```

Preview/lightbox `a.a.M`: `getViewData()` into `getFilesDir()/Photo/View/<uniqueName>`,
stored as `viewPath` **[OBS]**. Decode (`a.a.b`): `BitmapFactory` at <=640x640, then apply
`CameraImage.getOrientation()` (6 => rotate 90, 8 => rotate 270, 2/4/5/7 mirrors; fallback
`ExifInterface` when no CameraImage). Re-encodes JPEG q=100 only for paths containing `"Photo"`.

### 2.3 `ImageDataStream` contract (only async pattern used)

```java
public class ThumbStream extends ImageDataStream {
    final FileOutputStream fos;
    public ThumbStream(FileOutputStream out) { super(out); fos = out; }
    @Override public void progress() {          // SDK's raw download thread
        long done = getCurrentSize(), total = getTotalSize();
        int pct = total > 0 ? (int)(done * 100 / total) : 0;
        mainHandler.post(() -> ui.setProgress(pct));
    }
    @Override public void success() {
        try { fos.close(); } catch (IOException ignored) {}
        mainHandler.post(ui::showFile);
    }
    @Override public void error() {
        try { fos.close(); } catch (IOException ignored) {}
        file.delete();
        mainHandler.post(ui::showError);
    }
}
// image.getThumbnailAsync(new ThumbStream(fos));   or (fos, AcquisitionCallback)
```

Observed SDK mechanics: async download = `new Thread()` in `ImplCameraImage`; `progress`
per chunk, `success`/`error` exactly once (`ImplAcquisition.sendCallBack`); device not
connected => immediate `error`. The `ImageDataStream` constructor creates its
`ImplAcquisition` with `image == null`, so an `AcquisitionCallback` receives a **null
CameraImage** - keep your own reference (the `(OutputStream, callback)` overload passes
the real image). The SDK never closes the `OutputStream`; `cancel()` only sets
`CANCELLED` - delete partial files yourself.

## 3. Full-size download

### 3.1 Original chain (`a0.s` -> `j.d` -> `i` -> save)

1. Multi-select `a0.O` sorted (`m.e(3)`); takes `O.get(0)` ->
   `j.d(image, uuid, existingLocalPath, resizedFlag, viewHolder, completion)`.
2. Temp dir `getFilesDir()/Temp/`; name = `image.getName()` with literal `"?xs"` stripped;
   `new FileOutputStream(new File(tmpDir,name))`; requires
   `Environment.getExternalStorageState().equals("mounted")`.
   If `resizedFlag && format==JPEG` -> `getExtraSmallDataAsync`, else `getDataAsync`.
3. `i.progress()`: `pct = getCurrentSize()*100.0/getTotalSize()` -> main handler -> cell
   TextView `String.format(Locale.US,"%.1f", pct)`.
4. `i.success()`: flush/close; final dir
   `Environment.getExternalStorageDirectory()/Pictures/Image Sync/` (STILL_IMAGE) or
   `/Movies/Image Sync/` (movie); unique name `a.a.f` (`_001`,`_002`...); `a.a.p(tmp,dst)`
   FileChannel copy (10 MiB chunks) with delete-on-error; `a.a.R` =
   `MediaScannerConnection.scanFile` (jpg->image/jpeg, mov->video/quicktime,
   dng->image/x-adobe-dng, raw/tif->image/tiff, avi->video/avi); Room update
   `filePath/fileName/fileSize` (skipped when pref `TRANSFERRED_STATE_RESIZE_IMAGE` and
   resized); app thumbnail copied to `getFilesDir()/Photo/App Thumbnail/`; main post ->
   completion callback removes finished item from `a0.O` and starts the next.
5. `i.error()`: log `getErrors()`, close, delete temp file, DB status flag, main post;
   queue continues.

No **delete-on-camera** exists. SDK offers `CameraTransferImage.setTransferStatus(
TransferStatus.TRANSFERRED)` + `CameraDevice.getTransferImages()`; no app call site of
`setTransferStatus` in this APK **[OBS]**. `m.c` only waits <=10 s for a
`CameraTransferImage` to leave `getTransferImages()` (camera auto-removes it), then refreshes.

Size probe (`j.c`): MOV/AVI often report `getFileSize()==0`; original streams the entire
file into a `ByteArrayOutputStream` (`m.d`) just to read `getTotalSize()` and cancels as
soon as it is non-zero. Don't repeat - take it from `progress()`.

### 3.2 Scoped-storage reality check (important!)

The APK targets **SDK 36** but still writes with legacy `java.io.File` into
`/storage/emulated/0/Pictures/Image Sync/`; manifest carries only `WRITE_EXTERNAL_STORAGE`
+ `requestLegacyExternalStorage=true`, and dex has **no MediaStore insert, no
MANAGE_EXTERNAL_STORAGE, no SAF** **[OBS]**. On API 30+ the legacy flag is ignored, so the
final copy can silently fail in `a.a.p` (IOException swallowed, dst deleted) while the file
stays in `Temp/` and success is reported. Rewrite: write app-private, then publish via
MediaStore (`EXTERNAL_CONTENT_URI`, `RELATIVE_PATH="Pictures/Image Sync"`, `IS_PENDING=1`
while writing). **[OBS for original code; INF for on-device failure mode]**

## 4. Local phone photos (original)

No MediaStore query for phone images. `m.a1` (`DeviceImagesFragment`) lists Room rows
(`SvAppDatabase.i()`), groups by day (display format `yyyy年M月d日`, descending), sorts
date-DESC within a day. Only MediaStore use anywhere is
`delete(MediaStore.Files.getContentUri("external"), "_data=?", ...)` in `a.a.v`. **[OBS]**
Entity `q.a` (table `Photo`, columns from `o.a` UPDATE statements): `uuid`
(=name+date+folder+size), `fileName`, `cameraName`, `date`, `fileSize`, `filePath`,
`viewPath`, `cameraThumbnailPath`, `appThumbnailPath`, `storageId` (`sd1`/`sd2`/`sd1_sd2`),
`folderName`. Delete removes file + cached thumb/view files + row (`m.a1.a`).

Rewrite with MediaStore:

```java
Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;   // + Video.Media for movies
String[] cols = { MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                  MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.SIZE,
                  MediaStore.Images.Media.MIME_TYPE };
Cursor c = getContentResolver().query(uri, cols,
        MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?", new String[]{"Pictures/Image Sync%"},
        MediaStore.Images.Media.DATE_TAKEN + " DESC");
// thumb: ContentResolver.loadThumbnail(contentUri, new Size(256,256), null)  (API 29+)
```
Permissions: SDK 33+ `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` for foreign media; own inserted
rows need none. `WRITE_EXTERNAL_STORAGE` is a no-op on API 30+.

## 5. Camera status / model / battery, multi-select

* `dev.getModel()`, `getFirmwareVersion()`, `getManufacturer()`, `getSerialNumber()` -
  cached, no network.
* `dev.getStatus()` -> `getBatteryLevel()`, `getBatteryUsed()`, `getCurrentCapture()`
  (state/self-timer). App only uses `getStatus().getCurrentCapture()` in
  `ShootingActivity`/`m.v3`; **no battery UI in the gallery** **[OBS]**.
* `dev.isPower()`, `dev.isCapturingMovie()`; storage `getRemainingPictures()`,
  `getRecordableTime()`, `getPermission()`, `isFormatted()`, `isWritingTarget()`, `getType()`.
* Gates used by album: `s.g.K(model)` (multi-slot/dual-card), `s.g.N(dev)` (dual slot),
  `s.g.F(model)` (supported; else error dialog), `s.g.G(dev)` (model+firmware), `s.g.M/O/L`.
* Multi-select: statics `m.a0.K` select-mode, `L` downloading, `M` auto-transfer,
  `O` selected images, `P` batch snapshot. Long-press enters select mode; tap toggles
  check mark + alpha 0.5; title `select_count`; back exits. Batch download is
  **sequential**: one `getDataAsync` at a time, completion removes item 0 and calls `s()`
  again; empty queue resets `f1303s=false`.

## 6. Pitfalls (verified)

1. **Threading**: all non-`Async` SDK calls (`updateImages`, `getStorages`, `retrieveInfo`,
   `getThumbnail`, `getViewData`, `getData`, `getStatus`, `connect`) do blocking HTTP; the
   original only calls them from executors/Threads. Async callbacks run on SDK raw threads.
2. `updateImages()` only *starts* listing; poll `getListImagesState()` until `LISTED`
   (1 s x 60 in original). Handle `INTERRUPTED` by re-listing.
3. `getImages()` returns `null` when not connected - NPE inside `new ArrayList<>(...)` in
   `a0.c`. Null-check.
4. `CameraImage` equals/hashCode is identity-based and changes after `updateImages()`;
   never key maps across refreshes (original keys by synthesized uuid).
5. `getDateTime()` is null until `retrieveInfo()` (one HTTP call per image). Cache it;
   don't call synchronously for the whole grid on the UI thread.
6. `getFileSize()==0` for many movies; original downloads the whole file to learn total
   size (`m.d`) - avoid.
7. Strip `"?xs"` from `getName()` before building local filenames (`j.d`, `i`).
8. `getThumbnail` errors on unsupported images; branch on `hasThumbnail()/hasViewData()`
   and delete partial files on failure.
9. Orientation: use `getOrientation()` for camera images, `ExifInterface` only for saved files.
10. Close the `OutputStream` yourself; one stream per request; `success()` = exactly once.
11. Guard `getTotalSize()==0` in `progress()`.
12. `cancel()` doesn't delete files.
13. Don't copy the legacy save path (3.2) - it fails silently on API 30+.
14. Keep batch downloads sequential (camera HTTP server).
15. Listeners live on the `CameraDevice` singleton; remove in `onDestroy` or leak/duplicate refresh.

## 7. Not established

* `m.j.e(CameraDevice)` (497 bytecodes, JADX `UnsupportedOperationException`) - transfer
  image/auto-sync handler called after listing; exact ordering unknown **[INF]**.
* `x.c.onBindViewHolder` and `m.y.j(...)` not decompiled; thumbnail->ImageView plumbing is
  inferred from `a.a.L/M` and `x.d` holder fields.
* Folder sort comparator in `a0.l` decompiled to a constant (JADX artifact).
* Chunk sizes / whether `Content-Length` is always present (`ImplAcquisition.totalSize`
  starts at 0).
