package com.ricohimaging.imagesync.modern;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Full settings overlay: appearance (accent palette + light/dark mode),
 * camera Wi-Fi credentials and download quality.
 *
 * <p>Opened from the home gear via {@code host.openOverlay(new SettingsScreen(host))};
 * everything is themed with the current {@link Theme} tokens and scrolls so the
 * IME never covers the camera Wi-Fi fields.</p>
 */
public final class SettingsScreen extends Screen implements CameraBridge.StateListener {

    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout content;

    // Camera Wi-Fi section
    private LinearLayout wifiCard;
    private View wifiDot;
    private GradientDrawable wifiDotBackground;
    private TextView wifiStatus;
    private EditText ssidField;
    private EditText keyField;
    private TextView passwordToggle;
    private TextView wifiHint;
    private TextView saveWifiButton;
    private boolean passwordHidden = true;
    private boolean wifiLoaded;
    private boolean wifiLoading;
    private boolean savingWifi;

    // Download quality section
    private FrameLayout qualityTrack;
    private View qualityIndicator;
    private final TextView[] qualityTabs = new TextView[2];
    private int quality = CameraBridge.QUALITY_ORIGINAL;

    private boolean destroyed;
    private boolean closed;

    public SettingsScreen(ShellActivity host) {
        super(host);
    }

    // ======================================================================
    //  Construction
    // ======================================================================
    @Override
    protected View onCreateView() {
        root = new FrameLayout(activity());
        root.setBackgroundColor(t.background);

        scroll = new ScrollView(activity());
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        content = UiKit.column(activity());
        content.setPadding(dp(20), dp(16), dp(20), dp(34));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        buildHeader();
        buildAppearanceSection();
        buildWifiSection();
        buildQualitySection();
        buildFooter();

        destroyed = false;
        closed = false;
        host.bridge().addStateListener(this);
        return root;
    }

    private void buildHeader() {
        LinearLayout header = UiKit.row(activity());
        LinearLayout titles = UiKit.column(activity());
        titles.addView(UiKit.overline(activity(), t, "IMAGE SYNC · 设置", t.accent));
        TextView title = UiKit.title(activity(), t, "设置", 30);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.topMargin = dp(6);
        titles.addView(title, titleParams);
        TextView subtitle = UiKit.body(activity(), t,
                "外观、相机 Wi-Fi 与下载", 12.5f, t.muted);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-2, -2);
        subParams.topMargin = dp(4);
        titles.addView(subtitle, subParams);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        FrameLayout close = UiKit.iconButton(activity(), t, Glyph.Icon.CLOSE, t.text,
                this::close);
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));
        content.addView(UiKit.spacer(activity(), 16));
    }

    private void sectionHeader(String title) {
        TextView header = UiKit.overline(activity(), t, title, t.muted);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = dp(4);
        params.topMargin = content.getChildCount() == 0 ? 0 : dp(6);
        params.bottomMargin = dp(8);
        content.addView(header, params);
    }

    private LinearLayout sectionCard() {
        LinearLayout card = UiKit.column(activity());
        GradientDrawable background = new GradientDrawable();
        background.setColor(t.surface);
        background.setCornerRadius(dp(t.radiusInner));
        background.setStroke(Math.max(1, dp(0.7f)), t.hairline);
        card.setBackground(background);
        return card;
    }

    // ======================================================================
    //  Appearance
    // ======================================================================
    private void buildAppearanceSection() {
        sectionHeader("外观");
        LinearLayout card = sectionCard();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        int currentAccent = Theme.accentIndex(activity());
        TextView accentLabel = UiKit.label(activity(), t,
                "强调色 · 当前：" + accentName(currentAccent), 13, t.text, 1);
        card.addView(accentLabel, new LinearLayout.LayoutParams(-1, -2));
        card.addView(UiKit.spacer(activity(), 10));

        LinearLayout firstRow = UiKit.row(activity());
        LinearLayout secondRow = UiKit.row(activity());
        LinearLayout thirdRow = UiKit.row(activity());
        for (int i = 0; i < Theme.ACCENT_NAMES.length; i++) {
            View swatch = buildSwatch(i);
            if (i < 3) {
                firstRow.addView(swatch);
            } else if (i < 6) {
                secondRow.addView(swatch);
            } else {
                thirdRow.addView(swatch);
            }
        }
        card.addView(firstRow, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(-1, -2);
        secondParams.topMargin = dp(8);
        card.addView(secondRow, secondParams);
        LinearLayout.LayoutParams thirdParams = new LinearLayout.LayoutParams(-1, -2);
        thirdParams.topMargin = dp(8);
        card.addView(thirdRow, thirdParams);

        card.addView(UiKit.spacer(activity(), 16));
        card.addView(UiKit.hairline(activity(), t), new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.7f))));
        card.addView(UiKit.spacer(activity(), 12));

        TextView modeLabel = UiKit.label(activity(), t, "显示模式", 13, t.text, 1);
        card.addView(modeLabel, new LinearLayout.LayoutParams(-1, -2));
        card.addView(UiKit.spacer(activity(), 8));

        LinearLayout modeRow = UiKit.row(activity());
        int currentMode = Theme.modeIndex(activity());
        for (int i = 0; i < Theme.MODE_NAMES.length; i++) {
            final int mode = i;
            TextView chip = modeChip(Theme.MODE_NAMES[i], i == currentMode);
            chip.setOnClickListener(v -> {
                UiKit.hapticTick(v);
                if (mode != Theme.modeIndex(activity())) host.applyThemeMode(mode);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) params.leftMargin = dp(8);
            modeRow.addView(chip, params);
        }
        card.addView(modeRow, new LinearLayout.LayoutParams(-1, -2));

        content.addView(card, new LinearLayout.LayoutParams(-1, -2));
        content.addView(UiKit.spacer(activity(), 22));
    }

    private View buildSwatch(final int index) {
        int size = dp(46);
        // Uniform swatch colour for every palette entry (index 0 = 图标蓝).
        final int swatchColor = Theme.swatchColor(index, t.dark);
        FrameLayout cell = new FrameLayout(activity());
        // Equal-weight cells: a 3-per-row grid fits any phone width.
        LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, size, 1f);
        cell.setLayoutParams(cellParams);

        View circle = new View(activity());
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(swatchColor);
        fill.setShape(GradientDrawable.OVAL);
        circle.setBackground(fill);
        FrameLayout.LayoutParams circleParams = new FrameLayout.LayoutParams(dp(34), dp(34));
        circleParams.gravity = Gravity.CENTER;
        cell.addView(circle, circleParams);

        if (index == Theme.accentIndex(activity())) {
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(Color.TRANSPARENT);
            ring.setStroke(Math.max(1, dp(2f)), t.text);
            View ringView = new View(activity());
            ringView.setBackground(ring);
            // Gravity.CENTER is essential: the cell is weight-based and wider
            // than 46 dp, so a default top-left layout would shift the ring left.
            FrameLayout.LayoutParams ringParams = new FrameLayout.LayoutParams(dp(42), dp(42));
            ringParams.gravity = Gravity.CENTER;
            cell.addView(ringView, ringParams);
            Glyph check = new Glyph(activity(), Glyph.Icon.CHECK);
            check.setTint(contrastOn(swatchColor));
            check.setStrokeWidthDp(2.4f);
            FrameLayout.LayoutParams checkParams = new FrameLayout.LayoutParams(dp(16), dp(16));
            checkParams.gravity = Gravity.CENTER;
            cell.addView(check, checkParams);
        }

        cell.setOnClickListener(v -> {
            UiKit.hapticTick(v);
            if (index != Theme.accentIndex(activity())) host.applyAccent(index);
        });
        UiKit.pressFeedback(cell);
        return cell;
    }

    private TextView modeChip(String label, boolean selected) {
        TextView chip = UiKit.label(activity(), t, label, 12.5f,
                selected ? t.onAccent : t.text, 1);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(4), dp(10), dp(4), dp(10));
        chip.setBackground(selected
                ? UiKit.rounded(activity(), t.accent, t.radiusPill)
                : UiKit.pressable(activity(), t.surfaceAlt, t.radiusPill, t.hairline));
        chip.setClickable(true);
        chip.setFocusable(true);
        UiKit.pressFeedback(chip);
        return chip;
    }

    private static String accentName(int index) {
        if (index < 0 || index >= Theme.ACCENT_NAMES.length) return Theme.ACCENT_NAMES[1];
        return Theme.ACCENT_NAMES[index];
    }

    /** Pick black/white for the check mark on top of an arbitrary swatch colour. */
    private static int contrastOn(int color) {
        double luminance = 0.299 * Color.red(color)
                + 0.587 * Color.green(color) + 0.114 * Color.blue(color);
        return luminance > 150 ? 0xFF0F1319 : 0xFFFFFFFF;
    }

    // ======================================================================
    //  Camera Wi-Fi
    // ======================================================================
    private void buildWifiSection() {
        sectionHeader("相机 Wi-Fi");
        wifiCard = sectionCard();
        wifiCard.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout statusRow = UiKit.row(activity());
        wifiDot = new View(activity());
        wifiDotBackground = new GradientDrawable();
        wifiDotBackground.setShape(GradientDrawable.OVAL);
        wifiDotBackground.setColor(t.muted);
        wifiDot.setBackground(wifiDotBackground);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotParams.rightMargin = dp(9);
        statusRow.addView(wifiDot, dotParams);

        wifiStatus = UiKit.label(activity(), t, "未连接", 12.5f, t.muted, 0);
        wifiStatus.setSingleLine(true);
        statusRow.addView(wifiStatus, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView refresh = UiKit.textButton(activity(), t, "刷新");
        refresh.setOnClickListener(v -> {
            UiKit.hapticTick(v);
            if (host.bridge().state() == CameraBridge.State.CONNECTED) loadWifi();
        });
        statusRow.addView(refresh);
        wifiCard.addView(statusRow, new LinearLayout.LayoutParams(-1, -2));

        wifiCard.addView(fieldLabel("网络名称 (SSID)"),
                topMargin(new LinearLayout.LayoutParams(-1, -2), 12));
        ssidField = buildField("相机热点名称", false);
        wifiCard.addView(ssidField, new LinearLayout.LayoutParams(-1, -2));

        wifiCard.addView(fieldLabel("密码"),
                topMargin(new LinearLayout.LayoutParams(-1, -2), 10));
        LinearLayout passwordRow = UiKit.row(activity());
        keyField = buildField("相机热点密码", true);
        passwordRow.addView(keyField, new LinearLayout.LayoutParams(0, -2, 1f));
        passwordToggle = UiKit.textButton(activity(), t, "显示");
        passwordToggle.setOnClickListener(v -> {
            UiKit.hapticTick(v);
            togglePassword();
        });
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-2, -2);
        toggleParams.leftMargin = dp(6);
        passwordRow.addView(passwordToggle, toggleParams);
        wifiCard.addView(passwordRow, new LinearLayout.LayoutParams(-1, -2));

        wifiHint = UiKit.body(activity(), t, "连接相机后可修改", 11.5f, t.muted);
        wifiCard.addView(wifiHint, topMargin(new LinearLayout.LayoutParams(-1, -2), 10));

        saveWifiButton = UiKit.primaryButton(activity(), t, "保存 Wi-Fi 设置");
        saveWifiButton.setOnClickListener(v -> {
            UiKit.hapticTick(v);
            saveWifi();
        });
        wifiCard.addView(saveWifiButton,
                topMargin(new LinearLayout.LayoutParams(-1, -2), 12));

        content.addView(wifiCard, new LinearLayout.LayoutParams(-1, -2));
        content.addView(UiKit.spacer(activity(), 22));
        refreshWifiSection(false);
    }

    private TextView fieldLabel(String text) {
        return UiKit.label(activity(), t, text, 11.5f, t.muted, 0);
    }

    private LinearLayout.LayoutParams topMargin(LinearLayout.LayoutParams params, float marginDp) {
        params.topMargin = dp(marginDp);
        return params;
    }

    private EditText buildField(String hint, boolean password) {
        EditText field = new EditText(activity());
        field.setTextSize(14);
        field.setTextColor(t.text);
        field.setHintTextColor(t.muted);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setPadding(dp(14), dp(11), dp(14), dp(11));
        field.setBackground(UiKit.stroke(activity(), t.surfaceAlt, 12, t.hairline));
        field.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(password ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
        return field;
    }

    private void togglePassword() {
        passwordHidden = !passwordHidden;
        int selection = keyField.getSelectionEnd();
        keyField.setInputType(passwordHidden
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        int length = keyField.getText().length();
        keyField.setSelection(Math.max(0, Math.min(length, selection)));
        passwordToggle.setText(passwordHidden ? "显示" : "隐藏");
        passwordToggle.setTypeface(t.typeface(1));
    }

    private void refreshWifiSection(boolean load) {
        if (destroyed || wifiStatus == null) return;
        CameraBridge bridge = host.bridge();
        boolean connected = bridge.state() == CameraBridge.State.CONNECTED;
        if (wifiDotBackground != null) {
            wifiDotBackground.setColor(connected ? t.success : t.muted);
        }
        if (!wifiLoading) {
            wifiStatus.setText(connected ? "已连接" : "未连接");
            String model = connected ? bridge.model() : null;
            if (connected && model != null && !model.isEmpty()) {
                wifiStatus.setText("已连接 · " + model);
            }
        }
        float fieldAlpha = connected ? 1f : 0.5f;
        ssidField.setEnabled(connected && !wifiLoading);
        ssidField.setAlpha(fieldAlpha);
        keyField.setEnabled(connected && !wifiLoading);
        keyField.setAlpha(fieldAlpha);
        passwordToggle.setEnabled(connected && !wifiLoading);
        passwordToggle.setAlpha(fieldAlpha);
        saveWifiButton.setEnabled(connected && !savingWifi && !wifiLoading);
        saveWifiButton.setAlpha(connected ? 1f : 0.5f);
        wifiHint.setText(connected
                ? "相机固件可能拒绝写入；修改后需要重新连接相机。"
                : "连接相机后可修改");
        if (connected && load && !wifiLoaded && !wifiLoading) loadWifi();
    }

    private void loadWifi() {
        if (destroyed || wifiLoading) return;
        if (host.bridge().state() != CameraBridge.State.CONNECTED) return;
        wifiLoading = true;
        wifiStatus.setText("正在读取 Wi-Fi 设置…");
        if (ssidField != null) ssidField.setEnabled(false);
        if (keyField != null) keyField.setEnabled(false);
        host.bridge().readCameraWifi(new CameraBridge.WifiInfoCallback() {
            @Override
            public void onInfo(String ssid, String key, String message) {
                if (destroyed) return;
                wifiLoading = false;
                if (message != null && !message.isEmpty()) {
                    host.showSnack(message);
                } else {
                    if (ssid != null) ssidField.setText(ssid);
                    if (key != null) keyField.setText(key);
                    wifiLoaded = true;
                }
                refreshWifiSection(false);
            }
        });
    }

    private void saveWifi() {
        if (destroyed || savingWifi) return;
        String ssid = ssidField.getText().toString().trim();
        String key = keyField.getText().toString();
        if (ssid.isEmpty()) {
            host.showSnack("请输入网络名称 (SSID)");
            return;
        }
        savingWifi = true;
        saveWifiButton.setEnabled(false);
        saveWifiButton.setText("保存中…");
        host.bridge().writeCameraWifi(ssid, key, new CameraBridge.SimpleCallback() {
            @Override
            public void onDone(boolean success, String message) {
                if (destroyed) return;
                savingWifi = false;
                saveWifiButton.setText("保存 Wi-Fi 设置");
                refreshWifiSection(false);
                if (success) {
                    host.showSnack("已保存 Wi-Fi 设置，请重新连接相机");
                } else {
                    host.showSnack(message == null || message.isEmpty()
                            ? "相机拒绝了 Wi-Fi 设置修改" : message);
                }
            }
        });
    }

    // ======================================================================
    //  Download quality
    // ======================================================================
    private void buildQualitySection() {
        sectionHeader("下载尺寸");
        LinearLayout card = sectionCard();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        quality = host.bridge().downloadQuality();
        qualityTrack = new FrameLayout(activity());
        GradientDrawable track = new GradientDrawable();
        track.setColor(t.surfaceAlt);
        track.setCornerRadius(dp(t.radiusPill));
        track.setStroke(Math.max(1, dp(0.7f)), t.hairline);
        qualityTrack.setBackground(track);
        qualityTrack.setPadding(dp(3), dp(3), dp(3), dp(3));
        qualityTrack.setClipChildren(false);
        qualityTrack.setClipToPadding(false);

        qualityIndicator = new View(activity());
        qualityIndicator.setBackground(UiKit.rounded(activity(), t.accent, t.radiusPill));
        qualityTrack.addView(qualityIndicator,
                new FrameLayout.LayoutParams(dp(0), FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.LEFT | Gravity.CENTER_VERTICAL));

        LinearLayout labels = UiKit.row(activity());
        String[] options = {"原图", "压缩"};
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            TextView tab = UiKit.label(activity(), t, options[i], 13, t.muted, 1);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, dp(11), 0, dp(11));
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setOnClickListener(v -> selectQuality(index, true));
            qualityTabs[i] = tab;
            labels.addView(tab, new LinearLayout.LayoutParams(0, -1, 1f));
        }
        qualityTrack.addView(labels, new FrameLayout.LayoutParams(-1, -1));
        qualityTrack.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                    oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) != (oldRight - oldLeft)) layoutQuality(false);
        });
        card.addView(qualityTrack, new LinearLayout.LayoutParams(-1, dp(46)));

        TextView caption = UiKit.body(activity(), t,
                "压缩：使用相机生成的小尺寸 JPEG，省流量、传输更快；"
                        + "原图：保留完整分辨率与画质。",
                11.5f, t.muted);
        card.addView(caption, topMargin(new LinearLayout.LayoutParams(-1, -2), 10));

        content.addView(card, new LinearLayout.LayoutParams(-1, -2));
        content.addView(UiKit.spacer(activity(), 24));
        updateQualityTabs();
        qualityTrack.post(() -> layoutQuality(false));
    }

    private void selectQuality(int index, boolean animate) {
        if (destroyed) return;
        quality = index == 1 ? CameraBridge.QUALITY_COMPRESSED : CameraBridge.QUALITY_ORIGINAL;
        host.bridge().setDownloadQuality(quality);
        UiKit.hapticTick(qualityTrack);
        updateQualityTabs();
        layoutQuality(animate);
    }

    private void updateQualityTabs() {
        for (int i = 0; i < qualityTabs.length; i++) {
            TextView tab = qualityTabs[i];
            if (tab == null) continue;
            boolean selected = (i == 1) == (quality == CameraBridge.QUALITY_COMPRESSED);
            tab.setTextColor(selected ? t.onAccent : t.muted);
            tab.setTypeface(selected ? t.typeface(1) : t.typeface(0));
        }
    }

    private void layoutQuality(boolean animate) {
        if (qualityTrack == null || qualityIndicator == null) return;
        int contentWidth = qualityTrack.getWidth()
                - qualityTrack.getPaddingLeft() - qualityTrack.getPaddingRight();
        if (contentWidth <= 0) return;
        int half = contentWidth / 2;
        android.view.ViewGroup.LayoutParams params = qualityIndicator.getLayoutParams();
        if (params.width != half) {
            params.width = half;
            qualityIndicator.setLayoutParams(params);
        }
        float target = quality == CameraBridge.QUALITY_COMPRESSED ? half : 0f;
        qualityIndicator.animate().cancel();
        if (animate) {
            qualityIndicator.animate().translationX(target).setDuration(200)
                    .setInterpolator(UiKit.DECELERATE_SOFT).start();
        } else {
            qualityIndicator.setTranslationX(target);
        }
    }

    // ======================================================================
    //  Footer / lifecycle
    // ======================================================================
    private void buildFooter() {
        content.addView(UiKit.spacer(activity(), 18));
        TextView footer = UiKit.label(activity(), t, "Image Sync Modern · 2.1.32",
                11.5f, t.muted, 0);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(footer, new LinearLayout.LayoutParams(-1, -2));
    }

    @Override
    public void onShow() {
        closed = false;
        destroyed = false;
        refreshWifiSection(true);
    }

    @Override
    public void onHostResume() {
        if (!destroyed) refreshWifiSection(false);
    }

    @Override
    public void onHide() {
        destroyed = true;
        removeStateListener();
    }

    @Override
    public void onDestroyView() {
        destroyed = true;
        removeStateListener();
    }

    @Override
    public boolean onBackPressed() {
        close();
        return true;
    }

    @Override
    public void onStateChanged(CameraBridge.State state) {
        if (destroyed) return;
        refreshWifiSection(state == CameraBridge.State.CONNECTED);
    }

    private void close() {
        if (closed) return;
        closed = true;
        removeStateListener();
        host.closeOverlay();
    }

    private void removeStateListener() {
        try {
            host.bridge().removeStateListener(this);
        } catch (Throwable ignored) {
            // Bridge may already be gone during teardown.
        }
    }
}
