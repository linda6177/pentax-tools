package com.ricohimaging.imagesync.modern;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Haptic helpers with a real vibration fallback.
 *
 * <p>{@code performHapticFeedback} is capped by the system touch-feedback
 * setting; for deliberate actions such as the shutter release we ask the
 * vibrator for maximum amplitude instead. VIBRATE is a normal install-time
 * permission declared by integrate.py.</p>
 */
public final class Haptics {

    private Haptics() {}

    /** Strongest short pulse, used for shutter release and other decisive taps. */
    public static void strong(Context context) {
        vibrate(context, 255, 70L);
    }

    /** Medium pulse, used for arming a gesture / mode changes. */
    public static void medium(Context context) {
        vibrate(context, 180, 30L);
    }

    /** Very short tick, used for continuous focus-repeat feedback. */
    public static void tick(Context context) {
        vibrate(context, 120, 14L);
    }

    public static boolean available(Context context) {
        try {
            Vibrator vibrator = vibrator(context);
            return vibrator != null && vibrator.hasVibrator();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void vibrate(Context context, int amplitude, long milliseconds) {
        try {
            Vibrator vibrator = vibrator(context);
            if (vibrator == null || !vibrator.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(milliseconds,
                        Math.max(1, Math.min(255, amplitude))));
            } else {
                vibrator.vibrate(milliseconds);
            }
        } catch (Throwable ignored) {
            // Silent devices or revoked permission must never crash a screen.
        }
    }

    private static Vibrator vibrator(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                VibratorManager manager = (VibratorManager) context.getSystemService(
                        Context.VIBRATOR_MANAGER_SERVICE);
                if (manager != null) return manager.getDefaultVibrator();
            } catch (Throwable ignored) {
                // Fall back to the legacy service below.
            }
        }
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }
}
