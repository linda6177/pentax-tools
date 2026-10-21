package com.ricohimaging.imagesync.modern;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.ricohimaging.imagesync.ModernHomeAct;

/**
 * Rebuilt, single-activity shell for Image Sync.
 *
 * <p>Home / gallery / remote capture / settings are lightweight screens inside
 * this activity, so tab changes never recreate an Activity and every transition
 * stays at a stable 60 fps. The original Ricoh SDK and the legacy Activities
 * remain untouched and are used for connection setup, settings and fallbacks.</p>
 */
public class ShellActivity extends Activity {

    public static final int TAB_HOME = 0;
    public static final int TAB_REMOTE = 1;
    public static final int TAB_GALLERY = 2;

    private static final int REQUEST_STORAGE = 4210;

    private Theme theme;
    private CameraBridge bridge;
    private ImageLoader imageLoader;

    private FrameLayout root;
    private FrameLayout content;
    private FrameLayout overlayLayer;
    private FrameLayout bottomHost;
    private BottomBar bottomBar;

    private final Screen[] screens = new Screen[3];
    private int currentTab = -1;
    private Screen overlay;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        theme = Theme.of(this);
        bridge = CameraBridge.get(this);
        imageLoader = ImageLoader.of(this);
        configureWindow();
        buildShell();
        int initialTab = state != null ? state.getInt("modern_tab", TAB_HOME) : TAB_HOME;
        selectTab(initialTab < 0 || initialTab > 2 ? TAB_HOME : initialTab, false);
    }

    // --------------------------------------------------------------- window
    private void configureWindow() {
        Window window = getWindow();
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(theme.background));
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
            window.setStatusBarContrastEnforced(false);
        }
        applySystemBars();
    }

    private void applySystemBars() {
        Window window = getWindow();
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!theme.dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    // ---------------------------------------------------------------- shell
    private void buildShell() {
        root = new FrameLayout(this);
        root.setBackgroundColor(theme.background);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        content = new FrameLayout(this);
        content.setClipChildren(true);
        column.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        bottomHost = new FrameLayout(this);
        bottomBar = new BottomBar(this, theme,
                new String[]{"首页", "遥控", "照片"},
                new Glyph.Icon[]{Glyph.Icon.HOME, Glyph.Icon.CAMERA,
                        Glyph.Icon.GALLERY});
        bottomBar.setListener(this::onTabSelected);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(-1, UiKit.dp(this, 82));
        barParams.setMargins(UiKit.dp(this, 16), 0, UiKit.dp(this, 16), UiKit.dp(this, 10));
        bottomHost.addView(bottomBar, barParams);
        column.addView(bottomHost, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 96)));

        overlayLayer = new FrameLayout(this);
        overlayLayer.setVisibility(View.GONE);
        root.addView(overlayLayer, new FrameLayout.LayoutParams(-1, -1));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            int left;
            int right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
                left = bars.left;
                right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            content.setPadding(left, top, right, 0);
            bottomHost.setPadding(bottomHost.getPaddingLeft(), 0,
                    bottomHost.getPaddingRight(), bottom);
            overlayLayer.setPadding(left, top, right, bottom);
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
    }

    // ------------------------------------------------------------- screens
    private Screen screen(int tab) {
        if (screens[tab] == null) {
            try {
                switch (tab) {
                    case TAB_REMOTE:
                        screens[tab] = new RemoteScreen(this);
                        break;
                    case TAB_GALLERY:
                        screens[tab] = new GalleryScreen(this);
                        break;
                    default:
                        screens[tab] = new HomeScreen(this);
                        break;
                }
            } catch (Throwable error) {
                screens[tab] = new FallbackScreen(this, "此页暂时不可用",
                        "界面模块加载失败，你仍然可以使用原版功能入口。");
            }
        }
        return screens[tab];
    }

    /** Rebuilds the current tab after a screen failed to construct. */
    public void retryCurrentTab() {
        int tab = currentTab < 0 ? TAB_HOME : currentTab;
        for (int i = 0; i < screens.length; i++) screens[i] = null;
        content.removeAllViews();
        currentTab = -1;
        selectTab(tab, false);
    }

    /**
     * Rebuilds the whole UI in place for a theme change (accent or light/dark).
     *
     * <p>A bitmap of the current pixels is captured first and cross-faded over
     * the rebuilt hierarchy, so the change reads as a dissolve instead of an
     * Activity reload. The old screens are disposed so their camera listeners
     * are removed before the new instances register theirs.</p>
     */
    private void rebuildForTheme(Runnable mutation) {
        android.graphics.Bitmap snapshot = null;
        if (root != null && root.getWidth() > 0 && root.getHeight() > 0) {
            try {
                snapshot = android.graphics.Bitmap.createBitmap(
                        root.getWidth(), root.getHeight(),
                        android.graphics.Bitmap.Config.ARGB_8888);
                root.draw(new android.graphics.Canvas(snapshot));
            } catch (Throwable ignored) {
                snapshot = null;
            }
        }
        mutation.run();
        theme = Theme.of(this);
        int tab = currentTab < 0 ? TAB_HOME : currentTab;
        if (overlay != null) {
            try {
                overlay.onDestroyView();
            } catch (Throwable ignored) {
                // A broken screen must not block the theme switch.
            }
            overlay = null;
        }
        for (int i = 0; i < screens.length; i++) {
            if (screens[i] != null) {
                try {
                    screens[i].onHostPause();
                    screens[i].onDestroyView();
                } catch (Throwable ignored) {
                    // Keep rebuilding even if one screen misbehaves.
                }
                screens[i] = null;
            }
        }
        currentTab = -1;
        buildShell();
        getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(theme.background));
        selectTab(tab, false);
        applySystemBars();
        if (snapshot != null) {
            final android.graphics.Bitmap fading = snapshot;
            android.widget.ImageView veil = new android.widget.ImageView(this);
            veil.setImageBitmap(fading);
            veil.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            root.addView(veil, new FrameLayout.LayoutParams(-1, -1));
            veil.animate().alpha(0f).setDuration(320)
                    .setInterpolator(UiKit.DECELERATE_SOFT)
                    .withEndAction(() -> {
                        root.removeView(veil);
                        veil.setImageDrawable(null);
                        fading.recycle();
                    }).start();
        }
    }

    /** Switches the accent palette with a seamless cross-fade. */
    public void applyAccent(int index) {
        if (index == theme.accentIndex) return;
        rebuildForTheme(() -> Theme.setAccentIndex(this, index));
    }

    /** 0 = follow system, 1 = light, 2 = dark. */
    public void applyThemeMode(int mode) {
        if (mode == Theme.modeIndex(this)) return;
        rebuildForTheme(() -> Theme.setModeIndex(this, mode));
    }

    private void onTabSelected(int tab) {
        if (tab == currentTab) {
            Screen screen = screen(tab);
            screen.view().animate().scaleX(0.985f).scaleY(0.985f).setDuration(90)
                    .withEndAction(() -> screen.view().animate().scaleX(1f).scaleY(1f)
                            .setDuration(220).setInterpolator(UiKit.OVERSHOOT).start())
                    .start();
            return;
        }
        selectTab(tab, true);
    }

    public void selectTab(int tab, boolean animate) {
        if (tab < 0 || tab > 2) return;
        if (tab == currentTab) return;
        Screen incoming = screen(tab);
        View incomingView = incoming.view();
        if (incomingView.getParent() instanceof ViewGroup) {
            ((ViewGroup) incomingView.getParent()).removeView(incomingView);
        }
        int direction = currentTab < 0 ? 0 : Integer.compare(tab, currentTab);
        Screen outgoing = currentTab >= 0 ? screens[currentTab] : null;
        View outgoingView = outgoing != null ? outgoing.view() : null;

        if (outgoingView == null) {
            content.addView(incomingView, new FrameLayout.LayoutParams(-1, -1));
            currentTab = tab;
            bottomBar.setSelected(tab, false);
            incoming.onShow();
            return;
        }

        // Interrupted fast tab switches must not leave orphaned screens behind.
        for (int i = content.getChildCount() - 1; i >= 0; i--) {
            View child = content.getChildAt(i);
            if (child != outgoingView && child != incomingView) {
                content.removeViewAt(i);
            }
        }
        int shift = UiKit.dp(this, 26) * direction;
        incomingView.setAlpha(0f);
        incomingView.setTranslationX(shift);
        incomingView.setTranslationZ(1f);
        outgoingView.setTranslationZ(0f);
        content.addView(incomingView, new FrameLayout.LayoutParams(-1, -1));

        if (animate) {
            incomingView.animate().alpha(1f).translationX(0f)
                    .setDuration(300).setInterpolator(UiKit.DECELERATE_SOFT).start();
            outgoingView.animate().alpha(0f).translationX(-shift * 0.45f)
                    .setDuration(200).setInterpolator(UiKit.DECELERATE)
                    .withEndAction(() -> content.removeView(outgoingView)).start();
        } else {
            content.removeView(outgoingView);
            incomingView.setAlpha(1f);
            incomingView.setTranslationX(0f);
        }
        bottomBar.setSelected(tab, true);
        if (outgoing != null) outgoing.onHide();
        currentTab = tab;
        incoming.onShow();
    }

    public int currentTab() {
        return currentTab;
    }

    // ------------------------------------------------------------- overlay
    /** Full-screen overlay (image viewer) with a quick zoom-through transition. */
    public void openOverlay(final Screen overlayScreen) {
        if (overlay != null) return;
        overlay = overlayScreen;
        final View view = overlayScreen.view();
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
        overlayLayer.removeAllViews();
        overlayLayer.setVisibility(View.VISIBLE);
        overlayLayer.addView(view, new FrameLayout.LayoutParams(-1, -1));
        overlayLayer.setAlpha(0f);
        overlayLayer.animate().alpha(1f).setDuration(200).start();
        view.setScaleX(0.94f);
        view.setScaleY(0.94f);
        view.setAlpha(0f);
        view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(320).setInterpolator(UiKit.DECELERATE_SOFT).start();
        bottomHost.animate().translationY(UiKit.dp(this, 120)).alpha(0f)
                .setDuration(220).start();
        overlayScreen.onShow();
    }

    /** Opens the rebuilt image viewer on an explicit camera image list. */
    public void openViewer(java.util.List<com.ricoh.camera.sdk.wireless.api.CameraImage> images,
                           int index) {
        openOverlay(new ImageViewerScreen(this, images, index));
    }

    public void openViewer(java.util.List<com.ricoh.camera.sdk.wireless.api.CameraImage> images,
                           int index, View sharedFrom) {
        openOverlay(new ImageViewerScreen(this, images, index));
    }

    public void closeOverlay() {
        if (overlay == null) return;
        final Screen closing = overlay;
        overlay = null;
        View view = closing.view();
        view.animate().alpha(0f).scaleX(1.02f).scaleY(1.02f)
                .setDuration(200).setInterpolator(UiKit.DECELERATE)
                .withEndAction(() -> {
                    overlayLayer.removeAllViews();
                    overlayLayer.setVisibility(View.GONE);
                    closing.onHide();
                }).start();
        overlayLayer.animate().alpha(1f).setDuration(200).start();
        bottomHost.animate().translationY(0f).alpha(1f)
                .setDuration(260).setInterpolator(UiKit.OVERSHOOT).start();
    }

    public boolean hasOverlay() {
        return overlay != null;
    }

    // ------------------------------------------------------------ lifecycle
    // ------------------------------------------------------------ swipe pages
    private float swipeDownX;
    private float swipeDownY;
    private long swipeDownAt;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (overlay == null && currentTab >= 0) {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    swipeDownX = event.getRawX();
                    swipeDownY = event.getRawY();
                    swipeDownAt = android.os.SystemClock.uptimeMillis();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                    float dx = event.getRawX() - swipeDownX;
                    float dy = event.getRawY() - swipeDownY;
                    long elapsed = android.os.SystemClock.uptimeMillis() - swipeDownAt;
                    float threshold = UiKit.dp(this, 56);
                    if (Math.abs(dx) > threshold
                            && Math.abs(dx) > Math.abs(dy) * 1.9f
                            && elapsed < 700L
                            && content.getChildCount() < 2) {
                        int direction = dx < 0 ? 1 : -1;
                        int next = currentTab + direction;
                        if (next >= 0 && next <= 2) {
                            selectTab(next, true);
                            return true;
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentTab >= 0) screen(currentTab).onHostResume();
        if (overlay != null) overlay.onHostResume();
        bottomBar.postDelayed(this::refreshBarSelected, 120);
    }

    private void refreshBarSelected() {
        if (content.getChildCount() == 0) return;
        View selected = content.getChildAt(content.getChildCount() - 1);
        if (selected.getTranslationX() != 0f) selected.setTranslationX(0f);
    }

    @Override
    protected void onPause() {
        if (currentTab >= 0) screen(currentTab).onHostPause();
        if (overlay != null) overlay.onHostPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        for (Screen screen : screens) {
            if (screen != null) screen.onDestroyView();
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("modern_tab", currentTab < 0 ? TAB_HOME : currentTab);
    }

    @Override
    public void onBackPressed() {
        if (overlay != null) {
            if (!overlay.onBackPressed()) closeOverlay();
            return;
        }
        Screen screen = currentTab >= 0 ? screen(currentTab) : null;
        if (screen != null && screen.onBackPressed()) return;
        if (currentTab != TAB_HOME) {
            selectTab(TAB_HOME, true);
            bottomBar.setSelected(TAB_HOME, true);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------ accessors
    public Theme theme() {
        return theme;
    }

    public CameraBridge bridge() {
        return bridge;
    }

    public ImageLoader imageLoader() {
        return imageLoader;
    }

    public FrameLayout contentArea() {
        return content;
    }

    /** Opens the rebuilt settings sheet as a full-screen overlay. */
    public void openSettings() {
        openOverlay(new SettingsScreen(this));
    }

    public void openCameraGallery() {
        selectTab(TAB_GALLERY, true);
        bottomBar.setSelected(TAB_GALLERY, true);
    }

    public void openRemote() {
        selectTab(TAB_REMOTE, true);
        bottomBar.setSelected(TAB_REMOTE, true);
    }

    public void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** Saves a downloaded byte payload into the user gallery. */
    public void saveToGallery(byte[] data, String name, MediaStoreSaver.ResultCallback callback) {
        if (MediaStoreSaver.needsLegacyPermission()
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE);
            toast("请授予存储权限后重试");
            return;
        }
        MediaStoreSaver.save(this, data, name, "image/jpeg", callback);
    }

    public void showSnack(String message) {
        final TextView snack = UiKit.label(this, theme, message, 13.5f,
                theme.dark ? Color.parseColor("#0B0D10") : Color.WHITE, 1);
        snack.setGravity(Gravity.CENTER);
        snack.setBackground(UiKit.rounded(this,
                theme.dark ? Color.parseColor("#EAF0FA") : Color.parseColor("#1B2431"), 16));
        snack.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 12),
                UiKit.dp(this, 18), UiKit.dp(this, 12));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = UiKit.dp(this, 116);
        snack.setAlpha(0f);
        snack.setTranslationY(UiKit.dp(this, 16));
        root.addView(snack, params);
        snack.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(UiKit.OVERSHOOT).start();
        snack.postDelayed(() -> snack.animate().alpha(0f).translationY(UiKit.dp(this, 12))
                .setDuration(200).withEndAction(() -> root.removeView(snack)).start(), 2200);
    }
}
