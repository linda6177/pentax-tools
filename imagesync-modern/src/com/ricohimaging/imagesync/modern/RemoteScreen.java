package com.ricohimaging.imagesync.modern;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Remote-capture tab with two peer modes:
 *
 * <ul>
 *   <li><b>WiFi 遥控</b> — full-bleed MJPEG live view, tap-to-focus, shutter,
 *   self-timer, grid, rotation and the capture-settings sheet. Every camera
 *   operation goes through {@link CameraBridge}; frames are decoded off the main
 *   thread, coalesced here and the replaced bitmap is recycled. A watchdog
 *   restarts the stream when frames silently stop.</li>
 *   <li><b>红外遥控</b> — self-contained PENTAX O-RC1 panel driven by
 *   {@link IrRemote}; works with no WiFi connection and no camera bridge state.</li>
 * </ul>
 *
 * <p>The mode switch is a top-level segmented control persisted in
 * SharedPreferences ("remote_mode") and stays reachable while disconnected.
 * This screen never launches any original Ricoh Activity.</p>
 */
public final class RemoteScreen extends Screen implements CameraBridge.StateListener {

    private static final int MODE_WIFI = 0;
    private static final int MODE_IR = 1;
    private static final String PREFS = "remote_ui";
    private static final String PREF_MODE = "remote_mode";

    // Neutral white is kept only for affordances drawn directly over the black
    // camera preview (shutter ring/fill, flash, framing grid, reticle idle),
    // where it reads well on both themes and matches camera UIs.
    private static final int WHITE = 0xFFFFFFFF;

    private static final long START_TIMEOUT_MS = 5000L;
    private static final long STREAM_TIMEOUT_MS = 2500L;
    private static final long LIVE_SETTLE_MS = 220L;
    private static final long WATCHDOG_PERIOD_MS = 600L;
    private static final int MAX_RESTARTS = 3;
    private static final long[] RETRY_BACKOFF_MS = {1000L, 2000L, 3000L, 5000L};
    private static final int LIVE_IDLE = 0;
    private static final int LIVE_STARTING = 1;
    private static final int LIVE_STREAMING = 2;
    private static final int LIVE_STOPPING = 3;
    private static final int LIVE_FAILED = 4;
    private static final long FOCUS_TIMEOUT_MS = 8000L;
    private static final long CAPTURE_TIMEOUT_MS = 12000L;
    private static final long STATUS_TICK_MS = 1000L;
    private static final int MAX_FRAME_FPS = 30;
    private static final long ROTATION_MS = 300L;

    // ------------------------------------------------- chrome palette helpers
    /** Translucent surface pill floating over the live preview. */
    private int chromePillColor() {
        return withAlpha(t.surface, t.dark ? 0x8C : 0xE6);
    }

    private int chromeChipColor() {
        return withAlpha(t.text, t.dark ? 0x33 : 0x14);
    }

    private int chromeRippleColor() {
        return withAlpha(t.text, t.dark ? 0x33 : 0x1F);
    }

    /** Status / processing pills floating over the preview. */
    private int overlayPillColor() {
        return withAlpha(t.surface, t.dark ? 0x99 : 0xE6);
    }

    /** Rounded floating pill over the preview with a faint themed outline. */
    private GradientDrawable chromePill(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(t.radiusPill));
        shape.setStroke(Math.max(1, dp(0.7f)), withAlpha(t.text, t.dark ? 0x26 : 0x1A));
        return shape;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }


    // ------------------------------------------------------------------- UI
    private FrameLayout root;
    private FrameLayout previewLayer;
    private ImageView liveView;
    private ReticleView reticle;

    private View flash;

    private LinearLayout topBar;
    private View statusDot;
    private GradientDrawable statusDotBackground;
    private TextView modelLabel;
    private TextView batteryLabel;
    private Glyph batteryGlyph;

    private LinearLayout bottomControls;
    private LinearLayout bottomSurface;
    private TextView processingPill;
    private TextView recoveryPill;
    private ShutterView shutterButton;

    private FrameLayout placeholder;
    private boolean placeholderVisible;

    // ---------------------------------------------------------- mode switch
    private int remoteMode = MODE_WIFI;
    private FrameLayout modeSwitchWrap;
    private View modeIndicator;
    private LinearLayout modeSwitchRow;
    private final TextView[] modeTabs = new TextView[2];
    private ValueAnimator modeIndicatorAnimator;

    // ------------------------------------------------------------ IR panel
    private View irBackdrop;
    private FrameLayout irPanel;
    private LinearLayout irContent;
    private TextView irStatusText;
    private TextView irStatePill;
    private ProgressBar irProgress;
    private final List<IrKeyButton> irButtons = new ArrayList<>();
    private boolean irSending;
    private IrKeyButton irActiveRepeat;
    private int irRepeatKey = -1;
    private long irRepeatArmedAt;
    private boolean irEmitter;

    // -------------------------------------------------------------- session
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean visible;
    private boolean destroyed;

    // Live-view serialized state machine (all fields touched on the main thread).
    private int liveState = LIVE_IDLE;
    private long liveStateAt;
    private int liveGeneration;
    private int liveFailureCount;
    private boolean pendingStartWanted;
    private Runnable scheduledStart;
    private Runnable retryRunnable;
    private long lastFrameAt;

    private Bitmap currentFrame;
    private Bitmap pendingFrame;
    private long lastRenderAt;
    private int frameWidth;
    private int frameHeight;
    private int rotation;
    private float viewScale = 1f;
    private boolean rotating;
    private ValueAnimator rotationAnimator;
    private int pendingAutoRotation = -1;

    private boolean focusing;
    private int focusToken;
    private float lastFocusX = 0.5f;
    private float lastFocusY = 0.5f;
    private boolean hasFocusPoint;

    private boolean capturing;
    private int captureToken;

    private boolean connecting;
    private CameraBridge.State lastUiState = CameraBridge.State.DISCONNECTED;

    private final Runnable renderFrameRunnable = new Runnable() {
        @Override
        public void run() {
            renderPendingFrame();
        }
    };

    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            if (!visible || destroyed || remoteMode == MODE_IR) return;
            // The original wizard may connect the camera without notifying the
            // bridge, so the ticker also reconciles the coarse connection state.
            CameraBridge.State current = host.bridge().state();
            if (current != lastUiState) {
                updateConnectionUi(current);
                if (current == CameraBridge.State.CONNECTED) {
                    resetLiveRetries();
                    requestStart();
                } else {
                    stopNow(true);
                }
            } else {
                updateStatus();
                // Self-heal: if the stream is idle while connected and visible
                // (e.g. a lost callback), route a start through the machine.
                if (remoteMode == MODE_WIFI && liveState == LIVE_IDLE && !capturing
                        && host.bridge().state() == CameraBridge.State.CONNECTED) {
                    requestStart();
                }
            }
            main.postDelayed(this, STATUS_TICK_MS);
        }
    };

    private final Runnable watchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!visible || destroyed || remoteMode != MODE_WIFI) return;
            if (host.bridge().state() != CameraBridge.State.CONNECTED) {
                stopNow(true);
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (liveState == LIVE_STARTING && now - liveStateAt > START_TIMEOUT_MS) {
                onLiveFailure("取景启动超时");
            } else if (liveState == LIVE_STREAMING && lastFrameAt > 0
                    && now - lastFrameAt > STREAM_TIMEOUT_MS) {
                onLiveFailure("取景已暂停");
            }
            if (liveState == LIVE_STARTING || liveState == LIVE_STREAMING) {
                main.postDelayed(this, WATCHDOG_PERIOD_MS);
            }
        }
    };

    /** One callback per start attempt; frames are tagged with its generation. */
    private CameraBridge.LiveViewCallback liveCallbackFor(final int generation) {
        return new CameraBridge.LiveViewCallback() {
            @Override
            public void onFrame(Bitmap bitmap) {
                handleFrame(bitmap, generation);
            }

            @Override
            public void onError(String message) {
                if (destroyed || generation != liveGeneration) return;
                onLiveFailure("取景连接中断");
            }
        };
    }

    public RemoteScreen(ShellActivity host) {
        super(host);
    }

    // ======================================================================
    //  View construction
    // ======================================================================
    @Override
    protected View onCreateView() {
        root = new FrameLayout(activity());
        root.setBackgroundColor(t.background);

        loadRemoteMode();
        buildPreview();
        buildTopBar();
        buildRecoveryPill();
        buildControls();
        buildPlaceholder();
        buildIrPanel();
        buildModeSwitch();
        buildFlash();
        root.addOnLayoutChangeListener((view, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) != (oldRight - oldLeft)
                    || (bottom - top) != (oldBottom - oldTop)) {
                updatePreviewFrame();
            }
        });
        root.post(this::updatePreviewFrame);

        host.bridge().addStateListener(this);
        applyModeUi(false);
        updateConnectionUi(host.bridge().state());
        return root;
    }

    private void buildPreview() {
        previewLayer = new FrameLayout(activity());
        // Only the camera frame itself is black; the scaffold follows the theme.
        previewLayer.setBackgroundColor(t.background);
        root.addView(previewLayer, new FrameLayout.LayoutParams(-1, -1));

        liveView = new ImageView(activity());
        liveView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // Edge-to-edge camera window: square corners, no rounding.
        liveView.setBackgroundColor(Color.BLACK);
        liveView.setClipToOutline(false);
        FrameLayout.LayoutParams frameParams =
                new FrameLayout.LayoutParams(-1, dp(300));
        frameParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        frameParams.topMargin = dp(108);
        previewLayer.addView(liveView, frameParams);
        liveView.addOnLayoutChangeListener((view, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) != (oldRight - oldLeft)
                    || (bottom - top) != (oldBottom - oldTop)) {
                applyTransform();
            }
        });

        reticle = new ReticleView(activity(), t.success, t.danger);
        previewLayer.addView(reticle, new FrameLayout.LayoutParams(-1, -1));

        previewLayer.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        return true;
                    case MotionEvent.ACTION_UP:
                        float dx = Math.abs(event.getX() - downX);
                        float dy = Math.abs(event.getY() - downY);
                        if (dx + dy < dp(18)) {
                            if (currentFrame == null) {
                                resetLiveRetries();
                                requestStart();
                            } else {
                                onPreviewTap(event.getX(), event.getY());
                            }
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    /**
     * Portrait preview geometry: full-width 4:3 (portrait) camera frame centred
     * between the top status area and the bottom control surface. A landscape
     * camera stream is still displayed inside this portrait frame (the image
     * itself is rotated/fitted, the UI stays portrait).
     */
    private void updatePreviewFrame() {
        if (root == null || liveView == null) return;
        int width = root.getWidth();
        int height = root.getHeight();
        if (width <= 0 || height <= 0) return;
        int topReserved = dp(108);
        int bottomReserved = dp(150);
        int available = Math.max(dp(140), height - topReserved - bottomReserved);
        // Full-bleed: the window touches both screen edges and is as large as
        // the native landscape aspect allows between the chrome bars.
        int frameWidth = width;
        // Whole frame, no cropping and no left/right bars: the view is exactly
        // the image's aspect, centred between the chrome bars. Any leftover
        // space above/below is the themed page background.
        // Fixed 3:2 viewfinder matching the K-1 frame: full width, no side bars.
        float aspect = 3f / 2f;
        if (currentFrame != null && currentFrame.getHeight() > 0) {
            float actual = currentFrame.getWidth() / (float) currentFrame.getHeight();
            if (actual > 1.05f) aspect = actual;
        }
        int frameHeight = (int) (frameWidth / aspect);
        if (frameHeight > available) {
            frameHeight = available;
            frameWidth = (int) (frameHeight * aspect);
        }
        if (frameWidth < width) {
            // Keep it centred when the frame has to be reduced to fit.
            frameWidth = Math.min(width, frameWidth);
        }
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) liveView.getLayoutParams();
        if (params.width == frameWidth && params.height == frameHeight) return;
        params.width = frameWidth;
        params.height = frameHeight;
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.topMargin = topReserved + Math.max(0, (available - frameHeight) / 2);
        liveView.setLayoutParams(params);
    }

    private void buildTopBar() {
        topBar = UiKit.row(activity());
        topBar.setBackground(chromePill(chromePillColor()));
        topBar.setPadding(dp(14), dp(8), dp(6), dp(8));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2);
        params.gravity = Gravity.TOP;
        params.setMargins(dp(14), dp(58), dp(14), 0);
        root.addView(topBar, params);

        statusDot = new View(activity());
        statusDotBackground = new GradientDrawable();
        statusDotBackground.setShape(GradientDrawable.OVAL);
        statusDotBackground.setColor(t.success);
        statusDot.setBackground(statusDotBackground);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.rightMargin = dp(9);
        topBar.addView(statusDot, dotParams);

        modelLabel = UiKit.label(activity(), t, "相机", 13.5f, t.text, 1);
        modelLabel.setSingleLine(true);
        topBar.addView(modelLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout batteryPill = UiKit.row(activity());
        batteryPill.setBackground(chromePill(chromeChipColor()));
        batteryPill.setPadding(dp(9), dp(5), dp(11), dp(5));
        batteryGlyph = new Glyph(activity(), Glyph.Icon.BATTERY);
        batteryGlyph.setTint(t.text);
        batteryPill.addView(batteryGlyph, new LinearLayout.LayoutParams(dp(16), dp(16)));
        batteryLabel = UiKit.label(activity(), t, "—", 11.5f, t.text, 1);
        batteryLabel.setPadding(dp(5), 0, 0, 0);
        batteryPill.addView(batteryLabel);
        topBar.addView(batteryPill);
    }

    private void buildRecoveryPill() {
        recoveryPill = UiKit.label(activity(), t, "", 12, t.text, 1);
        recoveryPill.setGravity(Gravity.CENTER);
        recoveryPill.setBackground(chromePill(overlayPillColor()));
        recoveryPill.setPadding(dp(16), dp(8), dp(16), dp(8));
        recoveryPill.setVisibility(View.GONE);
        recoveryPill.setClickable(true);
        recoveryPill.setFocusable(true);
        recoveryPill.setOnClickListener(v -> {
            UiKit.hapticTick(v);
            retryLiveNow();
        });
        UiKit.pressFeedback(recoveryPill);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(110);
        root.addView(recoveryPill, params);
    }

    private void buildControls() {
        bottomControls = UiKit.column(activity());
        bottomControls.setPadding(dp(14), dp(4), dp(14), dp(12));

        processingPill = UiKit.label(activity(), t, "处理中…", 12.5f, t.text, 1);
        processingPill.setGravity(Gravity.CENTER);
        processingPill.setBackground(chromePill(overlayPillColor()));
        processingPill.setPadding(dp(16), dp(7), dp(16), dp(7));
        processingPill.setVisibility(View.GONE);
        LinearLayout.LayoutParams processingParams = new LinearLayout.LayoutParams(-2, -2);
        processingParams.gravity = Gravity.CENTER_HORIZONTAL;
        processingParams.bottomMargin = dp(10);
        bottomControls.addView(processingPill, processingParams);

        // One rounded themed surface holds the whole bottom control cluster.
        bottomSurface = UiKit.column(activity());
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(t.surface);
        surface.setCornerRadius(dp(t.radiusCard));
        surface.setStroke(Math.max(1, dp(0.7f)), t.hairline);
        bottomSurface.setBackground(surface);
        bottomSurface.setPadding(dp(18), dp(10), dp(18), dp(10));
        bottomSurface.setClipChildren(false);
        bottomSurface.setClipToPadding(false);

        FrameLayout row = new FrameLayout(activity());
        FrameLayout shutterWrap = new FrameLayout(activity());
        // ~84 dp shutter: thin outer ring + inner disc, theme-aware.
        shutterButton = new ShutterView(activity(), withAlpha(t.text, 0x8C), t.text, t.accent);
        FrameLayout.LayoutParams shutterParams = new FrameLayout.LayoutParams(dp(84), dp(84));
        shutterParams.gravity = Gravity.CENTER;
        shutterWrap.addView(shutterButton, shutterParams);
        shutterButton.setOnClickListener(v -> onShutter());
        FrameLayout.LayoutParams shutterWrapParams =
                new FrameLayout.LayoutParams(dp(96), dp(96));
        shutterWrapParams.gravity = Gravity.CENTER;
        row.addView(shutterWrap, shutterWrapParams);

        bottomSurface.addView(row, new LinearLayout.LayoutParams(-1, dp(96)));
        bottomControls.addView(bottomSurface, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2);
        params.gravity = Gravity.BOTTOM;
        params.setMargins(dp(10), 0, dp(10), dp(8));
        root.addView(bottomControls, params);
    }

    private void buildFlash() {
        flash = new View(activity());
        flash.setBackgroundColor(Color.WHITE);
        flash.setAlpha(0f);
        flash.setVisibility(View.GONE);
        root.addView(flash, new FrameLayout.LayoutParams(-1, -1));
    }

    private void buildPlaceholder() {
        placeholder = new FrameLayout(activity());
        placeholder.setBackgroundColor(t.background);
        placeholder.setVisibility(View.GONE);
        root.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
    }

    private void renderPlaceholder(CameraBridge.State state) {
        placeholder.removeAllViews();
        LinearLayout card = UiKit.column(activity());
        GradientDrawable background = new GradientDrawable();
        background.setColor(t.surface);
        background.setCornerRadius(dp(t.radiusCard));
        background.setStroke(dp(0.7f), t.hairline);
        card.setBackground(background);
        card.setPadding(dp(24), dp(26), dp(24), dp(22));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(-1, -2);
        cardParams.setMargins(dp(28), 0, dp(28), 0);
        cardParams.gravity = Gravity.CENTER;
        placeholder.addView(card, cardParams);

        boolean bleOnly = state == CameraBridge.State.BLE_ONLY;
        FrameLayout iconCircle = new FrameLayout(activity());
        iconCircle.setBackground(UiKit.rounded(activity(), t.accentSoft, t.radiusPill));
        Glyph icon = new Glyph(activity(), bleOnly ? Glyph.Icon.BLUETOOTH : Glyph.Icon.WIFI);
        icon.setTint(t.accent);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(30), dp(30));
        iconParams.gravity = Gravity.CENTER;
        iconCircle.addView(icon, iconParams);
        LinearLayout.LayoutParams circleParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        circleParams.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(iconCircle, circleParams);

        TextView title = UiKit.title(activity(), t,
                bleOnly ? "蓝牙已连接" : "相机未连接", 21);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(16);
        card.addView(title, titleParams);

        TextView body = UiKit.body(activity(), t,
                bleOnly ? "正在等待相机 Wi-Fi 建立，请稍候。"
                        : "打开相机的无线功能并让手机加入相机 Wi-Fi 后，点下方按钮连接；"
                        + "也可以直接切到「红外遥控」模式，无需 Wi-Fi 即可拍摄。",
                12.5f, t.muted);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(8);
        card.addView(body, bodyParams);

        if (connecting) {
            LinearLayout progress = UiKit.row(activity());
            ProgressBar spinner = new ProgressBar(activity(), null,
                    android.R.attr.progressBarStyleSmall);
            spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(t.accent));
            progress.addView(spinner, new LinearLayout.LayoutParams(dp(18), dp(18)));
            TextView label = UiKit.label(activity(), t, "正在检测相机 Wi-Fi…",
                    12.5f, t.muted, 0);
            label.setPadding(dp(10), 0, 0, 0);
            progress.addView(label);
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-2, -2);
            progressParams.gravity = Gravity.CENTER_HORIZONTAL;
            progressParams.topMargin = dp(18);
            card.addView(progress, progressParams);
        } else {
            TextView detect = UiKit.primaryButton(activity(), t, "检测相机 Wi-Fi");
            detect.setOnClickListener(v -> {
                UiKit.hapticTick(v);
                startWifiConnect();
            });
            LinearLayout.LayoutParams detectParams = new LinearLayout.LayoutParams(-1, -2);
            detectParams.topMargin = dp(20);
            card.addView(detect, detectParams);

            TextView retry = UiKit.tonalButton(activity(), t, "重试");
            retry.setOnClickListener(v -> {
                UiKit.hapticTick(v);
                startWifiConnect();
            });
            LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-1, -2);
            retryParams.topMargin = dp(10);
            card.addView(retry, retryParams);

            TextView hint = UiKit.body(activity(), t,
                    "只会在本机 Wi-Fi 网络中查找相机。",
                    11.5f, t.muted);
            hint.setGravity(Gravity.CENTER_HORIZONTAL);
            hint.setPadding(0, dp(12), 0, 0);
            card.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    // ======================================================================
    //  Live view
    // ======================================================================
    private boolean computeVisible() {
        return root != null && root.getParent() != null
                && host.currentTab() == ShellActivity.TAB_REMOTE;
    }

    // ------------------------------------------------- live state machine
    private boolean canStartLive() {
        return !destroyed && visible && remoteMode == MODE_WIFI
                && host.bridge().state() == CameraBridge.State.CONNECTED;
    }

    private void resetLiveRetries() {
        liveFailureCount = 0;
        main.removeCallbacks(retryRunnable);
        hideRecovery();
    }

    /**
     * Single entry point for (re)starting the stream. Callbacks and lifecycle
     * hooks never touch {@code bridge.startLiveView} directly; starts are
     * serialized behind a settle delay and a generation token.
     */
    private void requestStart() {
        if (!canStartLive() || capturing) return;
        if (liveState == LIVE_STARTING || liveState == LIVE_STREAMING) return;
        if (liveState == LIVE_STOPPING) {
            pendingStartWanted = true;
            return;
        }
        pendingStartWanted = false;
        liveState = LIVE_STARTING;
        liveStateAt = SystemClock.uptimeMillis();
        final int generation = ++liveGeneration;
        if (scheduledStart != null) main.removeCallbacks(scheduledStart);
        scheduledStart = () -> {
            scheduledStart = null;
            beginStart(generation);
        };
        main.postDelayed(scheduledStart, LIVE_SETTLE_MS);
    }

    private void beginStart(int generation) {
        if (generation != liveGeneration || !canStartLive()
                || liveState != LIVE_STARTING) {
            return;
        }
        lastFrameAt = 0;
        main.removeCallbacks(watchdogRunnable);
        main.postDelayed(watchdogRunnable, WATCHDOG_PERIOD_MS);
        host.bridge().startLiveView(liveCallbackFor(generation));
    }

    private final Runnable stopSettleRunnable = new Runnable() {
        @Override
        public void run() {
            if (liveState != LIVE_STOPPING) return;
            liveState = LIVE_IDLE;
            liveStateAt = SystemClock.uptimeMillis();
            if (pendingStartWanted) {
                pendingStartWanted = false;
                requestStart();
            }
        }
    };

    /** Stops cleanly; {@code clearFrames} also drops the last painted frame. */
    private void stopNow(boolean clearFrames) {
        liveGeneration++;
        if (scheduledStart != null) {
            main.removeCallbacks(scheduledStart);
            scheduledStart = null;
        }
        main.removeCallbacks(retryRunnable);
        main.removeCallbacks(watchdogRunnable);
        main.removeCallbacks(renderFrameRunnable);
        pendingStartWanted = false;
        if (pendingFrame != null) {
            recycle(pendingFrame);
            pendingFrame = null;
        }
        if (clearFrames) {
            recycle(currentFrame);
            currentFrame = null;
            frameWidth = 0;
            frameHeight = 0;
            if (liveView != null) liveView.setImageBitmap(null);
        }
        lastFrameAt = 0;
        liveState = LIVE_STOPPING;
        liveStateAt = SystemClock.uptimeMillis();
        host.bridge().stopLiveView();
        main.removeCallbacks(stopSettleRunnable);
        main.postDelayed(stopSettleRunnable, LIVE_SETTLE_MS);
    }

    /**
     * Consecutive-failure policy: stop, show the tappable recovery pill and
     * retry with 1/2/3/5 s backoff (capped) instead of giving up.
     */
    private void onLiveFailure(String reason) {
        if (destroyed || liveState == LIVE_STOPPING) return;
        liveGeneration++;
        if (scheduledStart != null) {
            main.removeCallbacks(scheduledStart);
            scheduledStart = null;
        }
        main.removeCallbacks(renderFrameRunnable);
        if (pendingFrame != null) {
            recycle(pendingFrame);
            pendingFrame = null;
        }
        main.removeCallbacks(watchdogRunnable);
        host.bridge().stopLiveView();
        liveState = LIVE_FAILED;
        liveStateAt = SystemClock.uptimeMillis();
        liveFailureCount++;
        showRecovery(liveFailureCount >= MAX_RESTARTS
                ? "取景未恢复 · 点击重试" : "取景中断 · 点击重试");
        final int retryGeneration = liveGeneration;
        long delay = RETRY_BACKOFF_MS[Math.min(liveFailureCount - 1,
                RETRY_BACKOFF_MS.length - 1)];
        main.removeCallbacks(retryRunnable);
        retryRunnable = () -> {
            retryRunnable = null;
            if (retryGeneration != liveGeneration) return;
            if (!canStartLive() || liveState != LIVE_FAILED) return;
            liveState = LIVE_IDLE;
            requestStart();
        };
        main.postDelayed(retryRunnable, delay);
    }

    /** Recovery-pill action: clear the failure streak and retry immediately. */
    private void retryLiveNow() {
        if (!canStartLive()) return;
        liveFailureCount = 0;
        main.removeCallbacks(retryRunnable);
        if (scheduledStart != null) {
            main.removeCallbacks(scheduledStart);
            scheduledStart = null;
        }
        liveGeneration++;
        host.bridge().stopLiveView();
        liveState = LIVE_STOPPING;
        liveStateAt = SystemClock.uptimeMillis();
        pendingStartWanted = true;
        main.removeCallbacks(stopSettleRunnable);
        main.postDelayed(stopSettleRunnable, LIVE_SETTLE_MS);
    }

    private void handleFrame(Bitmap bitmap, int generation) {
        if (bitmap == null) return;
        if (generation != liveGeneration || destroyed || !visible
                || remoteMode != MODE_WIFI
                || liveState == LIVE_STOPPING || liveState == LIVE_IDLE
                || liveState == LIVE_FAILED) {
            recycle(bitmap);
            return;
        }
        lastFrameAt = SystemClock.uptimeMillis();
        liveFailureCount = 0;
        if (liveState == LIVE_STARTING) {
            liveState = LIVE_STREAMING;
            liveStateAt = lastFrameAt;
        }
        main.removeCallbacks(retryRunnable);
        hideRecovery();

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        boolean first = currentFrame == null || width != frameWidth || height != frameHeight;
        frameWidth = width;
        frameHeight = height;

        if (pendingFrame != null && pendingFrame != bitmap) recycle(pendingFrame);
        pendingFrame = bitmap;

        if (first) {
            // Keep the camera's native orientation: landscape streams are shown
            // letterboxed and never rotated into a portrait strip.
            if (rotation != 0) pendingAutoRotation = 0;
        }

        long since = SystemClock.uptimeMillis() - lastRenderAt;
        long frameDelay = 1000L / MAX_FRAME_FPS;
        if (since >= frameDelay) {
            renderPendingFrame();
        } else {
            main.postDelayed(renderFrameRunnable, frameDelay - since);
        }
    }

    private void renderPendingFrame() {
        Bitmap next = pendingFrame;
        pendingFrame = null;
        if (next == null) return;
        if (destroyed || !visible || liveState == LIVE_STOPPING
                || liveState == LIVE_IDLE || liveState == LIVE_FAILED) {
            recycle(next);
            return;
        }
        Bitmap old = currentFrame;
        currentFrame = next;
        liveView.setImageBitmap(next);
        lastRenderAt = SystemClock.uptimeMillis();
        if (old != null && old != next) recycle(old);
        if (!rotating) applyTransform();
        if (pendingAutoRotation >= 0 && !rotating) {
            int target = pendingAutoRotation;
            pendingAutoRotation = -1;
            animateRotationTo(target, true);
        }
    }

    /** Fits the (possibly rotated) frame inside the view without stretching. */
    private float scaleForRotation(int degrees) {
        if (currentFrame == null) return 1f;
        int width = liveView.getWidth();
        int height = liveView.getHeight();
        if (width <= 0 || height <= 0) return 1f;
        int fw = currentFrame.getWidth();
        int fh = currentFrame.getHeight();
        if (fw <= 0 || fh <= 0) return 1f;
        float base = Math.min(width / (float) fw, height / (float) fh);
        boolean quarter = degrees == 90 || degrees == 270;
        float fitWidth = (quarter ? fh : fw) * base;
        float fitHeight = (quarter ? fw : fh) * base;
        float scale = Math.min(width / fitWidth, height / fitHeight);
        if (Float.isNaN(scale) || Float.isInfinite(scale) || scale < 1f) scale = 1f;
        return Math.min(scale, 4f);
    }

    private void applyTransform() {
        if (liveView == null || currentFrame == null) return;
        int width = liveView.getWidth();
        int height = liveView.getHeight();
        if (width <= 0 || height <= 0) return;
        // CENTER_CROP handles scaling itself; rotation is not used any more.
        liveView.setPivotX(width / 2f);
        liveView.setPivotY(height / 2f);
        liveView.setRotation(0f);
        liveView.setScaleX(1f);
        liveView.setScaleY(1f);
    }

    /**
     * Maps a touch in view coordinates to the normalized [0,1] live-view image
     * space, accounting for the view rotation and the fit scale.
     */
    private float[] mapToNormalized(float x, float y) {
        if (currentFrame == null) return null;
        int width = liveView.getWidth();
        int height = liveView.getHeight();
        int fw = frameWidth;
        int fh = frameHeight;
        if (width <= 0 || height <= 0 || fw <= 0 || fh <= 0) return null;
        float base = Math.min(width / (float) fw, height / (float) fh);
        if (base <= 0f) return null;
        float scale = viewScale <= 0f ? 1f : viewScale;
        float dx = (x - width / 2f) / scale;
        float dy = (y - height / 2f) / scale;
        double radians = Math.toRadians(rotation);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        // Inverse of the view rotation: local = R^T * delta.
        float lx = cos * dx + sin * dy;
        float ly = -sin * dx + cos * dy;
        float nx = 0.5f + lx / (fw * base);
        float ny = 0.5f + ly / (fh * base);
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null;
        return new float[]{nx, ny};
    }

    // ======================================================================
    //  Tap to focus
    // ======================================================================
    private void onPreviewTap(float x, float y) {
        if (destroyed || focusing || capturing || rotating) return;
        if (host.bridge().state() != CameraBridge.State.CONNECTED) return;
        if (currentFrame == null || liveState != LIVE_STREAMING) return;
        // Touch arrives in previewLayer coordinates; the camera frame is a
        // smaller portrait rectangle inside it, so convert to frame-local space.
        float localX = x - liveView.getLeft();
        float localY = y - liveView.getTop();
        if (localX < 0f || localY < 0f
                || localX > liveView.getWidth() || localY > liveView.getHeight()) {
            return;
        }
        float[] normalized = mapToNormalized(localX, localY);
        if (normalized == null) return;

        focusing = true;
        final int token = ++focusToken;
        lastFocusX = normalized[0];
        lastFocusY = normalized[1];
        hasFocusPoint = true;
        reticle.show(x, y);
        UiKit.hapticTick(previewLayer);

        host.bridge().focusAt(normalized[0], normalized[1], new CameraBridge.FocusCallback() {
            @Override
            public void onFocused(boolean success) {
                if (destroyed || token != focusToken) return;
                focusing = false;
                reticle.markResult(success);
                if (success) UiKit.hapticTick(previewLayer);
            }
        });
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (token == focusToken) {
                    focusing = false;
                    focusToken++;
                }
            }
        }, FOCUS_TIMEOUT_MS);
    }

    // ======================================================================
    //  Shutter
    // ======================================================================
    private void onShutter() {
        if (destroyed || capturing || remoteMode != MODE_WIFI) return;
        if (host.bridge().state() != CameraBridge.State.CONNECTED) {
            host.showSnack("相机未连接");
            return;
        }
        // Serialize: stop the stream through the state machine before capture.
        stopNow(false);
        capturing = true;
        final int token = ++captureToken;
        updateCaptureUi(true);
        // One maximum-strength pulse per shutter press (never per animation frame).
        Haptics.strong(activity());

        CameraBridge.CaptureCallback callback = new CameraBridge.CaptureCallback() {
            @Override
            public void onStarted() {
                if (destroyed || token != captureToken) return;
                // The shot is taken: release the shutter immediately so the next
                // photo can be captured without waiting for the camera callback.
                capturing = false;
                updateCaptureUi(false);
            }

            @Override
            public void onComplete() {
                if (destroyed || token != captureToken) return;
                finishCapture(true);
                requestStart();
            }

            @Override
            public void onError(String message) {
                if (destroyed || token != captureToken) return;
                finishCapture(false);
                requestStart();
                if (message != null && !message.isEmpty()) host.showSnack(message);
            }
        };

        if (hasFocusPoint) {
            host.bridge().captureAt(lastFocusX, lastFocusY, callback);
        } else {
            host.bridge().capture(false, callback);
        }

        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!destroyed && token == captureToken && capturing) {
                    finishCapture(true);
                    requestStart();
                }
            }
        }, CAPTURE_TIMEOUT_MS);
    }

    private void finishCapture(boolean success) {
        if (!capturing) return;
        capturing = false;
        captureToken++;
        updateCaptureUi(false);
        if (!success || destroyed) return;
        flashScreen();
        UiKit.haptic(previewLayer);
    }

    private void updateCaptureUi(boolean busy) {
        if (shutterButton != null) shutterButton.setCapturing(busy);
        if (processingPill != null) {
            UiKit.setVisibleAnimated(processingPill, busy, busy ? 140 : 180);
        }
    }

    private void flashScreen() {
        if (flash == null || remoteMode != MODE_WIFI) return;
        flash.animate().cancel();
        flash.setVisibility(View.VISIBLE);
        flash.setAlpha(0f);
        flash.animate().alpha(0.82f).setDuration(70).withEndAction(() -> {
            if (destroyed) return;
            flash.animate().alpha(0f).setDuration(320).withEndAction(() -> {
                if (flash != null) flash.setVisibility(View.GONE);
            }).start();
        }).start();
    }

    // ======================================================================
    //  Rotation
    // ======================================================================
    /**
     * Animates the live ImageView rotation (and its derived fit scale) instead
     * of snapping. The logical {@link #rotation} is updated immediately so the
     * tap-to-focus mapping stays consistent; taps are ignored while animating.
     */
    private void animateRotationTo(final int target, boolean animate) {
        final int normalized = ((target % 360) + 360) % 360;
        rotation = normalized;
        if (rotationAnimator != null) {
            rotationAnimator.cancel();
            rotationAnimator = null;
        }
        if (liveView == null) {
            rotating = false;
            return;
        }
        liveView.animate().cancel();
        final float targetScale = scaleForRotation(normalized);
        if (!animate || currentFrame == null || liveView.getWidth() <= 0) {
            rotating = false;
            viewScale = targetScale;
            applyTransform();
            return;
        }
        final float startRotation = liveView.getRotation();
        float delta = normalized - (startRotation % 360f);
        if (delta > 180f) delta -= 360f;
        if (delta < -180f) delta += 360f;
        final float endRotation = startRotation + delta;
        final float startScale = viewScale;
        rotating = true;
        rotationAnimator = ValueAnimator.ofFloat(0f, 1f);
        rotationAnimator.setDuration(ROTATION_MS);
        rotationAnimator.setInterpolator(UiKit.DECELERATE_SOFT);
        rotationAnimator.addUpdateListener(animation -> {
            if (destroyed || liveView == null) return;
            float fraction = (float) animation.getAnimatedValue();
            liveView.setRotation(startRotation + (endRotation - startRotation) * fraction);
            float scale = startScale + (targetScale - startScale) * fraction;
            viewScale = scale;
            liveView.setScaleX(scale);
            liveView.setScaleY(scale);
        });
        rotationAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                rotationAnimator = null;
                rotating = false;
                if (destroyed) return;
                rotation = normalized;
                viewScale = targetScale;
                applyTransform();
            }
        });
        rotationAnimator.start();
    }

    private void cancelRotationAnimation() {
        if (rotationAnimator != null) {
            rotationAnimator.cancel();
            rotationAnimator = null;
        }
        rotating = false;
        pendingAutoRotation = -1;
    }

    // ======================================================================
    //  Status
    // ======================================================================
    private void updateStatus() {
        if (destroyed || root == null) return;
        CameraBridge bridge = host.bridge();
        CameraBridge.State state = bridge.state();
        boolean connected = state == CameraBridge.State.CONNECTED;

        if (stateDotColor() != 0 && statusDotBackground != null) {
            statusDotBackground.setColor(stateDotColor());
        }
        if (modelLabel != null) {
            String model = bridge.model();
            modelLabel.setText(connected
                    ? (model == null || model.isEmpty() ? "相机" : model)
                    : (state == CameraBridge.State.BLE_ONLY ? "等待相机 Wi-Fi" : "未连接"));
        }
        int battery = connected ? bridge.batteryLevel() : -1;
        if (batteryLabel != null) {
            batteryLabel.setText(battery < 0 ? "—" : battery + "%");
        }
        if (batteryGlyph != null) {
            batteryGlyph.setTint(battery >= 0 && battery <= 20 ? t.danger : t.text);
        }
    }

    private int stateDotColor() {
        switch (host.bridge().state()) {
            case CONNECTED:
                return t.success;
            case BLE_ONLY:
                return t.accent;
            default:
                return t.muted;
        }
    }

    // ======================================================================
    //  Connection state
    // ======================================================================
    private void updateConnectionUi(CameraBridge.State state) {
        if (root == null) return;
        lastUiState = state;
        if (remoteMode == MODE_IR) {
            // IR is a peer mode: never reveal WiFi-only overlays behind it.
            if (state == CameraBridge.State.CONNECTED) updateStatus();
            return;
        }
        boolean connected = state == CameraBridge.State.CONNECTED;

        if (placeholder != null) {
            if (connected) {
                if (placeholder.getVisibility() == View.VISIBLE) {
                    placeholder.animate().alpha(0f).setDuration(160)
                            .withEndAction(() -> {
                                placeholder.setVisibility(View.GONE);
                                placeholderVisible = false;
                            }).start();
                }
            } else {
                renderPlaceholder(state);
                if (placeholder.getVisibility() != View.VISIBLE) {
                    placeholder.setAlpha(0f);
                    placeholder.setVisibility(View.VISIBLE);
                    placeholder.animate().alpha(1f).setDuration(200).start();
                    placeholderVisible = true;
                }
            }
        }

        // Surrounding area stays themed; only the camera frame is black.
        UiKit.setVisibleAnimated(topBar, connected, 180);
        UiKit.setVisibleAnimated(bottomControls, connected, 180);

        if (connected) {
            updateStatus();
        } else {
            stopNow(true);
            updateCaptureUi(false);
            focusing = false;
            if (reticle != null) reticle.hideNow();
        }
    }

    private void startWifiConnect() {
        if (connecting || destroyed) return;
        connecting = true;
        renderPlaceholder(host.bridge().state());
        host.bridge().connectOverWifi(new CameraBridge.ConnectCallback() {
            @Override
            public void onConnected(com.ricoh.camera.sdk.wireless.api.CameraDevice device) {
                connecting = false;
                if (destroyed) return;
                host.showSnack("相机已连接");
            }

            @Override
            public void onError(String message) {
                connecting = false;
                if (destroyed) return;
                renderPlaceholder(host.bridge().state());
                host.showSnack(message == null ? "连接相机失败" : message);
            }
        });
    }

    private void openSettingsSheet() {
        if (host.hasOverlay()) return;
        if (host.bridge().state() != CameraBridge.State.CONNECTED) {
            host.showSnack("请先连接相机");
            return;
        }
        host.openOverlay(new CaptureSettingsSheet(host));
    }

    // ======================================================================
    //  Top-level mode switch (WiFi 遥控 / 红外遥控)
    // ======================================================================
    private void loadRemoteMode() {
        try {
            SharedPreferences prefs = activity().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            remoteMode = "ir".equals(prefs.getString(PREF_MODE, "wifi")) ? MODE_IR : MODE_WIFI;
        } catch (Throwable ignored) {
            remoteMode = MODE_WIFI;
        }
    }

    private void saveRemoteMode() {
        try {
            activity().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(PREF_MODE, remoteMode == MODE_IR ? "ir" : "wifi").apply();
        } catch (Throwable ignored) {
            // Preference failure must never break the remote tab.
        }
    }

    private void buildModeSwitch() {
        modeSwitchWrap = new FrameLayout(activity());
        GradientDrawable track = new GradientDrawable();
        // 40 dp capsule track; radius is exactly half its height.
        track.setColor(t.surfaceAlt);
        track.setCornerRadius(dp(20));
        track.setStroke(Math.max(1, dp(0.8f)), t.hairline);
        modeSwitchWrap.setBackground(track);
        // 3 dp horizontal inset keeps the pill off the track edge on both ends.
        modeSwitchWrap.setPadding(dp(3), 0, dp(3), 0);
        FrameLayout.LayoutParams wrapParams = new FrameLayout.LayoutParams(dp(252), dp(40));
        wrapParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        wrapParams.topMargin = dp(9);
        root.addView(modeSwitchWrap, wrapParams);

        modeIndicator = new View(activity());
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(t.accent);
        pill.setCornerRadius(dp(20)); // capsule: half of the 40 dp track height
        modeIndicator.setBackground(pill);
        FrameLayout.LayoutParams indicatorParams =
                new FrameLayout.LayoutParams(dp(123), FrameLayout.LayoutParams.MATCH_PARENT);
        indicatorParams.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
        modeSwitchWrap.addView(modeIndicator, indicatorParams);

        modeSwitchRow = UiKit.row(activity());
        String[] labels = {"WiFi 遥控", "红外遥控"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView tab = UiKit.label(activity(), t, labels[i], 12.5f, t.muted, 1);
            tab.setGravity(Gravity.CENTER);
            tab.setSingleLine(true);
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setOnClickListener(v -> setRemoteMode(index, true));
            modeTabs[i] = tab;
            modeSwitchRow.addView(tab, new LinearLayout.LayoutParams(0, -1, 1f));
        }
        modeSwitchWrap.addView(modeSwitchRow, new FrameLayout.LayoutParams(-1, -1));
        modeSwitchWrap.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                   oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) != (oldRight - oldLeft)
                    || (bottom - top) != (oldBottom - oldTop)) {
                layoutModeSwitch();
            }
        });
        modeSwitchWrap.post(this::layoutModeSwitch);
        updateModeTabs();
    }

    /**
     * Sizes/positions the indicator once per track size change; the animated
     * path only changes translationX, never LayoutParams.
     */
    private void layoutModeSwitch() {
        if (modeSwitchWrap == null || modeIndicator == null) return;
        int content = modeSwitchWrap.getWidth()
                - modeSwitchWrap.getPaddingLeft() - modeSwitchWrap.getPaddingRight();
        if (content <= 0) return;
        int half = content / 2;
        if (modeIndicatorAnimator != null) {
            modeIndicatorAnimator.cancel();
            modeIndicatorAnimator = null;
        }
        ViewGroup.LayoutParams params = modeIndicator.getLayoutParams();
        if (params.width != half) {
            params.width = half;
            modeIndicator.setLayoutParams(params);
        }
        modeIndicator.setTranslationX(remoteMode == MODE_IR ? half : 0f);
        updateModeTabs();
    }

    private void updateModeTabs() {
        for (int i = 0; i < modeTabs.length; i++) {
            TextView tab = modeTabs[i];
            if (tab == null) continue;
            boolean selected = i == remoteMode;
            tab.setTextColor(selected ? t.onAccent : t.muted);
            tab.setTypeface(selected ? t.typeface(1) : t.typeface(0));
        }
    }

    private void moveModeIndicator(int index, boolean animate) {
        if (modeSwitchWrap == null || modeIndicator == null) return;
        int content = modeSwitchWrap.getWidth()
                - modeSwitchWrap.getPaddingLeft() - modeSwitchWrap.getPaddingRight();
        if (content <= 0) {
            if (modeSwitchWrap.isAttachedToWindow()) {
                modeSwitchWrap.post(this::layoutModeSwitch);
            }
            return;
        }
        final int half = content / 2;
        ViewGroup.LayoutParams params = modeIndicator.getLayoutParams();
        if (params.width != half) {
            params.width = half;
            modeIndicator.setLayoutParams(params);
        }
        final float startX = modeIndicator.getTranslationX();
        final float targetX = index == MODE_IR ? half : 0f;
        if (modeIndicatorAnimator != null) {
            modeIndicatorAnimator.cancel();
            modeIndicatorAnimator = null;
        }
        // Weight changes immediately; colours cross-fade with the pill.
        if (modeTabs[0] != null) {
            modeTabs[0].setTypeface(index == 0 ? t.typeface(1) : t.typeface(0));
        }
        if (modeTabs[1] != null) {
            modeTabs[1].setTypeface(index == 1 ? t.typeface(1) : t.typeface(0));
        }
        if (!animate) {
            modeIndicator.setTranslationX(targetX);
            updateModeTabs();
            return;
        }
        final int[] fromColors = {
                modeTabs[0] == null ? t.muted : modeTabs[0].getCurrentTextColor(),
                modeTabs[1] == null ? t.muted : modeTabs[1].getCurrentTextColor()};
        final int[] toColors = {
                index == 0 ? t.onAccent : t.muted,
                index == 1 ? t.onAccent : t.muted};
        final ArgbEvaluator evaluator = new ArgbEvaluator();
        modeIndicatorAnimator = ValueAnimator.ofFloat(0f, 1f);
        modeIndicatorAnimator.setDuration(260);
        modeIndicatorAnimator.setInterpolator(UiKit.DECELERATE_SOFT);
        modeIndicatorAnimator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            modeIndicator.setTranslationX(startX + (targetX - startX) * fraction);
            for (int i = 0; i < modeTabs.length; i++) {
                if (modeTabs[i] != null) {
                    modeTabs[i].setTextColor((int) evaluator.evaluate(
                            fraction, fromColors[i], toColors[i]));
                }
            }
        });
        modeIndicatorAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                modeIndicatorAnimator = null;
                if (modeIndicator != null) modeIndicator.setTranslationX(targetX);
                updateModeTabs();
            }
        });
        modeIndicatorAnimator.start();
    }

    private void setRemoteMode(int mode, boolean animate) {
        if (mode == remoteMode) {
            updateModeTabs();
            return;
        }
        remoteMode = mode;
        saveRemoteMode();
        UiKit.hapticTick(modeSwitchWrap);
        cancelLayerAnimations();
        // moveModeIndicator cross-fades the label colours as the pill slides.
        moveModeIndicator(mode, true);
        if (mode == MODE_IR) {
            enterIrMode(animate);
        } else {
            enterWifiMode(animate);
        }
    }

    /** Initial visibility reconciliation without entrance animations. */
    private void applyModeUi(boolean animate) {
        updateModeTabs();
        moveModeIndicator(remoteMode, false);
        if (remoteMode == MODE_IR) {
            stopNow(true);
            stopStatusTicker();
            if (irBackdrop != null) {
                irBackdrop.animate().cancel();
                irBackdrop.setVisibility(View.VISIBLE);
                irBackdrop.setAlpha(1f);
            }
            setWifiLayersShown(false, animate);
            attachIrInput();
            if (irPanel != null) {
                irPanel.setVisibility(View.VISIBLE);
                irPanel.setAlpha(1f);
                irPanel.setTranslationX(0f);
                irPanel.setScaleX(1f);
                irPanel.setScaleY(1f);
            }
            updateIrStatusUi();
            updateConnectionUi(host.bridge().state());
        } else {
            releaseIr();
            resetWifiLayerAlphas();
            if (irBackdrop != null) {
                irBackdrop.animate().cancel();
                irBackdrop.setVisibility(View.GONE);
                irBackdrop.setAlpha(1f);
            }
            if (irPanel != null) {
                irPanel.setVisibility(View.GONE);
                irPanel.setAlpha(1f);
                irPanel.setTranslationX(0f);
                irPanel.setScaleX(1f);
                irPanel.setScaleY(1f);
            }
            if (previewLayer != null) {
                previewLayer.setVisibility(View.VISIBLE);
                previewLayer.setAlpha(1f);
            }
            updateConnectionUi(host.bridge().state());
            updateStatus();
            if (visible) {
                startStatusTicker();
                resetLiveRetries();
                requestStart();
            }
        }
    }

    private void enterIrMode(boolean animate) {
        stopNow(true);
        stopStatusTicker();
        cancelRotationAnimation();
        if (liveView != null && currentFrame != null) applyTransform();
        focusing = false;
        if (reticle != null) reticle.hideNow();
        // Backdrop is opaque on the very first frame: no black can ever show.
        showIrBackdrop();
        if (animate) {
            // Horizontal cross-fade: WiFi chrome leaves to the left.
            animateWifiLayersOut(190);
        } else {
            setWifiLayersShown(false, false);
        }
        attachIrInput();
        animateIrPanelIn(animate);
        updateIrStatusUi();
        updateConnectionUi(host.bridge().state());
        if (animate) animateIrEntrance();
    }

    private void enterWifiMode(boolean animate) {
        releaseIr();
        // IR panel leaves to the left while the WiFi layer enters from the right.
        animateIrPanelOut(animate);
        updateConnectionUi(host.bridge().state());
        updateStatus();
        if (visible) {
            startStatusTicker();
            resetLiveRetries();
            requestStart();
        }
        animateWifiLayersIn(animate);
        // Backdrop stays opaque until the WiFi layer is fully opaque (290 ms),
        // then dissolves: no frame can expose the black root on any side.
        dissolveIrBackdrop(animate, animate ? 300L : 0L);
    }

    private View[] wifiLayers() {
        return new View[]{previewLayer, topBar, bottomControls, placeholder, recoveryPill};
    }

    /** WiFi chrome slides left 18 dp while fading; full-screen layers only fade. */
    private void animateWifiLayersOut(int duration) {
        for (View layer : wifiLayers()) {
            if (layer == null || layer.getVisibility() != View.VISIBLE) continue;
            layer.animate().cancel();
            layer.setScaleX(1f);
            layer.setScaleY(1f);
            // Translating the full-screen preview would drag its dark edge into
            // view; full-screen layers fade in place, only chrome shifts.
            if (isFullscreenWifiLayer(layer)) {
                layer.setTranslationX(0f);
                layer.animate().alpha(0f).setDuration(duration)
                        .setInterpolator(UiKit.DECELERATE).start();
            } else {
                layer.animate().alpha(0f).translationX(-dp(18))
                        .setDuration(duration).setInterpolator(UiKit.DECELERATE).start();
            }
        }
    }

    /** WiFi chrome enters from +24 dp; the preview/placeholder only cross-fade. */
    private void animateWifiLayersIn(boolean animate) {
        // Restore the frame after a previously interrupted entrance animation.
        if (liveView != null) {
            liveView.animate().cancel();
            liveView.setAlpha(1f);
            liveView.setTranslationX(0f);
            liveView.setScaleX(1f);
            liveView.setScaleY(1f);
        }
        for (View layer : wifiLayers()) {
            if (layer == null) continue;
            if (layer == previewLayer) layer.setVisibility(View.VISIBLE);
            if (layer.getVisibility() != View.VISIBLE) continue;
            layer.animate().cancel();
            layer.setScaleX(1f);
            layer.setScaleY(1f);
            boolean chrome = !isFullscreenWifiLayer(layer);
            if (!animate) {
                layer.setAlpha(1f);
                layer.setTranslationX(0f);
                continue;
            }
            layer.setAlpha(0f);
            layer.setTranslationX(chrome ? dp(24) : 0f);
            if (chrome) {
                layer.animate().alpha(1f).translationX(0f)
                        .setDuration(290).setInterpolator(UiKit.DECELERATE_SOFT).start();
            } else {
                layer.animate().alpha(1f)
                        .setDuration(290).setInterpolator(UiKit.DECELERATE_SOFT).start();
            }
        }
    }

    private boolean isFullscreenWifiLayer(View layer) {
        return layer == previewLayer || layer == placeholder;
    }

    private void animateIrPanelIn(boolean animate) {
        if (irPanel == null) return;
        irPanel.animate().cancel();
        irPanel.setVisibility(View.VISIBLE);
        if (!animate) {
            irPanel.setAlpha(1f);
            irPanel.setTranslationX(0f);
            irPanel.setScaleX(1f);
            irPanel.setScaleY(1f);
            return;
        }
        irPanel.setAlpha(0f);
        irPanel.setTranslationX(dp(24));
        irPanel.setScaleX(0.985f);
        irPanel.setScaleY(0.985f);
        irPanel.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                .setDuration(290).setInterpolator(UiKit.DECELERATE_SOFT).start();
    }

    private void animateIrPanelOut(boolean animate) {
        if (irPanel == null) return;
        irPanel.animate().cancel();
        if (!animate) {
            irPanel.setVisibility(View.GONE);
            irPanel.setAlpha(1f);
            irPanel.setTranslationX(0f);
            irPanel.setScaleX(1f);
            irPanel.setScaleY(1f);
            return;
        }
        irPanel.setScaleX(1f);
        irPanel.setScaleY(1f);
        irPanel.animate().alpha(0f).translationX(-dp(18))
                .setDuration(190).setInterpolator(UiKit.DECELERATE)
                .withEndAction(() -> {
                    if (remoteMode == MODE_WIFI && irPanel != null) {
                        irPanel.setVisibility(View.GONE);
                        irPanel.setAlpha(1f);
                        irPanel.setTranslationX(0f);
                    }
                }).start();
    }

    private void showIrBackdrop() {
        if (irBackdrop == null) return;
        irBackdrop.animate().cancel();
        irBackdrop.setVisibility(View.VISIBLE);
        irBackdrop.setAlpha(1f);
    }

    /** Dissolves the themed backdrop once the WiFi layer is already opaque. */
    private void dissolveIrBackdrop(boolean animate, long startDelay) {
        if (irBackdrop == null) return;
        irBackdrop.animate().cancel();
        if (!animate) {
            irBackdrop.setVisibility(View.GONE);
            irBackdrop.setAlpha(1f);
            return;
        }
        irBackdrop.animate().alpha(0f).setDuration(220)
                .setStartDelay(Math.max(0L, startDelay))
                .setInterpolator(UiKit.DECELERATE)
                .withEndAction(() -> {
                    if (remoteMode == MODE_WIFI && irBackdrop != null) {
                        irBackdrop.setVisibility(View.GONE);
                        irBackdrop.setAlpha(1f);
                    }
                }).start();
    }

    private void setWifiLayersShown(boolean shown, boolean animate) {
        int duration = animate ? 190 : 0;
        if (shown) resetWifiLayerAlphas();
        for (View layer : wifiLayers()) {
            if (layer == null) continue;
            if (!shown) {
                fadeOut(layer, duration);
            } else if (layer == previewLayer) {
                // Instant reveal: the themed backdrop is still covering it, so
                // fading it in from black would only introduce a dark frame.
                layer.setVisibility(View.VISIBLE);
                layer.setAlpha(1f);
            }
            // Other WiFi overlays are reconciled by updateConnectionUi().
        }
    }

    /** A cancelled fade can leave transforms mid-way; restore before reuse. */
    private void resetWifiLayerAlphas() {
        for (View layer : wifiLayers()) {
            if (layer == null) continue;
            layer.setAlpha(1f);
            layer.setTranslationX(0f);
            layer.setScaleX(1f);
            layer.setScaleY(1f);
        }
    }

    private void fadeOut(final View view, int duration) {
        if (view == null) return;
        view.animate().cancel();
        if (duration <= 0) {
            view.setAlpha(0f);
            view.setVisibility(View.GONE);
            return;
        }
        view.animate().alpha(0f).setDuration(duration).withEndAction(() -> {
            if (remoteMode == MODE_IR && view != null) {
                view.setVisibility(View.GONE);
                view.setAlpha(1f);
            }
        }).start();
    }

    private void fadeIn(View view, int duration) {
        if (view == null) return;
        view.animate().cancel();
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(Math.max(1, duration)).start();
    }

    private void cancelLayerAnimations() {
        View[] layers = {previewLayer, topBar, bottomControls,
                placeholder, recoveryPill, irPanel, irBackdrop, modeSwitchWrap};
        for (View layer : layers) {
            if (layer != null) layer.animate().cancel();
        }
        if (irContent != null) {
            for (int i = 0; i < irContent.getChildCount(); i++) {
                irContent.getChildAt(i).animate().cancel();
            }
        }
        if (modeIndicatorAnimator != null) {
            modeIndicatorAnimator.cancel();
            modeIndicatorAnimator = null;
        }
    }

    private void animateWifiEntrance() {
        // Same language as the shell tabs: cross-fade + small horizontal shift
        // + slight scale (never vertical).
        if (liveView != null) {
            liveView.animate().cancel();
            liveView.setAlpha(0f);
            liveView.setTranslationX(dp(16));
            liveView.setScaleX(0.99f);
            liveView.setScaleY(0.99f);
            liveView.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                    .setDuration(300).setInterpolator(UiKit.DECELERATE_SOFT).start();
        }
        if (topBar != null && topBar.getVisibility() == View.VISIBLE) {
            topBar.animate().cancel();
            topBar.setAlpha(0f);
            topBar.setTranslationX(dp(16));
            topBar.setScaleX(0.99f);
            topBar.setScaleY(0.99f);
            topBar.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(30).setDuration(280)
                    .setInterpolator(UiKit.DECELERATE_SOFT).start();
        }
        if (bottomControls != null && bottomControls.getVisibility() == View.VISIBLE) {
            bottomControls.animate().cancel();
            bottomControls.setAlpha(0f);
            bottomControls.setTranslationX(dp(24));
            bottomControls.setScaleX(0.985f);
            bottomControls.setScaleY(0.985f);
            bottomControls.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(70).setDuration(300)
                    .setInterpolator(UiKit.DECELERATE_SOFT).start();
        }
        if (modeSwitchWrap != null) {
            modeSwitchWrap.animate().cancel();
            modeSwitchWrap.setTranslationX(0f);
            modeSwitchWrap.setAlpha(0.35f);
            modeSwitchWrap.animate().alpha(1f).setDuration(260)
                    .setInterpolator(UiKit.DECELERATE).start();
        }
    }

    private void animateIrEntrance() {
        if (irContent == null) return;
        for (int i = 0; i < irContent.getChildCount(); i++) {
            View child = irContent.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            child.animate().cancel();
            child.setAlpha(0f);
            child.setTranslationX(dp(18));
            child.setScaleX(0.99f);
            child.setScaleY(0.99f);
            child.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                    .setStartDelay(20L + i * 36L).setDuration(280)
                    .setInterpolator(UiKit.DECELERATE_SOFT).start();
        }
    }

    // ======================================================================
    //  IR panel (self-contained, works without any WiFi connection)
    // ======================================================================
    private void buildIrPanel() {
        irEmitter = IrRemote.hasEmitter(activity());
        // Full-bleed themed backdrop behind the IR panel: it is switched on
        // before any IR animation starts, so the black camera preview can never
        // show through during the WiFi<->IR transition.
        irBackdrop = new View(activity());
        irBackdrop.setBackgroundColor(t.background);
        irBackdrop.setVisibility(View.GONE);
        root.addView(irBackdrop, new FrameLayout.LayoutParams(-1, -1));

        irPanel = new FrameLayout(activity());
        irPanel.setBackgroundColor(t.background);
        irPanel.setVisibility(View.GONE);
        root.addView(irPanel, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(activity());
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // Focus keys scale up slightly while repeating; never clip them.
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        irContent = UiKit.column(activity());
        irContent.setClipChildren(false);
        irContent.setClipToPadding(false);
        // Bottom padding keeps the 112 dp shutter clear of the shell dock; the
        // remote content area already excludes the 96 dp bottom navigation.
        irContent.setPadding(dp(22), dp(62), dp(22), dp(24));
        scroll.addView(irContent, new ScrollView.LayoutParams(-1, -2));
        irPanel.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // ---- compact header: overline + title + one status line + short hints
        irContent.addView(UiKit.overline(activity(), t, "PENTAX O-RC1 · 38 kHz", t.accent));

        TextView title = UiKit.title(activity(), t, "红外遥控", 24);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.topMargin = dp(4);
        irContent.addView(title, titleParams);

        LinearLayout statusRow = UiKit.row(activity());
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(8);
        irContent.addView(statusRow, statusParams);

        View dot = new View(activity());
        GradientDrawable dotBackground = new GradientDrawable();
        dotBackground.setShape(GradientDrawable.OVAL);
        dotBackground.setColor(irEmitter ? t.success : t.danger);
        dot.setBackground(dotBackground);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.rightMargin = dp(8);
        statusRow.addView(dot, dotParams);

        irStatusText = UiKit.label(activity(), t,
                IrRemote.describe(activity()), 12, t.muted, 0);
        irStatusText.setSingleLine(true);
        statusRow.addView(irStatusText, new LinearLayout.LayoutParams(0, -2, 1f));

        irProgress = new ProgressBar(activity(), null,
                android.R.attr.progressBarStyleSmall);
        irProgress.setIndeterminateTintList(
                android.content.res.ColorStateList.valueOf(t.accent));
        irProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams irProgressParams =
                new LinearLayout.LayoutParams(dp(15), dp(15));
        irProgressParams.leftMargin = dp(6);
        statusRow.addView(irProgress, irProgressParams);

        irStatePill = UiKit.label(activity(), t, "", 11, t.onAccent, 1);
        irStatePill.setGravity(Gravity.CENTER);
        irStatePill.setBackground(UiKit.rounded(activity(), t.accent, t.radiusPill));
        irStatePill.setPadding(dp(9), dp(4), dp(9), dp(4));
        irStatePill.setVisibility(View.GONE);
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(-2, -2);
        pillParams.leftMargin = dp(8);
        statusRow.addView(irStatePill, pillParams);

        TextView hint = UiKit.body(activity(), t,
                "相机需设为遥控拍摄模式；对焦微调需关闭机身/镜头 AF。",
                10.5f, t.muted);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(8);
        irContent.addView(hint, hintParams);

        TextView repeatHint = UiKit.body(activity(), t,
                "短按发送一次；长按对焦键 0.5 秒开始连发（200 ms/次，最长 30 秒）。",
                10.5f, t.muted);
        LinearLayout.LayoutParams repeatParams = new LinearLayout.LayoutParams(-1, -2);
        repeatParams.topMargin = dp(2);
        irContent.addView(repeatHint, repeatParams);

        // Flexible spacer: keys live in the lower part of the screen, above the dock.
        irContent.addView(new View(activity()), new LinearLayout.LayoutParams(1, 0, 1f));

        if (irEmitter) {
            buildIrKeys();
        } else {
            buildIrEmptyState();
        }
    }

    private void buildIrKeys() {
        LinearLayout focusRow = UiKit.row(activity());
        focusRow.setGravity(Gravity.CENTER);
        // 8 dp breathing room + no clipping so the 78 dp circles stay fully
        // drawn while the active key scales up during 连发中.
        focusRow.setClipChildren(false);
        focusRow.setClipToPadding(false);
        focusRow.setPadding(dp(8), dp(8), dp(8), dp(8));
        IrKeyButton near = new IrKeyButton(IrRemote.KEY_NEAR, "对焦近处",
                Glyph.Icon.CHEVRON_LEFT, true, false);
        IrKeyButton far = new IrKeyButton(IrRemote.KEY_FAR, "对焦远处",
                Glyph.Icon.CHEVRON_RIGHT, true, false);
        focusRow.addView(near, new LinearLayout.LayoutParams(dp(78), dp(78)));
        focusRow.addView(new View(activity()), new LinearLayout.LayoutParams(dp(32), 1));
        focusRow.addView(far, new LinearLayout.LayoutParams(dp(78), dp(78)));
        LinearLayout.LayoutParams focusParams = new LinearLayout.LayoutParams(-2, -2);
        focusParams.gravity = Gravity.CENTER_HORIZONTAL;
        focusParams.bottomMargin = dp(10);
        irContent.addView(focusRow, focusParams);

        IrKeyButton shutter = new IrKeyButton(IrRemote.KEY_SHUTTER, "快门释放",
                Glyph.Icon.SHUTTER, false, true);
        LinearLayout.LayoutParams shutterParams =
                new LinearLayout.LayoutParams(dp(112), dp(112));
        shutterParams.gravity = Gravity.CENTER_HORIZONTAL;
        irContent.addView(shutter, shutterParams);
        irContent.addView(UiKit.spacer(activity(), 4));
    }

    private void buildIrEmptyState() {
        LinearLayout card = UiKit.column(activity());
        GradientDrawable background = new GradientDrawable();
        background.setColor(t.surface);
        background.setCornerRadius(dp(t.radiusInner));
        background.setStroke(Math.max(1, dp(0.7f)), t.hairline);
        card.setBackground(background);
        card.setPadding(dp(18), dp(16), dp(18), dp(14));

        FrameLayout iconCircle = new FrameLayout(activity());
        iconCircle.setBackground(UiKit.rounded(activity(), t.accentSoft, t.radiusPill));
        Glyph icon = new Glyph(activity(), Glyph.Icon.WARNING);
        icon.setTint(t.danger);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(22), dp(22));
        iconParams.gravity = Gravity.CENTER;
        iconCircle.addView(icon, iconParams);
        LinearLayout.LayoutParams circleParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        circleParams.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(iconCircle, circleParams);

        TextView title = UiKit.label(activity(), t, "此手机没有红外发射器", 15, t.text, 2);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(10);
        card.addView(title, titleParams);

        TextView body = UiKit.body(activity(), t,
                IrRemote.describe(activity()) + "\n红外遥控需要手机自带的红外发射器；"
                        + "可切回「WiFi 遥控」或使用相机机身按键。",
                11.5f, t.muted);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(0, dp(6), 0, 0);
        card.addView(body, new LinearLayout.LayoutParams(-1, -2));

        irContent.addView(card, new LinearLayout.LayoutParams(-1, -2));
        irContent.addView(UiKit.spacer(activity(), 4));
    }

    /** Sends exactly one O-RC1 frame; async, guarded so ticks never overlap. */
    private void sendIrFrame(int key) {
        if (destroyed || remoteMode != MODE_IR || irSending) return;
        irSending = true;
        updateIrStatusUi();
        IrRemote.send(activity(), key, (sent, message) -> {
            if (destroyed) return;
            irSending = false;
            if (!sent) {
                stopIrRepeat(null);
                host.showSnack(message == null || message.isEmpty()
                        ? "红外发送失败" : message);
            }
            updateIrStatusUi();
        });
    }

    /**
     * Self-driving repeat loop: ticks every 200 ms from a Handler and skips a
     * tick while the previous frame is still in flight (frames are ~95 ms), so
     * repeat speed never depends on callback timing.
     */
    private final Runnable irRepeatTick = new Runnable() {
        @Override
        public void run() {
            if (destroyed || remoteMode != MODE_IR || irActiveRepeat == null) return;
            if (SystemClock.uptimeMillis() - irRepeatArmedAt > IrRemote.REPEAT_LIMIT_MS) {
                stopIrRepeat("已停止连发（30 秒上限）");
                return;
            }
            // Continuous, subtle haptic tick on every 200 ms repeat frame; the
            // loop is removed on release/cancel so it stops immediately.
            Haptics.tick(activity());
            if (!irSending) sendIrFrame(irRepeatKey);
            main.postDelayed(this, IrRemote.REPEAT_INTERVAL_MS);
        }
    };

    private void beginIrRepeat(IrKeyButton source) {
        if (source == null || destroyed || remoteMode != MODE_IR) return;
        if (irActiveRepeat != null || irSending) return;
        irActiveRepeat = source;
        irRepeatKey = source.key;
        irRepeatArmedAt = SystemClock.uptimeMillis();
        source.setRepeating(true);
        Haptics.medium(activity());
        updateIrStatusUi();
        sendIrFrame(irRepeatKey);
        main.removeCallbacks(irRepeatTick);
        main.postDelayed(irRepeatTick, IrRemote.REPEAT_INTERVAL_MS);
    }

    private void stopIrRepeat(String message) {
        IrKeyButton source = irActiveRepeat;
        irActiveRepeat = null;
        irRepeatKey = -1;
        main.removeCallbacks(irRepeatTick);
        if (source != null) source.setRepeating(false);
        updateIrStatusUi();
        if (message != null) host.showSnack(message);
    }

    private void updateIrStatusUi() {
        if (irStatusText == null) return;
        if (irSending) {
            irStatusText.setText("发送中…");
        } else if (irActiveRepeat != null) {
            irStatusText.setText(irActiveRepeat.key == IrRemote.KEY_NEAR
                    ? "连发中 · 对焦近处" : "连发中 · 对焦远处");
        } else {
            irStatusText.setText(IrRemote.describe(activity()));
        }
        if (irProgress != null) {
            irProgress.setVisibility(irSending ? View.VISIBLE : View.GONE);
        }
        if (irStatePill != null) {
            boolean show = irSending || irActiveRepeat != null;
            if (show) {
                irStatePill.setText(irSending ? "发送中…" : "连发中");
                if (irStatePill.getVisibility() != View.VISIBLE) {
                    irStatePill.setAlpha(0f);
                    irStatePill.setVisibility(View.VISIBLE);
                    irStatePill.animate().alpha(1f).setDuration(160).start();
                }
            } else if (irStatePill.getVisibility() == View.VISIBLE) {
                irStatePill.animate().alpha(0f).setDuration(140)
                        .withEndAction(() -> {
                            if (irStatePill != null) irStatePill.setVisibility(View.GONE);
                        }).start();
            }
        }
        boolean locked = irSending || irActiveRepeat != null;
        for (IrKeyButton button : irButtons) button.setDimmed(locked);
    }

    private void releaseIr() {
        irActiveRepeat = null;
        irRepeatKey = -1;
        main.removeCallbacks(irRepeatTick);
        for (IrKeyButton button : irButtons) {
            button.cleanup();
            button.detachListeners();
        }
        irSending = false;
        if (!destroyed) updateIrStatusUi();
    }

    private void attachIrInput() {
        for (IrKeyButton button : irButtons) button.attachListener();
    }

    /**
     * O-RC1 key. {@code primary} = 112 dp circular accent shutter key;
     * otherwise a 78 dp tonal focus key. The touch listener owns all sends;
     * no click listener is attached (it would steal the long-press gesture).
     */
    private final class IrKeyButton extends FrameLayout implements View.OnTouchListener {
        final int key;
        private final boolean repeatable;
        private final boolean primary;
        private final TextView caption;
        private boolean pressed;
        private boolean repeating;
        private boolean movedAway;
        private float downRawX;
        private float downRawY;

        private final Runnable armRunnable = new Runnable() {
            @Override
            public void run() {
                if (!pressed || !repeatable || destroyed || remoteMode != MODE_IR) return;
                beginIrRepeat(IrKeyButton.this);
            }
        };

        IrKeyButton(int key, String label, Glyph.Icon icon, boolean repeatable, boolean primary) {
            super(activity());
            this.key = key;
            this.repeatable = repeatable;
            this.primary = primary;

            GradientDrawable content = new GradientDrawable();
            content.setShape(GradientDrawable.OVAL);
            content.setColor(primary ? t.accent : t.surfaceAlt);
            if (!primary) content.setStroke(Math.max(1, dp(0.8f)), t.hairline);
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.OVAL);
            mask.setColor(Color.WHITE);
            setBackground(new RippleDrawable(
                    ColorStateList.valueOf(withAlpha(primary ? t.onAccent : t.text,
                            primary ? 0x33 : 0x22)), content, mask));
            setClickable(true);
            setFocusable(true);
            setOnTouchListener(this);

            LinearLayout inner = UiKit.column(activity());
            inner.setGravity(Gravity.CENTER);
            addView(inner, new FrameLayout.LayoutParams(-1, -1));

            int iconSize = primary ? 32 : 22;
            Glyph glyph = new Glyph(activity(), icon);
            glyph.setTint(primary ? t.onAccent : t.accent);
            inner.addView(glyph, new LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)));

            TextView name = UiKit.label(activity(), t, label, primary ? 12.5f : 10.5f,
                    primary ? t.onAccent : t.text, 1);
            name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, -2);
            nameParams.topMargin = dp(primary ? 8 : 4);
            inner.addView(name, nameParams);

            if (!primary) {
                caption = UiKit.label(activity(), t, "长按连发", 8.5f, t.muted, 0);
                caption.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams captionParams =
                        new LinearLayout.LayoutParams(-1, -2);
                captionParams.topMargin = dp(1);
                inner.addView(caption, captionParams);
            } else {
                caption = null;
            }
            irButtons.add(this);
        }

        void setRepeating(boolean value) {
            repeating = value;
            if (caption != null) caption.setText(value ? "连发中…" : "长按连发");
            animate().cancel();
            setAlpha(1f);
            // 1.06x fits inside the 8 dp row padding; clipChildren is off as well.
            setScaleX(value ? 1.06f : 1f);
            setScaleY(value ? 1.06f : 1f);
        }

        void setDimmed(boolean dimmed) {
            animate().cancel();
            if (repeating) {
                setAlpha(1f);
                return;
            }
            setAlpha(dimmed ? 0.45f : 1f);
        }

        void cleanup() {
            pressed = false;
            movedAway = false;
            repeating = false;
            removeCallbacks(armRunnable);
            if (caption != null) caption.setText("长按连发");
            animate().cancel();
            setAlpha(1f);
            setScaleX(1f);
            setScaleY(1f);
        }

        void attachListener() {
            setOnTouchListener(this);
        }

        void detachListeners() {
            setOnTouchListener(null);
        }

        private void releaseParentIntercept() {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }

        private void resetPressScale() {
            animate().scaleX(1f).scaleY(1f).setDuration(180)
                    .setInterpolator(UiKit.OVERSHOOT).start();
        }

        /** One strong pulse for the shutter; a light tick for the focus keys. */
        private void hapticForSend() {
            if (key == IrRemote.KEY_SHUTTER) {
                Haptics.strong(activity());
            } else {
                Haptics.tick(activity());
            }
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (destroyed || remoteMode != MODE_IR) return false;
                    if (irSending || irActiveRepeat != null) return false;
                    pressed = true;
                    movedAway = false;
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    // The panel scrolls; without this the ScrollView steals the
                    // gesture on the first move and the long-press never fires.
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                    removeCallbacks(armRunnable);
                    if (repeatable) postDelayed(armRunnable, IrRemote.REPEAT_HOLD_MS);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!pressed) return false;
                    if (Math.abs(event.getRawX() - downRawX) > dp(40)
                            || Math.abs(event.getRawY() - downRawY) > dp(40)) {
                        movedAway = true;
                        removeCallbacks(armRunnable);
                        if (repeating || irActiveRepeat == this) stopIrRepeat(null);
                        releaseParentIntercept();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!pressed) return false;
                    pressed = false;
                    removeCallbacks(armRunnable);
                    releaseParentIntercept();
                    resetPressScale();
                    if (repeating) {
                        stopIrRepeat(null);
                    } else if (!movedAway) {
                        hapticForSend();
                        sendIrFrame(key);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (!pressed) return false;
                    pressed = false;
                    removeCallbacks(armRunnable);
                    releaseParentIntercept();
                    resetPressScale();
                    if (repeating) {
                        stopIrRepeat(null);
                    } else if (!movedAway) {
                        // Cancelled before the arm window still counts as a tap,
                        // unless the finger already moved away from the key.
                        hapticForSend();
                        sendIrFrame(key);
                    }
                    return true;
                default:
                    return false;
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            pressed = false;
            removeCallbacks(armRunnable);
            if (irActiveRepeat == this) stopIrRepeat(null);
            super.onDetachedFromWindow();
        }
    }

    // ======================================================================
    //  Lifecycle
    // ======================================================================
    @Override
    public void onShow() {
        visible = true;
        if (root == null || destroyed) return;
        loadRemoteMode();
        applyModeUi(false);
        updateStatus();
        // applyModeUi(WiFi) already requested a start through the state machine.
        if (remoteMode == MODE_IR) {
            updateIrStatusUi();
            animateIrEntrance();
        } else {
            animateWifiEntrance();
        }
    }

    @Override
    public void onHide() {
        visible = false;
        stopStatusTicker();
        // Drop the stream and stale frames so re-entry always starts clean.
        stopNow(true);
        releaseIr();
    }

    @Override
    public void onHostResume() {
        boolean nowVisible = computeVisible();
        visible = nowVisible;
        if (root == null || destroyed) {
            stopStatusTicker();
            stopNow(true);
            releaseIr();
            return;
        }
        if (!nowVisible) {
            stopStatusTicker();
            stopNow(true);
            releaseIr();
            return;
        }
        loadRemoteMode();
        applyModeUi(false);
        updateStatus();
        if (remoteMode == MODE_IR) updateIrStatusUi();
    }

    @Override
    public void onHostPause() {
        visible = false;
        stopStatusTicker();
        stopNow(true);
        releaseIr();
    }

    @Override
    public boolean onBackPressed() {
        return false;
    }

    @Override
    public void onDestroyView() {
        destroyed = true;
        visible = false;
        stopStatusTicker();
        stopNow(true);
        releaseIr();
        for (IrKeyButton button : irButtons) button.detachListeners();
        irButtons.clear();
        cancelRotationAnimation();
        cancelLayerAnimations();
        if (modeIndicatorAnimator != null) {
            modeIndicatorAnimator.cancel();
            modeIndicatorAnimator = null;
        }
        main.removeCallbacksAndMessages(null);
        host.bridge().removeStateListener(this);
        if (reticle != null) reticle.cancel();
        if (shutterButton != null) shutterButton.stop();
        // The screen may be disposed before/without a view (theme rebuild).
        if (liveView != null) liveView.setImageBitmap(null);
        recycle(currentFrame);
        currentFrame = null;
        recycle(pendingFrame);
        pendingFrame = null;
    }

    @Override
    public void onStateChanged(CameraBridge.State state) {
        if (destroyed) return;
        updateConnectionUi(state);
        if (state == CameraBridge.State.CONNECTED) {
            updateStatus();
            if (visible && remoteMode == MODE_WIFI) {
                resetLiveRetries();
                requestStart();
            }
        } else {
            stopNow(true);
        }
    }

    // ======================================================================
    //  Ticker / helpers
    // ======================================================================
    private void startStatusTicker() {
        stopStatusTicker();
        main.post(statusTicker);
    }

    private void stopStatusTicker() {
        main.removeCallbacks(statusTicker);
    }

    private void showRecovery(String text) {
        if (destroyed || recoveryPill == null) return;
        recoveryPill.setText(text);
        UiKit.setVisibleAnimated(recoveryPill, true, 200);
    }

    private void hideRecovery() {
        if (recoveryPill != null && recoveryPill.getVisibility() == View.VISIBLE) {
            UiKit.setVisibleAnimated(recoveryPill, false, 160);
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            try {
                bitmap.recycle();
            } catch (Throwable ignored) {
                // Already gone; nothing to release.
            }
        }
    }

    // ======================================================================
    //  Custom views
    // ======================================================================
    /** Animated focus reticle: springs in, holds, fades; tinted on AF result. */
    private static final class ReticleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final int successColor;
        private final int dangerColor;
        private float cx;
        private float cy;
        private float progress = -1f;
        private int color = Color.WHITE;
        private android.animation.ValueAnimator animator;

        ReticleView(Context context, int successColor, int dangerColor) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            this.successColor = successColor;
            this.dangerColor = dangerColor;
            paint.setAntiAlias(true);
            setWillNotDraw(false);
        }

        void show(float x, float y) {
            cx = x;
            cy = y;
            color = Color.WHITE;
            progress = 0f;
            if (animator != null) animator.cancel();
            animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(760L);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(animation -> {
                progress = (float) animation.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        void markResult(boolean success) {
            color = success ? successColor : dangerColor;
            invalidate();
        }

        void hideNow() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            progress = -1f;
            invalidate();
        }

        void cancel() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (progress < 0f) return;
            float scale;
            float alpha;
            if (progress < 0.18f) {
                float k = progress / 0.18f;
                scale = 1.22f - 0.22f * k;
                alpha = k;
            } else if (progress < 0.62f) {
                scale = 1f;
                alpha = 1f;
            } else {
                float k = (progress - 0.62f) / 0.38f;
                scale = 1f;
                alpha = 1f - k;
            }
            if (alpha <= 0f) return;
            int tint = (color & 0x00FFFFFF) | (((int) (alpha * 255f)) << 24);
            paint.setColor(tint);
            paint.setStyle(Paint.Style.STROKE);
            UiKit.drawFocusReticle(canvas, paint, cx, cy, 76f * density * scale);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(tint);
            canvas.drawCircle(cx, cy, 2.2f * density, paint);
        }
    }

    /** 78dp shutter: ring + inner disc, animated sweep while capturing. */
    private static final class ShutterView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arcRect = new RectF();
        private final float density;
        private final int ringColor;
        private final int fillColor;
        private final int accentColor;
        private boolean capturing;
        private float sweep;
        private android.animation.ValueAnimator spinner;

        ShutterView(Context context, int ringColor, int fillColor, int accentColor) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            this.ringColor = ringColor;
            this.fillColor = fillColor;
            this.accentColor = accentColor;
            setWillNotDraw(false);
            setClickable(true);
            setFocusable(true);
        }

        void setCapturing(boolean value) {
            if (capturing == value) return;
            capturing = value;
            if (value) {
                spinner = android.animation.ValueAnimator.ofFloat(0f, 360f);
                spinner.setDuration(900L);
                spinner.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                spinner.setInterpolator(new LinearInterpolator());
                spinner.addUpdateListener(animation -> {
                    sweep = (float) animation.getAnimatedValue();
                    invalidate();
                });
                spinner.start();
            } else {
                if (spinner != null) {
                    spinner.cancel();
                    spinner = null;
                }
                sweep = 0f;
                invalidate();
            }
        }

        void stop() {
            if (spinner != null) {
                spinner.cancel();
                spinner = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float centerX = getWidth() / 2f;
            float centerY = getHeight() / 2f;
            float radius = Math.min(centerX, centerY) - density;
            if (radius <= 0f) return;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.6f * density);
            paint.setColor(ringColor);
            canvas.drawCircle(centerX, centerY, radius, paint);

            float inner = radius - 8f * density;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(capturing ? 0xCCFFFFFF : fillColor);
            canvas.drawCircle(centerX, centerY, inner, paint);

            if (capturing) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(4f * density);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(accentColor);
                arcRect.set(centerX - radius + 2f * density, centerY - radius + 2f * density,
                        centerX + radius - 2f * density, centerY + radius - 2f * density);
                canvas.drawArc(arcRect, sweep, 92f, false, paint);
            }
        }
    }
}
