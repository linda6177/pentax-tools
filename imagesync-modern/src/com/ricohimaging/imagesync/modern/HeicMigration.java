package com.ricohimaging.imagesync.modern;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot migration/repair of the app-owned photos in
 * {@code Pictures/Image Sync}.
 *
 * <p>Two kinds of work are performed:</p>
 * <ol>
 *   <li><b>JPEG -> HEIC conversion</b> (the normal migration). The JPEG is
 *       decoded and re-encoded by {@link HeicConverter}, which validates the
 *       result (>= 20 KB and decodable at the expected dimensions) before
 *       returning. Only after that validated HEIC has been inserted and
 *       <b>read back</b> from MediaStore with a size >= 20 KB is the original
 *       JPEG row deleted.</li>
 *   <li><b>Repair of previously damaged HEICs</b> (the earlier 18 KB broken
 *       files). A HEIC row smaller than 20 KB or whose header cannot be decoded
 *       is treated as damaged. For a JPEG + damaged HEIC pair the new validated
 *       conversion replaces the broken row. For a damaged HEIC with no surviving
 *       JPEG, the broken file is decoded and re-encoded; the broken row is only
 *       deleted after the validated replacement exists. If a broken HEIC cannot
 *       be decoded it is never deleted.</li>
 * </ol>
 *
 * <p>{@link #migrate(Context, ProgressCallback)} returns immediately and works
 * on its own background thread; callbacks arrive on the main thread and
 * {@link NotificationHelper} id {@code 4311} mirrors progress. {@link #cancel()}
 * stops after the current file. No storage permission is requested: only rows
 * the app itself published are touched.</p>
 */
public final class HeicMigration {

    private static final String TAG = "HeicMigration";

    /** Stable notification id used by {@link NotificationHelper}. */
    public static final int NOTIFICATION_ID = 4311;

    /** App-managed gallery folder (relative to {@code Pictures}). */
    private static final String FOLDER_RELATIVE = "Pictures/Image Sync";
    private static final String FOLDER_LIKE = FOLDER_RELATIVE + "%";
    private static final String FOLDER_LIKE_LEGACY = "%Pictures/Image Sync/%";

    private static final String MIME_JPEG = "image/jpeg";
    private static final String MIME_JPG = "image/jpg";
    private static final String MIME_HEIC = "image/heic";
    private static final String MIME_HEIF = "image/heif";

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean CANCELLED = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface ProgressCallback {
        /** Called once per processed file; main thread. */
        void onProgress(int done, int total);

        /**
         * Called exactly once when the run ends (finished, cancelled or
         * unsupported); main thread.
         *
         * @param converted  files successfully converted/repaired
         * @param failed     files whose conversion/validation failed
         * @param savedBytes aggregate byte saving (only counted when the HEIC
         *                   is smaller and the original was really removed)
         * @param firstError concrete validation/sample-count failure reason (or
         *                   a note when nothing could be converted), null when
         *                   everything went fine
         */
        void onFinished(int converted, int failed, long savedBytes, String firstError);
    }

    private HeicMigration() {}

    // ------------------------------------------------------------------
    // Public entry points
    // ------------------------------------------------------------------

    /**
     * Starts the migration on a background thread. A second call while a run is
     * active is ignored. {@code context} may be any context; only its
     * application context is retained.
     */
    public static void migrate(final Context context, final ProgressCallback callback) {
        if (context == null) return;
        final Context app;
        try {
            Context application = context.getApplicationContext();
            app = application != null ? application : context;
        } catch (Throwable error) {
            Log.w(TAG, "cannot resolve application context", error);
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            Log.d(TAG, "HEIC migration already running; ignoring duplicate start");
            return;
        }
        CANCELLED.set(false);
        try {
            Thread worker = new Thread(() -> runMigration(app, callback), "heic-migration");
            worker.setPriority(Thread.NORM_PRIORITY - 1);
            worker.start();
        } catch (Throwable error) {
            Log.e(TAG, "cannot start HEIC migration thread", error);
            RUNNING.set(false);
            pushFinished(callback, 0, 0, 0L, "无法启动 HEIC 转换线程：" + describe(error));
        }
    }

    /** Requests cancellation; the current file finishes before the loop stops. */
    public static void cancel() {
        CANCELLED.set(true);
    }

    /** True while a migration worker is active. */
    public static boolean isRunning() {
        return RUNNING.get();
    }

    /**
     * Cheap probe for settings UI: true when there are JPEGs to convert or any
     * HEIC in the folder that is small enough to be a damaged previous output.
     * Never throws.
     */
    public static boolean hasPendingCandidates(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 28) return false;
        return countMatching(context, new String[]{MIME_JPEG, MIME_JPG}, -1) > 0
                || countMatching(context, new String[]{MIME_HEIC, MIME_HEIF},
                HeicConverter.MIN_VALID_SIZE_BYTES) > 0;
    }

    // ------------------------------------------------------------------
    // Worker
    // ------------------------------------------------------------------

    private static void runMigration(Context context, ProgressCallback callback) {
        int converted = 0;
        int failed = 0;
        int skipped = 0;
        int repaired = 0;
        int downscaled = 0;
        long savedBytes = 0L;
        String firstError = null;
        String firstWarning = null;
        try {
            try {
                // Background priority: conversion is CPU heavy and must not
                // compete with the UI thread.
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {
                // Priority is an optimisation only.
            }
            NotificationHelper.ensureChannel(context);
            if (!HeicConverter.isSupported(context)) {
                String reason = HeicConverter.lastError();
                if (reason == null || reason.trim().isEmpty()) {
                    reason = "当前系统不支持 HEVC 编码";
                }
                NotificationHelper.finish(context, NOTIFICATION_ID,
                        "无法转换 HEIC", truncate(reason, 90));
                pushFinished(callback, 0, 0, 0L, reason);
                return;
            }
            List<Candidate> candidates = collectCandidates(context);
            int total = candidates.size();
            if (total == 0) {
                NotificationHelper.finish(context, NOTIFICATION_ID,
                        "HEIC 转换", "Pictures/Image Sync 中没有需要转换或修复的照片");
                pushFinished(callback, 0, 0, 0L, null);
                return;
            }
            NotificationHelper.progress(context, NOTIFICATION_ID, 0, total,
                    "正在转换 HEIC 照片");
            pushProgress(callback, 0, total);

            int done = 0;
            for (int i = 0; i < total; i++) {
                if (CANCELLED.get()) break;
                Candidate candidate = candidates.get(i);
                done = i + 1;
                try {
                    Conversion outcome = migrateOne(context, candidate);
                    if (outcome == null) {
                        // Converted result would not be smaller: keep the JPEG.
                        skipped++;
                    } else {
                        converted++;
                        if (candidate.repair
                                || (candidate.damagedSiblings != null
                                && !candidate.damagedSiblings.isEmpty())) {
                            repaired++;
                        }
                        if (HeicConverter.lastEncodedWasClamped()) downscaled++;
                        savedBytes += Math.max(0L, outcome.savedBytes);
                        if (outcome.warning != null && firstWarning == null) {
                            firstWarning = outcome.warning;
                        }
                    }
                } catch (Throwable error) {
                    failed++;
                    String reason = withDiagnostics(describe(error));
                    Log.w(TAG, "HEIC conversion failed for "
                            + candidate.row.displayName + ": " + reason, error);
                    if (firstError == null) firstError = reason;
                }
                NotificationHelper.progress(context, NOTIFICATION_ID, done, total,
                        "正在转换 HEIC 照片");
                pushProgress(callback, done, total);
            }

            String note = firstError;
            if (note == null && converted == 0) {
                // Nothing failed but nothing was converted either: explain why so
                // the UI does not just say "no photos to convert".
                if (firstWarning != null) {
                    note = firstWarning;
                } else if (skipped > 0) {
                    note = "已跳过 " + skipped + " 张：转换后体积未小于原图，已保留 JPEG";
                }
            }

            if (CANCELLED.get()) {
                NotificationHelper.finish(context, NOTIFICATION_ID, "HEIC 转换已取消",
                        "已转换 " + converted + " 张");
            } else {
                String detail = "已转换 " + converted + " 张";
                if (repaired > 0) detail += "，修复损坏 HEIC " + repaired + " 个";
                if (failed > 0) detail += "，失败 " + failed + " 张";
                if (skipped > 0) detail += "，跳过 " + skipped + " 张（体积未减小）";
                if (downscaled > 0) detail += "，" + downscaled + " 张按编码器上限缩小";
                if (savedBytes > 0) detail += "，节省 " + formatMegabytes(savedBytes);
                if (firstError != null && failed > 0) {
                    detail += " · " + truncate(firstError, 80);
                }
                NotificationHelper.finish(context, NOTIFICATION_ID, "HEIC 转换完成", detail);
            }
            pushFinished(callback, converted, failed, savedBytes, truncate(note, 240));
        } catch (Throwable error) {
            String reason = describe(error);
            Log.e(TAG, "HEIC migration aborted", error);
            NotificationHelper.finish(context, NOTIFICATION_ID, "HEIC 转换失败",
                    truncate(reason, 90));
            pushFinished(callback, converted, failed, savedBytes,
                    firstError != null ? firstError : reason);
        } finally {
            RUNNING.set(false);
        }
    }

    /**
     * Converts/repairs one candidate. Returns {@code null} when the HEIC would
     * not be smaller and there is nothing to repair (skipped, original kept),
     * otherwise the saved byte count plus an optional warning.
     */
    private static Conversion migrateOne(Context context, Candidate candidate)
            throws Exception {
        if (candidate == null || candidate.row == null || candidate.row.uri == null) {
            throw new IOException("无效的媒体条目");
        }
        return Build.VERSION.SDK_INT >= 29
                ? migrateModern(context, candidate)
                : migrateLegacy(context, candidate);
    }

    // ------------------------------------------------------------------
    // API 29+: MediaStore insert with RELATIVE_PATH + IS_PENDING
    // ------------------------------------------------------------------

    private static Conversion migrateModern(Context context, Candidate candidate)
            throws Exception {
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) throw new IOException("没有可用的 ContentResolver");
        Row row = candidate.row;
        byte[] sourceBytes = readAllBytes(context, row.uri, row.size);
        if (sourceBytes.length == 0) throw new IOException("源文件为空");

        // HeicConverter validates the produced HEIC (>= 20 KB, decodable,
        // dimensions match); a failure here throws and the original is untouched.
        byte[] heic = HeicConverter.encodeJpegToHeic(sourceBytes, cacheDir(context));
        if (heic.length == 0) throw new IOException("HEIC 结果为空");

        boolean needsRepair = candidate.repair
                || (candidate.damagedSiblings != null && !candidate.damagedSiblings.isEmpty());
        if (!needsRepair && heic.length >= sourceBytes.length) {
            Log.d(TAG, "skip " + row.displayName + ": heic=" + heic.length
                    + " jpeg=" + sourceBytes.length);
            return null;
        }

        // Insert the validated replacement first and read it back from
        // MediaStore. Nothing is deleted before this succeeds.
        Uri inserted = insertHeicVerified(context, resolver,
                heicDisplayName(row.displayName), relativePathFor(row), heic);

        boolean warning = false;
        long removedBytes = 0L;
        if (candidate.damagedSiblings != null) {
            for (Row broken : candidate.damagedSiblings) {
                try {
                    if (resolver.delete(broken.uri, null, null) > 0) {
                        removedBytes += Math.max(0L, broken.size);
                        Log.i(TAG, "removed damaged HEIC " + broken.displayName);
                    } else {
                        warning = true;
                    }
                } catch (Throwable error) {
                    warning = true;
                    Log.w(TAG, "cannot delete damaged HEIC " + broken.displayName, error);
                }
            }
        }
        if (candidate.repair) {
            // This candidate itself is a damaged HEIC; the validated copy exists.
            try {
                if (resolver.delete(row.uri, null, null) > 0) {
                    removedBytes += Math.max(0L, row.size);
                } else {
                    warning = true;
                }
            } catch (Throwable error) {
                warning = true;
                Log.w(TAG, "cannot delete damaged HEIC " + row.displayName, error);
            }
        } else {
            // JPEG original: safe to delete now (validated + read-back verified).
            try {
                int deleted = resolver.delete(row.uri, null, null);
                if (deleted <= 0) warning = true;
            } catch (Throwable error) {
                warning = true;
                Log.w(TAG, "cannot delete original JPEG " + row.displayName, error);
            }
        }

        // MediaStore may have appended " (1)" because the broken file existed;
        // restore the canonical name once the old row is gone (best effort).
        if (candidate.repair || (candidate.damagedSiblings != null
                && !candidate.damagedSiblings.isEmpty())) {
            renameRowBestEffort(context, inserted, heicDisplayName(row.displayName));
        }

        long saved = candidate.repair
                ? 0L
                : Math.max(0L, sourceBytes.length + removedBytes - heic.length);
        String note = warning
                ? "已生成并校验有效 HEIC，但部分旧文件未能删除（可能没有写入权限）"
                : null;
        return new Conversion(saved, note);
    }

    // ------------------------------------------------------------------
    // API 23-28: legacy public file + DATA-based selection
    // ------------------------------------------------------------------

    private static Conversion migrateLegacy(Context context, Candidate candidate)
            throws Exception {
        Row row = candidate.row;
        byte[] sourceBytes = readAllBytes(context, row.uri, row.size);
        if (sourceBytes.length == 0) throw new IOException("源文件为空");
        byte[] heic = HeicConverter.encodeJpegToHeic(sourceBytes, cacheDir(context));
        if (heic.length == 0) throw new IOException("HEIC 结果为空");

        boolean needsRepair = candidate.repair
                || (candidate.damagedSiblings != null && !candidate.damagedSiblings.isEmpty());
        if (!needsRepair && heic.length >= sourceBytes.length) {
            Log.d(TAG, "skip " + row.displayName + ": heic=" + heic.length
                    + " jpeg=" + sourceBytes.length);
            return null;
        }

        File directory = legacyTargetDirectory(row.data);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("无法创建目录 " + directory);
        }
        String desiredName = heicDisplayName(row.displayName);
        File target = uniqueFile(directory, desiredName);
        try {
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(target);
                out.write(heic);
            } finally {
                closeQuietly(out);
            }
            // Extra validation of the written file.
            if (target.length() < HeicConverter.MIN_VALID_SIZE_BYTES) {
                throw new IOException("HEIC 文件写入校验失败（" + target.length() + " 字节）");
            }
            MediaScannerConnection.scanFile(context.getApplicationContext(),
                    new String[]{target.getAbsolutePath()},
                    new String[]{HeicConverter.MIME_HEIC}, null);
        } catch (Throwable error) {
            deleteQuietly(target);
            if (error instanceof Exception) throw (Exception) error;
            throw new IOException("写入 HEIC 文件失败", error);
        }

        boolean warning = false;
        long removedBytes = 0L;
        if (candidate.damagedSiblings != null) {
            for (Row broken : candidate.damagedSiblings) {
                if (deleteLegacyRow(context, broken)) {
                    removedBytes += Math.max(0L, broken.size);
                } else {
                    warning = true;
                }
            }
        }
        if (candidate.repair) {
            if (deleteLegacyRow(context, row)) {
                removedBytes += Math.max(0L, row.size);
            } else {
                warning = true;
            }
        } else {
            try {
                ContentResolver resolver = context.getContentResolver();
                if (resolver == null || resolver.delete(row.uri, null, null) <= 0) {
                    warning = true;
                }
            } catch (Throwable error) {
                warning = true;
                Log.w(TAG, "cannot delete original JPEG " + row.displayName, error);
            }
        }

        // Restore the canonical file name if " (1)" was needed while the broken
        // file still existed, now that it is gone (best effort).
        if (!target.getName().equals(desiredName)) {
            File canonical = new File(directory, desiredName);
            try {
                if (!canonical.exists() && target.renameTo(canonical)) {
                    MediaScannerConnection.scanFile(context.getApplicationContext(),
                            new String[]{canonical.getAbsolutePath()},
                            new String[]{HeicConverter.MIME_HEIC}, null);
                }
            } catch (Throwable error) {
                Log.w(TAG, "cannot restore HEIC file name", error);
            }
        }

        long saved = candidate.repair
                ? 0L
                : Math.max(0L, sourceBytes.length + removedBytes - heic.length);
        String note = warning
                ? "已生成并校验有效 HEIC，但部分旧文件未能删除（可能没有写入权限）"
                : null;
        return new Conversion(saved, note);
    }

    // ------------------------------------------------------------------
    // Validated MediaStore publishing / deletion helpers
    // ------------------------------------------------------------------

    /**
     * Inserts a validated HEIC into MediaStore, publishes it and then verifies
     * by reading its size back. On any failure the new row is rolled back and
     * an IOException with the concrete reason is thrown.
     */
    private static Uri insertHeicVerified(Context context, ContentResolver resolver,
                                          String displayName, String relativePath,
                                          byte[] heic) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, HeicConverter.MIME_HEIC);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri inserted = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (inserted == null) throw new IOException("MediaStore 插入失败");
        try {
            OutputStream out = null;
            try {
                out = resolver.openOutputStream(inserted);
                if (out == null) throw new IOException("MediaStore 输出流不可用");
                out.write(heic);
            } finally {
                closeQuietly(out);
            }
            ContentValues published = new ContentValues();
            published.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(inserted, published, null, null);
        } catch (Throwable error) {
            deleteQuietly(resolver, inserted);
            if (error instanceof Exception) throw (Exception) error;
            throw new IOException("写入 HEIC 失败", error);
        }

        long verified = readBackSize(context, resolver, inserted);
        if (verified < HeicConverter.MIN_VALID_SIZE_BYTES) {
            deleteQuietly(resolver, inserted);
            throw new IOException("HEIC 写入回读校验失败（" + verified
                    + " 字节 < " + HeicConverter.MIN_VALID_SIZE_BYTES
                    + "），已保留原图");
        }
        return inserted;
    }

    /** Reads the published row's size, falling back to counting stream bytes. */
    private static long readBackSize(Context context, ContentResolver resolver, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri,
                    new String[]{MediaStore.MediaColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                long size = cursor.getLong(0);
                if (size > 0) return size;
            }
        } catch (Throwable error) {
            Log.d(TAG, "cannot query published HEIC size", error);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                    // Nothing to release.
                }
            }
        }
        InputStream in = null;
        long total = 0L;
        try {
            in = resolver.openInputStream(uri);
            if (in != null) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while (total <= HeicConverter.MIN_VALID_SIZE_BYTES
                        && (count = in.read(buffer)) > 0) {
                    total += count;
                }
            }
        } catch (Throwable error) {
            Log.d(TAG, "cannot read published HEIC size", error);
        } finally {
            closeQuietly(in);
        }
        return total;
    }

    private static void deleteQuietly(ContentResolver resolver, Uri uri) {
        try {
            resolver.delete(uri, null, null);
        } catch (Throwable error) {
            Log.w(TAG, "cannot roll back HEIC row", error);
        }
    }

    private static void renameRowBestEffort(Context context, Uri uri, String displayName) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            context.getContentResolver().update(uri, values, null, null);
        } catch (Throwable error) {
            Log.d(TAG, "cannot restore HEIC display name", error);
        }
    }

    /** Deletes a legacy (pre-API 29) row and its backing file. */
    private static boolean deleteLegacyRow(Context context, Row row) {
        if (row == null) return true;
        boolean deleted = false;
        try {
            ContentResolver resolver = context.getContentResolver();
            if (resolver != null && resolver.delete(row.uri, null, null) > 0) {
                deleted = true;
            }
        } catch (Throwable error) {
            Log.w(TAG, "cannot delete legacy media row " + row.displayName, error);
        }
        if (row.data != null) {
            try {
                File file = new File(row.data);
                if (file.exists() && file.delete()) deleted = true;
            } catch (Throwable error) {
                Log.w(TAG, "cannot delete legacy media file " + row.data, error);
            }
        }
        return deleted;
    }

    // ------------------------------------------------------------------
    // Candidate collection (JPEG conversions + damaged HEIC repairs)
    // ------------------------------------------------------------------

    /**
     * Queries the app gallery folder and builds the work list: every JPEG, plus
     * damaged HEICs. Damaged HEICs whose base name has a surviving JPEG are
     * attached to that JPEG as repair siblings; the rest become standalone
     * repair candidates.
     */
    private static List<Candidate> collectCandidates(Context context) {
        List<Candidate> candidates = new ArrayList<>();
        List<Row> jpegRows = new ArrayList<>();
        List<Row> damagedHeics = new ArrayList<>();
        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            if (resolver == null) return candidates;
            boolean modern = Build.VERSION.SDK_INT >= 29;
            String[] projection = modern
                    ? new String[]{
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.RELATIVE_PATH}
                    : new String[]{
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.DATA};
            String selection = folderCondition(modern) + " AND "
                    + MediaStore.Images.Media.MIME_TYPE + " IN (?,?,?,?)";
            String[] args = folderArgs(modern,
                    new String[]{MIME_JPEG, MIME_JPG, MIME_HEIC, MIME_HEIF});
            String sort = MediaStore.Images.Media.DATE_ADDED + " ASC";
            cursor = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, args, sort);
            if (cursor == null) return candidates;
            while (cursor.moveToNext()) {
                Row row = new Row();
                row.id = cursor.getLong(0);
                row.displayName = cursor.isNull(1) ? null : cursor.getString(1);
                row.size = cursor.isNull(2) ? 0L : cursor.getLong(2);
                row.mimeType = cursor.isNull(3) ? null : cursor.getString(3);
                if (modern) {
                    row.relativePath = cursor.isNull(4) ? null : cursor.getString(4);
                } else {
                    row.data = cursor.isNull(4) ? null : cursor.getString(4);
                }
                row.uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, row.id);
                row.baseName = baseName(row.displayName);
                row.heic = isHeic(row.mimeType, row.displayName);
                if (row.heic) {
                    if (isDamagedHeic(context, row)) damagedHeics.add(row);
                } else {
                    jpegRows.add(row);
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "cannot query HEIC migration candidates", error);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                    // Nothing to release.
                }
            }
        }

        Set<String> jpegBases = new HashSet<>();
        for (Row row : jpegRows) jpegBases.add(row.baseName);
        Map<String, List<Row>> damagedByBase = new LinkedHashMap<>();
        for (Row broken : damagedHeics) {
            if (jpegBases.contains(broken.baseName)) {
                List<Row> siblings = damagedByBase.get(broken.baseName);
                if (siblings == null) {
                    siblings = new ArrayList<>();
                    damagedByBase.put(broken.baseName, siblings);
                }
                siblings.add(broken);
            } else {
                // No surviving JPEG: repair from the HEIC itself if decodable.
                candidates.add(new Candidate(broken, true, null));
            }
        }
        // JPEG conversions first (they may also clear damaged siblings).
        List<Candidate> jpegCandidates = new ArrayList<>();
        for (Row row : jpegRows) {
            jpegCandidates.add(new Candidate(row, false, damagedByBase.get(row.baseName)));
        }
        candidates.addAll(0, jpegCandidates);
        return candidates;
    }

    private static boolean isDamagedHeic(Context context, Row row) {
        if (row == null) return false;
        if (row.size > 0 && row.size < HeicConverter.MIN_VALID_SIZE_BYTES) return true;
        return !isDecodable(context, row.uri);
    }

    /** Header-only decodability check (HEIF is decodable natively on API 28+). */
    private static boolean isDecodable(Context context, Uri uri) {
        InputStream in = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            if (resolver == null) return false;
            in = resolver.openInputStream(uri);
            if (in == null) return false;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, bounds);
            return bounds.outWidth > 0 && bounds.outHeight > 0;
        } catch (Throwable error) {
            Log.d(TAG, "cannot decode media header " + uri, error);
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    private static boolean isHeic(String mimeType, String displayName) {
        String mime = mimeType != null ? mimeType.toLowerCase(Locale.US) : "";
        if (mime.contains("heic") || mime.contains("heif")) return true;
        String name = displayName != null ? displayName.toLowerCase(Locale.US) : "";
        return name.endsWith(".heic") || name.endsWith(".heif");
    }

    /** {@code IMG_0001.JPG} -> {@code img_0001} (case-insensitive matching key). */
    private static String baseName(String displayName) {
        if (displayName == null) return "";
        String name = displayName.trim().toLowerCase(Locale.US);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ------------------------------------------------------------------
    // MediaStore folder selection
    // ------------------------------------------------------------------

    private static String folderCondition(boolean modern) {
        return (modern ? MediaStore.Images.Media.RELATIVE_PATH
                : MediaStore.Images.Media.DATA) + " LIKE ?";
    }

    private static String[] folderArgs(boolean modern, String[] extra) {
        String[] args = new String[1 + (extra != null ? extra.length : 0)];
        args[0] = modern ? FOLDER_LIKE : FOLDER_LIKE_LEGACY;
        if (extra != null) {
            for (int i = 0; i < extra.length; i++) args[i + 1] = extra[i];
        }
        return args;
    }

    private static int countMatching(Context context, String[] mimes, long maxSize) {
        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            if (resolver == null) return 0;
            boolean modern = Build.VERSION.SDK_INT >= 29;
            StringBuilder selection = new StringBuilder(folderCondition(modern));
            selection.append(" AND ").append(MediaStore.Images.Media.MIME_TYPE)
                    .append(" IN (");
            for (int i = 0; i < mimes.length; i++) {
                if (i > 0) selection.append(',');
                selection.append('?');
            }
            selection.append(')');
            String[] args;
            if (maxSize >= 0) {
                selection.append(" AND ").append(MediaStore.Images.Media.SIZE)
                        .append(" < ?");
                args = append(folderArgs(modern, mimes), String.valueOf(maxSize));
            } else {
                args = folderArgs(modern, mimes);
            }
            cursor = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID},
                    selection.toString(), args, null);
            return cursor != null && cursor.getCount() > 0 ? 1 : 0;
        } catch (Throwable error) {
            Log.d(TAG, "cannot count HEIC migration candidates", error);
            return 0;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                    // Nothing to release.
                }
            }
        }
    }

    private static String[] append(String[] source, String value) {
        String[] result = new String[(source != null ? source.length : 0) + 1];
        if (source != null) {
            System.arraycopy(source, 0, result, 0, source.length);
        }
        result[result.length - 1] = value;
        return result;
    }

    private static String relativePathFor(Row row) {
        if (row != null && row.relativePath != null
                && row.relativePath.startsWith(FOLDER_RELATIVE)) {
            return row.relativePath;
        }
        return FOLDER_RELATIVE;
    }

    private static File legacyTargetDirectory(String data) {
        if (data != null) {
            try {
                File source = new File(data);
                File parent = source.getParentFile();
                if (parent != null) return parent;
            } catch (Throwable ignored) {
                // Fall through to the public folder.
            }
        }
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "Image Sync");
    }

    // ------------------------------------------------------------------
    // Names / bytes / callbacks
    // ------------------------------------------------------------------

    /** {@code IMG_0001.JPG} -> {@code IMG_0001.heic}; always non-empty. */
    private static String heicDisplayName(String displayName) {
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

    private static byte[] readAllBytes(Context context, Uri uri, long expectedSize)
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) throw new IOException("没有可用的 ContentResolver");
        InputStream in = null;
        try {
            in = resolver.openInputStream(uri);
            if (in == null) throw new IOException("无法打开 " + uri);
            int initial = (int) Math.max(64L * 1024,
                    Math.min(expectedSize > 0 ? expectedSize : 0L, 32L * 1024 * 1024));
            ByteArrayOutputStream out = new ByteArrayOutputStream(initial);
            byte[] buffer = new byte[256 * 1024];
            int count;
            while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
            return out.toByteArray();
        } finally {
            closeQuietly(in);
        }
    }

    private static File cacheDir(Context context) {
        try {
            File dir = context.getCacheDir();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                return null;
            }
            return dir;
        } catch (Throwable error) {
            Log.w(TAG, "cannot resolve cache directory", error);
            return null;
        }
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

    private static String formatMegabytes(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Concrete, user-readable failure reason for the UI. */
    private static String describe(Throwable error) {
        if (error == null) return "未知错误";
        if (error instanceof OutOfMemoryError) return "内存不足（OOM）";
        String message = error.getMessage();
        if (message != null && !message.trim().isEmpty()) return message.trim();
        return error.getClass().getSimpleName();
    }

    /** Appends encoder sample/size diagnostics when the message lacks them. */
    private static String withDiagnostics(String reason) {
        String value = reason != null ? reason : "未知错误";
        if (value.contains("样本")) return value;
        return value + "（样本数=" + HeicConverter.lastSampleCount()
                + "，输出=" + HeicConverter.lastEncodedBytes() + " 字节）";
    }

    private static String truncate(String text, int max) {
        if (text == null) return null;
        String value = text.trim();
        if (value.length() <= max) return value;
        return value.substring(0, max) + "…";
    }

    private static void pushProgress(final ProgressCallback callback, final int done,
                                     final int total) {
        if (callback == null) return;
        try {
            MAIN.post(() -> {
                try {
                    callback.onProgress(done, total);
                } catch (Throwable error) {
                    Log.w(TAG, "progress callback failed", error);
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "cannot post progress callback", error);
        }
    }

    private static void pushFinished(final ProgressCallback callback, final int converted,
                                     final int failed, final long savedBytes,
                                     final String firstError) {
        if (callback == null) return;
        try {
            MAIN.post(() -> {
                try {
                    callback.onFinished(converted, failed, savedBytes, firstError);
                } catch (Throwable error) {
                    Log.w(TAG, "finished callback failed", error);
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "cannot post finished callback", error);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // Nothing to flush.
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

    /** Outcome of one successful conversion/repair. */
    private static final class Conversion {
        final long savedBytes;
        final String warning;

        Conversion(long savedBytes, String warning) {
            this.savedBytes = savedBytes;
            this.warning = warning;
        }
    }

    /**
     * One unit of work: a JPEG to convert, or a damaged HEIC to repair, plus any
     * damaged HEIC rows sharing the same base name (deleted only after the
     * validated replacement exists).
     */
    private static final class Candidate {
        final Row row;
        final boolean repair;
        final List<Row> damagedSiblings;

        Candidate(Row row, boolean repair, List<Row> damagedSiblings) {
            this.row = row;
            this.repair = repair;
            this.damagedSiblings = damagedSiblings;
        }
    }

    /** One MediaStore row selected for migration. */
    private static final class Row {
        long id;
        Uri uri;
        String displayName;
        long size;
        String mimeType;
        String relativePath;
        String data;
        String baseName;
        boolean heic;
    }
}
