package com.ricohimaging.imagesync.modern;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JPEG/PNG -> HEIC (HEIF) still-image encoder built only on framework APIs.
 *
 * <p>Uses the platform HEVC encoder plus {@link MediaMuxer}'s
 * {@code MUXER_OUTPUT_HEIF} container (API 28+): one intra frame is encoded and
 * muxed as a single HEIF sample. {@link #isSupported(Context)} /
 * {@link #probe(Context)} verify encoder + muxer availability and never throw.</p>
 *
 * <h3>Encoder selection (capability driven)</h3>
 * {@code createEncoderByType("video/hevc")} may select an encoder that rejects
 * the requested size with {@code IllegalArgumentException}. Instead, all HEVC
 * encoders are enumerated with {@link MediaCodecList#ALL_CODECS}; each one's
 * {@link MediaCodecInfo.CodecCapabilities} / {@link MediaCodecInfo.VideoCapabilities}
 * are inspected and the target frame is clamped to
 * {@code supportedWidths}/{@code supportedHeights} and
 * {@code widthAlignment}/{@code heightAlignment} (rounding down, min 16). If an
 * encoder's maximum is smaller than the bitmap (e.g. 3840x2160 max for a
 * 3680x2456 target), the bitmap is scaled down to the encoder maximum instead
 * of failing; the achieved size is logged and returned by
 * {@link #lastEncodedWidth()} / {@link #lastEncodedHeight()}. Only encoders
 * whose {@code isSizeSupported()} accepts the clamped size are used. Encoders
 * are tried largest-achievable-area first (hardware preferred on ties).
 *
 * <p>Only well-supported MediaFormat keys are set: MIME, width, height,
 * bit-rate, frame-rate=1 and I-frame-interval=0; {@code KEY_BITRATE_MODE=VBR}
 * only when the encoder reports it supported. No profile/level is forced.
 * The bit-rate is ~1 bit per pixel clamped to [2.5, 18] Mbit/s and then to the
 * encoder's advertised bit-rate range (frame rate is 1 fps, so bit/s is
 * bits-per-image; a typical 24 MP JPEG of 8-12 MB becomes a ~2 MB HEIC).</p>
 *
 * <h3>Input strategy order (nested encoder x color format)</h3>
 * <ol>
 *   <li>Input Surface + Canvas (color format omitted, or COLOR_FormatSurface
 *       only when the encoder advertises it) — primary, most widely supported;</li>
 *   <li>ByteBuffer strategies limited to color formats present in
 *       {@code CodecCapabilities.colorFormats}: YUV420Flexible (0x7F420888),
 *       then YUV420SemiPlanar (21 / NV12), then YUV420Planar (19 / I420).</li>
 * </ol>
 * The first successful (encoder, strategy, color format) wins; every failed
 * attempt is recorded with encoder name, color format, supported size/alignment
 * and the exact stage exception, surfaced through {@link #lastError()} and the
 * thrown IOException.
 *
 * <h3>Decoding / resolution policy (unchanged)</h3>
 * Bounds + {@code inSampleSize} put the decoded long edge in [3000, 4096] where
 * possible (24 MP 6000 px -> 3000 px, ~23 MB instead of ~96 MB); a safety valve
 * caps the decode at ~17 MP; only decodes above 4096 are scaled once.
 *
 * <p>All methods block and must be called from a worker thread; failures are
 * thrown as exceptions with concrete reasons so callers can fall back to JPEG.</p>
 */
public final class HeicConverter {

    private static final String TAG = "HeicConverter";

    /** HEVC encoder MIME type (a still image is encoded as one I frame). */
    public static final String MIME_HEVC = "video/hevc";
    /** MIME type stored in MediaStore for the produced files. */
    public static final String MIME_HEIC = "image/heic";

    /** Hard cap for the encoded longest edge. */
    private static final int MAX_LONG_EDGE = 4096;
    /** Preferred floor for the decoded/encoded longest edge. */
    private static final int MIN_WORKING_EDGE = 3000;
    /** Safety valve: never decode more than this many pixels (ARGB memory). */
    private static final long MAX_DECODE_PIXELS = 17_000_000L;
    /** Lowest long edge the safety valve may produce when backing off. */
    private static final int VALVE_MIN_EDGE = 2400;

    /** ~2 bits per pixel for a single still frame; clamped to the useful range. */
    private static final int BITRATE_BITS_PER_PIXEL = 2;
    private static final long MIN_BITRATE = 8_000_000L;
    private static final long MAX_BITRATE = 48_000_000L;

    /**
     * A valid still HEIC must be at least this large. Anything smaller is either
     * a container holding only the codec configuration (the 18 KB bug) or a
     * truncated sample, so it must never be accepted or saved.
     */
    public static final int MIN_VALID_SIZE_BYTES = 20 * 1024;
    /** Decoded dimensions must match the encoder target within this tolerance. */
    private static final int VALIDATION_TOLERANCE_PX = 2;

    private static final int CODEC_TIMEOUT_US = 10_000;
    private static final int INPUT_BUFFER_ATTEMPTS = 200;
    private static final int DRAIN_LOOP_GUARD = 4_000;
    /** Cap the reported per-attempt failures so the message stays readable. */
    private static final int MAX_REPORTED_FAILURES = 8;

    /** Encoder attempt strategy identifiers. */
    private static final int STRATEGY_SURFACE = 1;
    private static final int STRATEGY_FLEXIBLE = 2;
    private static final int STRATEGY_PLANAR = 3;

    /** Cached positive capability probe; false results are re-probed. */
    private static volatile Boolean sSupported;
    /** Most recent concrete failure reason, or null after a success. */
    private static volatile String sLastError;
    /** Dimensions / encoder of the most recent successful encode (diagnostics). */
    private static volatile int sLastWidth;
    private static volatile int sLastHeight;
    private static volatile String sLastEncoderName;
    /** True when the last successful encode had to scale down to encoder limits. */
    private static volatile boolean sLastClamped;
    /** Number of real frame samples written into the HEIF muxer by the last attempt. */
    private static volatile int sLastSampleCount;
    /** Bytes of the last produced HEIC file (whether or not it validated). */
    private static volatile long sLastEncodedBytes;

    private HeicConverter() {}

    // ------------------------------------------------------------------
    // Capability probe + diagnostics
    // ------------------------------------------------------------------

    /**
     * True when HEIC encoding is usable. Never throws; on failure the concrete
     * reason is available from {@link #lastError()}.
     */
    public static boolean isSupported(Context context) {
        try {
            Boolean cached = sSupported;
            if (cached != null && cached.booleanValue()) return true;
            if (probe(context) == null) {
                sSupported = Boolean.TRUE;
                return true;
            }
            return false;
        } catch (Throwable error) {
            setLastError("HEIC 支持检测异常：" + describe(error));
            return false;
        }
    }

    /**
     * Runs the capability probe and returns {@code null} when HEIC encoding is
     * possible, otherwise a concrete user-readable reason (also stored in
     * {@link #lastError()}). Never throws.
     */
    public static String probe(Context context) {
        try {
            if (Build.VERSION.SDK_INT < 28) {
                return setLastError("系统版本过低（API " + Build.VERSION.SDK_INT
                        + "）：HEIC 需要 Android 9（API 28）及以上");
            }
            if (!hasHevcEncoder()) {
                return setLastError("未找到 HEVC 编码器（video/hevc）");
            }
            if (!hasEncodableSize()) {
                return setLastError("HEVC 编码器不支持 4096x2730 范围内的图像尺寸");
            }
            File probeFile = null;
            MediaMuxer muxer = null;
            try {
                probeFile = createTempFile(cacheDirectory(context), "is_heic_probe_", ".heic");
                muxer = new MediaMuxer(probeFile.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF);
                sLastError = null;
                return null;
            } catch (Throwable error) {
                return setLastError("HEIF 封装器不可用：" + describe(error));
            } finally {
                if (muxer != null) {
                    try {
                        muxer.release();
                    } catch (Throwable ignored) {
                        // Already unusable.
                    }
                }
                deleteQuietly(probeFile);
            }
        } catch (Throwable error) {
            return setLastError("HEIC 支持检测异常：" + describe(error));
        }
    }

    /**
     * One-image diagnostic probe for the settings UI: encodes the supplied
     * JPEG with all real strategies and returns {@code null} on success or the
     * concrete failure string. Never throws. Call from a worker thread.
     */
    public static String tryEncodeError(Context context, byte[] jpeg) {
        try {
            if (jpeg == null || jpeg.length == 0) {
                return setLastError("诊断失败：输入 JPEG 为空");
            }
            File jpegFile = null;
            File heicFile = null;
            try {
                File dir = cacheDirectory(context);
                jpegFile = createTempFile(dir, "is_probe_src_", ".jpg");
                heicFile = createTempFile(dir, "is_probe_dst_", ".heic");
                writeBytes(jpegFile, jpeg);
                if (encodeFileToHeic(jpegFile, heicFile)) return null;
                String message = sLastError;
                if (message == null || message.trim().isEmpty()) {
                    message = "HEIC 编码未生成数据";
                }
                return setLastError(message);
            } finally {
                deleteQuietly(jpegFile);
                deleteQuietly(heicFile);
            }
        } catch (Throwable error) {
            String message = sLastError;
            if (message == null || message.trim().isEmpty()) {
                message = "HEIC 诊断失败：" + describe(error);
            }
            return setLastError(message);
        }
    }

    /** Most recent concrete failure reason; null after a successful operation. */
    public static String lastError() {
        return sLastError;
    }

    /** Achieved width of the last successful encode (0 when unknown). */
    public static int lastEncodedWidth() {
        return sLastWidth;
    }

    /** Achieved height of the last successful encode (0 when unknown). */
    public static int lastEncodedHeight() {
        return sLastHeight;
    }

    /** Codec name of the last successful encode, or null. */
    public static String lastEncoderName() {
        return sLastEncoderName;
    }

    /**
     * True when the last successful encode had to be scaled down to the
     * selected encoder's maximum supported frame (see
     * {@link #lastEncodedWidth()} / {@link #lastEncodedHeight()} for the size
     * that was actually encoded).
     */
    public static boolean lastEncodedWasClamped() {
        return sLastClamped;
    }

    /**
     * Number of real frame samples written into the HEIF container by the most
     * recent attempt. A value of 0 means only codec configuration was muxed
     * (the "18 KB unopenable HEIC" failure mode).
     */
    public static int lastSampleCount() {
        return sLastSampleCount;
    }

    /** Bytes of the most recent HEIC file produced by any attempt. */
    public static long lastEncodedBytes() {
        return sLastEncodedBytes;
    }

    private static String setLastError(String message) {
        sLastError = message;
        Log.w(TAG, message != null ? message : "HEIC failure without reason");
        return message;
    }

    /** True when any registered encoder advertises {@code video/hevc}. */
    private static boolean hasHevcEncoder() {
        MediaCodecInfo[] codecs = codecInfos();
        if (codecs == null) return false;
        for (MediaCodecInfo info : codecs) {
            if (info == null || !info.isEncoder()) continue;
            if (supportsType(info, MIME_HEVC)) return true;
        }
        return false;
    }

    /** True when at least one HEVC encoder can handle a nominal 4096x2730 frame. */
    private static boolean hasEncodableSize() {
        MediaCodecInfo[] codecs = codecInfos();
        if (codecs == null) return false;
        for (MediaCodecInfo info : codecs) {
            if (info == null || !info.isEncoder() || !supportsType(info, MIME_HEVC)) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps =
                        info.getCapabilitiesForType(MIME_HEVC);
                if (caps == null) continue;
                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                if (video == null) continue;
                if (clampSize(video, MAX_LONG_EDGE, 2730) != null) return true;
            } catch (Throwable ignored) {
                // Try the next encoder.
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Public encode entry points
    // ------------------------------------------------------------------

    /**
     * Encodes JPEG/PNG bytes to a HEIC byte array. Temp files use the process
     * temp directory; callers inside the app should prefer
     * {@link #encodeJpegToHeic(byte[], File)} with the app cache directory.
     */
    public static byte[] encodeJpegToHeic(byte[] jpeg) throws Exception {
        return encodeJpegToHeic(jpeg, null);
    }

    /** Same as {@link #encodeJpegToHeic(byte[])}, using {@code workDir} for temps. */
    public static byte[] encodeJpegToHeic(byte[] jpeg, File workDir) throws Exception {
        if (jpeg == null || jpeg.length == 0) {
            throw new IllegalArgumentException("empty JPEG data");
        }
        File jpegFile = null;
        File heicFile = null;
        try {
            jpegFile = createTempFile(workDir, "is_heic_src_", ".jpg");
            heicFile = createTempFile(workDir, "is_heic_dst_", ".heic");
            writeBytes(jpegFile, jpeg);
            if (!encodeFileToHeic(jpegFile, heicFile)) {
                throw new IOException("HEIC encoding produced no data");
            }
            return readBytes(heicFile);
        } finally {
            deleteQuietly(jpegFile);
            deleteQuietly(heicFile);
        }
    }

    /**
     * Encodes one JPEG/PNG file into {@code heicOut} (overwritten). Returns
     * true only when a non-empty muxed file was produced; on failure throws an
     * IOException whose message lists every encoder/strategy attempt with its
     * concrete error.
     */
    public static boolean encodeFileToHeic(File jpegIn, File heicOut) throws Exception {
        if (jpegIn == null || heicOut == null) {
            throw new IllegalArgumentException("input/output file is null");
        }
        if (!jpegIn.isFile()) {
            throw new IllegalArgumentException("input is not a file: " + jpegIn);
        }
        if (Build.VERSION.SDK_INT < 28) {
            throw new UnsupportedOperationException("HEIC encoding requires API 28+");
        }
        File parent = heicOut.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create output directory: " + parent);
        }
        // The muxer appends to a file path: always start from a clean target so
        // a failed attempt cannot be mistaken for a successful one.
        if (heicOut.exists() && !heicOut.delete()) {
            throw new IOException("cannot replace " + heicOut);
        }
        sLastError = null;
        sLastWidth = 0;
        sLastHeight = 0;
        sLastEncoderName = null;
        sLastClamped = false;
        sLastSampleCount = 0;
        sLastEncodedBytes = 0;

        Bitmap working = null;
        try {
            try {
                working = decodeOriented(jpegIn);
            } catch (Throwable error) {
                String message = "图片解码失败：" + describe(error);
                setLastError(message);
                throw new IOException(message);
            }
            working = fitForEncoder(working);
            if (working == null) {
                String message = "无法准备编码位图";
                setLastError(message);
                throw new IOException(message);
            }
            List<EncoderTarget> targets = discoverEncoders(working);
            if (targets.isEmpty()) {
                String message = "未找到支持 " + working.getWidth() + "x"
                        + working.getHeight() + " 的 HEVC 编码器（已检查全部编码器能力）";
                setLastError(message);
                throw new IOException(message);
            }
            List<String> failures = new ArrayList<>();
            for (EncoderTarget target : targets) {
                Bitmap attemptBitmap = scaleToTarget(working, target);
                if (attemptBitmap == null) {
                    failures.add(target.name + "：无法缩放到 " + target.width
                            + "x" + target.height);
                    continue;
                }
                try {
                    if (attemptAllStrategies(target, attemptBitmap, heicOut, failures)) {
                        sLastWidth = target.width;
                        sLastHeight = target.height;
                        sLastEncoderName = target.name;
                        sLastClamped = target.clamped;
                        Log.i(TAG, "HEIC encoded at " + target.width + "x" + target.height
                                + " with " + target.name + " (bitrate=" + target.bitrate
                                + " bit/s, source=" + working.getWidth() + "x"
                                + working.getHeight() + ")");
                        sLastError = null;
                        return true;
                    }
                } finally {
                    if (attemptBitmap != working) recycleQuietly(attemptBitmap);
                }
            }
            String message = "HEIC 编码失败（" + working.getWidth() + "x"
                    + working.getHeight() + "，最后一次输出 " + sLastEncodedBytes
                    + " 字节 / 样本 " + sLastSampleCount + "）：" + join(limit(failures));
            setLastError(message);
            throw new IOException(message);
        } finally {
            recycleQuietly(working);
        }
    }

    // ------------------------------------------------------------------
    // Decode + transform
    // ------------------------------------------------------------------

    /**
     * Decodes {@code input} to an ARGB_8888 bitmap with EXIF orientation baked
     * in (our muxed HEIF stores upright pixels). Returns a bitmap owned by the
     * caller.
     */
    private static Bitmap decodeOriented(File input) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(input.getAbsolutePath(), bounds);
        int width = bounds.outWidth;
        int height = bounds.outHeight;
        if (width <= 0 || height <= 0) {
            throw new IOException("cannot read image bounds: " + input);
        }
        int sample = chooseSampleSize(width, height);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(input.getAbsolutePath(), options);
        if (bitmap == null) throw new IOException("cannot decode image: " + input);
        bitmap = toArgb8888(bitmap);
        if (bitmap == null) throw new IOException("cannot convert image to ARGB_8888");
        return applyOrientation(bitmap, readOrientation(input));
    }

    /**
     * Power-of-two decode sample so the decoded long edge lands in
     * [3000, 4096] when possible (24 MP 6000 px -> 3000 px), with a memory
     * safety valve that accepts ~2400-3000 px rather than decoding > 17 MP.
     */
    private static int chooseSampleSize(int width, int height) {
        int longEdge = Math.max(width, height);
        int sample = 1;
        while (longEdge / sample > MAX_LONG_EDGE) sample *= 2;
        if (sample > 1 && longEdge / sample < MIN_WORKING_EDGE) {
            // Backing off one step keeps the 3000 px floor; the decoded frame
            // is then scaled down once to 4096.
            sample /= 2;
        }
        while ((long) (width / sample) * (long) (height / sample) > MAX_DECODE_PIXELS
                && Math.max(width / (sample * 2), height / (sample * 2)) >= VALVE_MIN_EDGE) {
            sample *= 2;
        }
        return Math.max(1, sample);
    }

    /** Best-effort EXIF orientation; EXIF-less PNGs simply report NORMAL. */
    private static int readOrientation(File input) {
        try {
            ExifInterface exif = new ExifInterface(input.getAbsolutePath());
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable error) {
            Log.d(TAG, "no readable EXIF orientation", error);
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap toArgb8888(Bitmap source) {
        if (source == null) return null;
        if (source.getConfig() == Bitmap.Config.ARGB_8888) return source;
        Bitmap converted = null;
        try {
            converted = source.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable error) {
            Log.w(TAG, "cannot convert bitmap to ARGB_8888", error);
        }
        if (converted != null && converted != source) source.recycle();
        return converted;
    }

    /** Bakes EXIF orientation into the pixels; returns the (possibly new) bitmap. */
    private static Bitmap applyOrientation(Bitmap source, int orientation) {
        if (source == null) return null;
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setRotate(180f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90f);
                break;
            default:
                return source;
        }
        Bitmap rotated = null;
        try {
            rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(),
                    source.getHeight(), matrix, true);
        } catch (Throwable error) {
            Log.w(TAG, "EXIF orientation transform failed", error);
        }
        if (rotated == null || rotated == source) return source;
        source.recycle();
        return rotated;
    }

    /**
     * Scales down to {@link #MAX_LONG_EDGE} when needed and rounds to even
     * dimensions (HEVC 4:2:0 encoders reject odd sizes on many devices).
     * Returns the bitmap to encode; a replaced source is recycled.
     */
    private static Bitmap fitForEncoder(Bitmap source) {
        if (source == null) return null;
        int width = source.getWidth();
        int height = source.getHeight();
        int maxEdge = Math.max(width, height);
        int targetWidth = width;
        int targetHeight = height;
        if (maxEdge > MAX_LONG_EDGE) {
            // Only ever scale *down* to the 4096 cap; small images are kept as
            //-is so we never invent pixels.
            double ratio = (double) MAX_LONG_EDGE / (double) maxEdge;
            targetWidth = (int) Math.round(width * ratio);
            targetHeight = (int) Math.round(height * ratio);
        }
        targetWidth -= targetWidth & 1;
        targetHeight -= targetHeight & 1;
        if (targetWidth < 2) targetWidth = 2;
        if (targetHeight < 2) targetHeight = 2;
        if (targetWidth == width && targetHeight == height) return source;
        Bitmap scaled = null;
        try {
            scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
        } catch (Throwable error) {
            Log.w(TAG, "cannot scale bitmap for encoder", error);
        }
        if (scaled == null || scaled == source) return source;
        source.recycle();
        return scaled;
    }

    /** Scales {@code source} to the encoder target; null when scaling fails. */
    private static Bitmap scaleToTarget(Bitmap source, EncoderTarget target) {
        if (source == null || target == null) return null;
        if (source.getWidth() == target.width && source.getHeight() == target.height) {
            return source;
        }
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(source, target.width, target.height, true);
            return scaled != null ? scaled : null;
        } catch (Throwable error) {
            Log.w(TAG, "cannot scale bitmap to " + target.width + "x" + target.height, error);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Encoder discovery (capability driven)
    // ------------------------------------------------------------------

    private static MediaCodecInfo[] codecInfos() {
        try {
            MediaCodecInfo[] all = new MediaCodecList(MediaCodecList.ALL_CODECS)
                    .getCodecInfos();
            if (all != null && all.length > 0) return all;
        } catch (Throwable error) {
            Log.w(TAG, "cannot enumerate ALL_CODECS", error);
        }
        try {
            return new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        } catch (Throwable error) {
            Log.w(TAG, "cannot enumerate REGULAR_CODECS", error);
            return null;
        }
    }

    private static boolean supportsType(MediaCodecInfo info, String mime) {
        if (info == null) return false;
        try {
            String[] types = info.getSupportedTypes();
            if (types == null) return false;
            for (String type : types) {
                if (mime.equalsIgnoreCase(type)) return true;
            }
        } catch (Throwable ignored) {
            // No usable type list.
        }
        return false;
    }

    private static boolean includeCodec(MediaCodecInfo info) {
        if (info == null || info.getName() == null) return false;
        try {
            // Aliases duplicate the same component: skip to avoid wasted attempts.
            if (Build.VERSION.SDK_INT >= 29 && info.isAlias()) return false;
        } catch (Throwable ignored) {
            // isAlias() unsupported: keep the codec.
        }
        return true;
    }

    /**
     * Enumerates usable HEVC encoders and computes the maximum frame each one
     * accepts for the current bitmap. Sorted by achievable area (largest first,
     * hardware preferred on ties) so the best quality that still works wins.
     */
    private static List<EncoderTarget> discoverEncoders(Bitmap bitmap) {
        List<EncoderTarget> targets = new ArrayList<>();
        MediaCodecInfo[] codecs = codecInfos();
        if (codecs == null || bitmap == null) return targets;
        int sourceWidth = bitmap.getWidth();
        int sourceHeight = bitmap.getHeight();
        for (MediaCodecInfo info : codecs) {
            if (info == null || !info.isEncoder() || !includeCodec(info)) continue;
            if (!supportsType(info, MIME_HEVC)) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MIME_HEVC);
                if (caps == null) {
                    Log.d(TAG, info.getName() + ": no CodecCapabilities, skipped");
                    continue;
                }
                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                if (video == null) {
                    Log.d(TAG, info.getName() + ": no VideoCapabilities, skipped");
                    continue;
                }
                int[] size = clampSize(video, sourceWidth, sourceHeight);
                if (size == null) {
                    Log.d(TAG, info.getName() + ": no supported size near "
                            + sourceWidth + "x" + sourceHeight + ", skipped");
                    continue;
                }
                int[] colorFormats = supportedColorFormats(caps);
                boolean surfaceAdvertised = containsColorFormat(caps,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                boolean vbr = false;
                try {
                    MediaCodecInfo.EncoderCapabilities encCaps = caps.getEncoderCapabilities();
                    vbr = encCaps != null && encCaps.isBitrateModeSupported(
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                } catch (Throwable ignored) {
                    // Treat as unsupported.
                }
                long bitrate = chooseBitrate(video, size[0], size[1]);
                int preferredProfile = pickPreferredProfile(caps);
                EncoderTarget target = new EncoderTarget(info.getName(), size[0], size[1],
                        colorFormats, surfaceAdvertised, vbr, bitrate,
                        isHardware(info), describeCapabilities(video), preferredProfile);
                // Record whether the bitmap had to be scaled down to fit this
                // encoder (reported in logs / failure messages, never fatal).
                target.clamped = target.width != sourceWidth
                        || target.height != sourceHeight;
                if (target.clamped) {
                    Log.i(TAG, "encoder " + target.name + " max " + target.width + "x"
                            + target.height + "; scaling " + sourceWidth + "x"
                            + sourceHeight + " down to " + target.width + "x"
                            + target.height);
                }
                targets.add(target);
            } catch (Throwable error) {
                Log.w(TAG, "cannot inspect HEVC encoder " + info.getName(), error);
            }
        }
        Collections.sort(targets, (left, right) -> {
            long leftArea = (long) left.width * (long) left.height;
            long rightArea = (long) right.width * (long) right.height;
            if (leftArea != rightArea) return leftArea > rightArea ? -1 : 1;
            if (left.hardware != right.hardware) return left.hardware ? -1 : 1;
            return left.name.compareTo(right.name);
        });
        return targets;
    }

    /**
     * Prefers the HEVC Main Still Picture profile when the encoder advertises
     * it (still images do not need inter-frame tools), then Main, then no
     * profile at all. Setting a profile is also retried without it if the
     * encoder rejects the configure step.
     */
    private static int pickPreferredProfile(MediaCodecInfo.CodecCapabilities caps) {
        if (caps == null) return 0;
        try {
            MediaCodecInfo.CodecProfileLevel[] levels = caps.profileLevels;
            if (levels == null) return 0;
            int main = 0;
            for (MediaCodecInfo.CodecProfileLevel level : levels) {
                if (level == null) continue;
                if (level.profile
                        == MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill) {
                    return level.profile;
                }
                if (level.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain) {
                    main = level.profile;
                }
            }
            return main;
        } catch (Throwable error) {
            Log.d(TAG, "cannot read profileLevels", error);
            return 0;
        }
    }

    /**
     * Clamps the desired frame into the encoder's supported width/height ranges
     * and alignment (rounding down), then shrinks until
     * {@code VideoCapabilities.isSizeSupported()} accepts it. Returns null when
     * the encoder cannot represent the image at all.
     */
    private static int[] clampSize(MediaCodecInfo.VideoCapabilities video,
                                   int width, int height) {
        if (video == null || width <= 0 || height <= 0) return null;
        try {
            Range<Integer> widthRange = video.getSupportedWidths();
            Range<Integer> heightRange = video.getSupportedHeights();
            if (widthRange == null || heightRange == null) return null;
            int alignWidth = Math.max(1, video.getWidthAlignment());
            int alignHeight = Math.max(1, video.getHeightAlignment());
            int maxWidth = widthRange.getUpper();
            int maxHeight = heightRange.getUpper();
            int minWidth = Math.max(16, widthRange.getLower());
            int minHeight = Math.max(16, heightRange.getLower());
            if (maxWidth < 16 || maxHeight < 16) return null;

            // Preserve aspect ratio while fitting inside the maximum.
            double scale = Math.min(1.0, Math.min((double) maxWidth / (double) width,
                    (double) maxHeight / (double) height));
            int targetWidth = alignDown((int) Math.floor(width * scale), alignWidth);
            int targetHeight = alignDown((int) Math.floor(height * scale), alignHeight);

            // Encoders with a minimum size: scale up to the smallest accepted frame.
            if (targetWidth < minWidth || targetHeight < minHeight) {
                double upscale = Math.max((double) minWidth / Math.max(1, targetWidth),
                        (double) minHeight / Math.max(1, targetHeight));
                targetWidth = alignUp((int) Math.ceil(targetWidth * upscale), alignWidth);
                targetHeight = alignUp((int) Math.ceil(targetHeight * upscale), alignHeight);
            }

            for (int attempt = 0; attempt < 32; attempt++) {
                int candidateWidth = Math.min(maxWidth, Math.max(minWidth, targetWidth));
                int candidateHeight = Math.min(maxHeight, Math.max(minHeight, targetHeight));
                candidateWidth = alignDown(candidateWidth, alignWidth);
                candidateHeight = alignDown(candidateHeight, alignHeight);
                if (candidateWidth < minWidth || candidateHeight < minHeight) return null;
                if (video.isSizeSupported(candidateWidth, candidateHeight)) {
                    return new int[]{candidateWidth, candidateHeight};
                }
                // Some encoders additionally bound the total area: shrink a bit.
                targetWidth = (int) Math.floor(candidateWidth * 0.9);
                targetHeight = (int) Math.floor(candidateHeight * 0.9);
            }
        } catch (Throwable error) {
            Log.d(TAG, "cannot clamp size to encoder capabilities", error);
        }
        return null;
    }

    /** Color formats this converter can feed, ordered by preference. */
    private static int[] supportedColorFormats(MediaCodecInfo.CodecCapabilities caps) {
        int[] candidates = {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar};
        List<Integer> present = new ArrayList<>();
        for (int candidate : candidates) {
            if (containsColorFormat(caps, candidate)) present.add(candidate);
        }
        int[] result = new int[present.size()];
        for (int i = 0; i < present.size(); i++) result[i] = present.get(i);
        return result;
    }

    private static boolean containsColorFormat(MediaCodecInfo.CodecCapabilities caps,
                                               int colorFormat) {
        if (caps == null) return false;
        try {
            // CodecCapabilities exposes the supported input color formats as a
            // public field (there is no getColorFormats() method).
            int[] formats = caps.colorFormats;
            if (formats == null) return false;
            for (int format : formats) {
                if (format == colorFormat) return true;
            }
        } catch (Throwable ignored) {
            // Treat as absent.
        }
        return false;
    }

    private static long chooseBitrate(MediaCodecInfo.VideoCapabilities video,
                                      int width, int height) {
        // ~2 bits per pixel for one still frame (fps is 1), clamped to the
        // useful quality range and then to what the encoder advertises.
        long pixels = (long) width * (long) height;
        long requested = Math.max(MIN_BITRATE, Math.min(MAX_BITRATE,
                pixels * BITRATE_BITS_PER_PIXEL));
        try {
            Range<Integer> range = video.getBitrateRange();
            if (range != null) {
                long lower = range.getLower();
                long upper = range.getUpper();
                if (lower > 0) requested = Math.max(lower, requested);
                if (upper > 0) requested = Math.min(upper, requested);
            }
        } catch (Throwable ignored) {
            // Keep the requested value.
        }
        return requested;
    }

    private static boolean isHardware(MediaCodecInfo info) {
        try {
            if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated();
        } catch (Throwable ignored) {
            // Fall through to the name heuristic.
        }
        String name = info.getName();
        if (name == null) return false;
        return (name.startsWith("OMX.") || name.startsWith("c2."))
                && !name.contains(".sw.") && !name.contains(".software.");
    }

    private static String describeCapabilities(MediaCodecInfo.VideoCapabilities video) {
        try {
            return "支持尺寸 " + video.getSupportedWidths() + " x "
                    + video.getSupportedHeights() + "，对齐 "
                    + video.getWidthAlignment() + "x" + video.getHeightAlignment();
        } catch (Throwable error) {
            return "能力信息不可用";
        }
    }

    private static int alignDown(int value, int alignment) {
        if (alignment <= 1) return value;
        return value - (value % alignment);
    }

    private static int alignUp(int value, int alignment) {
        if (alignment <= 1) return value;
        int remainder = value % alignment;
        return remainder == 0 ? value : value + (alignment - remainder);
    }

    // ------------------------------------------------------------------
    // Encoder attempts: surface first, then capability-advertised formats
    // ------------------------------------------------------------------

    /**
     * Tries every strategy for one encoder: Surface first, then each ByteBuffer
     * color format advertised by the encoder. A preferred HEVC profile is tried
     * first and retried without it when the encoder rejects the configure step.
     * Any produced file must pass {@link #validateOutput} before it is accepted.
     */
    private static boolean attemptAllStrategies(EncoderTarget target, Bitmap bitmap,
                                                File outFile, List<String> failures) {
        String surfaceLabel = colorFormatLabel(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        if (runAttempt(target, bitmap, outFile, STRATEGY_SURFACE, 0,
                surfaceLabel, failures)) {
            return true;
        }
        for (int colorFormat : target.colorFormats) {
            String label = colorFormatLabel(colorFormat);
            int strategy = colorFormat == MediaCodecInfo.CodecCapabilities
                    .COLOR_FormatYUV420Flexible ? STRATEGY_FLEXIBLE : STRATEGY_PLANAR;
            if (runAttempt(target, bitmap, outFile, strategy, colorFormat,
                    label, failures)) {
                return true;
            }
        }
        return false;
    }

    /** Attempts one strategy with the preferred profile, then without it. */
    private static boolean runAttempt(EncoderTarget target, Bitmap bitmap, File outFile,
                                      int strategy, int colorFormat, String label,
                                      List<String> failures) {
        if (target.preferredProfile != 0) {
            if (tryAttempt(target, bitmap, outFile, strategy, colorFormat, true,
                    label + (target.preferredProfile
                            == MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill
                            ? "（MainStill）" : "（Main）"), failures)) {
                return true;
            }
            Log.w(TAG, "retrying " + target.name + " without HEVC profile");
            return tryAttempt(target, bitmap, outFile, strategy, colorFormat, false,
                    label + "（无 profile）", failures);
        }
        return tryAttempt(target, bitmap, outFile, strategy, colorFormat, false,
                label, failures);
    }

    private static boolean tryAttempt(EncoderTarget target, Bitmap bitmap, File outFile,
                                      int strategy, int colorFormat, boolean useProfile,
                                      String label, List<String> failures) {
        try {
            boolean produced = attemptOnce(target, bitmap, outFile, strategy,
                    colorFormat, useProfile);
            if (!produced) {
                failures.add(attemptLabel(target, label) + "未生成数据（样本数="
                        + sLastSampleCount + "）");
                deleteQuietly(outFile);
                return false;
            }
            String validationError = validateOutput(outFile, target);
            if (validationError == null) {
                sLastEncodedBytes = fileLength(outFile);
                return true;
            }
            failures.add(attemptLabel(target, label) + validationError);
            deleteQuietly(outFile);
            return false;
        } catch (Throwable error) {
            failures.add(attemptLabel(target, label) + describe(error)
                    + "（样本数=" + sLastSampleCount + "，输出="
                    + fileLength(outFile) + " 字节）");
            deleteQuietly(outFile);
            return false;
        }
    }

    private static boolean attemptOnce(EncoderTarget target, Bitmap bitmap, File outFile,
                                       int strategy, int colorFormat, boolean useProfile)
            throws Exception {
        switch (strategy) {
            case STRATEGY_SURFACE:
                return attemptSurface(target, bitmap, outFile, useProfile);
            case STRATEGY_FLEXIBLE:
                return attemptFlexible(target, bitmap, outFile, useProfile);
            case STRATEGY_PLANAR:
            default:
                return attemptPlanarFamily(target, bitmap, outFile, colorFormat, useProfile);
        }
    }

    private static String attemptLabel(EncoderTarget target, String colorLabel) {
        return target.name + " / " + colorLabel + " / " + target.width + "x"
                + target.height + (target.clamped ? "（已按编码器上限缩小）" : "")
                + " / " + target.capabilities + " -> ";
    }

    /**
     * Validates a produced HEIC before it may be accepted/saved: the file must
     * be at least {@link #MIN_VALID_SIZE_BYTES} (a smaller file means only codec
     * configuration was muxed) and the framework must decode its dimensions
     * back within {@link #VALIDATION_TOLERANCE_PX} of the encoder target.
     * Returns null when valid, otherwise the concrete failure reason.
     */
    private static String validateOutput(File file, EncoderTarget target) {
        long length = fileLength(file);
        sLastEncodedBytes = length;
        if (length < MIN_VALID_SIZE_BYTES) {
            return "输出过小（" + length + " 字节 < " + MIN_VALID_SIZE_BYTES
                    + "，样本数=" + sLastSampleCount
                    + "，疑似只写入了编码器配置数据）";
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try {
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        } catch (Throwable error) {
            Log.w(TAG, "BitmapFactory cannot read the produced HEIC", error);
        }
        int decodedWidth = bounds.outWidth;
        int decodedHeight = bounds.outHeight;
        if (decodedWidth <= 0 || decodedHeight <= 0) {
            // BitmapFactory may lack HEIF support on some builds: fall back to
            // the API 28+ ImageDecoder header probe.
            int[] imageDecoderSize = decodeBoundsWithImageDecoder(file);
            if (imageDecoderSize != null) {
                decodedWidth = imageDecoderSize[0];
                decodedHeight = imageDecoderSize[1];
            }
        }
        if (decodedWidth <= 0 || decodedHeight <= 0) {
            return "输出 HEIC 无法解码（decodeFile 返回 " + decodedWidth + "x"
                    + decodedHeight + "，样本数=" + sLastSampleCount + "）";
        }
        if (Math.abs(decodedWidth - target.width) > VALIDATION_TOLERANCE_PX
                || Math.abs(decodedHeight - target.height) > VALIDATION_TOLERANCE_PX) {
            return "输出尺寸异常（解码 " + decodedWidth + "x" + decodedHeight
                    + "，期望 " + target.width + "x" + target.height
                    + "，样本数=" + sLastSampleCount + "）";
        }
        return null;
    }

    /**
     * Header-only HEIF probe with {@link android.graphics.ImageDecoder}
     * (API 28+): aborts the decode from the header callback so no full bitmap
     * is allocated. Returns null when unavailable/undecodable.
     */
    private static int[] decodeBoundsWithImageDecoder(File file) {
        if (Build.VERSION.SDK_INT < 28) return null;
        final int[] size = new int[2];
        try {
            android.graphics.ImageDecoder.Source source =
                    android.graphics.ImageDecoder.createSource(file);
            try {
                android.graphics.ImageDecoder.decodeDrawable(source,
                        (decoder, info, src) -> {
                            size[0] = info.getSize().getWidth();
                            size[1] = info.getSize().getHeight();
                            // Header only: abort the expensive full decode.
                            throw new IllegalStateException("bounds-only");
                        });
            } catch (Throwable ignored) {
                // Expected: the callback aborted the decode.
            }
            if (size[0] > 0 && size[1] > 0) return size;
        } catch (Throwable error) {
            Log.d(TAG, "ImageDecoder cannot read the produced HEIC", error);
        }
        return null;
    }

    /** MediaFormat with only well-supported keys; profile only if advertised. */
    private static MediaFormat buildMediaFormat(EncoderTarget target, int colorFormat,
                                                boolean surface, boolean useProfile) {
        MediaFormat format = MediaFormat.createVideoFormat(MIME_HEVC,
                target.width, target.height);
        if (surface) {
            // For input surfaces most encoders infer the format; force
            // COLOR_FormatSurface only when it is actually advertised.
            if (target.surfaceAdvertised) {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            }
        } else {
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        }
        format.setLong(MediaFormat.KEY_BIT_RATE, target.bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 1);
        // Single still frame: every frame is an I frame.
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0);
        if (useProfile && target.preferredProfile != 0) {
            try {
                format.setInteger(MediaFormat.KEY_PROFILE, target.preferredProfile);
            } catch (Throwable ignored) {
                // Optional key.
            }
        }
        if (target.vbrSupported) {
            try {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            } catch (Throwable ignored) {
                // Optional key.
            }
        }
        return format;
    }

    private static MediaCodec createCodec(EncoderTarget target) throws IOException {
        try {
            return MediaCodec.createByCodecName(target.name);
        } catch (Throwable error) {
            throw new IOException("创建编码器失败：" + describe(error), error);
        }
    }

    private static MediaMuxer createMuxer(File outFile) throws IOException {
        try {
            return new MediaMuxer(outFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF);
        } catch (Throwable error) {
            throw new IOException("创建 HEIF 封装器失败：" + describe(error), error);
        }
    }

    private static void configure(MediaCodec codec, MediaFormat format) throws IOException {
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Throwable error) {
            throw new IOException("configure 失败：" + describe(error)
                    + "，format=" + format, error);
        }
    }

    private static boolean attemptSurface(EncoderTarget target, Bitmap bitmap, File outFile,
                                          boolean useProfile)
            throws Exception {
        MediaFormat format = buildMediaFormat(target, 0, true, useProfile);
        MediaMuxer muxer = null;
        MediaCodec codec = null;
        Surface surface = null;
        try {
            muxer = createMuxer(outFile);
            codec = createCodec(target);
            configure(codec, format);
            try {
                surface = codec.createInputSurface();
            } catch (Throwable error) {
                throw new IOException("createInputSurface 失败：" + describe(error), error);
            }
            try {
                codec.start();
            } catch (Throwable error) {
                throw new IOException("start 失败：" + describe(error), error);
            }
            Canvas canvas;
            try {
                canvas = surface.lockCanvas(null);
            } catch (Throwable error) {
                throw new IOException("lockCanvas 失败：" + describe(error), error);
            }
            if (canvas == null) throw new IOException("lockCanvas 返回 null");
            try {
                canvas.drawColor(Color.BLACK);
                canvas.drawBitmap(bitmap, null,
                        new Rect(0, 0, target.width, target.height), null);
            } finally {
                try {
                    surface.unlockCanvasAndPost(canvas);
                } catch (Throwable error) {
                    Log.w(TAG, "cannot submit encoder surface frame", error);
                }
            }
            try {
                codec.signalEndOfInputStream();
            } catch (Throwable error) {
                throw new IOException("signalEndOfInputStream 失败：" + describe(error), error);
            }
            return drainAndMux(codec, muxer) > 0 && fileHasData(outFile);
        } finally {
            if (surface != null) {
                try {
                    surface.release();
                } catch (Throwable ignored) {
                    // Already released.
                }
            }
            releaseQuietly(muxer);
            stopReleaseQuietly(codec);
        }
    }

    private static boolean attemptFlexible(EncoderTarget target, Bitmap bitmap, File outFile,
                                           boolean useProfile)
            throws Exception {
        MediaFormat format = buildMediaFormat(target,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible, false, useProfile);
        MediaMuxer muxer = null;
        MediaCodec codec = null;
        try {
            muxer = createMuxer(outFile);
            codec = createCodec(target);
            configure(codec, format);
            try {
                codec.start();
            } catch (Throwable error) {
                throw new IOException("start 失败：" + describe(error), error);
            }
            int inputIndex = awaitInputBuffer(codec);
            if (inputIndex < 0) throw new IOException("没有可用的 HEVC 输入缓冲区");
            int inputCapacity = 0;
            try {
                ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                if (inputBuffer != null) inputCapacity = inputBuffer.capacity();
            } catch (Throwable ignored) {
                // Capacity is an extra safety clamp only.
            }
            Image image = null;
            int frameBytes;
            try {
                try {
                    image = codec.getInputImage(inputIndex);
                } catch (Throwable error) {
                    throw new IOException("getInputImage 失败：" + describe(error), error);
                }
                if (image == null) {
                    throw new IOException("编码器未提供 flexible YUV 输入 Image");
                }
                frameBytes = fillFlexibleYuvImage(image, bitmap);
            } finally {
                if (image != null) {
                    try {
                        image.close();
                    } catch (Throwable ignored) {
                        // Already invalid.
                    }
                }
            }
            if (frameBytes <= 0) throw new IOException("无法填充 YUV 帧");
            if (inputCapacity > 0) frameBytes = Math.min(frameBytes, inputCapacity);
            queueFrame(codec, inputIndex, frameBytes);
            queueEos(codec);
            return drainAndMux(codec, muxer) > 0 && fileHasData(outFile);
        } finally {
            releaseQuietly(muxer);
            stopReleaseQuietly(codec);
        }
    }

    private static boolean attemptPlanarFamily(EncoderTarget target, Bitmap bitmap,
                                               File outFile, int colorFormat,
                                               boolean useProfile)
            throws Exception {
        MediaFormat format = buildMediaFormat(target, colorFormat, false, useProfile);
        MediaMuxer muxer = null;
        MediaCodec codec = null;
        try {
            muxer = createMuxer(outFile);
            codec = createCodec(target);
            configure(codec, format);
            try {
                codec.start();
            } catch (Throwable error) {
                throw new IOException("start 失败：" + describe(error), error);
            }
            int inputIndex = awaitInputBuffer(codec);
            if (inputIndex < 0) throw new IOException("没有可用的 HEVC 输入缓冲区");
            ByteBuffer input = codec.getInputBuffer(inputIndex);
            if (input == null) throw new IOException("输入缓冲区不可用");
            int frameBytes = target.width * target.height * 3 / 2;
            if (input.capacity() < frameBytes) {
                throw new IOException("输入缓冲区过小：capacity=" + input.capacity()
                        + " 需要=" + frameBytes);
            }
            byte[] frame = new byte[frameBytes];
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                fillSemiPlanarYuv420(bitmap, frame, target.width, target.height);
            } else {
                fillPlanarYuv420(bitmap, frame, target.width, target.height);
            }
            try {
                input.position(0);
                input.put(frame, 0, frameBytes);
                input.position(0);
            } catch (Throwable error) {
                throw new IOException("填充输入缓冲区失败：" + describe(error), error);
            }
            queueFrame(codec, inputIndex, frameBytes);
            queueEos(codec);
            return drainAndMux(codec, muxer) > 0 && fileHasData(outFile);
        } finally {
            releaseQuietly(muxer);
            stopReleaseQuietly(codec);
        }
    }

    private static void queueFrame(MediaCodec codec, int inputIndex, int frameBytes)
            throws IOException {
        try {
            codec.queueInputBuffer(inputIndex, 0, frameBytes, 0L,
                    MediaCodec.BUFFER_FLAG_KEY_FRAME);
        } catch (Throwable error) {
            throw new IOException("queueInputBuffer 失败：" + describe(error), error);
        }
    }

    private static void queueEos(MediaCodec codec) {
        int eosIndex = awaitInputBuffer(codec);
        if (eosIndex < 0) return;
        try {
            codec.queueInputBuffer(eosIndex, 0, 0, 0L,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        } catch (Throwable error) {
            Log.w(TAG, "cannot queue EOS", error);
        }
    }

    private static int awaitInputBuffer(MediaCodec codec) {
        for (int attempt = 0; attempt < INPUT_BUFFER_ATTEMPTS; attempt++) {
            int index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
            if (index >= 0) return index;
        }
        return -1;
    }

    private static boolean fileHasData(File file) {
        try {
            return file != null && file.isFile() && file.length() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long fileLength(File file) {
        try {
            return file != null && file.isFile() ? file.length() : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String colorFormatLabel(int colorFormat) {
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
            return "Surface(0x7F000789)";
        }
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
            return "YUV420Flexible(0x7F420888)";
        }
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
            return "YUV420SemiPlanar(21/NV12)";
        }
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
            return "YUV420Planar(19/I420)";
        }
        return "color=" + colorFormat;
    }

    // ------------------------------------------------------------------
    // YUV conversion
    // ------------------------------------------------------------------

    /**
     * Fills the encoder's flexible YUV420 image from the ARGB bitmap and
     * returns the input buffer size to queue. Vendor layouts are honoured via
     * the plane strides of the {@link Image} itself.
     */
    private static int fillFlexibleYuvImage(Image image, Bitmap bitmap) throws IOException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3
                || planes[0] == null || planes[1] == null || planes[2] == null) {
            throw new IOException("编码器未暴露 YUV420 平面");
        }
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        if (yBuffer == null || uBuffer == null || vBuffer == null) {
            throw new IOException("编码器 YUV 平面不可用");
        }
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();
        if (yRowStride <= 0 || yPixelStride <= 0) {
            throw new IOException("Y 平面步长无效");
        }
        int chromaWidth = (width + 1) / 2;
        int chromaHeight = (height + 1) / 2;
        byte[] yData = new byte[yRowStride * height];
        byte[] uData = new byte[Math.max(1, uRowStride) * chromaHeight];
        byte[] vData = new byte[Math.max(1, vRowStride) * chromaHeight];
        convertBitmapToYuv420(bitmap,
                yData, 0, yRowStride, yPixelStride,
                uData, 0, uRowStride, uPixelStride,
                vData, 0, vRowStride, vPixelStride);
        copyPlane(yBuffer, yData);
        copyPlane(uBuffer, uData);
        copyPlane(vBuffer, vData);
        return Math.min(yData.length, Math.max(1, yBuffer.capacity()));
    }

    /** Fills a contiguous I420 buffer (Y plane, then U, then V). */
    private static void fillPlanarYuv420(Bitmap bitmap, byte[] frame, int width, int height)
            throws IOException {
        if ((width & 1) != 0 || (height & 1) != 0) {
            throw new IOException("planar YUV420 需要偶数尺寸：" + width + "x" + height);
        }
        int chromaWidth = width / 2;
        int chromaHeight = height / 2;
        int ySize = width * height;
        int uSize = chromaWidth * chromaHeight;
        if (frame.length < ySize + uSize * 2) {
            throw new IOException("planar 缓冲区过小：" + frame.length);
        }
        convertBitmapToYuv420(bitmap,
                frame, 0, width, 1,
                frame, ySize, chromaWidth, 1,
                frame, ySize + uSize, chromaWidth, 1);
    }

    /** Fills a contiguous NV12 buffer (Y plane, then interleaved U/V pairs). */
    private static void fillSemiPlanarYuv420(Bitmap bitmap, byte[] frame,
                                             int width, int height) throws IOException {
        if ((width & 1) != 0 || (height & 1) != 0) {
            throw new IOException("semi-planar YUV420 需要偶数尺寸：" + width + "x" + height);
        }
        int ySize = width * height;
        int uvSize = width * (height / 2);
        if (frame.length < ySize + uvSize) {
            throw new IOException("semi-planar 缓冲区过小：" + frame.length);
        }
        convertBitmapToYuv420(bitmap,
                frame, 0, width, 1,
                frame, ySize, width, 2,
                frame, ySize + 1, width, 2);
    }

    /**
     * Shared ARGB -> YUV420 conversion. Plane offsets/strides are explicit so
     * the same code serves flexible (separate plane buffers), planar (I420) and
     * semi-planar (NV12) layouts. BT.601 limited-range coefficients; chroma is
     * averaged over each 2x2 block.
     */
    private static void convertBitmapToYuv420(Bitmap bitmap,
                                              byte[] yPlane, int yBase, int yRowStride,
                                              int yPixelStride,
                                              byte[] uPlane, int uBase, int uRowStride,
                                              int uPixelStride,
                                              byte[] vPlane, int vBase, int vRowStride,
                                              int vPixelStride) throws IOException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int chromaWidth = (width + 1) / 2;
        int[] rowTop = new int[width];
        int[] rowBottom = new int[width];
        for (int y = 0; y < height; y += 2) {
            boolean hasBottom = (y + 1) < height;
            try {
                bitmap.getPixels(rowTop, 0, width, 0, y, width, 1);
                if (hasBottom) {
                    bitmap.getPixels(rowBottom, 0, width, 0, y + 1, width, 1);
                }
            } catch (Throwable error) {
                throw new IOException("读取位图像素失败", error);
            }
            int topOffset = yBase + y * yRowStride;
            for (int x = 0; x < width; x++) {
                putByte(yPlane, topOffset + x * yPixelStride, luma(rowTop[x]));
            }
            if (hasBottom) {
                int bottomOffset = yBase + (y + 1) * yRowStride;
                for (int x = 0; x < width; x++) {
                    putByte(yPlane, bottomOffset + x * yPixelStride, luma(rowBottom[x]));
                }
            }
            int chromaRow = y / 2;
            int uRowOffset = uBase + chromaRow * uRowStride;
            int vRowOffset = vBase + chromaRow * vRowStride;
            for (int cx = 0; cx < chromaWidth; cx++) {
                int x0 = cx * 2;
                int x1 = Math.min(x0 + 1, width - 1);
                int c0 = rowTop[x0];
                int c1 = rowTop[x1];
                int c2 = hasBottom ? rowBottom[x0] : c0;
                int c3 = hasBottom ? rowBottom[x1] : c1;
                int r = (((c0 >> 16) & 0xFF) + ((c1 >> 16) & 0xFF)
                        + ((c2 >> 16) & 0xFF) + ((c3 >> 16) & 0xFF) + 2) >> 2;
                int g = (((c0 >> 8) & 0xFF) + ((c1 >> 8) & 0xFF)
                        + ((c2 >> 8) & 0xFF) + ((c3 >> 8) & 0xFF) + 2) >> 2;
                int b = ((c0 & 0xFF) + (c1 & 0xFF) + (c2 & 0xFF) + (c3 & 0xFF) + 2) >> 2;
                putByte(uPlane, uRowOffset + cx * uPixelStride,
                        clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128));
                putByte(vPlane, vRowOffset + cx * vPixelStride,
                        clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128));
            }
        }
    }

    private static byte luma(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        // BT.601 limited range: Y = 16 + (66R + 129G + 25B) / 256
        return (byte) clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
    }

    private static int clamp(int value) {
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }

    private static void putByte(byte[] plane, int index, int value) {
        if (plane != null && index >= 0 && index < plane.length) {
            plane[index] = (byte) value;
        }
    }

    private static void copyPlane(ByteBuffer buffer, byte[] data) throws IOException {
        if (buffer == null || data == null || data.length == 0) return;
        try {
            int count = Math.min(data.length, buffer.capacity());
            buffer.position(0);
            buffer.put(data, 0, count);
        } catch (Throwable error) {
            throw new IOException("填充 YUV 平面失败", error);
        }
    }

    // ------------------------------------------------------------------
    // Muxing
    // ------------------------------------------------------------------

    /**
     * Drains the encoder into the HEIF muxer and returns the number of real
     * frame samples written.
     *
     * <p>The track must be added from the encoder's *output* format (it carries
     * the hvcC codec-specific data), the muxer must be started before the first
     * sample, codec-config buffers are not samples, and a still HEIF sample must
     * have PTS 0. The loop writes every buffer with {@code size > 0} until
     * {@code BUFFER_FLAG_END_OF_STREAM} arrives and does not exit early: stopping
     * before the real frame is what produced the 18 KB unopenable files. Every
     * stage is wrapped so the exact failing step reaches the user-visible
     * error.</p>
     */
    private static int drainAndMux(MediaCodec codec, MediaMuxer muxer) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int track = -1;
        boolean started = false;
        boolean sawEos = false;
        int sampleCount = 0;
        for (int guard = 0; guard < DRAIN_LOOP_GUARD && !sawEos; guard++) {
            int index = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
            if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue;
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!started) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    if (outputFormat == null) {
                        throw new IOException("编码器输出格式不可用");
                    }
                    try {
                        // addTrack/start exactly once, from the real output format.
                        track = muxer.addTrack(outputFormat);
                        muxer.start();
                        started = true;
                    } catch (Throwable error) {
                        throw new IOException("addTrack/start 失败：" + describe(error)
                                + "，format=" + outputFormat, error);
                    }
                }
                continue;
            }
            if (index < 0) continue;

            ByteBuffer buffer = codec.getOutputBuffer(index);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                // Codec specific data belongs in the container header, not a sample.
                info.size = 0;
            }
            if (info.size > 0 && buffer != null) {
                if (!started || track < 0) {
                    throw new IOException("编码器在封装器启动前输出了样本");
                }
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                info.presentationTimeUs = 0L;
                try {
                    muxer.writeSampleData(track, buffer, info);
                } catch (Throwable error) {
                    throw new IOException("writeSampleData 失败：" + describe(error), error);
                }
                sampleCount++;
                Log.d(TAG, "wrote HEVC frame sample #" + sampleCount
                        + " (" + info.size + " bytes)");
            }
            sawEos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(index, false);
        }
        sLastSampleCount = sampleCount;
        if (started && sampleCount > 0) {
            try {
                // stop() finalises the HEIF container (writes the meta box).
                muxer.stop();
            } catch (Throwable error) {
                throw new IOException("封装器 stop 失败：" + describe(error), error);
            }
        }
        return sampleCount;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    /** Concrete, user-readable description of a failure. */
    private static String describe(Throwable error) {
        if (error == null) return "未知错误";
        if (error instanceof OutOfMemoryError) return "内存不足（OOM）";
        String message = error.getMessage();
        if (message != null && !message.trim().isEmpty()) return message;
        return error.getClass().getSimpleName();
    }

    private static String join(List<String> parts) {
        if (parts == null || parts.isEmpty()) return "未知错误";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) builder.append("；");
            builder.append(parts.get(i));
        }
        return builder.toString();
    }

    /** Keeps the aggregated failure message readable (first N attempts). */
    private static List<String> limit(List<String> failures) {
        if (failures == null) return new ArrayList<>();
        if (failures.size() <= MAX_REPORTED_FAILURES) return failures;
        List<String> limited = new ArrayList<>(failures.subList(0, MAX_REPORTED_FAILURES));
        limited.add("…（另有 " + (failures.size() - MAX_REPORTED_FAILURES) + " 次尝试失败）");
        return limited;
    }

    private static File cacheDirectory(Context context) {
        if (context == null) return null;
        try {
            Context app = context.getApplicationContext();
            File dir = (app != null ? app : context).getCacheDir();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                return null;
            }
            return dir;
        } catch (Throwable error) {
            Log.w(TAG, "cannot resolve cache directory", error);
            return null;
        }
    }

    private static File createTempFile(File workDir, String prefix, String suffix)
            throws IOException {
        if (workDir != null) {
            if (!workDir.isDirectory() && !workDir.mkdirs() && !workDir.isDirectory()) {
                throw new IOException("cannot create temp directory: " + workDir);
            }
            return File.createTempFile(prefix, suffix, workDir);
        }
        return File.createTempFile(prefix, suffix);
    }

    private static void writeBytes(File target, byte[] data) throws IOException {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(target);
            out.write(data);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                    // Nothing to flush.
                }
            }
        }
    }

    private static byte[] readBytes(File source) throws IOException {
        FileInputStream in = null;
        try {
            in = new FileInputStream(source);
            long length = source.length();
            int initial = (int) Math.max(64 * 1024, Math.min(length, 32L * 1024 * 1024));
            ByteArrayOutputStream out = new ByteArrayOutputStream(initial);
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
            return out.toByteArray();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // Nothing to flush.
                }
            }
        }
    }

    private static void deleteQuietly(File file) {
        if (file == null) return;
        try {
            if (file.exists()) file.delete();
        } catch (Throwable ignored) {
            // Best effort.
        }
    }

    private static void recycleQuietly(Bitmap bitmap) {
        if (bitmap == null) return;
        try {
            if (!bitmap.isRecycled()) bitmap.recycle();
        } catch (Throwable ignored) {
            // Best effort.
        }
    }

    private static void releaseQuietly(MediaMuxer muxer) {
        if (muxer == null) return;
        try {
            muxer.release();
        } catch (Throwable ignored) {
            // Already released.
        }
    }

    private static void stopReleaseQuietly(MediaCodec codec) {
        if (codec == null) return;
        try {
            codec.stop();
        } catch (Throwable ignored) {
            // Not started or already stopped.
        }
        try {
            codec.release();
        } catch (Throwable ignored) {
            // Already released.
        }
    }

    /** One usable HEVC encoder with its clamped target frame and capabilities. */
    private static final class EncoderTarget {
        final String name;
        final int width;
        final int height;
        final int[] colorFormats;
        final boolean surfaceAdvertised;
        final boolean vbrSupported;
        final long bitrate;
        final boolean hardware;
        final String capabilities;
        /** HEVC profile to prefer (MainStillPicture/Main), 0 = omit. */
        final int preferredProfile;
        /** True when the bitmap had to be scaled down to this encoder's limit. */
        boolean clamped;

        EncoderTarget(String name, int width, int height, int[] colorFormats,
                      boolean surfaceAdvertised, boolean vbrSupported, long bitrate,
                      boolean hardware, String capabilities, int preferredProfile) {
            this.name = name;
            this.width = width;
            this.height = height;
            this.colorFormats = colorFormats != null ? colorFormats : new int[0];
            this.surfaceAdvertised = surfaceAdvertised;
            this.vbrSupported = vbrSupported;
            this.bitrate = bitrate;
            this.hardware = hardware;
            this.capabilities = capabilities;
            this.preferredProfile = preferredProfile;
        }
    }
}
