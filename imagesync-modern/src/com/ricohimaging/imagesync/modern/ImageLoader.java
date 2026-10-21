package com.ricohimaging.imagesync.modern;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny asynchronous bitmap loader with a two-level cache.
 *
 * <p>Camera thumbnails are fetched through the original Ricoh SDK on a small
 * worker pool, decoded with inSampleSize, cached by content key and faded in.
 * Disk cache keeps re-opening the gallery instant even after Android killed
 * the activity.</p>
 */
public final class ImageLoader {
    public interface Source {
        /** Writes the encoded image bytes into {@code sink}; return false on error. */
        boolean load(OutputStream sink);
    }

    public interface Callback {
        void onBitmap(String key, Bitmap bitmap);
        void onError(String key, Throwable error);
    }

    private static volatile ImageLoader instance;

    private final LruCache<String, Bitmap> memory;
    private final File diskDir;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<ImageView, String> pending = new LinkedHashMap<>();

    private ImageLoader(android.content.Context context) {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 6);
        memory = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        diskDir = new File(context.getCacheDir(), "image-loader");
        if (!diskDir.exists()) diskDir.mkdirs();
    }

    public static ImageLoader of(android.content.Context context) {
        if (instance == null) {
            synchronized (ImageLoader.class) {
                if (instance == null) instance = new ImageLoader(context.getApplicationContext());
            }
        }
        return instance;
    }

    public void put(String key, Bitmap bitmap) {
        if (key != null && bitmap != null) memory.put(key, bitmap);
    }

    public Bitmap cached(String key) {
        return key == null ? null : memory.get(key);
    }

    public void load(String key, Source source, int targetPx, final Callback callback) {
        Bitmap hit = memory.get(key);
        if (hit != null) {
            callback.onBitmap(key, hit);
            return;
        }
        pool.execute(() -> {
            try {
                byte[] data = readCached(key);
                if (data == null) {
                    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                    if (!source.load(buffer)) {
                        throw new java.io.IOException("source produced no bytes");
                    }
                    data = buffer.toByteArray();
                    writeCached(key, data);
                }
                Bitmap decoded = decode(data, targetPx);
                if (decoded == null) throw new java.io.IOException("decode failed");
                memory.put(key, decoded);
                final Bitmap result = decoded;
                main.post(() -> callback.onBitmap(key, result));
            } catch (final Throwable error) {
                main.post(() -> callback.onError(key, error));
            }
        });
    }

    /** Convenience for grid cells: cancels stale requests through the view tag. */
    public void into(final ImageView view, final String key, final Source source,
                     final int targetPx, final int fadeMs) {
        view.setTag(key);
        Bitmap hit = memory.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        final int placeholderAlpha = fadeMs > 0 ? 90 : 255;
        view.setAlpha(placeholderAlpha / 255f);
        pending.put(view, key);
        load(key, source, targetPx, new Callback() {
            @Override
            public void onBitmap(String loadedKey, Bitmap bitmap) {
                if (!key.equals(view.getTag())) return;
                view.setImageBitmap(bitmap);
                if (fadeMs > 0) {
                    view.animate().alpha(1f).setDuration(fadeMs)
                            .setInterpolator(UiKit.DECELERATE).start();
                } else {
                    view.setAlpha(1f);
                }
            }

            @Override
            public void onError(String loadedKey, Throwable error) {
                if (!key.equals(view.getTag())) return;
                view.setAlpha(1f);
            }
        });
    }

    private byte[] readCached(String key) {
        try {
            File file = new File(diskDir, Integer.toHexString(key.hashCode()) + ".bin");
            if (!file.exists() || file.length() == 0) return null;
            byte[] data = new byte[(int) file.length()];
            try (InputStream in = new java.io.FileInputStream(file)) {
                int read = 0;
                while (read < data.length) {
                    int count = in.read(data, read, data.length - read);
                    if (count < 0) break;
                    read += count;
                }
            }
            return data;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void writeCached(String key, byte[] data) {
        try {
            File file = new File(diskDir, Integer.toHexString(key.hashCode()) + ".bin");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(data);
            }
        } catch (Throwable ignored) {
            // Cache is best effort only.
        }
    }

    /** RGB_565 decode for high-rate live view frames: half the memory, no alpha. */
    public static Bitmap decodeFrame(byte[] data) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    public static Bitmap decode(byte[] data, int targetPx) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        if (targetPx > 0) {
            while (bounds.outWidth / (sample * 2) >= targetPx
                    && bounds.outHeight / (sample * 2) >= targetPx) {
                sample *= 2;
            }
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }

    /** Drops cached entries for keys that no longer exist (kept intentionally small). */
    public void trimTo(List<String> keep) {
        List<String> keys = new ArrayList<>(keep);
        for (Map.Entry<String, Bitmap> entry : snapshot()) {
            if (!keys.contains(entry.getKey())) memory.remove(entry.getKey());
        }
    }

    private List<Map.Entry<String, Bitmap>> snapshot() {
        Map<String, Bitmap> copy = memory.snapshot();
        return new ArrayList<>(copy.entrySet());
    }
}
