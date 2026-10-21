package com.ricohimaging.imagesync.modern;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;

/**
 * Design tokens for the rebuilt Image Sync UI.
 *
 * <p>Night mode and the user-selected accent palette are resolved at runtime,
 * so no new resources are needed. The default accent is a calm blue instead of
 * the platform default (which can be purple); users can pick another palette in
 * Settings, including "follow system".</p>
 */
public final class Theme {
    public static final String PREFS = "modern_ui";
    public static final String KEY_ACCENT = "accent_palette";
    public static final String KEY_MODE = "theme_mode"; // 0 system, 1 light, 2 dark
    public static final String[] MODE_NAMES = {"跟随系统", "浅色", "深色"};

    /** Selectable accent palettes: 0 = system, then hand tuned light/dark pairs. */
    public static final String[] ACCENT_NAMES = {
            "图标蓝", "海蓝", "青碧", "森绿", "琥珀", "珊瑚", "紫罗兰", "石墨", "樱粉"
    };
    private static final int[][] ACCENTS = {
            {0xFF0087D6, 0xFF8ED2F5},
            {0xFF2F6BD8, 0xFFB6CBFF},
            {0xFF0E8C8C, 0xFF7FE3D4},
            {0xFF2E7D32, 0xFF9BE7A0},
            {0xFFB26A00, 0xFFFFD08A},
            {0xFFC2413A, 0xFFFFB4AB},
            {0xFF6D4AC7, 0xFFCEB9FF},
            {0xFF4A5568, 0xFFB8C2D0},
            {0xFFB03A6A, 0xFFFFB1CE}
    };

    public final boolean dark;
    public final int background;
    public final int surface;
    public final int surfaceAlt;
    public final int surfaceHigh;
    public final int text;
    public final int muted;
    public final int accent;
    public final int onAccent;
    public final int accentSoft;
    public final int danger;
    public final int success;
    public final int hairline;
    public final int scrim;
    public final int accentIndex;

    public final int radiusCard = 26;
    public final int radiusInner = 18;
    public final int radiusPill = 100;

    private Theme(boolean dark, int accent, int accentIndex) {
        this.dark = dark;
        this.accent = accent;
        this.accentIndex = accentIndex;
        background = Color.parseColor(dark ? "#0B0D10" : "#F4F6FB");
        surface = Color.parseColor(dark ? "#15181D" : "#FFFFFF");
        surfaceAlt = Color.parseColor(dark ? "#1D222A" : "#E9EEF7");
        surfaceHigh = Color.parseColor(dark ? "#262C36" : "#FFFFFF");
        text = Color.parseColor(dark ? "#F2F5FA" : "#0F1319");
        muted = Color.parseColor(dark ? "#98A2B3" : "#5B6676");
        onAccent = dark ? Color.parseColor("#07132E") : Color.WHITE;
        accentSoft = (accent & 0x00FFFFFF) | 0x22000000;
        danger = Color.parseColor(dark ? "#FFB4AB" : "#B3261E");
        success = Color.parseColor(dark ? "#8BE9B4" : "#157F4A");
        hairline = dark ? 0x24FFFFFF : 0x14000000;
        scrim = dark ? 0xD9000000 : 0x99000000;
    }

    public static Theme of(Activity activity) {
        boolean systemNight = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        boolean night = isNight(activity, systemNight);
        int index = accentIndex(activity);
        return new Theme(night, accentColor(activity, night, index), index);
    }

    /** 0 = follow system, 1 = force light, 2 = force dark. */
    public static int modeIndex(Context context) {
        try {
            int mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_MODE, 0);
            return mode < 0 || mode > 2 ? 0 : mode;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static void setModeIndex(Context context, int mode) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_MODE, mode < 0 || mode > 2 ? 0 : mode).apply();
        } catch (Throwable ignored) {
            // Non fatal: the previous appearance stays active.
        }
    }

    private static boolean isNight(Activity activity, boolean systemNight) {
        switch (modeIndex(activity)) {
            case 1:
                return false;
            case 2:
                return true;
            default:
                return systemNight;
        }
    }

    // ------------------------------------------------------------ accent prefs
    public static int accentIndex(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int index = prefs.getInt(KEY_ACCENT, 0);
            return index < 0 || index >= ACCENT_NAMES.length ? 0 : index;
        } catch (Throwable ignored) {
            return 1;
        }
    }

    public static void setAccentIndex(Context context, int index) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_ACCENT, index).apply();
        } catch (Throwable ignored) {
            // Non fatal: the previous palette stays active.
        }
    }

    /** Resolved accent for one palette index; 0 follows the system when possible. */
    public static int accentColor(Activity activity, boolean night, int index) {
        if (index <= 0) {
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    int id = activity.getResources().getIdentifier(
                            night ? "system_accent1_200" : "system_accent1_600",
                            "color", "android");
                    if (id != 0) {
                        int value = activity.getColor(id);
                        if (value != 0) return value;
                    }
                } catch (Throwable ignored) {
                    // fall through to the blue default
                }
            }
            return ACCENTS[0][night ? 1 : 0];
        }
        int safe = Math.min(index, ACCENTS.length - 1);
        return ACCENTS[safe][night ? 1 : 0];
    }

    /** Swatch colour shown in the picker (light-theme variant is enough). */
    public static int swatchColor(int index, boolean night) {
        return ACCENTS[Math.min(index, ACCENTS.length - 1)][night ? 1 : 0];
    }

    public int accentWithAlpha(int alpha) {
        return (accent & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /** 0 = regular, 1 = medium, 2 = bold. */
    public Typeface typeface(int weight) {
        switch (weight) {
            case 1:
                return Typeface.create("sans-serif-medium", Typeface.NORMAL);
            case 2:
                return Typeface.create("sans-serif", Typeface.BOLD);
            default:
                return Typeface.create("sans-serif", Typeface.NORMAL);
        }
    }

    public Typeface display() {
        return Typeface.create("sans-serif", Typeface.BOLD);
    }

    public static int dp(Activity activity, float value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static float sp(Activity activity, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                activity.getResources().getDisplayMetrics());
    }
}
