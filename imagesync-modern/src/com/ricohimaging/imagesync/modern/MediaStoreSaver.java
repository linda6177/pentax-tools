package com.ricohimaging.imagesync.modern;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Saves downloaded camera files into the user gallery without new resources.
 *
 * <p>Android 10+ uses a MediaStore insert into {@code Pictures/Image Sync};
 * older releases write to the public Pictures folder and trigger a media scan.
 * Every call is off-main-thread and reports one final display path or an error.</p>
 *
 * <p>When the user picked HEIC in the settings ("modern_ui"/"save_format" ==
 * "heic") and {@link HeicConverter#isSupported(Context)} is true, JPEG/PNG
 * downloads are re-encoded to HEIC before the MediaStore insert: the display
 * name keeps its base and gets a {@code .heic} extension, the MIME type becomes
 * {@code image/heic} and the relative path stays {@code Pictures/Image Sync}.
 * Any conversion problem is logged and silently falls back to the original
 * JPEG/PNG bytes, so saving never becomes less reliable because of HEIC.</p>
 */
public final class MediaStoreSaver {

    private static final String TAG = "MediaStoreSaver";
    private static final String PREFS_NAME = "modern_ui";
    private static final String PREF_SAVE_FORMAT = "save_format";
    private static final String SAVE_FORMAT_HEIC = "heic";

    public interface ResultCallback {
        void onSaved(String displayPath);
        void onError(Throwable error);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MediaStoreSaver() {}

    /** True when the user selected HEIC as the save format (default is JPEG). */
    public static boolean isHeicEnabled(Context context) {
        try {
            if (context == null) return false;
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME,
                    Context.MODE_PRIVATE);
            if (prefs == null) return false;
            return SAVE_FORMAT_HEIC.equalsIgnoreCase(
                    prefs.getString(PREF_SAVE_FORMAT, "jpeg"));
        } catch (Throwable error) {
            Log.w(TAG, "cannot read the save-format preference", error);
            return false;
        }
    }

    /**
     * True when this payload should go through the HEIC encoder: HEIC selected,
     * an image payload, and the device can actually produce HEIC.
     */
    public static boolean shouldEncodeHeic(Context context, String mimeType) {
        try {
            if (context == null || mimeType == null) return false;
            if (!isHeicEnabled(context)) return false;
            boolean encodable = "image/jpeg".equalsIgnoreCase(mimeType)
                    || "image/jpg".equalsIgnoreCase(mimeType)
                    || "image/png".equalsIgnoreCase(mimeType);
            if (!encodable) return false;
            return HeicConverter.isSupported(context);
        } catch (Throwable error) {
            Log.w(TAG, "cannot decide on HEIC encoding", error);
            return false;
        }
    }

    public static boolean needsLegacyPermission() {
        return Build.VERSION.SDK_INT < 29;
    }

    public static void save(final Context context, final byte[] data,
                            final String displayName, final String mimeType,
                            final ResultCallback callback) {
        new Thread(() -> {
            try {
                String path = saveBlocking(context, data, displayName, mimeType);
                MAIN.post(() -> callback.onSaved(path));
            } catch (Throwable error) {
                MAIN.post(() -> callback.onError(error));
            }
        }, "gallery-save").start();
    }

    public static void save(final Context context, final File source,
                            final String displayName, final String mimeType,
                            final ResultCallback callback) {
        new Thread(() -> {
            try {
                String path = saveBlocking(context, source, displayName, mimeType);
                MAIN.post(() -> callback.onSaved(path));
            } catch (Throwable error) {
                MAIN.post(() -> callback.onError(error));
            }
        }, "gallery-save").start();
    }

    public static String saveBlocking(Context context, byte[] data, String displayName,
                                      String mimeType) throws Exception {
        if (shouldEncodeHeic(context, mimeType)) {
            try {
                byte[] heic = HeicConverter.encodeJpegToHeic(data, cacheDirectory(context));
                // Recurse with the HEIC MIME type: shouldEncodeHeic() then sees a
                // non-JPEG payload and takes the normal MediaStore path.
                return saveBlocking(context, heic, heicName(displayName),
                        HeicConverter.MIME_HEIC);
            } catch (Throwable error) {
                // Silent fallback to the original JPEG/PNG: log only.
                Log.w(TAG, "HEIC conversion failed, saving the original image", error);
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Image Sync");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = mimeType != null && mimeType.startsWith("video/")
                    ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            Uri uri = resolver.insert(collection, values);
            if (uri == null) throw new java.io.IOException("MediaStore insert failed");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("MediaStore stream unavailable");
                out.write(data);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return Environment.DIRECTORY_PICTURES + "/Image Sync/" + displayName;
        }
        File directory = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "Image Sync");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new java.io.IOException("Cannot create " + directory);
        }
        File target = uniqueFile(directory, displayName);
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(data);
        }
        MediaScannerConnection.scanFile(context.getApplicationContext(),
                new String[]{target.getAbsolutePath()}, new String[]{mimeType}, null);
        return target.getAbsolutePath();
    }

    public static String saveBlocking(Context context, File source, String displayName,
                                      String mimeType) throws Exception {
        if (shouldEncodeHeic(context, mimeType)) {
            File converted = null;
            try {
                converted = File.createTempFile("is_heic_", ".heic", cacheDirectory(context));
                if (HeicConverter.encodeFileToHeic(source, converted)) {
                    return saveBlocking(context, converted, heicName(displayName),
                            HeicConverter.MIME_HEIC);
                }
            } catch (Throwable error) {
                // Silent fallback to the original JPEG/PNG: log only.
                Log.w(TAG, "HEIC conversion failed, saving the original image", error);
            } finally {
                if (converted != null && converted.exists()) converted.delete();
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/Image Sync");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = mimeType != null && mimeType.startsWith("video/")
                    ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            Uri uri = resolver.insert(collection, values);
            if (uri == null) throw new java.io.IOException("MediaStore insert failed");
            try (OutputStream out = resolver.openOutputStream(uri);
                 java.io.InputStream in = new java.io.FileInputStream(source)) {
                if (out == null) throw new java.io.IOException("MediaStore stream unavailable");
                byte[] buffer = new byte[128 * 1024];
                int count;
                while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return Environment.DIRECTORY_PICTURES + "/Image Sync/" + displayName;
        }
        File directory = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "Image Sync");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new java.io.IOException("Cannot create " + directory);
        }
        File target = uniqueFile(directory, displayName);
        try (java.io.InputStream in = new java.io.FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
        }
        MediaScannerConnection.scanFile(context.getApplicationContext(),
                new String[]{target.getAbsolutePath()}, new String[]{mimeType}, null);
        return target.getAbsolutePath();
    }

    /** {@code IMG_0001.JPG} -> {@code IMG_0001.heic}; always returns a name. */
    private static String heicName(String displayName) {
        if (displayName != null) {
            String name = displayName.trim();
            if (!name.isEmpty()) {
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                if (!base.isEmpty()) return base + ".heic";
            }
        }
        return "IMG_" + System.currentTimeMillis() + ".heic";
    }

    private static File cacheDirectory(Context context) {
        try {
            if (context != null) {
                File dir = context.getCacheDir();
                if (dir != null && (dir.isDirectory() || dir.mkdirs() || dir.isDirectory())) {
                    return dir;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "cannot resolve cache directory", error);
        }
        return null;
    }

    private static File uniqueFile(File directory, String name) {
        File candidate = new File(directory, name);
        if (!candidate.exists()) return candidate;
        String base = name;
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            extension = name.substring(dot);
        }
        for (int i = 1; i < 1000; i++) {
            candidate = new File(directory, base + " (" + i + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(directory, base + "-" + System.currentTimeMillis() + extension);
    }
}
