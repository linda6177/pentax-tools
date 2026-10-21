package com.ricohimaging.imagesync.modern;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ricoh.camera.sdk.wireless.api.CameraImage;

import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilt home screen: live camera status, direct Wi-Fi connect, recent shots
 * and quick actions. The connection itself is still performed by the original
 * Ricoh SDK; this screen only observes and triggers it.
 */
public final class HomeScreen extends Screen implements CameraBridge.StateListener {

    private ScrollView scroll;
    private LinearLayout page;
    private FrameLayout heroCard;
    private LinearLayout recentSection;
    private LinearLayout recentRow;
    private TextView heroStatus;
    private TextView heroTitle;
    private TextView heroDetail;
    private LinearLayout heroActions;
    private View connectProgress;
    private TextView batteryLabel;
    private CameraBridge.State renderedState;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private boolean connecting;
    private boolean visible;
    private List<CameraImage> recentImages = new ArrayList<>();
    private LinearLayout paletteSection;

    public HomeScreen(ShellActivity host) {
        super(host);
    }

    @Override
    protected View onCreateView() {
        scroll = new ScrollView(activity());
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        page = UiKit.column(activity());
        int side = dp(20);
        page.setPadding(side, dp(14), side, dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        buildHeader();
        buildHero();
        buildQuickActions();
        buildPalette();
        buildRecent();
        buildFooter();
        host.bridge().addStateListener(this);
        return scroll;
    }

    // -------------------------------------------------------------- header
    private void buildHeader() {
        LinearLayout row = UiKit.row(activity());
        LinearLayout titles = UiKit.column(activity());
        TextView overline = UiKit.overline(activity(), t, "IMAGE SYNC", t.accent);
        titles.addView(overline);
        TextView title = UiKit.title(activity(), t, "宾得，欣然发布", 27);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.topMargin = dp(7);
        titles.addView(title, titleParams);
        TextView subtitle = UiKit.label(activity(), t,
                "照片传输 · 遥控拍摄 · 设备管理", 12.5f, t.muted, 0);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-2, -2);
        subParams.topMargin = dp(6);
        titles.addView(subtitle, subParams);
        row.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(UiKit.iconButton(activity(), t, Glyph.Icon.SETTINGS, t.muted,
                host::openSettings));
        page.addView(row);
        page.addView(UiKit.spacer(activity(), 22));
    }

    // ---------------------------------------------------------------- hero
    private void buildHero() {
        heroCard = new FrameLayout(activity());
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{t.surface, blend(t.surface, t.accent, t.dark ? 0.14f : 0.07f)});
        background.setCornerRadius(dp(t.radiusCard));
        background.setStroke(dp(0.7f), t.hairline);
        heroCard.setBackground(background);
        if (android.os.Build.VERSION.SDK_INT >= 21) heroCard.setElevation(dp(1.2f));
        page.addView(heroCard, new LinearLayout.LayoutParams(-1, -2));
        renderHero();
    }

    private void renderHero() {
        renderedState = host.bridge().state();
        batteryLabel = null;
        heroCard.removeAllViews();
        FrameLayout content = new FrameLayout(activity());
        content.setPadding(dp(20), dp(20), dp(20), dp(18));
        heroCard.addView(content);
        LinearLayout column = UiKit.column(activity());
        content.addView(column);

        CameraBridge bridge = host.bridge();
        CameraBridge.State state = bridge.state();

        LinearLayout top = UiKit.row(activity());
        FrameLayout badge = new FrameLayout(activity());
        badge.setBackground(UiKit.rounded(activity(),
                state == CameraBridge.State.CONNECTED ? t.accentSoft : t.surfaceAlt, 22));
        Glyph glyph = new Glyph(activity(),
                state == CameraBridge.State.CONNECTED ? Glyph.Icon.CAMERA : Glyph.Icon.WIFI);
        glyph.setTint(state == CameraBridge.State.CONNECTED ? t.accent : t.muted);
        FrameLayout.LayoutParams glyphParams = new FrameLayout.LayoutParams(dp(26), dp(26));
        glyphParams.gravity = Gravity.CENTER;
        badge.addView(glyph, glyphParams);
        top.addView(badge, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout texts = UiKit.column(activity());
        texts.setPadding(dp(14), 0, 0, 0);
        heroTitle = UiKit.label(activity(), t,
                state == CameraBridge.State.CONNECTED
                        ? (bridge.model() == null ? "相机已连接" : bridge.model())
                        : "还没有连接相机",
                17.5f, t.text, 2);
        texts.addView(heroTitle);
        heroStatus = UiKit.label(activity(), t, statusLine(state), 12, t.muted, 0);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-2, -2);
        statusParams.topMargin = dp(4);
        texts.addView(heroStatus, statusParams);
        top.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        if (state == CameraBridge.State.CONNECTED) {
            top.addView(batteryPill());
        }
        column.addView(top);

        heroDetail = UiKit.body(activity(), t, detailLine(state), 13, t.muted);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(14);
        column.addView(heroDetail, detailParams);

        heroActions = UiKit.column(activity());
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dp(16);
        column.addView(heroActions, actionsParams);
        buildHeroActions(state);

        connectProgress = buildConnectingIndicator();
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, -2);
        progressParams.topMargin = dp(12);
        column.addView(connectProgress, progressParams);
        connectProgress.setVisibility(connecting ? View.VISIBLE : View.GONE);
    }

    private void buildHeroActions(CameraBridge.State state) {
        heroActions.removeAllViews();
        if (state == CameraBridge.State.CONNECTED) {
            LinearLayout row = UiKit.row(activity());
            row.addView(actionButton("查看照片", true,
                    () -> host.selectTab(ShellActivity.TAB_GALLERY, true)), weight());
            row.addView(UiKit.hspacer(activity(), 10));
            row.addView(actionButton("遥控拍摄", false,
                    () -> host.selectTab(ShellActivity.TAB_REMOTE, true)), weight());
            heroActions.addView(row);
            TextView disconnect = UiKit.textButton(activity(), t, "断开相机连接");
            disconnect.setOnClickListener(v -> {
                UiKit.haptic(v);
                host.toast("正在断开…");
                host.bridge().disconnect(activity());
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(10);
            heroActions.addView(disconnect, params);
        } else {
            TextView connect = actionButton("检测相机 Wi-Fi", true, this::startWifiConnect);
            heroActions.addView(connect);
            TextView wifi = UiKit.tonalButton(activity(), t, "打开系统 Wi-Fi 设置");
            wifi.setOnClickListener(v -> {
                UiKit.haptic(v);
                openSystemWifiSettings();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(10);
            heroActions.addView(wifi, params);
        }
    }

    private TextView actionButton(String text, boolean primary, Runnable action) {
        TextView button = primary
                ? UiKit.primaryButton(activity(), t, text)
                : UiKit.tonalButton(activity(), t, text);
        button.setOnClickListener(v -> {
            UiKit.hapticTick(v);
            action.run();
        });
        return button;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    private View buildConnectingIndicator() {
        LinearLayout row = UiKit.row(activity());
        ProgressBar spinner = new ProgressBar(activity(), null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(t.accent));
        row.addView(spinner, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView label = UiKit.label(activity(), t,
                "正在通过 Wi-Fi 查找相机…", 12.5f, t.muted, 0);
        label.setPadding(dp(10), 0, 0, 0);
        row.addView(label);
        return row;
    }

    private View batteryPill() {
        LinearLayout pill = UiKit.row(activity());
        pill.setBackground(UiKit.rounded(activity(), t.surfaceAlt, 100));
        pill.setPadding(dp(10), dp(6), dp(12), dp(6));
        int level = host.bridge().batteryLevel();
        Glyph battery = new Glyph(activity(), Glyph.Icon.BATTERY);
        battery.setTint(level >= 0 && level <= 20 ? t.danger : t.muted);
        pill.addView(battery, new LinearLayout.LayoutParams(dp(17), dp(17)));
        batteryLabel = UiKit.label(activity(), t, level < 0 ? "—" : level + "%",
                11.5f, level >= 0 && level <= 20 ? t.danger : t.muted, 1);
        batteryLabel.setPadding(dp(5), 0, 0, 0);
        pill.addView(batteryLabel);
        return pill;
    }

    private String statusLine(CameraBridge.State state) {
        switch (state) {
            case CONNECTED:
                return "● Wi-Fi 已连接 · 可以浏览和拍摄";
            case BLE_ONLY:
                return "◐ 蓝牙已连接 · 等待 Wi-Fi 建立";
            default:
                return "○ 未连接 · 打开相机的无线功能后重试";
        }
    }

    private String detailLine(CameraBridge.State state) {
        if (state == CameraBridge.State.CONNECTED) {
            String firmware = host.bridge().displayFirmware();
            return "固件 " + (firmware == null || firmware.isEmpty() ? "未知" : firmware)
                    + " · 传输与遥控由原版 Ricoh SDK 处理";
        }
        return "先在系统设置里把手机连接到相机的 Wi-Fi 热点，再回到这里点一下即可连接。"
                + "连接、传输与遥控全部在本应用内完成，不会跳到其他界面。";
    }

    private void startWifiConnect() {
        if (connecting) return;
        connecting = true;
        renderHero();
        host.bridge().connectOverWifi(new CameraBridge.ConnectCallback() {
            @Override
            public void onConnected(com.ricoh.camera.sdk.wireless.api.CameraDevice device) {
                connecting = false;
                host.toast("相机已连接");
                renderHero();
                loadRecent();
            }

            @Override
            public void onError(String message) {
                connecting = false;
                renderHero();
                host.showSnack(message);
            }
        });
    }

    // -------------------------------------------------------- quick actions
    private void buildQuickActions() {
        page.addView(UiKit.spacer(activity(), 22));
        LinearLayout header = UiKit.row(activity());
        header.addView(UiKit.title(activity(), t, "快捷操作", 18),
                new LinearLayout.LayoutParams(0, -2, 1f));
        page.addView(header);
        page.addView(UiKit.spacer(activity(), 12));

        LinearLayout first = UiKit.row(activity());
        first.addView(quickCard("相册", "浏览相机与本机照片", Glyph.Icon.GALLERY,
                () -> host.selectTab(ShellActivity.TAB_GALLERY, true)), weight());
        first.addView(UiKit.hspacer(activity(), 12));
        first.addView(quickCard("遥控", "实时取景与快门", Glyph.Icon.CAMERA,
                () -> host.selectTab(ShellActivity.TAB_REMOTE, true)), weight());
        page.addView(first);
        page.addView(UiKit.spacer(activity(), 12));
        LinearLayout second = UiKit.row(activity());
        second.addView(quickCard("检测连接", "重新查找相机 Wi-Fi", Glyph.Icon.SYNC,
                this::startWifiConnect), weight());
        second.addView(UiKit.hspacer(activity(), 12));
        second.addView(quickCard("主题色", "切换界面强调色", Glyph.Icon.PALETTE,
                this::showPalette), weight());
        page.addView(second);
    }

    private View quickCard(String title, String subtitle, Glyph.Icon icon, Runnable action) {
        FrameLayout card = new FrameLayout(activity());
        GradientDrawable background = new GradientDrawable();
        background.setColor(t.surface);
        background.setCornerRadius(dp(t.radiusInner));
        background.setStroke(dp(0.7f), t.hairline);
        card.setBackground(background);
        card.setClickable(true);
        card.setFocusable(true);
        card.setPadding(dp(15), dp(15), dp(15), dp(15));
        UiKit.pressFeedback(card);
        card.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            action.run();
        });
        LinearLayout column = UiKit.column(activity());
        card.addView(column);
        FrameLayout iconCircle = new FrameLayout(activity());
        iconCircle.setBackground(UiKit.rounded(activity(), t.accentSoft, 15));
        Glyph glyph = new Glyph(activity(), icon);
        glyph.setTint(t.accent);
        FrameLayout.LayoutParams glyphParams = new FrameLayout.LayoutParams(dp(21), dp(21));
        glyphParams.gravity = Gravity.CENTER;
        iconCircle.addView(glyph, glyphParams);
        column.addView(iconCircle, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView titleView = UiKit.label(activity(), t, title, 15, t.text, 2);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.topMargin = dp(12);
        column.addView(titleView, titleParams);
        TextView subtitleView = UiKit.label(activity(), t, subtitle, 11.5f, t.muted, 0);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-2, -2);
        subParams.topMargin = dp(3);
        column.addView(subtitleView, subParams);
        return card;
    }

    // ------------------------------------------------------------- palette
    private void buildPalette() {
        paletteSection = UiKit.column(activity());
        page.addView(UiKit.spacer(activity(), 22));
        page.addView(paletteSection);

        LinearLayout header = UiKit.row(activity());
        header.addView(UiKit.title(activity(), t, "主题色", 18),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView current = UiKit.label(activity(), t,
                Theme.ACCENT_NAMES[Math.max(0, Math.min(Theme.accentIndex(activity()),
                        Theme.ACCENT_NAMES.length - 1))], 12, t.accent, 1);
        header.addView(current);
        paletteSection.addView(header);
        paletteSection.addView(UiKit.spacer(activity(), 12));

        FrameLayout card = new FrameLayout(activity());
        GradientDrawable background = new GradientDrawable();
        background.setColor(t.surface);
        background.setCornerRadius(dp(t.radiusInner));
        background.setStroke(dp(0.7f), t.hairline);
        card.setBackground(background);
        card.setPadding(dp(16), dp(14), dp(16), dp(12));
        LinearLayout column = UiKit.column(activity());
        card.addView(column);

        int active = Theme.accentIndex(activity());
        LinearLayout row = UiKit.row(activity());
        row.setGravity(Gravity.START);
        LinearLayout secondRow = UiKit.row(activity());
        secondRow.setGravity(Gravity.START);
        for (int i = 0; i < Theme.ACCENT_NAMES.length; i++) {
            final int index = i;
            FrameLayout swatch = new FrameLayout(activity());
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(index == active ? t.accentSoft : 0x00000000);
            ring.setStroke(index == active ? dp(2) : dp(1),
                    index == active ? t.accent : t.hairline);
            swatch.setBackground(ring);
            View dot = new View(activity());
            GradientDrawable dotShape = new GradientDrawable();
            dotShape.setShape(GradientDrawable.OVAL);
            dotShape.setColor(Theme.swatchColor(index, t.dark));
            dot.setBackground(dotShape);
            FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(dp(24), dp(24));
            dotParams.gravity = Gravity.CENTER;
            swatch.addView(dot, dotParams);
            swatch.setClickable(true);
            swatch.setFocusable(true);
            swatch.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                if (index == active) return;
                host.applyAccent(index);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(40), dp(40));
            params.rightMargin = dp(2);
            (i < 5 ? row : secondRow).addView(swatch, params);
        }
        column.addView(row);
        if (secondRow.getChildCount() > 0) {
            LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(-2, -2);
            secondParams.topMargin = dp(4);
            column.addView(secondRow, secondParams);
        }

        // Light / dark / follow-system selector.
        LinearLayout modes = UiKit.row(activity());
        int modeNow = Theme.modeIndex(activity());
        for (int i = 0; i < Theme.MODE_NAMES.length; i++) {
            final int mode = i;
            boolean selected = mode == modeNow;
            TextView chip = UiKit.label(activity(), t, Theme.MODE_NAMES[i], 12.5f,
                    selected ? t.onAccent : t.text, selected ? 2 : 1);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(UiKit.rounded(activity(),
                    selected ? t.accent : t.surfaceAlt, 14));
            chip.setPadding(dp(12), dp(9), dp(12), dp(9));
            chip.setClickable(true);
            chip.setFocusable(true);
            UiKit.pressFeedback(chip);
            chip.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                if (mode != Theme.modeIndex(activity())) host.applyThemeMode(mode);
            });
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(0, -2, 1f);
            chipParams.rightMargin = dp(6);
            if (i == Theme.MODE_NAMES.length - 1) chipParams.rightMargin = 0;
            modes.addView(chip, chipParams);
        }
        LinearLayout.LayoutParams modesParams = new LinearLayout.LayoutParams(-1, -2);
        modesParams.topMargin = dp(12);
        column.addView(modes, modesParams);

        TextView note = UiKit.body(activity(), t,
                "点选立即生效，界面平滑过渡不重载。外观可跟随系统昼夜自动切换，也可固定浅色/深色。",
                11.5f, t.muted);
        note.setPadding(0, dp(10), 0, 0);
        column.addView(note);
        paletteSection.addView(card);
    }

    private void showPalette() {
        if (paletteSection == null) return;
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, paletteSection.getTop() - dp(16))));
        paletteSection.setScaleX(0.98f);
        paletteSection.setScaleY(0.98f);
        paletteSection.animate().scaleX(1f).scaleY(1f).setDuration(420)
                .setInterpolator(UiKit.OVERSHOOT).start();
    }

    private void openSystemWifiSettings() {
        try {
            activity().startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_WIFI_SETTINGS));
            activity().overridePendingTransition(android.R.anim.fade_in,
                    android.R.anim.fade_out);
        } catch (Throwable error) {
            host.showSnack("请到系统设置中连接相机 Wi-Fi");
        }
    }

    // ---------------------------------------------------------- recent shots
    private void buildRecent() {
        recentSection = UiKit.column(activity());
        page.addView(UiKit.spacer(activity(), 22));
        LinearLayout header = UiKit.row(activity());
        header.addView(UiKit.title(activity(), t, "最近拍摄", 18),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView all = UiKit.textButton(activity(), t, "查看全部");
        all.setOnClickListener(v -> host.selectTab(ShellActivity.TAB_GALLERY, true));
        header.addView(all);
        recentSection.addView(header);
        recentRow = UiKit.row(activity());
        recentRow.setPadding(dp(2), dp(4), dp(2), dp(4));
        HorizontalScrollView strip = new HorizontalScrollView(activity());
        strip.setHorizontalScrollBarEnabled(false);
        strip.setOverScrollMode(View.OVER_SCROLL_NEVER);
        strip.addView(recentRow);
        LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(-1, -2);
        stripParams.topMargin = dp(10);
        recentSection.addView(strip, stripParams);
        page.addView(recentSection);
        recentRow.addView(recentHint("连接相机后，这里会显示最新照片"));
        recentSection.setVisibility(View.GONE);
    }

    private View recentHint(String text) {
        TextView hint = UiKit.body(activity(), t, text, 12.5f, t.muted);
        hint.setBackground(UiKit.rounded(activity(), t.surfaceAlt, t.radiusInner));
        hint.setPadding(dp(18), dp(20), dp(18), dp(20));
        return hint;
    }

    private void loadRecent() {
        if (host.bridge().state() != CameraBridge.State.CONNECTED) {
            recentSection.setVisibility(View.GONE);
            return;
        }
        host.bridge().loadCameraImages(new CameraBridge.ImageListCallback() {
            @Override
            public void onImages(List<CameraImage> images) {
                if (!alive() || !visible) return;
                recentImages = images;
                if (images.isEmpty()) {
                    recentSection.setVisibility(View.GONE);
                    return;
                }
                recentSection.setVisibility(View.VISIBLE);
                recentRow.removeAllViews();
                int limit = Math.min(8, images.size());
                for (int i = 0; i < limit; i++) {
                    final int index = i;
                    final CameraImage image = images.get(i);
                    FrameLayout cell = new FrameLayout(activity());
                    cell.setBackground(UiKit.rounded(activity(), t.surfaceAlt, 16));
                    UiKit.roundClip(activity(), cell, 16);
                    ImageView picture = new ImageView(activity());
                    picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    cell.addView(picture, new FrameLayout.LayoutParams(-1, -1));
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            dp(132), dp(132));
                    params.rightMargin = dp(10);
                    recentRow.addView(cell, params);
                    cell.setOnClickListener(v -> {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                        host.openViewer(recentImages, index, cell);
                    });
                    host.imageLoader().into(picture, imageKey(image),
                            sink -> { image.getThumbnail(sink); return true; }, 320, 180);
                }
            }

            @Override
            public void onError(String message) {
                recentSection.setVisibility(View.GONE);
            }
        });
    }

    private static String imageKey(CameraImage image) {
        try {
            return "cam:" + image.getFolderName() + "/" + image.getName();
        } catch (Throwable ignored) {
            return "cam:" + System.identityHashCode(image);
        }
    }

    private void buildFooter() {
        page.addView(UiKit.spacer(activity(), 14));
    }

    // ------------------------------------------------------------ lifecycle
    @Override
    public void onShow() {
        visible = true;
        renderHero();
        loadRecent();
        animateIn();
    }

    @Override
    public void onHide() {
        visible = false;
    }

    @Override
    public void onHostResume() {
        if (heroCard == null) return;
        visible = true;
        renderHero();
        startTicker();
    }

    @Override
    public void onHostPause() {
        visible = false;
        stopTicker();
    }

    @Override
    public void onDestroyView() {
        stopTicker();
        host.bridge().removeStateListener(this);
    }

    @Override
    public void onStateChanged(CameraBridge.State state) {
        if (!alive()) return;
        renderHero();
        if (state == CameraBridge.State.CONNECTED) loadRecent();
        else recentSection.setVisibility(View.GONE);
    }

    private void animateIn() {
        if (page.getChildCount() == 0) return;
        for (int i = 0; i < Math.min(page.getChildCount(), 6); i++) {
            View child = page.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(dp(14));
            child.animate().alpha(1f).translationY(0f)
                    .setStartDelay(i * 48L).setDuration(320)
                    .setInterpolator(UiKit.DECELERATE_SOFT).start();
        }
    }

    private void startTicker() {
        stopTicker();
        ticker.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!visible) return;
                CameraBridge bridge = host.bridge();
                if (bridge.state() != renderedState) {
                    // Connection changed under us (wizard, camera power off...).
                    renderHero();
                    if (bridge.state() == CameraBridge.State.CONNECTED) loadRecent();
                } else if (batteryLabel != null) {
                    // Battery is polled into the SDK cache; just refresh the label.
                    int level = bridge.batteryLevel();
                    batteryLabel.setText(level < 0 ? "—" : level + "%");
                    batteryLabel.setTextColor(level >= 0 && level <= 20 ? t.danger : t.muted);
                }
                ticker.postDelayed(this, 2000);
            }
        }, 2000);
    }

    private void stopTicker() {
        ticker.removeCallbacksAndMessages(null);
    }

    private static int blend(int from, int to, float fraction) {
        float f = Math.max(0f, Math.min(1f, fraction));
        return Color.argb(
                (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f),
                (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * f),
                (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * f),
                (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f));
    }
}
