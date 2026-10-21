package com.ricohimaging.imagesync.modern;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.animation.ValueAnimator;

/**
 * Floating navigation bar with a label-free, indicator-free selection style:
 * the active icon simply becomes accent-coloured and grows slightly, matching
 * the rest of the rebuilt UI.
 */
public final class BottomBar extends View {

    public interface Listener {
        void onItemSelected(int index);
    }

    private final Theme theme;
    private final String[] labels;
    private final Glyph.Icon[] icons;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private int selected;
    private float[] active = new float[0];
    private Listener listener;
    private int pressed = -1;
    private float pressGlow;
    private ValueAnimator animator;

    public BottomBar(Context context, Theme theme, String[] labels, Glyph.Icon[] icons) {
        super(context);
        this.theme = theme;
        this.labels = labels;
        this.icons = icons;
        this.active = new float[labels.length];
        active[0] = 1f;
        setClickable(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public int selectedIndex() {
        return selected;
    }

    public void setSelected(int index, boolean animate) {
        if (index < 0 || index >= icons.length) return;
        if (!animate) {
            selected = index;
            for (int i = 0; i < active.length; i++) active[i] = i == index ? 1f : 0f;
            invalidate();
            return;
        }
        final float[] startActive = active.clone();
        final float[] endActive = new float[active.length];
        endActive[index] = 1f;
        selected = index;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(320);
        animator.setInterpolator(new DecelerateInterpolator(1.8f));
        animator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            for (int i = 0; i < active.length; i++) {
                active[i] = startActive[i] + (endActive[i] - startActive[i]) * f;
            }
            invalidate();
        });
        animator.start();
    }

    private float centerOf(int index) {
        if (icons.length == 0) return 0f;
        return getWidth() * (index + 0.5f) / icons.length;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float barTop = dp(4f);
        float radius = dp(26f);
        rect.set(0, barTop, getWidth(), getHeight());
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(theme.surface);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f));
        paint.setColor(theme.hairline);
        rect.set(dp(0.5f), barTop + dp(0.5f), getWidth() - dp(0.5f), getHeight() - dp(0.5f));
        canvas.drawRoundRect(rect, radius, radius, paint);

        float iconCenterY = barTop + (getHeight() - barTop) / 2f;

        // Touch highlight only (no persistent bubble).
        if (pressed >= 0 && pressGlow > 0f) {
            float cx = centerOf(pressed);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(theme.accentWithAlpha((int) (22 * pressGlow)));
            rect.set(cx - dp(26f), iconCenterY - dp(26f),
                    cx + dp(26f), iconCenterY + dp(26f));
            canvas.drawRoundRect(rect, dp(26f), dp(26f), paint);
        }

        for (int i = 0; i < icons.length; i++) {
            float fraction = active[i];
            float cx = centerOf(i);
            int tint = blend(theme.muted, theme.accent, fraction);
            float scale = 1f + 0.16f * fraction;

            Glyph.buildPath(path, icons[i]);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.85f + 0.15f * fraction);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(tint);

            canvas.save();
            canvas.translate(cx, iconCenterY);
            canvas.scale(scale, scale);
            float unit = dp(26f) / 24f;
            canvas.scale(unit, unit);
            canvas.translate(-12f, -12f);
            canvas.drawPath(path, paint);
            canvas.restore();
        }
    }

    private int blend(int from, int to, float fraction) {
        float f = Math.max(0f, Math.min(1f, fraction));
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * f);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * f);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f);
        return Color.argb(a, r, g, b);
    }

    private int hitTest(float x) {
        if (icons.length == 0) return -1;
        int index = (int) (x / getWidth() * icons.length);
        return Math.max(0, Math.min(icons.length - 1, index));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hitTest(event.getX());
                pressGlow = 0f;
                ValueAnimator glow = ValueAnimator.ofFloat(0f, 1f);
                glow.setDuration(120);
                glow.addUpdateListener(a -> {
                    pressGlow = (float) a.getAnimatedValue();
                    invalidate();
                });
                glow.start();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pressed != hitTest(event.getX())) {
                    pressed = hitTest(event.getX());
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                int target = hitTest(event.getX());
                boolean inside = event.getActionMasked() == MotionEvent.ACTION_UP
                        && pressed >= 0 && target == pressed;
                int previous = pressed;
                pressed = -1;
                pressGlow = 0f;
                invalidate();
                if (inside && previous >= 0) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                    if (previous != selected) setSelected(previous, true);
                    if (listener != null) listener.onItemSelected(previous);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }
}
