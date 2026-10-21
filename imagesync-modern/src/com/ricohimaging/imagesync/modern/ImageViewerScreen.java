package com.ricohimaging.imagesync.modern;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.ricoh.camera.sdk.wireless.api.CameraImage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen image viewer overlay.
 *
 * <p>Works for both the camera album ({@link CameraImage}) and the local
 * MediaStore album ({@link LocalMedia}). The displayed bitmap lives on a single
 * {@link ImageView} with {@link ImageView.ScaleType#MATRIX}: one {@link Matrix}
 * owns fit, zoom and pan so view properties and matrix maths never fight each
 * other. A focal-point-preserving {@link ScaleGestureDetector} clamps the zoom to
 * [1x, 4x] of the fitted base scale and a double tap toggles between fit and
 * 2.5x around the tap point (both directions). Horizontal swipes page with a
 * parallax ghost layer, vertical drags dismiss while at fit scale.</p>
 *
 * <p>Camera frames use the bridge's fast compressed preview first and sharpen in
 * the background; the full file is only downloaded for save/share. Camera
 * disconnecting only ever produces a retry state, never a crash.</p>
 */
public final class ImageViewerScreen extends Screen {

    private static final float MAX_SCALE = 4f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;
    private static final float ZOOMED_EPSILON = 1.02f;

    private final List<CameraImage> cameraImages;
    private final List<LocalMedia> localItems;
    private final boolean localMode;
    private int index;

    private FrameLayout root;
    private FrameLayout stage;
    private ImageView current;
    private ImageView ghost;
    private FrameLayout loadingOverlay;
    private LinearLayout errorView;
    private TextView errorLabel;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private TextView indexLabel;
    private TextView nameLabel;
    private TextView infoLabel;
    private TextView rawHint;
    private FrameLayout downloadButton;

    // Decoded image state.
    private Bitmap originalBitmap;
    private boolean originalOwned;
    // The neighbour thumbnail currently painted on the ghost layer, and whether
    // this viewer owns it (newly decoded) or it belongs to the shared cache.
    private Bitmap ghostBitmap;
    private boolean ghostOwned;

    private boolean destroyed;
    private boolean closing;
    private boolean pageAnimating;
    private boolean chromeVisible = true;
    private boolean downloading;
    private int loadToken;
    private int ghostToken;
    private int ghostIndex = Integer.MIN_VALUE;

    // One matrix owns fit/zoom/pan; never mixed with view scaleX/scaleY.
    private final Matrix imageMatrix = new Matrix();
    private final float[] matrixValues = new float[9];
    private float baseScale = 1f;
    private boolean gestureInProgress;

    // Gesture state.
    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleDetector;
    private VelocityTracker velocity;
    private int touchSlop;
    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private float lastFocusX;
    private float lastFocusY;
    private float dragDx;
    private float dragDy;
    private boolean gestureDecided;
    private boolean dragging;
    private boolean horizontalDrag;
    private boolean dismissDrag;
    private long lastDoubleTapAt;
    private ValueAnimator zoomAnimator;

    private long lastSnackAt;
    private boolean windowStyled;
    private int savedStatusBarColor;
    private int savedNavigationBarColor;
    private int savedUiFlags;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    public ImageViewerScreen(ShellActivity host, List<CameraImage> images, int index) {
        super(host);
        this.cameraImages = images == null ? new ArrayList<>() : images;
        this.localItems = new ArrayList<>();
        this.localMode = false;
        this.index = clampIndex(index, this.cameraImages.size());
    }

    public ImageViewerScreen(ShellActivity host, List<LocalMedia> items, int index,
                             boolean local) {
        super(host);
        this.cameraImages = new ArrayList<>();
        this.localItems = items == null ? new ArrayList<>() : items;
        this.localMode = true;
        this.index = clampIndex(index, this.localItems.size());
    }

    private static int clampIndex(int value, int count) {
        if (count <= 0) return 0;
        return Math.max(0, Math.min(count - 1, value));
    }

    private int count() {
        return localMode ? localItems.size() : cameraImages.size();
    }

    // =====================================================================
    // Construction
    // =====================================================================
    @Override
    protected View onCreateView() {
        touchSlop = ViewConfiguration.get(activity()).getScaledTouchSlop();

        root = new FrameLayout(activity());
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(true);

        stage = new FrameLayout(activity());
        stage.setClipChildren(true);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));

        ghost = new ImageView(activity());
        ghost.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ghost.setVisibility(View.INVISIBLE);
        ghost.setAlpha(0.92f);
        stage.addView(ghost, new FrameLayout.LayoutParams(-1, -1));

        current = new ImageView(activity());
        current.setScaleType(ImageView.ScaleType.MATRIX);
        current.setAlpha(1f);
        stage.addView(current, new FrameLayout.LayoutParams(-1, -1));

        buildLoading();
        buildError();
        buildTopBar();
        buildBottomBar();

        stage.addOnLayoutChangeListener((view, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) -> {
            if (destroyed || gestureInProgress || zoomAnimator != null) return;
            fitMatrix();
        });
        setupGestures();

        updateChromeText();
        loadCurrent(false);
        return root;
    }

    private void buildLoading() {
        loadingOverlay = new FrameLayout(activity());
        LinearLayout column = UiKit.column(activity());
        column.setGravity(Gravity.CENTER);
        ProgressBar spinner = new ProgressBar(activity(), null,
                android.R.attr.progressBarStyle);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
        column.addView(spinner, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView label = UiKit.label(activity(), t, "正在载入…", 12.5f, 0xCCFFFFFF, 0);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
        labelParams.topMargin = dp(12);
        column.addView(label, labelParams);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2);
        params.gravity = Gravity.CENTER;
        loadingOverlay.addView(column, params);
        loadingOverlay.setAlpha(0f);
        loadingOverlay.setVisibility(View.GONE);
        root.addView(loadingOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private void buildError() {
        errorView = UiKit.column(activity());
        errorView.setGravity(Gravity.CENTER);
        errorView.setPadding(dp(30), dp(20), dp(30), dp(2));
        Glyph warning = new Glyph(activity(), Glyph.Icon.WARNING);
        warning.setTint(0xFFFFB4AB);
        LinearLayout.LayoutParams glyphParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        glyphParams.bottomMargin = dp(14);
        errorView.addView(warning, glyphParams);
        errorLabel = UiKit.label(activity(), t, "无法加载预览", 14, 0xCCFFFFFF, 1);
        errorLabel.setGravity(Gravity.CENTER);
        errorView.addView(errorLabel);
        TextView retry = UiKit.textButton(activity(), t, "重试");
        retry.setTextColor(Color.WHITE);
        retry.setBackground(UiKit.rounded(activity(), 0x33FFFFFF, 18));
        retry.setPadding(dp(22), dp(11), dp(22), dp(11));
        retry.setOnClickListener(v -> {
            UiKit.hapticTick(v);
            hideError();
            loadCurrent(false);
        });
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-2, -2);
        retryParams.topMargin = dp(16);
        retryParams.gravity = Gravity.CENTER_HORIZONTAL;
        errorView.addView(retry, retryParams);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2);
        params.gravity = Gravity.CENTER;
        errorView.setVisibility(View.GONE);
        root.addView(errorView, params);
    }

    private void buildTopBar() {
        topBar = UiKit.row(activity());
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(6), dp(8), dp(6), dp(16));
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x00000000});
        topBar.setBackground(gradient);
        topBar.setAlpha(0f);
        topBar.setTranslationY(-dp(10));

        topBar.addView(chromeButton(Glyph.Icon.CLOSE, this::closeViewer),
                new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout titles = UiKit.column(activity());
        titles.setPadding(dp(8), 0, dp(6), 0);
        indexLabel = UiKit.label(activity(), t, "", 11.5f, 0xB3FFFFFF, 0);
        indexLabel.setLetterSpacing(0.04f);
        titles.addView(indexLabel);
        nameLabel = UiKit.label(activity(), t, "", 14.5f, Color.WHITE, 2);
        nameLabel.setSingleLine(true);
        nameLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, -2);
        nameParams.topMargin = dp(2);
        titles.addView(nameLabel, nameParams);
        topBar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        FrameLayout shareButton = chromeButton(Glyph.Icon.SHARE, this::startShare);
        topBar.addView(shareButton, new LinearLayout.LayoutParams(dp(46), dp(46)));
        downloadButton = chromeButton(Glyph.Icon.DOWNLOAD, this::startDownload);
        topBar.addView(downloadButton, new LinearLayout.LayoutParams(dp(46), dp(46)));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2);
        params.gravity = Gravity.TOP;
        root.addView(topBar, params);
    }

    private void buildBottomBar() {
        bottomBar = UiKit.column(activity());
        bottomBar.setGravity(Gravity.CENTER_HORIZONTAL);
        bottomBar.setPadding(dp(20), dp(18), dp(20), dp(26));
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x00000000});
        bottomBar.setBackground(gradient);
        bottomBar.setAlpha(0f);
        bottomBar.setTranslationY(dp(10));

        infoLabel = UiKit.label(activity(), t, "", 12, 0xCCFFFFFF, 0);
        infoLabel.setGravity(Gravity.CENTER);
        bottomBar.addView(infoLabel);

        rawHint = UiKit.label(activity(), t, "RAW · 内嵌 JPEG 预览", 10.5f, t.accent, 2);
        rawHint.setGravity(Gravity.CENTER);
        rawHint.setLetterSpacing(0.04f);
        rawHint.setBackground(UiKit.rounded(activity(), t.accentSoft, 100));
        rawHint.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams rawParams = new LinearLayout.LayoutParams(-2, -2);
        rawParams.topMargin = dp(6);
        rawParams.gravity = Gravity.CENTER_HORIZONTAL;
        rawHint.setVisibility(View.GONE);
        bottomBar.addView(rawHint, rawParams);

        TextView hint = UiKit.label(activity(), t, "双指缩放 · 双击放大 · 下滑关闭",
                11, 0x80FFFFFF, 0);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-2, -2);
        hintParams.topMargin = dp(7);
        bottomBar.addView(hint, hintParams);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2);
        params.gravity = Gravity.BOTTOM;
        root.addView(bottomBar, params);
    }

    private FrameLayout chromeButton(Glyph.Icon icon, final Runnable action) {
        FrameLayout holder = new FrameLayout(activity());
        holder.setBackground(UiKit.circularRipple(activity(), 0x33FFFFFF));
        holder.setClickable(true);
        holder.setFocusable(true);
        Glyph glyph = new Glyph(activity(), icon);
        glyph.setTint(Color.WHITE);
        FrameLayout.LayoutParams glyphParams = new FrameLayout.LayoutParams(dp(23), dp(23));
        glyphParams.gravity = Gravity.CENTER;
        holder.addView(glyph, glyphParams);
        holder.setOnClickListener(v -> {
            UiKit.hapticTick(v);
            action.run();
        });
        UiKit.pressFeedback(holder);
        return holder;
    }

    // =====================================================================
    // Preview loading
    // =====================================================================
    private int previewTargetPx() {
        android.util.DisplayMetrics metrics = activity().getResources().getDisplayMetrics();
        int longest = Math.max(metrics.widthPixels, metrics.heightPixels);
        return Math.max(1280, Math.min(1920, longest));
    }

    private void loadCurrent(boolean fadeIn) {
        if (destroyed) return;
        final int token = ++loadToken;
        hideError();
        if (originalBitmap == null) showLoading(true);
        resetPageDrag();
        updateChromeText();

        if (localMode) {
            final LocalMedia item = currentLocal();
            if (item == null) {
                showLoading(false);
                showError("照片不可用");
                return;
            }
            final Context context = activity().getApplicationContext();
            final int target = previewTargetPx();
            final boolean raw = item.isRaw();
            worker.execute(() -> {
                final Bitmap bitmap = LocalMedia.decodePreview(context, item, target);
                main.post(() -> {
                    if (destroyed || token != loadToken) {
                        recycleQuietly(bitmap);
                        return;
                    }
                    if (bitmap == null) {
                        showLoading(false);
                        if (originalBitmap == null) {
                            showError(raw ? "无法解析 RAW 内嵌预览"
                                    : "无法加载这张照片");
                        } else {
                            snack(raw ? "RAW 内嵌预览不可用" : "无法加载高清预览", false);
                        }
                        return;
                    }
                    setLoadedBitmap(bitmap, fadeIn);
                });
            });
            return;
        }

        final CameraImage image = currentCamera();
        if (image == null) {
            showLoading(false);
            showError("照片不可用");
            return;
        }
        loadCameraFastFrame(image, token, fadeIn);
    }

    /**
     * Camera first frame: the bridge returns the camera's own compressed JPEG
     * (extra-small rendition or thumbnail) which is far faster than pulling view
     * data. A second compressed view-data pass sharpens the image in the
     * background; the original file is only downloaded for save/share.
     */
    private void loadCameraFastFrame(final CameraImage image, final int token,
                                     final boolean fadeIn) {
        try {
            host.bridge().loadPreview(image, new CameraBridge.PreviewCallback() {
                @Override
                public void onPreview(Bitmap bitmap, String message) {
                    if (destroyed || token != loadToken) {
                        recycleQuietly(bitmap);
                        return;
                    }
                    if (bitmap == null) {
                        loadCameraDetailedPreview(image, token, fadeIn);
                        return;
                    }
                    Bitmap oriented = CameraBridge.orient(bitmap, image);
                    setLoadedBitmap(oriented, fadeIn);
                    if (oriented != null && oriented.getWidth() < 900) {
                        upgradeCameraPreview(image, token);
                    }
                }
            });
        } catch (Throwable error) {
            loadCameraDetailedPreview(image, token, fadeIn);
        }
    }

    /** Background fill upgrade using the compressed VGA view-data still. */
    private void upgradeCameraPreview(final CameraImage image, final int token) {
        try {
            host.bridge().loadPreview(image, previewTargetPx(), new CameraBridge.BitmapCallback() {
                @Override
                public void onBitmap(Bitmap bitmap) {
                    if (destroyed || token != loadToken || bitmap == null) {
                        recycleQuietly(bitmap);
                        return;
                    }
                    if (gestureInProgress || zoomAnimator != null) {
                        // Never swap the bitmap under an active gesture/zoom; the
                        // fast frame is already good.
                        recycleQuietly(bitmap);
                        return;
                    }
                    Bitmap displayed = originalBitmap;
                    if (displayed != null && bitmap.getWidth() <= displayed.getWidth()
                            && bitmap.getHeight() <= displayed.getHeight()) {
                        recycleQuietly(bitmap);
                        return;
                    }
                    setLoadedBitmap(bitmap, true);
                }

                @Override
                public void onError(String message) {
                    // The fast first frame is already visible; keep it.
                }
            });
        } catch (Throwable ignored) {
            // Keep the fast first frame.
        }
    }

    private void loadCameraDetailedPreview(final CameraImage image, final int token,
                                           final boolean fadeIn) {
        try {
            host.bridge().loadPreview(image, previewTargetPx(), new CameraBridge.BitmapCallback() {
                @Override
                public void onBitmap(Bitmap bitmap) {
                    if (destroyed || token != loadToken) {
                        recycleQuietly(bitmap);
                        return;
                    }
                    if (bitmap == null) {
                        showLoading(false);
                        if (originalBitmap == null) showError("无法加载预览");
                        return;
                    }
                    setLoadedBitmap(bitmap, fadeIn);
                }

                @Override
                public void onError(String message) {
                    if (destroyed || token != loadToken) return;
                    showLoading(false);
                    boolean disconnected = host.bridge().state()
                            != CameraBridge.State.CONNECTED;
                    if (originalBitmap == null) {
                        showError(disconnected ? "相机已断开连接" : "无法加载预览");
                    } else {
                        snack(disconnected ? "相机已断开连接" : "无法加载高清预览", false);
                    }
                }
            });
        } catch (Throwable error) {
            showLoading(false);
            if (originalBitmap == null) showError("无法加载预览");
        }
    }

    /** Installs a freshly decoded original (drops the previous one safely). */
    private void setLoadedBitmap(Bitmap bitmap, boolean fadeIn) {
        showLoading(false);
        replaceOriginal(bitmap, true, fadeIn);
    }

    private void replaceOriginal(Bitmap bitmap, boolean owned, boolean fadeIn) {
        Bitmap previousOriginal = originalBitmap;
        boolean previousOwned = originalOwned;
        originalBitmap = bitmap;
        originalOwned = owned;
        cancelZoomAnimator();
        // A new image invalidates any in-flight gesture against the old matrix.
        gestureInProgress = false;

        current.animate().cancel();
        current.setImageBitmap(bitmap);
        fitMatrix();
        if (previousOriginal != null && previousOwned && previousOriginal != bitmap
                && !previousOriginal.isRecycled()) {
            previousOriginal.recycle();
        }
        if (fadeIn && bitmap != null) {
            current.setAlpha(0f);
            current.animate().alpha(1f).setDuration(220)
                    .setInterpolator(UiKit.DECELERATE).start();
        } else {
            current.setAlpha(1f);
        }
        updateChromeText();
    }

    private void showLoading(boolean show) {
        if (show) {
            loadingOverlay.animate().cancel();
            loadingOverlay.setVisibility(View.VISIBLE);
            loadingOverlay.animate().alpha(1f).setDuration(180).start();
        } else {
            loadingOverlay.animate().cancel();
            loadingOverlay.animate().alpha(0f).setDuration(160)
                    .withEndAction(() -> {
                        if (loadingOverlay.getAlpha() < 0.05f) {
                            loadingOverlay.setVisibility(View.GONE);
                        }
                    }).start();
        }
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorView.animate().cancel();
        errorView.setVisibility(View.VISIBLE);
        errorView.setAlpha(0f);
        errorView.animate().alpha(1f).setDuration(220).start();
    }

    private void hideError() {
        if (errorView.getVisibility() != View.VISIBLE) return;
        errorView.animate().cancel();
        errorView.animate().alpha(0f).setDuration(160)
                .withEndAction(() -> {
                    if (errorView.getAlpha() < 0.05f) errorView.setVisibility(View.GONE);
                }).start();
    }

    // =====================================================================
    // Matrix zoom / pan
    // =====================================================================
    private Bitmap displayBitmap() {
        return originalBitmap;
    }

    private boolean isZoomed() {
        return zoomFactor() > ZOOMED_EPSILON;
    }

    private float zoomFactor() {
        if (baseScale <= 0f) return 1f;
        imageMatrix.getValues(matrixValues);
        return matrixValues[Matrix.MSCALE_X] / baseScale;
    }

    private void applyMatrix() {
        current.setImageMatrix(imageMatrix);
    }

    /** Fits the displayed bitmap into the stage; never call mid-gesture. */
    private void fitMatrix() {
        Bitmap bitmap = displayBitmap();
        int viewWidth = stage.getWidth();
        int viewHeight = stage.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) {
            android.util.DisplayMetrics metrics =
                    activity().getResources().getDisplayMetrics();
            viewWidth = metrics.widthPixels;
            viewHeight = metrics.heightPixels;
        }
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            baseScale = 1f;
            imageMatrix.reset();
            applyMatrix();
            return;
        }
        float fit = Math.min(viewWidth / (float) bitmap.getWidth(),
                viewHeight / (float) bitmap.getHeight());
        if (!(fit > 0f)) fit = 1f;
        baseScale = fit;
        imageMatrix.reset();
        imageMatrix.postScale(fit, fit);
        imageMatrix.postTranslate((viewWidth - bitmap.getWidth() * fit) / 2f,
                (viewHeight - bitmap.getHeight() * fit) / 2f);
        applyMatrix();
    }

    /** Clamps the matrix translation so the image never leaves the viewport. */
    private void clampMatrix() {
        Bitmap bitmap = displayBitmap();
        if (bitmap == null) return;
        imageMatrix.getValues(matrixValues);
        float scale = matrixValues[Matrix.MSCALE_X];
        float contentWidth = bitmap.getWidth() * scale;
        float contentHeight = bitmap.getHeight() * scale;
        int viewWidth = stage.getWidth();
        int viewHeight = stage.getHeight();
        float translateX = matrixValues[Matrix.MTRANS_X];
        float translateY = matrixValues[Matrix.MTRANS_Y];
        if (contentWidth <= viewWidth) {
            translateX = (viewWidth - contentWidth) / 2f;
        } else {
            translateX = Math.max(viewWidth - contentWidth, Math.min(0f, translateX));
        }
        if (contentHeight <= viewHeight) {
            translateY = (viewHeight - contentHeight) / 2f;
        } else {
            translateY = Math.max(viewHeight - contentHeight, Math.min(0f, translateY));
        }
        matrixValues[Matrix.MTRANS_X] = translateX;
        matrixValues[Matrix.MTRANS_Y] = translateY;
        imageMatrix.setValues(matrixValues);
        applyMatrix();
    }

    private void setMatrixScaleTranslation(float scale, float translateX, float translateY) {
        matrixValues[Matrix.MSCALE_X] = scale;
        matrixValues[Matrix.MSCALE_Y] = scale;
        matrixValues[Matrix.MSKEW_X] = 0f;
        matrixValues[Matrix.MSKEW_Y] = 0f;
        matrixValues[Matrix.MTRANS_X] = translateX;
        matrixValues[Matrix.MTRANS_Y] = translateY;
        matrixValues[Matrix.MPERSP_0] = 0f;
        matrixValues[Matrix.MPERSP_1] = 0f;
        matrixValues[Matrix.MPERSP_2] = 1f;
        imageMatrix.setValues(matrixValues);
        applyMatrix();
    }

    /** Focal-point pinch with [1x, 4x] clamp; ignores extra delta at the bounds. */
    private void setupGestures() {
        scaleDetector = new ScaleGestureDetector(activity(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        cancelZoomAnimator();
                        resetPageDrag();
                        gestureInProgress = true;
                        gestureDecided = true;
                        dragging = false;
                        horizontalDrag = false;
                        dismissDrag = false;
                        lastFocusX = detector.getFocusX();
                        lastFocusY = detector.getFocusY();
                        current.animate().cancel();
                        return true;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (displayBitmap() == null) return true;
                        float currentZoom = zoomFactor();
                        if (currentZoom <= 0f) return true;
                        float focusX = detector.getFocusX();
                        float focusY = detector.getFocusY();
                        float factor = detector.getScaleFactor();
                        float target = Math.max(1f, Math.min(MAX_SCALE, currentZoom * factor));
                        float applied = target / currentZoom;
                        if (applied > 1.0005f || applied < 0.9995f) {
                            imageMatrix.postScale(applied, applied, focusX, focusY);
                        }
                        imageMatrix.postTranslate(focusX - lastFocusX, focusY - lastFocusY);
                        lastFocusX = focusX;
                        lastFocusY = focusY;
                        clampMatrix();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        gestureInProgress = false;
                        gestureDecided = false;
                        dragging = false;
                        if (zoomFactor() < 1.0005f) {
                            fitMatrix();
                        } else {
                            clampMatrix();
                        }
                    }
                });

        gestureDetector = new GestureDetector(activity(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent event) {
                        toggleChrome();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent event) {
                        if (displayBitmap() == null) return true;
                        UiKit.hapticTick(stage);
                        lastDoubleTapAt = SystemClock.elapsedRealtime();
                        // Toggle fit <-> 2.5x around the tap point, both directions.
                        if (isZoomed()) {
                            animateZoomTo(1f, event.getX(), event.getY(), 280L);
                        } else {
                            animateZoomTo(DOUBLE_TAP_ZOOM, event.getX(), event.getY(), 300L);
                        }
                        return true;
                    }
                });
        stage.setClickable(true);
        stage.setOnTouchListener((view, event) -> handleTouch(event));
    }

    private boolean handleTouch(MotionEvent event) {
        if (closing) return true;
        int action = event.getActionMasked();
        if (pageAnimating && action == MotionEvent.ACTION_DOWN) return true;
        if (velocity != null) velocity.addMovement(event);
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                dragDx = 0f;
                dragDy = 0f;
                gestureDecided = false;
                dragging = false;
                horizontalDrag = false;
                dismissDrag = false;
                // A double tap starts its zoom animation during this same DOWN
                // dispatch; cancelling here would kill the zoom-in (the old
                // "only zooms out" bug). Fresh gestures still cancel animators.
                if (SystemClock.elapsedRealtime() - lastDoubleTapAt > 90L) {
                    cancelZoomAnimator();
                }
                gestureInProgress = true;
                current.animate().cancel();
                ghost.animate().cancel();
                if (velocity == null) velocity = VelocityTracker.obtain();
                velocity.clear();
                velocity.addMovement(event);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                gestureDecided = true;
                dragging = false;
                horizontalDrag = false;
                dismissDrag = false;
                gestureInProgress = true;
                resetPageDrag();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                // Re-anchor the single-pointer drag at the finger that stays down;
                // otherwise the first post-pinch move jumps from the old anchor.
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                gestureDecided = false;
                dragging = false;
                horizontalDrag = false;
                dismissDrag = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (event.getPointerCount() > 1) {
                    lastX = event.getX();
                    lastY = event.getY();
                    return true;
                }
                float x = event.getX();
                float y = event.getY();
                dragDx = x - downX;
                dragDy = y - downY;
                if (!gestureDecided) {
                    if (Math.abs(dragDx) > touchSlop || Math.abs(dragDy) > touchSlop) {
                        gestureDecided = true;
                        dragging = true;
                        cancelZoomAnimator();
                        if (isZoomed()) {
                            horizontalDrag = false;
                            dismissDrag = false;
                        } else {
                            horizontalDrag = Math.abs(dragDx) > Math.abs(dragDy) * 1.2f;
                            dismissDrag = !horizontalDrag;
                        }
                    }
                }
                if (!dragging) return true;
                if (isZoomed()) {
                    imageMatrix.postTranslate(x - lastX, y - lastY);
                    clampMatrix();
                } else if (horizontalDrag) {
                    onHorizontalDrag(dragDx);
                } else if (dismissDrag) {
                    onDismissDrag(dragDy);
                }
                lastX = x;
                lastY = y;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float velocityX = 0f;
                float velocityY = 0f;
                if (velocity != null) {
                    velocity.computeCurrentVelocity(1000);
                    velocityX = velocity.getXVelocity();
                    velocityY = velocity.getYVelocity();
                    velocity.recycle();
                    velocity = null;
                }
                gestureInProgress = false;
                if (zoomAnimator != null) {
                    // Double-tap zoom is animating; let it finish instead of
                    // clamping the matrix mid-animation.
                    dragging = false;
                    return true;
                }
                if (action == MotionEvent.ACTION_CANCEL) {
                    dragging = false;
                    if (horizontalDrag || dismissDrag) {
                        snapBack();
                    } else {
                        clampMatrix();
                    }
                    return true;
                }
                if (!gestureDecided || !dragging) {
                    if (isZoomed()) clampMatrix();
                    return true;
                }
                dragging = false;
                if (isZoomed()) {
                    clampMatrix();
                    return true;
                }
                if (horizontalDrag) {
                    float threshold = stage.getWidth() * 0.28f;
                    if (dragDx > threshold || velocityX > 850f) {
                        commitPage(-1);
                    } else if (dragDx < -threshold || velocityX < -850f) {
                        commitPage(1);
                    } else {
                        snapBack();
                    }
                } else if (dismissDrag) {
                    if (dragDy > stage.getHeight() * 0.22f || velocityY > 950f) {
                        dismiss();
                    } else {
                        snapBack();
                    }
                } else {
                    snapBack();
                }
                return true;
            }
            default:
                return true;
        }
    }

    /** Animated zoom around a focal point (double tap / zoom-out / back). */
    private void animateZoomTo(final float targetZoom, float focusX, float focusY,
                               long durationMs) {
        Bitmap bitmap = displayBitmap();
        if (bitmap == null) return;
        cancelZoomAnimator();
        final float zoom = Math.max(1f, Math.min(MAX_SCALE, targetZoom));
        final float fromZoom = zoomFactor();
        if (Math.abs(zoom - fromZoom) < 0.001f) {
            if (zoom <= 1.0005f) fitMatrix();
            return;
        }
        imageMatrix.getValues(matrixValues);
        final float fromScale = matrixValues[Matrix.MSCALE_X];
        final float fromX = matrixValues[Matrix.MTRANS_X];
        final float fromY = matrixValues[Matrix.MTRANS_Y];
        final float toScale = baseScale * zoom;
        float toX = focusX - (focusX - fromX) * (toScale / fromScale);
        float toY = focusY - (focusY - fromY) * (toScale / fromScale);
        int viewWidth = stage.getWidth();
        int viewHeight = stage.getHeight();
        float contentWidth = bitmap.getWidth() * toScale;
        float contentHeight = bitmap.getHeight() * toScale;
        if (contentWidth <= viewWidth) {
            toX = (viewWidth - contentWidth) / 2f;
        } else {
            toX = Math.max(viewWidth - contentWidth, Math.min(0f, toX));
        }
        if (contentHeight <= viewHeight) {
            toY = (viewHeight - contentHeight) / 2f;
        } else {
            toY = Math.max(viewHeight - contentHeight, Math.min(0f, toY));
        }
        final float targetX = toX;
        final float targetY = toY;
        gestureInProgress = true;
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f);
        zoomAnimator.setDuration(durationMs);
        zoomAnimator.setInterpolator(UiKit.DECELERATE_SOFT);
        zoomAnimator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            float scale = fromScale + (toScale - fromScale) * fraction;
            float translateX = fromX + (targetX - fromX) * fraction;
            float translateY = fromY + (targetY - fromY) * fraction;
            setMatrixScaleTranslation(scale, translateX, translateY);
        });
        zoomAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                zoomAnimator = null;
                gestureInProgress = false;
                if (zoom <= 1.0005f) fitMatrix();
                else clampMatrix();
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                if (zoomAnimator == animation) zoomAnimator = null;
                gestureInProgress = false;
            }
        });
        zoomAnimator.start();
    }

    private void cancelZoomAnimator() {
        if (zoomAnimator != null) {
            zoomAnimator.cancel();
            zoomAnimator = null;
        }
    }

    // =====================================================================
    // Paging / dismiss gestures
    // =====================================================================
    private void onHorizontalDrag(float dx) {
        int width = Math.max(1, stage.getWidth());
        current.setTranslationX(dx);
        current.setTranslationY(0f);
        current.setAlpha(1f - Math.min(0.3f, Math.abs(dx) / width * 0.3f));
        int neighbor = dx > 0 ? index - 1 : index + 1;
        ensureGhost(neighbor);
        float sign = dx > 0 ? 1f : -1f;
        ghost.setTranslationX((dx - sign * width) * 0.35f);
        ghost.setAlpha(0.92f);
        applyChromeFactor(1f - Math.min(1f, Math.abs(dx) / (width * 0.7f)));
    }

    private void onDismissDrag(float dy) {
        int height = Math.max(1, stage.getHeight());
        float distance = Math.max(0f, dy);
        float progress = Math.min(1f, distance / (height * 0.85f));
        current.setTranslationY(distance);
        current.setAlpha(1f - 0.18f * progress);
        root.setAlpha(1f - 0.45f * progress);
        applyChromeFactor(1f - Math.min(1f, progress * 1.6f));
    }

    private void ensureGhost(int neighbor) {
        if (neighbor == ghostIndex) {
            boolean valid = neighbor >= 0 && neighbor < count();
            ghost.setVisibility(valid ? View.VISIBLE : View.INVISIBLE);
            return;
        }
        ghostIndex = neighbor;
        final int token = ++ghostToken;
        ghost.setImageDrawable(null);
        dropGhostReference();
        if (neighbor < 0 || neighbor >= count()) {
            ghost.setVisibility(View.INVISIBLE);
            return;
        }
        ghost.setVisibility(View.VISIBLE);
        ghost.setAlpha(0.92f);
        if (localMode) {
            final LocalMedia item = localItems.get(neighbor);
            Bitmap cached = host.imageLoader().cached(item.key());
            if (cached != null && !cached.isRecycled()) {
                ghostBitmap = cached;
                ghostOwned = false;
                ghost.setImageBitmap(cached);
                return;
            }
            final Context context = activity().getApplicationContext();
            try {
                worker.execute(() -> {
                    if (destroyed) return;
                    final Bitmap bitmap = LocalMedia.loadThumbnailBitmap(context, item, 900);
                    main.post(() -> {
                        if (bitmap == null) return;
                        if (destroyed || token != ghostToken) {
                            recycleQuietly(bitmap);
                            return;
                        }
                        ghostBitmap = bitmap;
                        ghostOwned = true;
                        ghost.setImageBitmap(bitmap);
                    });
                });
            } catch (Throwable ignored) {
                // Ghost is decoration only.
            }
        } else {
            final CameraImage image = cameraImages.get(neighbor);
            Bitmap cached = host.imageLoader().cached(cameraGridKey(image));
            if (cached != null && !cached.isRecycled()) {
                ghostBitmap = cached;
                ghostOwned = false;
                ghost.setImageBitmap(cached);
                return;
            }
            try {
                // Fast compressed preview for the ghost layer as well.
                host.bridge().loadPreview(image, new CameraBridge.PreviewCallback() {
                    @Override
                    public void onPreview(Bitmap bitmap, String message) {
                        if (bitmap == null) return;
                        if (destroyed || token != ghostToken) {
                            recycleQuietly(bitmap);
                            return;
                        }
                        Bitmap oriented = CameraBridge.orient(bitmap, image);
                        ghostBitmap = oriented;
                        ghostOwned = true;
                        ghost.setImageBitmap(oriented);
                    }
                });
            } catch (Throwable ignored) {
                // Ignore decorative failures.
            }
        }
    }

    /** Releases the previous ghost frame only when this viewer decoded it. */
    private void dropGhostReference() {
        Bitmap previous = ghostBitmap;
        boolean owned = ghostOwned;
        ghostBitmap = null;
        ghostOwned = false;
        if (owned) recycleQuietly(previous);
    }

    private void commitPage(final int direction) {
        final int next = index + direction;
        if (next < 0 || next >= count()) {
            snapBack();
            return;
        }
        final int width = Math.max(1, stage.getWidth());
        final float outX = direction < 0 ? width : -width;
        pageAnimating = true;
        ghost.setVisibility(View.VISIBLE);
        ghost.animate().translationX(0f).alpha(1f)
                .setDuration(240).setInterpolator(UiKit.DECELERATE_SOFT).start();
        current.animate().translationX(outX).alpha(0.25f)
                .setDuration(240).setInterpolator(UiKit.DECELERATE)
                .withEndAction(() -> {
                    if (destroyed) return;
                    pageAnimating = false;
                    index = next;
                    Bitmap neighborBitmap = ghostBitmap;
                    boolean neighborOwned = ghostOwned;
                    ghostBitmap = null;
                    ghostOwned = false;
                    current.animate().cancel();
                    current.setTranslationX(0f);
                    current.setTranslationY(0f);
                    current.setAlpha(1f);
                    replaceOriginal(neighborBitmap, neighborOwned, false);
                    ghost.setImageDrawable(null);
                    ghost.setVisibility(View.INVISIBLE);
                    ghost.setTranslationX(0f);
                    ghost.setAlpha(0.92f);
                    ghostIndex = Integer.MIN_VALUE;
                    root.setAlpha(1f);
                    snapChrome();
                    loadCurrent(true);
                }).start();
    }

    private void snapBack() {
        int width = Math.max(1, stage.getWidth());
        ghost.animate().translationX(dragDx > 0 ? -width * 0.35f : width * 0.35f)
                .alpha(0f).setDuration(200).setInterpolator(UiKit.DECELERATE)
                .withEndAction(() -> {
                    if (destroyed) return;
                    ghost.setVisibility(View.INVISIBLE);
                    ghost.setTranslationX(0f);
                }).start();
        current.animate().translationX(0f).translationY(0f).alpha(1f)
                .setDuration(250).setInterpolator(UiKit.DECELERATE_SOFT).start();
        root.animate().alpha(1f).setDuration(200).start();
        snapChrome();
    }

    /** Resets page-drag translations only; the zoom matrix is never touched here. */
    private void resetPageDrag() {
        if (current == null) return;
        current.animate().cancel();
        current.setTranslationX(0f);
        current.setTranslationY(0f);
        current.setAlpha(1f);
        if (ghost != null) {
            ghost.animate().cancel();
            ghost.setImageDrawable(null);
            ghost.setVisibility(View.INVISIBLE);
            ghost.setTranslationX(0f);
            ghost.setAlpha(0.92f);
        }
        dropGhostReference();
        ghostIndex = Integer.MIN_VALUE;
        if (root != null) root.setAlpha(1f);
        applyChromeFactor(1f);
    }

    private void dismiss() {
        if (closing) return;
        closing = true;
        int height = Math.max(1, stage.getHeight());
        current.animate().translationY(height).alpha(0f)
                .setDuration(240).setInterpolator(UiKit.DECELERATE).start();
        root.animate().alpha(0f).setDuration(240).start();
        main.postDelayed(() -> {
            if (destroyed) return;
            try {
                host.closeOverlay();
            } catch (Throwable ignored) {
                // The shell is already tearing the overlay down.
            }
        }, 210);
    }

    private void closeViewer() {
        if (closing) return;
        closing = true;
        try {
            host.closeOverlay();
        } catch (Throwable ignored) {
            // Already closing.
        }
    }

    // =====================================================================
    // Chrome
    // =====================================================================
    private void applyChromeFactor(float factor) {
        float clamped = Math.max(0f, Math.min(1f, factor));
        float alpha = chromeVisible ? clamped : 0f;
        topBar.setAlpha(alpha);
        bottomBar.setAlpha(alpha);
        int shift = (int) ((1f - clamped) * dp(12));
        topBar.setTranslationY(-shift);
        bottomBar.setTranslationY(shift);
    }

    private void snapChrome() {
        if (chromeVisible) {
            topBar.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.VISIBLE);
        }
        topBar.animate().alpha(chromeVisible ? 1f : 0f)
                .translationY(chromeVisible ? 0f : -dp(10))
                .setDuration(240).setInterpolator(UiKit.DECELERATE)
                .withEndAction(() -> {
                    if (!chromeVisible) topBar.setVisibility(View.INVISIBLE);
                }).start();
        bottomBar.animate().alpha(chromeVisible ? 1f : 0f)
                .translationY(chromeVisible ? 0f : dp(10))
                .setDuration(240).setInterpolator(UiKit.DECELERATE)
                .withEndAction(() -> {
                    if (!chromeVisible) bottomBar.setVisibility(View.INVISIBLE);
                }).start();
    }

    private void toggleChrome() {
        chromeVisible = !chromeVisible;
        UiKit.hapticTick(stage);
        snapChrome();
    }

    private void updateChromeText() {
        if (topBar == null) return;
        int total = count();
        indexLabel.setText((total == 0 ? 0 : index + 1) + " / " + total);
        String name = "照片";
        String date = "—";
        String size = "—";
        String format = "IMG";
        boolean raw = false;
        if (localMode) {
            downloadButton.setVisibility(View.GONE);
            LocalMedia item = currentLocal();
            if (item != null) {
                name = item.displayName == null ? "照片" : item.displayName;
                date = LocalMedia.formatDate(item.dateMillis);
                size = LocalMedia.formatSize(item.sizeBytes);
                format = item.formatLabel();
                raw = item.isRaw();
            }
        } else {
            CameraImage image = currentCamera();
            if (image != null) {
                name = safeName(image);
                date = LocalMedia.formatDate(cameraDateMillis(image));
                size = LocalMedia.formatSize(cameraFileSize(image));
                format = cameraFormat(image);
                downloadButton.setVisibility(View.VISIBLE);
            }
        }
        nameLabel.setText(name);
        if (rawHint != null) {
            rawHint.setVisibility(raw ? View.VISIBLE : View.GONE);
        }
        infoLabel.setText(String.format(Locale.CHINA, "%s · %s · %s", date, size, format));
    }

    // =====================================================================
    // Share / download
    // =====================================================================
    private void startDownload() {
        if (localMode || downloading) {
            if (localMode) snack("照片已保存在本机相册中", false);
            return;
        }
        final CameraImage image = currentCamera();
        if (image == null) return;
        downloading = true;
        snack("正在保存到 Pictures/Image Sync…", true);
        final String name = safeName(image);
        try {
            host.bridge().downloadImage(image, name, null, new CameraBridge.SaveCallback() {
                @Override
                public void onSaved(String displayPath) {
                    downloading = false;
                    if (destroyed) return;
                    snack("已保存到 Pictures/Image Sync", true);
                }

                @Override
                public void onError(String message) {
                    downloading = false;
                    if (destroyed) return;
                    snack("保存失败，请检查相机连接", true);
                }
            });
        } catch (Throwable error) {
            downloading = false;
            snack("保存失败，请检查相机连接", true);
        }
    }

    private void startShare() {
        if (downloading) {
            snack("正在保存，请稍候", true);
            return;
        }
        if (localMode) {
            LocalMedia item = currentLocal();
            if (item == null) return;
            shareUri(item.uri, item.mimeType);
            return;
        }
        final CameraImage image = currentCamera();
        if (image == null) return;
        downloading = true;
        snack("正在保存以便分享…", true);
        final String name = safeName(image);
        try {
            host.bridge().downloadImage(image, name, null, new CameraBridge.SaveCallback() {
                @Override
                public void onSaved(String displayPath) {
                    downloading = false;
                    if (destroyed) return;
                    final Context context = activity().getApplicationContext();
                    worker.execute(() -> {
                        final Uri uri = LocalMedia.findSavedUri(context, name);
                        main.post(() -> {
                            if (destroyed) return;
                            if (uri != null) {
                                shareUri(uri, "image/*");
                            } else {
                                snack("已保存到 Pictures/Image Sync", true);
                            }
                        });
                    });
                }

                @Override
                public void onError(String message) {
                    downloading = false;
                    if (destroyed) return;
                    snack("保存失败，请检查相机连接", true);
                }
            });
        } catch (Throwable error) {
            downloading = false;
            snack("保存失败，请检查相机连接", true);
        }
    }

    private void shareUri(Uri uri, String mime) {
        if (uri == null) return;
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(mime == null || mime.isEmpty() ? "image/*" : mime);
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity().startActivity(Intent.createChooser(send, "分享照片"));
        } catch (Throwable error) {
            snack("没有可用的分享应用", true);
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================
    private CameraImage currentCamera() {
        if (localMode) return null;
        return index >= 0 && index < cameraImages.size() ? cameraImages.get(index) : null;
    }

    private LocalMedia currentLocal() {
        if (!localMode) return null;
        return index >= 0 && index < localItems.size() ? localItems.get(index) : null;
    }

    private static long cameraDateMillis(CameraImage image) {
        try {
            java.util.Date date = image.getDateTime();
            return date == null ? 0L : date.getTime();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long cameraFileSize(CameraImage image) {
        try {
            return image.getFileSize();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String cameraFormat(CameraImage image) {
        try {
            Object format = image.getFormat();
            return format == null ? "IMG" : String.valueOf(format);
        } catch (Throwable ignored) {
            return "IMG";
        }
    }

    private static String safeName(CameraImage image) {
        try {
            String name = image.getName();
            if (name != null && !name.isEmpty()) {
                return name.replace("?xs", "");
            }
        } catch (Throwable ignored) {
            // Fall through.
        }
        return "IMG_" + System.currentTimeMillis() + ".jpg";
    }

    private static String cameraGridKey(CameraImage image) {
        try {
            String folder = image.getFolderName();
            String name = image.getName();
            return "cam:" + (folder == null ? "" : folder) + "/"
                    + (name == null ? String.valueOf(System.identityHashCode(image)) : name);
        } catch (Throwable ignored) {
            return "cam:" + System.identityHashCode(image);
        }
    }

    private static void recycleQuietly(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        try {
            bitmap.recycle();
        } catch (Throwable ignored) {
            // Best effort.
        }
    }

    private void snack(String message, boolean force) {
        if (message == null || message.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastSnackAt < 2200L) return;
        lastSnackAt = now;
        host.showSnack(message);
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================
    @Override
    public void onShow() {
        styleWindow(true);
        chromeVisible = true;
        topBar.setAlpha(0f);
        topBar.setTranslationY(-dp(10));
        bottomBar.setAlpha(0f);
        bottomBar.setTranslationY(dp(10));
        topBar.animate().alpha(1f).translationY(0f)
                .setStartDelay(40).setDuration(300)
                .setInterpolator(UiKit.DECELERATE_SOFT).start();
        bottomBar.animate().alpha(1f).translationY(0f)
                .setStartDelay(90).setDuration(300)
                .setInterpolator(UiKit.DECELERATE_SOFT).start();
    }

    @Override
    public void onHide() {
        release();
        styleWindow(false);
    }

    @Override
    public void onHostPause() {
        cancelZoomAnimator();
        // Overlays are not owned by ShellActivity's destroy loop, so a finishing
        // activity must release the decode worker here.
        if (activity().isFinishing()) release();
    }

    @Override
    public boolean onBackPressed() {
        if (isZoomed()) {
            animateZoomTo(1f, stage.getWidth() / 2f, stage.getHeight() / 2f, 280L);
            return true;
        }
        return false;
    }

    @Override
    public void onDestroyView() {
        release();
    }

    private void release() {
        if (destroyed) return;
        destroyed = true;
        cancelZoomAnimator();
        main.removeCallbacksAndMessages(null);
        if (velocity != null) {
            try {
                velocity.recycle();
            } catch (Throwable ignored) {
                // Best effort.
            }
            velocity = null;
        }
        try {
            current.setImageDrawable(null);
            ghost.setImageDrawable(null);
        } catch (Throwable ignored) {
            // Views may already be detached.
        }
        dropGhostReference();
        Bitmap original = originalBitmap;
        boolean owned = originalOwned;
        originalBitmap = null;
        originalOwned = false;
        if (owned) recycleQuietly(original);
        try {
            worker.shutdownNow();
        } catch (Throwable ignored) {
            // Best effort.
        }
    }

    private void styleWindow(boolean enter) {
        try {
            Window window = activity().getWindow();
            if (enter) {
                if (windowStyled) return;
                windowStyled = true;
                savedStatusBarColor = window.getStatusBarColor();
                savedNavigationBarColor = window.getNavigationBarColor();
                savedUiFlags = window.getDecorView().getSystemUiVisibility();
                window.setStatusBarColor(Color.BLACK);
                window.setNavigationBarColor(Color.BLACK);
                int flags = savedUiFlags
                        & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                window.getDecorView().setSystemUiVisibility(flags);
            } else {
                if (!windowStyled) return;
                windowStyled = false;
                window.setStatusBarColor(savedStatusBarColor);
                window.setNavigationBarColor(savedNavigationBarColor);
                window.getDecorView().setSystemUiVisibility(savedUiFlags);
            }
        } catch (Throwable ignored) {
            // Window styling is cosmetic; never fail the viewer for it.
        }
    }
}
