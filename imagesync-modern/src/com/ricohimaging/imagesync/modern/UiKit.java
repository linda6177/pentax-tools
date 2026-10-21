package com.ricohimaging.imagesync.modern;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small framework-only widget kit shared by every rebuilt screen.
 *
 * <p>Deliberately no AppCompat / Material dependency: the host APK already
 * contains those libraries but the modern UI ships as an isolated auxiliary
 * DEX, so it only relies on the Android platform and a handful of animated
 * custom views.</p>
 */
public final class UiKit {
    private UiKit() {}

    public static final DecelerateInterpolator DECELERATE = new DecelerateInterpolator(1.7f);
    public static final DecelerateInterpolator DECELERATE_SOFT = new DecelerateInterpolator(1.2f);
    public static final OvershootInterpolator OVERSHOOT = new OvershootInterpolator(1.05f);

    // ---------------------------------------------------------------- sizing
    public static int dp(Activity a, float value) {
        return (int) (value * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static float sp(Activity a, float value) {
        return value * a.getResources().getDisplayMetrics().scaledDensity;
    }

    // ------------------------------------------------------------- drawables
    public static GradientDrawable rounded(Activity a, int color, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(a, radiusDp));
        return shape;
    }

    public static GradientDrawable stroke(Activity a, int color, float radiusDp, int strokeColor) {
        GradientDrawable shape = rounded(a, color, radiusDp);
        shape.setStroke(Math.max(1, dp(a, 1)), strokeColor);
        return shape;
    }

    public static GradientDrawable gradient(Activity a, int top, int bottom, float radiusDp) {
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{top, bottom});
        shape.setCornerRadius(dp(a, radiusDp));
        return shape;
    }

    /** Ripple over a rounded surface; falls back to the plain shape on old APIs. */
    public static Drawable pressable(Activity a, int fill, float radiusDp, int rippleColor) {
        GradientDrawable content = rounded(a, fill, radiusDp);
        if (Build.VERSION.SDK_INT >= 21) {
            return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
        }
        return content;
    }

    public static RippleDrawable circularRipple(Activity a, int color) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(color),
                new GradientDrawable(), mask);
        return ripple;
    }

    /** Clips children to a rounded rect so image cards get true rounded corners. */
    public static void roundClip(Activity a, View view, float radiusDp) {
        view.setClipToOutline(true);
        view.setBackground(rounded(a, Color.TRANSPARENT, radiusDp));
    }

    // ---------------------------------------------------------------- labels
    public static TextView label(Activity a, Theme t, String value, float sizeSp,
                                 int color, int weight) {
        TextView text = new TextView(a);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        text.setIncludeFontPadding(false);
        text.setTypeface(weight == 2 ? t.display() : t.typeface(weight));
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    public static TextView title(Activity a, Theme t, String value, float sizeSp) {
        TextView text = label(a, t, value, sizeSp, t.text, 2);
        text.setLetterSpacing(-0.015f);
        return text;
    }

    public static TextView overline(Activity a, Theme t, String value, int color) {
        TextView text = label(a, t, value, 11.5f, color, 2);
        text.setLetterSpacing(0.14f);
        text.setAllCaps(true);
        return text;
    }

    public static TextView body(Activity a, Theme t, String value, float sizeSp, int color) {
        TextView text = label(a, t, value, sizeSp, color, 0);
        text.setLineSpacing(dp(a, 3), 1.02f);
        return text;
    }

    // ------------------------------------------------------------- containers
    public static LinearLayout column(Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    public static LinearLayout row(Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        return box;
    }

    public static View spacer(Activity a, float heightDp) {
        View view = new View(a);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(a, heightDp)));
        return view;
    }

    public static View hspacer(Activity a, float widthDp) {
        View view = new View(a);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(a, widthDp), 1));
        return view;
    }

    public static void addSpacer(LinearLayout parent, Activity a, float heightDp) {
        parent.addView(spacer(a, heightDp));
    }

    public static FrameLayout card(Activity a, Theme t, float radiusDp) {
        FrameLayout card = new FrameLayout(a);
        card.setBackground(rounded(a, t.surface, radiusDp));
        if (Build.VERSION.SDK_INT >= 21) card.setElevation(dp(a, 0.5f));
        return card;
    }

    public static View hairline(Activity a, Theme t) {
        View line = new View(a);
        line.setBackgroundColor(t.hairline);
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(a, 0.7f))));
        return line;
    }

    // ---------------------------------------------------------------- buttons
    public static TextView primaryButton(Activity a, Theme t, String text) {
        TextView button = label(a, t, text, 15, t.onAccent, 2);
        button.setGravity(Gravity.CENTER);
        button.setLetterSpacing(0.01f);
        button.setBackground(pressable(a, t.accent, 18, 0x33FFFFFF));
        button.setClickable(true);
        button.setFocusable(true);
        button.setMinHeight(dp(a, 52));
        button.setPadding(dp(a, 22), dp(a, 15), dp(a, 22), dp(a, 15));
        pressFeedback(button);
        return button;
    }

    public static TextView tonalButton(Activity a, Theme t, String text) {
        TextView button = label(a, t, text, 15, t.accent, 1);
        button.setGravity(Gravity.CENTER);
        button.setBackground(pressable(a, t.accentSoft, 18, t.accentWithAlpha(0x33)));
        button.setClickable(true);
        button.setFocusable(true);
        button.setMinHeight(dp(a, 50));
        button.setPadding(dp(a, 20), dp(a, 14), dp(a, 20), dp(a, 14));
        pressFeedback(button);
        return button;
    }

    public static TextView textButton(Activity a, Theme t, String text) {
        TextView button = label(a, t, text, 14, t.muted, 1);
        button.setGravity(Gravity.CENTER);
        button.setBackground(circularRipple(a, t.hairline));
        button.setClickable(true);
        button.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10));
        pressFeedback(button);
        return button;
    }

    public static FrameLayout iconButton(Activity a, Theme t, Glyph.Icon icon, int tint,
                                         final Runnable action) {
        FrameLayout holder = new FrameLayout(a);
        int size = dp(a, 46);
        holder.setLayoutParams(new FrameLayout.LayoutParams(size, size));
        holder.setBackground(circularRipple(a, t.hairline));
        holder.setClickable(true);
        holder.setFocusable(true);
        Glyph glyph = new Glyph(a, icon);
        glyph.setTint(tint);
        int iconSize = dp(a, 24);
        FrameLayout.LayoutParams glyphParams = new FrameLayout.LayoutParams(iconSize, iconSize);
        glyphParams.gravity = Gravity.CENTER;
        holder.addView(glyph, glyphParams);
        holder.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            action.run();
        });
        pressFeedback(holder);
        return holder;
    }

    // -------------------------------------------------------------- feedback
    /** Subtle spring-like press scale without allocating on every touch. */
    public static void pressFeedback(final View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.animate().scaleX(0.965f).scaleY(0.965f)
                                .setDuration(90).setInterpolator(DECELERATE).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.animate().scaleX(1f).scaleY(1f)
                                .setDuration(240).setInterpolator(OVERSHOOT).start();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    // ------------------------------------------------------------ animations
    public static void enterFromBottom(View view, int delayMs) {
        view.setAlpha(0f);
        view.setTranslationY(dp((Activity) view.getContext(), 18));
        view.setScaleX(0.985f);
        view.setScaleY(0.985f);
        view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(delayMs).setDuration(320)
                .setInterpolator(DECELERATE_SOFT).start();
    }

    public static void enterFade(View view, int delayMs) {
        view.setAlpha(0f);
        view.animate().alpha(1f).setStartDelay(delayMs).setDuration(260)
                .setInterpolator(DECELERATE).start();
    }

    public static void staggerChildren(ViewGroup group, int startDelayMs, int stepMs) {
        for (int i = 0; i < group.getChildCount(); i++) {
            enterFromBottom(group.getChildAt(i), startDelayMs + i * stepMs);
        }
    }

    public static void setVisibleAnimated(View view, boolean visible, int durationMs) {
        if (visible) {
            if (view.getVisibility() != View.VISIBLE) {
                view.setAlpha(0f);
                view.setVisibility(View.VISIBLE);
                view.animate().alpha(1f).setDuration(durationMs).start();
            }
        } else if (view.getVisibility() == View.VISIBLE) {
            view.animate().alpha(0f).setDuration(durationMs)
                    .withEndAction(() -> view.setVisibility(View.GONE)).start();
        }
    }

    // ----------------------------------------------------------------- misc
    public static void haptic(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    public static void hapticTick(View view) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    /** Draws a soft camera-style framing overlay used by the remote screen. */
    public static void drawFocusReticle(Canvas canvas, Paint paint, float cx, float cy, float size) {
        float half = size / 2f;
        float arm = size * 0.26f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, size * 0.022f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        Path path = new Path();
        path.moveTo(cx - half, cy - half + arm);
        path.lineTo(cx - half, cy - half);
        path.lineTo(cx - half + arm, cy - half);
        path.moveTo(cx + half - arm, cy - half);
        path.lineTo(cx + half, cy - half);
        path.lineTo(cx + half, cy - half + arm);
        path.moveTo(cx + half, cy + half - arm);
        path.lineTo(cx + half, cy + half);
        path.lineTo(cx + half - arm, cy + half);
        path.moveTo(cx - half + arm, cy + half);
        path.lineTo(cx - half, cy + half);
        path.lineTo(cx - half, cy + half - arm);
        canvas.drawPath(path, paint);
    }

    /** Rounded bitmap helper for avatars / thumbnails without extra libraries. */
    public static Bitmap roundBitmap(Bitmap source, float radiusPx) {
        if (source == null) return null;
        int width = source.getWidth();
        int height = source.getHeight();
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF rect = new RectF(0, 0, width, height);
        Path clip = new Path();
        clip.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW);
        canvas.drawPath(clip, paint);
        paint.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(source, 0, 0, paint);
        return output;
    }
}
