package com.ricohimaging.imagesync.modern;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Hand-drawn vector icon set on a 24x24 grid.
 *
 * <p>No icon font, no vector drawable resources: icons are Canvas paths so the
 * auxiliary DEX stays self-contained and every icon can be animated freely.</p>
 */
public final class Glyph extends View {
    public enum Icon {
        HOME, GALLERY, CAMERA, SETTINGS, CLOSE, CHEVRON_RIGHT, CHEVRON_LEFT,
        BACK, DOWNLOAD, SHARE, DELETE, INFO, REFRESH, WIFI, BLUETOOTH, BATTERY,
        FLASH, TIMER, FOCUS, SHUTTER, GRID, CHECK, MORE, SUN, SLIDERS, PLAY,
        RECORD, ALBUM, LINK, SYNC, PHONE, TELE, FOLDER, UPLOAD, EXPAND,
        SPARKLE, WARNING, CAMERA_FLIP, PALETTE
    }

    private static final int GRID = 24;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Icon icon;
    private int tint = Color.WHITE;
    private float strokeWidth = 1.7f;
    private boolean filled;
    private int fillAlpha = 255;
    private final Path cached = new Path();
    private boolean built;

    public Glyph(Context context, Icon icon) {
        super(context);
        this.icon = icon;
        setWillNotDraw(false);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    public Glyph setTint(int color) {
        tint = color;
        invalidate();
        return this;
    }

    public Glyph setStrokeWidthDp(float width) {
        strokeWidth = width;
        invalidate();
        return this;
    }

    /** Filled variant (used for the active bottom-navigation item). */
    public Glyph setFilled(boolean value) {
        filled = value;
        invalidate();
        return this;
    }

    public Icon icon() {
        return icon;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!built) {
            buildPath(cached, icon);
            built = true;
        }
        int size = Math.min(getWidth(), getHeight());
        if (size <= 0) return;
        float scale = size / (float) GRID;
        int save = canvas.save();
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(scale, scale);
        paint.setStrokeWidth(strokeWidth);
        if (filled) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((tint & 0x00FFFFFF) | (fillAlpha << 24));
            canvas.drawPath(cached, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(tint);
        canvas.drawPath(cached, paint);
        canvas.restoreToCount(save);
    }

    /** Builds the icon outline; shared by views, bottom bar and dialogs. */
    public static void buildPath(Path path, Icon icon) {
        path.reset();
        final float r;
        switch (icon) {
            case HOME:
                path.moveTo(3.2f, 10.6f);
                path.lineTo(12f, 3.4f);
                path.lineTo(20.8f, 10.6f);
                path.lineTo(20.8f, 20f);
                path.lineTo(14.4f, 20f);
                path.lineTo(14.4f, 14.2f);
                path.lineTo(9.6f, 14.2f);
                path.lineTo(9.6f, 20f);
                path.lineTo(3.2f, 20f);
                path.close();
                break;
            case GALLERY:
                r = 3f;
                path.addRoundRect(new RectF(3f, 4.5f, 21f, 19.5f), r, r, Path.Direction.CW);
                path.moveTo(3.6f, 16.6f);
                path.lineTo(8.6f, 11.9f);
                path.lineTo(12.6f, 15.4f);
                path.lineTo(15.6f, 12.6f);
                path.lineTo(20.4f, 17.2f);
                path.moveTo(16.3f, 8.4f);
                path.addCircle(16.3f, 8.4f, 1.55f, Path.Direction.CW);
                break;
            case CAMERA:
                path.moveTo(8.4f, 7.2f);
                path.lineTo(9.9f, 4.7f);
                path.lineTo(14.1f, 4.7f);
                path.lineTo(15.6f, 7.2f);
                r = 2.6f;
                path.addRoundRect(new RectF(2.8f, 7.2f, 21.2f, 19.6f), r, r, Path.Direction.CW);
                path.moveTo(16.1f, 13.4f);
                path.addCircle(12f, 13.4f, 4.1f, Path.Direction.CW);
                break;
            case SETTINGS: {
                // Proper cog: ring of 8 teeth around a hollow centre.
                android.graphics.Path ring = new android.graphics.Path();
                ring.addCircle(12f, 12f, 4.1f, android.graphics.Path.Direction.CW);
                path.addPath(ring);
                for (int i = 0; i < 8; i++) {
                    double angle = Math.toRadians(i * 45);
                    float cos = (float) Math.cos(angle);
                    float sin = (float) Math.sin(angle);
                    float x0 = 12f + cos * 5.6f;
                    float y0 = 12f + sin * 5.6f;
                    float x1 = 12f + cos * 8.6f;
                    float y1 = 12f + sin * 8.6f;
                    path.moveTo(x0 - sin * 1.5f, y0 + cos * 1.5f);
                    path.lineTo(x1 - sin * 1.1f, y1 + cos * 1.1f);
                    path.lineTo(x1 + sin * 1.1f, y1 - cos * 1.1f);
                    path.lineTo(x0 + sin * 1.5f, y0 - cos * 1.5f);
                    path.close();
                }
                path.addCircle(12f, 12f, 1.5f, android.graphics.Path.Direction.CW);
                break;
            }
            case CLOSE:
                path.moveTo(6f, 6f);
                path.lineTo(18f, 18f);
                path.moveTo(18f, 6f);
                path.lineTo(6f, 18f);
                break;
            case CHEVRON_RIGHT:
                path.moveTo(9.5f, 5.5f);
                path.lineTo(16f, 12f);
                path.lineTo(9.5f, 18.5f);
                break;
            case CHEVRON_LEFT:
                path.moveTo(14.5f, 5.5f);
                path.lineTo(8f, 12f);
                path.lineTo(14.5f, 18.5f);
                break;
            case BACK:
                path.moveTo(19.5f, 12f);
                path.lineTo(5f, 12f);
                path.moveTo(11f, 5.5f);
                path.lineTo(4.6f, 12f);
                path.lineTo(11f, 18.5f);
                break;
            case DOWNLOAD:
                path.moveTo(12f, 3.6f);
                path.lineTo(12f, 14.4f);
                path.moveTo(7.2f, 9.9f);
                path.lineTo(12f, 14.7f);
                path.lineTo(16.8f, 9.9f);
                path.moveTo(4.6f, 17.2f);
                path.lineTo(4.6f, 19.4f);
                path.lineTo(19.4f, 19.4f);
                path.lineTo(19.4f, 17.2f);
                break;
            case SHARE:
                path.addCircle(6.2f, 12f, 2.3f, Path.Direction.CW);
                path.addCircle(17.8f, 6.2f, 2.3f, Path.Direction.CW);
                path.addCircle(17.8f, 17.8f, 2.3f, Path.Direction.CW);
                path.moveTo(8.2f, 10.9f);
                path.lineTo(15.8f, 7.3f);
                path.moveTo(8.2f, 13.1f);
                path.lineTo(15.8f, 16.7f);
                break;
            case DELETE:
                path.moveTo(4.8f, 7.2f);
                path.lineTo(19.2f, 7.2f);
                path.moveTo(9.6f, 7.2f);
                path.lineTo(9.6f, 4.8f);
                path.lineTo(14.4f, 4.8f);
                path.lineTo(14.4f, 7.2f);
                path.moveTo(6.6f, 7.2f);
                path.lineTo(7.5f, 19.6f);
                path.lineTo(16.5f, 19.6f);
                path.lineTo(17.4f, 7.2f);
                path.moveTo(10.4f, 10.6f);
                path.lineTo(10.8f, 16.6f);
                path.moveTo(13.6f, 10.6f);
                path.lineTo(13.2f, 16.6f);
                break;
            case INFO:
                path.addCircle(12f, 12f, 8.6f, Path.Direction.CW);
                path.moveTo(12f, 11f);
                path.lineTo(12f, 16.4f);
                path.moveTo(12f, 7.8f);
                path.lineTo(12f, 8f);
                break;
            case REFRESH:
                // Circular arrow: one 280 degree arc plus a clean arrow head,
                // drawn on the 24x24 grid so it stays crisp at 18-24 dp.
                path.addArc(new RectF(4.8f, 4.8f, 19.2f, 19.2f), 40f, 280f);
                path.moveTo(17.43f, 3.25f);
                path.lineTo(17.50f, 7.37f);
                path.lineTo(13.45f, 6.59f);
                break;
            case WIFI:
                path.moveTo(4.4f, 9.6f);
                path.quadTo(12f, 3.2f, 19.6f, 9.6f);
                path.moveTo(7.2f, 13f);
                path.quadTo(12f, 8.6f, 16.8f, 13f);
                path.moveTo(9.9f, 16.4f);
                path.quadTo(12f, 14.2f, 14.1f, 16.4f);
                path.moveTo(12f, 19.4f);
                path.lineTo(12f, 19.5f);
                break;
            case BLUETOOTH:
                path.moveTo(8.4f, 7.6f);
                path.lineTo(15.6f, 16.4f);
                path.lineTo(12f, 20f);
                path.lineTo(12f, 4f);
                path.lineTo(15.6f, 7.6f);
                path.lineTo(8.4f, 16.4f);
                break;
            case BATTERY:
                path.addRoundRect(new RectF(2.8f, 7.4f, 19.2f, 16.6f), 2.4f, 2.4f, Path.Direction.CW);
                path.moveTo(21f, 10.4f);
                path.lineTo(21f, 13.6f);
                path.moveTo(5.6f, 10.2f);
                path.lineTo(5.6f, 13.8f);
                path.moveTo(8.6f, 10.2f);
                path.lineTo(8.6f, 13.8f);
                path.moveTo(11.6f, 10.2f);
                path.lineTo(11.6f, 13.8f);
                break;
            case FLASH:
                path.moveTo(13.2f, 2.8f);
                path.lineTo(6f, 13.4f);
                path.lineTo(11.4f, 13.4f);
                path.lineTo(10.4f, 21.2f);
                path.lineTo(18f, 10.4f);
                path.lineTo(12.4f, 10.4f);
                path.close();
                break;
            case TIMER:
                path.addCircle(12f, 13.4f, 7.2f, Path.Direction.CW);
                path.moveTo(12f, 9.6f);
                path.lineTo(12f, 13.6f);
                path.lineTo(14.8f, 15.2f);
                path.moveTo(9.2f, 3.2f);
                path.lineTo(14.8f, 3.2f);
                path.moveTo(12f, 3.4f);
                path.lineTo(12f, 6.2f);
                break;
            case FOCUS:
                path.moveTo(4f, 8.4f);
                path.lineTo(4f, 4f);
                path.lineTo(8.4f, 4f);
                path.moveTo(15.6f, 4f);
                path.lineTo(20f, 4f);
                path.lineTo(20f, 8.4f);
                path.moveTo(20f, 15.6f);
                path.lineTo(20f, 20f);
                path.lineTo(15.6f, 20f);
                path.moveTo(8.4f, 20f);
                path.lineTo(4f, 20f);
                path.lineTo(4f, 15.6f);
                path.moveTo(12f, 9.4f);
                path.lineTo(12f, 14.6f);
                path.moveTo(9.4f, 12f);
                path.lineTo(14.6f, 12f);
                break;
            case SHUTTER:
                path.addCircle(12f, 12f, 8.6f, Path.Direction.CW);
                path.addCircle(12f, 12f, 5.1f, Path.Direction.CW);
                break;
            case GRID:
                for (int i = 1; i <= 2; i++) {
                    float p = 4f + i * 16f / 3f;
                    path.moveTo(p, 4f);
                    path.lineTo(p, 20f);
                    path.moveTo(4f, p);
                    path.lineTo(20f, p);
                }
                path.addRect(4f, 4f, 20f, 20f, Path.Direction.CW);
                break;
            case CHECK:
                path.moveTo(5f, 12.6f);
                path.lineTo(9.8f, 17.4f);
                path.lineTo(19f, 6.8f);
                break;
            case MORE:
                path.addCircle(5.4f, 12f, 1.7f, Path.Direction.CW);
                path.addCircle(12f, 12f, 1.7f, Path.Direction.CW);
                path.addCircle(18.6f, 12f, 1.7f, Path.Direction.CW);
                break;
            case SUN:
                path.addCircle(12f, 12f, 4.4f, Path.Direction.CW);
                for (int i = 0; i < 8; i++) {
                    double angle = Math.PI * i / 4d;
                    float cx = (float) Math.cos(angle);
                    float cy = (float) Math.sin(angle);
                    path.moveTo(12 + cx * 7f, 12 + cy * 7f);
                    path.lineTo(12 + cx * 9.4f, 12 + cy * 9.4f);
                }
                break;
            case SLIDERS:
                path.moveTo(4.6f, 7.4f);
                path.lineTo(19.4f, 7.4f);
                path.moveTo(4.6f, 16.6f);
                path.lineTo(19.4f, 16.6f);
                path.addCircle(9.2f, 7.4f, 2.4f, Path.Direction.CW);
                path.addCircle(15.2f, 16.6f, 2.4f, Path.Direction.CW);
                break;
            case PLAY:
                path.moveTo(8.2f, 5.4f);
                path.lineTo(18.6f, 12f);
                path.lineTo(8.2f, 18.6f);
                path.close();
                break;
            case RECORD:
                path.addCircle(12f, 12f, 6.2f, Path.Direction.CW);
                break;
            case ALBUM:
                path.addRoundRect(new RectF(7f, 3.4f, 21f, 14.4f), 2.2f, 2.2f, Path.Direction.CW);
                path.addRoundRect(new RectF(3f, 9.6f, 17f, 20.6f), 2.2f, 2.2f, Path.Direction.CW);
                break;
            case LINK:
                path.moveTo(9.6f, 14.4f);
                path.lineTo(14.4f, 9.6f);
                path.moveTo(10.6f, 6.6f);
                path.lineTo(12.4f, 4.8f);
                path.addArc(new RectF(8.6f, 8.6f, 19.4f, 15.4f), 200f, 200f);
                path.moveTo(4.6f, 15.4f);
                path.addArc(new RectF(4.6f, 8.6f, 15.4f, 15.4f), -20f, -200f);
                break;
            case SYNC:
                path.moveTo(18.6f, 8.4f);
                path.lineTo(18.6f, 12.4f);
                path.lineTo(14.6f, 12.4f);
                path.moveTo(18.6f, 12.2f);
                path.addArc(new RectF(5.4f, 4.6f, 20.6f, 19.8f), -70f, -160f);
                path.moveTo(5.4f, 15.6f);
                path.lineTo(5.4f, 11.6f);
                path.lineTo(9.4f, 11.6f);
                path.moveTo(5.4f, 11.8f);
                path.addArc(new RectF(3.4f, 4.2f, 18.6f, 19.4f), 110f, -160f);
                break;
            case PHONE:
                path.addRoundRect(new RectF(6.4f, 2.6f, 17.6f, 21.4f), 3f, 3f, Path.Direction.CW);
                path.moveTo(10.4f, 18.4f);
                path.lineTo(13.6f, 18.4f);
                break;
            case TELE:
                path.moveTo(4f, 8f);
                path.lineTo(20f, 8f);
                path.lineTo(20f, 19f);
                path.lineTo(4f, 19f);
                path.close();
                path.moveTo(8f, 8f);
                path.lineTo(10f, 4.4f);
                path.lineTo(14f, 4.4f);
                path.lineTo(16f, 8f);
                break;
            case FOLDER:
                path.moveTo(3.4f, 7.2f);
                path.lineTo(10f, 7.2f);
                path.lineTo(11.8f, 9.4f);
                path.lineTo(20.6f, 9.4f);
                path.lineTo(20.6f, 18.6f);
                path.lineTo(3.4f, 18.6f);
                path.close();
                break;
            case UPLOAD:
                path.moveTo(12f, 20.4f);
                path.lineTo(12f, 9.6f);
                path.moveTo(7.2f, 14.4f);
                path.lineTo(12f, 9.6f);
                path.lineTo(16.8f, 14.4f);
                path.moveTo(4.6f, 6.8f);
                path.lineTo(4.6f, 4.6f);
                path.lineTo(19.4f, 4.6f);
                path.lineTo(19.4f, 6.8f);
                break;
            case EXPAND:
                path.moveTo(9f, 4f);
                path.lineTo(4f, 4f);
                path.lineTo(4f, 9f);
                path.moveTo(15f, 4f);
                path.lineTo(20f, 4f);
                path.lineTo(20f, 9f);
                path.moveTo(20f, 15f);
                path.lineTo(20f, 20f);
                path.lineTo(15f, 20f);
                path.moveTo(9f, 20f);
                path.lineTo(4f, 20f);
                path.lineTo(4f, 15f);
                break;
            case PALETTE:
                path.addCircle(12f, 12f, 8.4f, Path.Direction.CW);
                path.addCircle(8.6f, 10.2f, 1.25f, Path.Direction.CW);
                path.addCircle(12f, 7.6f, 1.25f, Path.Direction.CW);
                path.addCircle(15.4f, 10.2f, 1.25f, Path.Direction.CW);
                break;
            case SPARKLE:
                path.moveTo(12f, 3.2f);
                path.lineTo(13.9f, 9.4f);
                path.lineTo(20.4f, 11.4f);
                path.lineTo(13.9f, 13.4f);
                path.lineTo(12f, 19.8f);
                path.lineTo(10.1f, 13.4f);
                path.lineTo(3.6f, 11.4f);
                path.lineTo(10.1f, 9.4f);
                path.close();
                break;
            case WARNING:
                path.moveTo(12f, 3.6f);
                path.lineTo(21.4f, 19.6f);
                path.lineTo(2.6f, 19.6f);
                path.close();
                path.moveTo(12f, 9f);
                path.lineTo(12f, 14f);
                path.moveTo(12f, 16.6f);
                path.lineTo(12f, 16.8f);
                break;
            case CAMERA_FLIP:
                path.moveTo(3.4f, 9.4f);
                path.lineTo(3.4f, 6.4f);
                path.lineTo(20.6f, 6.4f);
                path.lineTo(20.6f, 9.4f);
                path.moveTo(7f, 9.4f);
                path.lineTo(7f, 19.4f);
                path.lineTo(17f, 19.4f);
                path.lineTo(17f, 9.4f);
                path.moveTo(10.2f, 13.6f);
                path.lineTo(13.8f, 13.6f);
                path.lineTo(13.8f, 16.4f);
                break;
            default:
                path.addCircle(12f, 12f, 9f, Path.Direction.CW);
                break;
        }
    }
}
