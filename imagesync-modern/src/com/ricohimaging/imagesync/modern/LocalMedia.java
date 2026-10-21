package com.ricohimaging.imagesync.modern;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One photo discovered in the device MediaStore, plus the tiny amount of
 * shared formatting/decoding helpers used by the gallery and the full-screen
 * viewer.
 *
 * <p>Besides normal JPEG/PNG entries the scan also picks up RAW photos
 * (DNG/PEF): they are displayed through the embedded sRGB JPEG preview decoded
 * by {@link RawPreview} — never demosaiced. All queries and decoding happen on
 * worker threads; this class never touches the UI.</p>
 */
public final class LocalMedia {

    /** MediaStore id (stable per row). */
    public final long id;
    public final Uri uri;
    public final String displayName;
    public final long dateMillis;
    public final long sizeBytes;
    public final String mimeType;
    /** EXIF orientation in degrees as reported by MediaStore (0/90/180/270). */
    public final int orientation;
    /** True for DNG/PEF RAW files that must be shown through their JPEG preview. */
    public final boolean raw;

    private LocalMedia(long id, Uri uri, String displayName, long dateMillis,
                       long sizeBytes, String mimeType, int orientation, boolean raw) {
        this.id = id;
        this.uri = uri;
        this.displayName = displayName;
        this.dateMillis = dateMillis;
        this.sizeBytes = sizeBytes;
        this.mimeType = mimeType;
        this.orientation = orientation;
        this.raw = raw;
    }

    /** Stable cache key; MediaStore ids survive process restarts. */
    public String key() {
        return "loc:" + uri;
    }

    /** True for DNG/PEF photos displayed via their embedded JPEG preview. */
    public boolean isRaw() {
        return raw;
    }

    /** Short uppercase format label for the cell badge / viewer info (JPG…). */
    public String formatLabel() {
        String extension = extensionOf(displayName);
        if (raw) {
            return extension.isEmpty() ? "RAW" : extension;
        }
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.US);
        if (mime.contains("jpeg") || mime.contains("jpg")) return "JPG";
        if (mime.contains("png")) return "PNG";
        if (mime.contains("webp")) return "WEBP";
        if (mime.contains("heic") || mime.contains("heif")) return "HEIC";
        if (mime.contains("gif")) return "GIF";
        if (mime.contains("bmp")) return "BMP";
        return extension.isEmpty() ? "IMG" : extension;
    }

    // ------------------------------------------------------------------ query
    /** Result of one MediaStore scan, including why it may be incomplete. */
    public static final class Result {
        public final List<LocalMedia> items;
        public final boolean fullAccess;
        public final boolean failed;
        public final String message;

        Result(List<LocalMedia> items, boolean fullAccess, boolean failed, String message) {
            this.items = items;
            this.fullAccess = fullAccess;
            this.failed = failed;
            this.message = message;
        }
    }

    /** True when the app may see every photo, not only its own contributions. */
    public static boolean hasFullAccess(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return context.checkSelfPermission("android.permission.READ_MEDIA_IMAGES")
                        == PackageManager.PERMISSION_GRANTED;
            }
            return context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Permission worth requesting on this OS release. Android 13+ would need
     * READ_MEDIA_IMAGES, which the shipped manifest does not declare, so the
     * caller only offers the button below API 33.
     */
    public static String requestablePermission() {
        return Build.VERSION.SDK_INT >= 33
                ? "android.permission.READ_MEDIA_IMAGES"
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    /**
     * Scans recent photos newest first. When full access is missing we still
     * list the rows the app itself saved (Android 10+ grants own-media access),
     * so downloaded photos stay visible.
     */
    public static Result query(Context context, int limit) {
        ContentResolver resolver = context.getContentResolver();
        boolean fullAccess = hasFullAccess(context);
        List<LocalMedia> items = new ArrayList<>(Math.min(limit, 256));
        Set<String> seen = new HashSet<>();

        boolean failed = false;
        String message = null;
        try {
            queryImages(resolver, fullAccess, limit, items, seen);
        } catch (SecurityException denied) {
            return new Result(new ArrayList<>(), false, true, "没有读取照片的权限");
        } catch (Throwable error) {
            failed = true;
            message = "读取本机照片失败";
        }

        try {
            Collections.sort(items, new Comparator<LocalMedia>() {
                @Override
                public int compare(LocalMedia left, LocalMedia right) {
                    return Long.compare(right.dateMillis, left.dateMillis);
                }
            });
        } catch (Throwable ignored) {
            // Keep MediaStore order.
        }
        return new Result(items, fullAccess, failed, message);
    }

    private static void queryImages(ContentResolver resolver, boolean fullAccess, int limit,
                                    List<LocalMedia> items, Set<String> seen) {
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.ORIENTATION
        };
        String selection = null;
        String[] selectionArgs = null;
        if (!fullAccess) {
            selection = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
            selectionArgs = new String[]{"Pictures/Image Sync%"};
        }
        String sort = MediaStore.Images.Media.DATE_TAKEN + " DESC, "
                + MediaStore.Images.Media.DATE_ADDED + " DESC";
        Cursor cursor = null;
        try {
            cursor = resolver.query(collection, projection, selection, selectionArgs, sort);
            if (cursor == null) return;
            int idColumn = cursor.getColumnIndex(MediaStore.Images.Media._ID);
            int nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
            int takenColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN);
            int addedColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED);
            int sizeColumn = cursor.getColumnIndex(MediaStore.Images.Media.SIZE);
            int mimeColumn = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE);
            int orientationColumn = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION);
            while (cursor.moveToNext() && items.size() < limit) {
                try {
                    if (idColumn < 0) break;
                    long id = cursor.getLong(idColumn);
                    if (id <= 0) continue;
                    String name = nameColumn < 0 || cursor.isNull(nameColumn)
                            ? "IMG_" + id : cursor.getString(nameColumn);
                    long taken = takenColumn < 0 || cursor.isNull(takenColumn)
                            ? 0L : cursor.getLong(takenColumn);
                    long added = addedColumn < 0 || cursor.isNull(addedColumn)
                            ? 0L : cursor.getLong(addedColumn);
                    long size = sizeColumn < 0 || cursor.isNull(sizeColumn)
                            ? 0L : cursor.getLong(sizeColumn);
                    String mime = mimeColumn < 0 || cursor.isNull(mimeColumn)
                            ? null : cursor.getString(mimeColumn);
                    int orientation = orientationColumn < 0 || cursor.isNull(orientationColumn)
                            ? 0 : cursor.getInt(orientationColumn);
                    long date = taken > 0 ? taken : added * 1000L;
                    Uri uri = ContentUris.withAppendedId(collection, id);
                    boolean raw = isRaw(name, mime);
                    if (!seen.add(dedupeKey(name, size))) continue;
                    items.add(new LocalMedia(id, uri, name, date, size, mime, orientation, raw));
                } catch (Throwable rowError) {
                    // A single malformed row must never abort the whole album.
                }
            }
        } finally {
            closeQuietly(cursor);
        }
    }

    private static String dedupeKey(String name, long size) {
        return (name == null ? "" : name) + '|' + size;
    }

    // ---------------------------------------------------------------- decoding
    /**
     * Decodes a screen-sized preview with the MediaStore orientation applied.
     * RAW files are decoded from their embedded sRGB JPEG preview only.
     */
    public static Bitmap decodePreview(Context context, LocalMedia item, int targetPx) {
        if (item == null) return null;
        try {
            if (item.raw) {
                return RawPreview.decode(context, item.uri, targetPx);
            }
            return decodeStream(context, item.uri, targetPx, item.orientation);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Decodes an ARGB_8888 grid thumbnail on the calling (worker) thread.
     * Android 10+ asks MediaProvider for an orientation-correct thumbnail; RAW
     * rows (and providers without thumbnails) fall back to the embedded JPEG
     * preview, never to a full-size raw decode.
     */
    public static Bitmap loadThumbnailBitmap(Context context, LocalMedia item, int sizePx) {
        if (context == null || item == null) return null;
        ContentResolver resolver = context.getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                int size = Math.max(64, sizePx);
                Bitmap thumb = resolver.loadThumbnail(item.uri,
                        new android.util.Size(size, size), null);
                if (thumb != null) return thumb;
            } catch (Throwable ignored) {
                // Fall through to the embedded preview / stream decode.
            }
        }
        if (item.raw) return RawPreview.decode(context, item.uri, sizePx);
        return decodeStream(context, item.uri, sizePx, item.orientation);
    }

    private static Bitmap decodeStream(Context context, Uri uri, int targetPx, int orientation) {
        ContentResolver resolver = context.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream probe = null;
        try {
            probe = resolver.openInputStream(uri);
            if (probe == null) return null;
            BitmapFactory.decodeStream(probe, null, bounds);
        } catch (Throwable ignored) {
            return null;
        } finally {
            closeQuietly(probe);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        // Bound by the longest edge: a 3:2 photo must not fall back to
        // inSampleSize 1 just because its short edge is near the target.
        int longestEdge = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        if (targetPx > 0) {
            while (longestEdge / (sample * 2) >= targetPx) {
                sample *= 2;
            }
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                // The display pipeline composes in sRGB; make the decode target
                // sRGB explicitly so wide-gamut camera JPEGs do not shift.
                options.inPreferredColorSpace =
                        android.graphics.ColorSpace.get(
                                android.graphics.ColorSpace.Named.SRGB);
            } catch (Throwable ignored) {
                // Default color management is still sRGB on these releases.
            }
        }
        Bitmap bitmap;
        InputStream stream = null;
        try {
            stream = resolver.openInputStream(uri);
            if (stream == null) return null;
            bitmap = BitmapFactory.decodeStream(stream, null, options);
        } catch (Throwable ignored) {
            return null;
        } finally {
            closeQuietly(stream);
        }
        if (bitmap == null) return null;
        return applyOrientation(bitmap, orientation);
    }

    /**
     * Writes an orientation-correct thumbnail into an {@link ImageLoader.Source}
     * sink. Kept for the shared loader; the gallery now uses
     * {@link #loadThumbnailBitmap(Context, LocalMedia, int)} directly.
     */
    public boolean writeThumbnail(ContentResolver resolver, OutputStream sink, int sizePx) {
        if (resolver == null || sink == null) return false;
        if (Build.VERSION.SDK_INT >= 29) {
            Bitmap thumb = null;
            try {
                int size = Math.max(64, sizePx);
                thumb = resolver.loadThumbnail(uri, new android.util.Size(size, size), null);
                if (thumb != null) {
                    boolean ok = thumb.compress(Bitmap.CompressFormat.JPEG, 88, sink);
                    return ok;
                }
            } catch (Throwable ignored) {
                // Fall through to the raw stream below.
            } finally {
                if (thumb != null && !thumb.isRecycled()) thumb.recycle();
            }
        }
        if (raw) return false;
        return writeBytes(resolver, sink);
    }

    /**
     * Streams this row's bytes into an {@link ImageLoader.Source} sink. Used as
     * the API 23–28 fallback when {@code loadThumbnail} is unavailable.
     */
    public boolean writeBytes(ContentResolver resolver, OutputStream sink) {
        if (resolver == null || sink == null) return false;
        try {
            InputStream in = resolver.openInputStream(uri);
            if (in == null) return false;
            try {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = in.read(buffer)) > 0) {
                    sink.write(buffer, 0, count);
                }
            } finally {
                closeQuietly(in);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------- raw
    public static boolean isRaw(String displayName, String mimeType) {
        String extension = extensionOf(displayName);
        if ("DNG".equals(extension) || "PEF".equals(extension)) return true;
        if (mimeType == null) return false;
        String mime = mimeType.toLowerCase(Locale.US);
        return mime.contains("dng") || mime.contains("pef")
                || mime.contains("x-adobe-dng") || mime.contains("x-pentax-pef");
    }

    private static String extensionOf(String displayName) {
        if (displayName == null) return "";
        int dot = displayName.lastIndexOf('.');
        if (dot < 0 || dot >= displayName.length() - 1) return "";
        return displayName.substring(dot + 1).toUpperCase(Locale.US);
    }

    // ---------------------------------------------------------------- sharing
    /** Resolves the MediaStore row most likely created for a saved download. */
    public static Uri findSavedUri(Context context, String displayName) {
        if (displayName == null || displayName.isEmpty()) return null;
        ContentResolver resolver = context.getContentResolver();
        String base = displayName;
        int dot = base.lastIndexOf('.');
        if (dot > 1) base = base.substring(0, dot);
        Uri found = queryOne(resolver, MediaStore.Images.Media.DISPLAY_NAME + "=?",
                new String[]{displayName}, true);
        if (found != null) return found;
        return queryOne(resolver, MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?",
                new String[]{base + "%"}, true);
    }

    private static Uri queryOne(ContentResolver resolver, String selection,
                                String[] args, boolean newest) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID}, selection, args,
                    newest ? MediaStore.Images.Media.DATE_ADDED + " DESC" : null);
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0));
            }
        } catch (Throwable ignored) {
            // Fall through to null; the caller shows a graceful message.
        } finally {
            closeQuietly(cursor);
        }
        return null;
    }

    // -------------------------------------------------------------- formatting
    public static String formatSize(long bytes) {
        if (bytes <= 0) return "—";
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.1f GB", mb / 1024.0);
    }

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.CHINA);

    public static String formatDate(long millis) {
        if (millis <= 0) return "—";
        try {
            return DATE_FORMAT.format(new Date(millis));
        } catch (Throwable ignored) {
            return "—";
        }
    }

    public static String formatShortDate(long millis) {
        if (millis <= 0) return "";
        try {
            return new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date(millis));
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ------------------------------------------------------------------ utils
    private static Bitmap applyOrientation(Bitmap bitmap, int orientation) {
        int degrees = orientation;
        if (degrees == 0) return bitmap;
        if (degrees < 0) degrees = (degrees % 360 + 360) % 360;
        if (degrees != 90 && degrees != 180 && degrees != 270) return bitmap;
        try {
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle();
            return rotated;
        } catch (Throwable ignored) {
            return bitmap;
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // Best effort.
        }
    }
}
