package com.ricohimaging.imagesync.modern;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Extracts the embedded sRGB JPEG preview from a RAW file (DNG/PEF) and decodes
 * it. RAW photos are never demosaiced here: cameras embed a full or
 * near-full-size JPEG precisely so that galleries, cameras and quick viewers can
 * display the shot without a raw pipeline. We scan the container for JPEG
 * SOI/EOI pairs, prefer the largest candidate and validate it by decoding, so
 * the EXIF thumbnail nested inside the main preview cannot win by accident.
 *
 * <p>Everything runs on a worker thread; the file is read in a streamed first
 * pass (positions only) and then only the winning JPEG range is loaded into
 * memory. All bitmaps are ARGB_8888 for the display/LUT pipeline.</p>
 */
public final class RawPreview {

    /** Embedded previews are a few MB; refuse anything absurdly large. */
    private static final int MAX_PREVIEW_BYTES = 24 * 1024 * 1024;
    private static final int MAX_MARKERS = 128;
    private static final int MAX_CANDIDATES = 12;

    private RawPreview() {}

    /** Largest decodable embedded JPEG, orientation applied, or null. */
    public static Bitmap decode(Context context, Uri uri, int targetPx) {
        if (context == null || uri == null) return null;
        try {
            List<JpegCandidate> candidates = findJpegCandidates(context, uri);
            for (JpegCandidate candidate : candidates) {
                byte[] jpeg = readRange(context, uri, candidate);
                if (jpeg == null || jpeg.length < 4) continue;
                Bitmap bitmap = decodeJpeg(jpeg, targetPx);
                if (bitmap != null) {
                    return applyExifOrientation(jpeg, bitmap);
                }
            }
        } catch (Throwable ignored) {
            // Unsupported/corrupt containers simply produce no preview.
        }
        return null;
    }

    /** Cheap title check used for logging/diagnostics only. */
    public static boolean looksLikeRaw(String displayName) {
        if (displayName == null) return false;
        String lower = displayName.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".dng") || lower.endsWith(".pef");
    }

    // ------------------------------------------------------------------ scan
    private static final class JpegCandidate {
        final long offset;
        final int length;

        JpegCandidate(long offset, int length) {
            this.offset = offset;
            this.length = length;
        }
    }

    /**
     * Streams the file once, recording every SOI/EOI offset, then pairs them by
     * length. Pairing every start with every end (instead of blindly pairing a
     * start with the next end) is what keeps a nested EXIF thumbnail from
     * truncating the real preview.
     */
    private static List<JpegCandidate> findJpegCandidates(Context context, Uri uri)
            throws Exception {
        List<Long> starts = new ArrayList<>();
        List<Long> ends = new ArrayList<>();
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) return Collections.emptyList();
        try {
            byte[] buffer = new byte[128 * 1024];
            long position = 0L;
            int previous = -1;
            int read;
            while ((read = in.read(buffer)) > 0) {
                for (int i = 0; i < read; i++) {
                    int value = buffer[i] & 0xFF;
                    if (previous == 0xFF) {
                        long offset = position + i - 1;
                        if (value == 0xD8 && starts.size() < MAX_MARKERS) {
                            starts.add(offset);
                        } else if (value == 0xD9 && ends.size() < MAX_MARKERS) {
                            ends.add(offset);
                        }
                    }
                    previous = value;
                }
                position += read;
            }
        } finally {
            closeQuietly(in);
        }
        if (starts.isEmpty() || ends.isEmpty()) return Collections.emptyList();

        List<JpegCandidate> candidates = new ArrayList<>();
        for (Long start : starts) {
            for (Long end : ends) {
                if (end <= start) continue;
                long length = end - start + 2L;
                if (length < 4L || length > MAX_PREVIEW_BYTES) continue;
                candidates.add(new JpegCandidate(start, (int) length));
            }
        }
        // Largest first: the real preview is normally much larger than either
        // the EXIF thumbnail or any stray marker pair inside the container.
        Collections.sort(candidates, new Comparator<JpegCandidate>() {
            @Override
            public int compare(JpegCandidate left, JpegCandidate right) {
                return Integer.compare(right.length, left.length);
            }
        });
        if (candidates.size() > MAX_CANDIDATES) {
            return candidates.subList(0, MAX_CANDIDATES);
        }
        return candidates;
    }

    private static byte[] readRange(Context context, Uri uri, JpegCandidate candidate) {
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            long skipped = 0L;
            while (skipped < candidate.offset) {
                long step = in.skip(candidate.offset - skipped);
                if (step <= 0) {
                    // Some providers do not implement skip: consume manually.
                    if (in.read() < 0) return null;
                    step = 1;
                }
                skipped += step;
            }
            byte[] data = new byte[candidate.length];
            int read = 0;
            while (read < data.length) {
                int count = in.read(data, read, data.length - read);
                if (count < 0) break;
                read += count;
            }
            if (read < 4) return null;
            if (read == data.length) return data;
            byte[] shorter = new byte[read];
            System.arraycopy(data, 0, shorter, 0, read);
            return shorter;
        } catch (Throwable ignored) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static Bitmap decodeJpeg(byte[] jpeg, int targetPx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        if (targetPx > 0) {
            while (longest / (sample * 2) >= targetPx) sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                // RAW previews are displayed/LUT-processed as sRGB.
                options.inPreferredColorSpace =
                        android.graphics.ColorSpace.get(
                                android.graphics.ColorSpace.Named.SRGB);
            } catch (Throwable ignored) {
                // Default color management keeps sRGB on these releases.
            }
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
    }

    // ------------------------------------------------------------- orientation
    /**
     * The embedded JPEG may carry its own EXIF orientation (RAW containers often
     * leave the preview pixels unrotated). Apply it, then let the caller treat
     * the bitmap as plain sRGB.
     */
    private static Bitmap applyExifOrientation(byte[] jpeg, Bitmap bitmap) {
        if (bitmap == null || Build.VERSION.SDK_INT < 24) return bitmap;
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpeg));
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable ignored) {
            return bitmap;
        }
        int degrees;
        boolean flipHorizontal = false;
        boolean flipVertical = false;
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                degrees = 90;
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                degrees = 180;
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                degrees = 270;
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                degrees = 0;
                flipHorizontal = true;
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                degrees = 0;
                flipVertical = true;
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                degrees = 90;
                flipHorizontal = true;
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                degrees = 270;
                flipHorizontal = true;
                break;
            default:
                return bitmap;
        }
        try {
            Matrix matrix = new Matrix();
            if (flipHorizontal) matrix.postScale(-1f, 1f);
            if (flipVertical) matrix.postScale(1f, -1f);
            if (degrees != 0) matrix.postRotate(degrees);
            Bitmap transformed = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (transformed != bitmap) bitmap.recycle();
            return transformed;
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
