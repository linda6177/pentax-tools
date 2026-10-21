package com.ricohimaging.imagesync.modern;

import android.content.Context;
import android.hardware.ConsumerIrManager;

/**
 * PENTAX O-RC1 compatible infrared transmitter.
 *
 * <p>The camera accepts the classic PENTAX IR envelope on a 38 kHz carrier:
 * a 13 ms/3 ms header, six MSB-first data bits (1 ms mark plus 1 ms or 3 ms
 * space), a stop mark and a 60 ms quiet tail. The three O-RC1 keys are:
 * shutter release (0), zoom/focus-near (1) and Fn/focus-far (2).</p>
 *
 * <p>Only phones with a real IR emitter can use this path; every call reports
 * a precise failure instead of pretending the frame was sent.</p>
 */
public final class IrRemote {

    public static final int KEY_SHUTTER = 0;
    public static final int KEY_NEAR = 1;
    public static final int KEY_FAR = 2;

    public static final int CARRIER_HZ = 38000;

    /** Long-press assisted focus fine adjustment. */
    public static final long REPEAT_HOLD_MS = 500;
    public static final long REPEAT_INTERVAL_MS = 200;
    public static final long REPEAT_LIMIT_MS = 30000;

    public interface Callback {
        void onResult(boolean sent, String message);
    }

    private static final Object LOCK = new Object();
    private static long lastSentAt;

    private IrRemote() {}

    /** True when this device really exposes a consumer IR emitter. */
    public static boolean hasEmitter(Context context) {
        try {
            ConsumerIrManager manager = manager(context);
            return manager != null && manager.hasIrEmitter();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Human readable hardware state for the settings/diagnostics UI. */
    public static String describe(Context context) {
        try {
            ConsumerIrManager manager = manager(context);
            if (manager == null) return "系统未提供红外服务";
            if (!manager.hasIrEmitter()) return "此手机没有红外发射器";
            ConsumerIrManager.CarrierFrequencyRange[] ranges = manager.getCarrierFrequencies();
            if (ranges != null && ranges.length > 0) {
                boolean supported = false;
                for (ConsumerIrManager.CarrierFrequencyRange range : ranges) {
                    if (range.getMinFrequency() <= CARRIER_HZ
                            && range.getMaxFrequency() >= CARRIER_HZ) {
                        supported = true;
                        break;
                    }
                }
                if (!supported) return "红外硬件不支持 38 kHz 载波";
            }
            return "红外发射器可用 · 38 kHz";
        } catch (Throwable error) {
            return "红外状态未知";
        }
    }

    /**
     * Sends one O-RC1 frame asynchronously. Frames are serialized; a burst of
     * long-press repeats never interleaves two transmissions.
     */
    public static void send(final Context context, final int key, final Callback callback) {
        new Thread(() -> {
            String failure = transmitBlocking(context, key);
            final boolean ok = failure == null;
            final String message = ok ? "已发送" : failure;
            if (callback != null) {
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .post(() -> callback.onResult(ok, message));
            }
        }, "ir-remote").start();
    }

    /** @return null on success, otherwise a user facing failure reason. */
    public static String transmitBlocking(Context context, int key) {
        if (key < 0 || key > 2) return "未知的遥控按键";
        synchronized (LOCK) {
            try {
                ConsumerIrManager manager = manager(context);
                if (manager == null) return "系统未提供红外服务";
                if (!manager.hasIrEmitter()) return "此手机没有红外发射器";
                ConsumerIrManager.CarrierFrequencyRange[] ranges = manager.getCarrierFrequencies();
                if (ranges != null && ranges.length > 0) {
                    boolean supported = false;
                    for (ConsumerIrManager.CarrierFrequencyRange range : ranges) {
                        if (range.getMinFrequency() <= CARRIER_HZ
                                && range.getMaxFrequency() >= CARRIER_HZ) {
                            supported = true;
                            break;
                        }
                    }
                    if (!supported) return "红外硬件不支持 38 kHz 载波";
                }
                manager.transmit(CARRIER_HZ, frame(key));
                lastSentAt = System.currentTimeMillis();
                return null;
            } catch (SecurityException error) {
                return "缺少 TRANSMIT_IR 权限";
            } catch (Throwable error) {
                return "红外发送失败：" + error.getClass().getSimpleName();
            }
        }
    }

    /** PENTAX O-RC1 envelope, microsecond durations, one frame per key tap. */
    static int[] frame(int command) {
        int[] pattern = new int[16];
        pattern[0] = 13000;
        pattern[1] = 3000;
        for (int bit = 5; bit >= 0; bit--) {
            int index = 2 + (5 - bit) * 2;
            pattern[index] = 1000;
            pattern[index + 1] = (command & (1 << bit)) == 0 ? 1000 : 3000;
        }
        pattern[14] = 1000;
        pattern[15] = 60000;
        return pattern;
    }

    private static ConsumerIrManager manager(Context context) {
        return (ConsumerIrManager) context.getSystemService(Context.CONSUMER_IR_SERVICE);
    }
}
