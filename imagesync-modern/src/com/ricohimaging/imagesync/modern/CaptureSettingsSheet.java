package com.ricohimaging.imagesync.modern;

import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Capture-settings sheet, round 2.
 *
 * <p>The first version expanded inline chip strips which crowded the panel and
 * made values hard to hit on a phone. This version is a near-full-height sheet
 * with a 模式 / 曝光 / 画质色彩 segmented control and a two-level flow: the
 * category shows grouped rows (label + current value + chevron) and tapping a
 * row replaces the list with an in-sheet value picker that has a back arrow.
 * Selected values are written through {@link CameraBridge#writeSetting} (never
 * an SDK setting object) and the sheet re-reads that setting afterwards.</p>
 */
public final class CaptureSettingsSheet extends Screen {

    private static final int CAT_MODE = 0;
    private static final int CAT_EXPOSURE = 1;
    private static final int CAT_IMAGE = 2;
    private static final String[] TABS = {"模式", "曝光", "画质色彩"};

    private static final int KIND_SETTING = 0;
    private static final int KIND_CAPTURE_MODE = 1;
    private static final int KIND_SELF_TIMER = 2;

    private static final String PSEUDO_MODE = "__capture_mode__";
    private static final String PSEUDO_TIMER = "__self_timer__";

    /** Every setting the sheet tries to show; unknown ones are skipped silently. */
    private static final String[] NAMES = {
            CameraBridge.S_CAPTURE_METHOD,
            CameraBridge.S_EXPOSURE_PROGRAM,
            CameraBridge.S_ISO,
            CameraBridge.S_SHUTTER_SPEED,
            CameraBridge.S_F_NUMBER,
            CameraBridge.S_EXPOSURE_COMPENSATION,
            CameraBridge.S_METERING,
            CameraBridge.S_WHITE_BALANCE,
            CameraBridge.S_FLASH_MODE,
            CameraBridge.S_DIGITAL_FILTER,
            CameraBridge.S_STILL_QUALITY,
            CameraBridge.S_STILL_FORMAT
    };

    private static final String[][] SELF_TIMERS = {
            {"2 秒", "SelfTimer2s"},
            {"2 秒", "SelfTimer2S"},
            {"10 秒", "SelfTimer10S"},
            {"10 秒", "SelfTimer10s"},
            {"12 秒", "SelfTimer12S"},
            {"12 秒", "SelfTimer12s"}
    };

    // ------------------------------------------------------------------- UI
    private FrameLayout root;
    private View scrim;
    private LinearLayout sheet;
    private View dragHandle;
    private LinearLayout headerRow;
    private FrameLayout backButton;
    private TextView sheetTitle;
    private ProgressBar syncProgress;
    private TextView syncState;
    private FrameLayout segmentWrap;
    private View segmentIndicator;
    private LinearLayout segmentRow;
    private final TextView[] tabViews = new TextView[TABS.length];
    private ScrollView listScroll;
    private LinearLayout listContent;
    private TextView diagnostic;

    // ----------------------------------------------------------------- data
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinkedHashMap<String, CameraBridge.SettingInfo> settings = new LinkedHashMap<>();
    private final List<Row> currentRows = new ArrayList<>();
    private final List<View> pickerItems = new ArrayList<>();
    private Row pickerRow;
    private int category = CAT_MODE;
    private boolean reading;
    private boolean writing;
    private boolean closing;
    private boolean destroyed;

    private final Runnable syncTimeout = new Runnable() {
        @Override
        public void run() {
            if (destroyed || closing || !reading) return;
            reading = false;
            updateSyncHeader();
        }
    };

    public CaptureSettingsSheet(ShellActivity host) {
        super(host);
    }

    // ======================================================================
    //  Construction
    // ======================================================================
    @Override
    protected View onCreateView() {
        root = new FrameLayout(activity());
        root.setClickable(true);

        scrim = new View(activity());
        scrim.setBackgroundColor(t.scrim);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        scrim.setOnClickListener(v -> dismiss());

        sheet = UiKit.column(activity());
        GradientDrawable background = new GradientDrawable();
        background.setColor(t.surface);
        float radius = dp(t.radiusCard);
        background.setCornerRadii(new float[]{radius, radius, radius, radius, 0f, 0f, 0f, 0f});
        sheet.setBackground(background);
        if (android.os.Build.VERSION.SDK_INT >= 21) sheet.setElevation(dp(18));
        FrameLayout.LayoutParams sheetParams = new FrameLayout.LayoutParams(-1, -2);
        sheetParams.gravity = Gravity.BOTTOM;
        // Sensible starting height; onShow recomputes it from the real window.
        int screenHeight = activity().getResources().getDisplayMetrics().heightPixels;
        sheetParams.height = (int) (screenHeight * 0.88f);
        root.addView(sheet, sheetParams);

        buildDragHandle();
        buildHeader();
        buildSegments();
        buildList();
        buildFooter();
        return root;
    }

    private void buildDragHandle() {
        dragHandle = new View(activity());
        dragHandle.setBackground(UiKit.rounded(activity(), t.hairline, t.radiusPill));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(42), dp(4));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(10);
        params.bottomMargin = dp(2);
        sheet.addView(dragHandle, params);
        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            private float startRaw;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRaw = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        sheet.setTranslationY(Math.max(0f, event.getRawY() - startRaw));
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (sheet.getTranslationY() > sheet.getHeight() * 0.25f) {
                            dismiss();
                        } else {
                            sheet.animate().translationY(0f).setDuration(220)
                                    .setInterpolator(UiKit.DECELERATE_SOFT).start();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void buildHeader() {
        headerRow = UiKit.row(activity());
        headerRow.setPadding(dp(10), dp(2), dp(6), dp(6));

        backButton = UiKit.iconButton(activity(), t, Glyph.Icon.CHEVRON_LEFT, t.text,
                () -> closePicker(null));
        backButton.setVisibility(View.GONE);
        headerRow.addView(backButton, new LinearLayout.LayoutParams(dp(40), dp(40)));

        sheetTitle = UiKit.title(activity(), t, "拍摄参数", 20);
        sheetTitle.setSingleLine(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.leftMargin = dp(4);
        headerRow.addView(sheetTitle, titleParams);

        syncProgress = new ProgressBar(activity(), null,
                android.R.attr.progressBarStyleSmall);
        syncProgress.setIndeterminateTintList(
                android.content.res.ColorStateList.valueOf(t.accent));
        syncProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(15), dp(15));
        progressParams.rightMargin = dp(6);
        headerRow.addView(syncProgress, progressParams);

        syncState = UiKit.label(activity(), t, "", 11.5f, t.muted, 0);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-2, -2);
        stateParams.rightMargin = dp(4);
        headerRow.addView(syncState, stateParams);

        FrameLayout close = UiKit.iconButton(activity(), t, Glyph.Icon.CLOSE, t.muted,
                this::dismiss);
        headerRow.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        sheet.addView(headerRow, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildSegments() {
        segmentWrap = new FrameLayout(activity());
        segmentWrap.setBackground(UiKit.rounded(activity(), t.surfaceAlt, t.radiusPill));
        segmentWrap.setPadding(dp(4), dp(4), dp(4), dp(4));

        segmentIndicator = new View(activity());
        segmentIndicator.setBackground(UiKit.rounded(activity(), t.accent, t.radiusPill));
        FrameLayout.LayoutParams indicatorParams = new FrameLayout.LayoutParams(0, -1);
        segmentWrap.addView(segmentIndicator, indicatorParams);

        segmentRow = UiKit.row(activity());
        segmentRow.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            TextView tab = UiKit.label(activity(), t, TABS[i], 13, t.muted, 0);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, dp(9), 0, dp(9));
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setOnClickListener(v -> selectCategory(index));
            tabViews[i] = tab;
            segmentRow.addView(tab, new LinearLayout.LayoutParams(0, -1, 1f));
        }
        segmentWrap.addView(segmentRow, new FrameLayout.LayoutParams(-1, -1));
        segmentWrap.addOnLayoutChangeListener((view, left, top, right, bottom,
                                               oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) != (oldRight - oldLeft)
                    || (bottom - top) != (oldBottom - oldTop)) {
                moveSegmentIndicator(category, false);
            }
        });

        LinearLayout.LayoutParams wrapParams = new LinearLayout.LayoutParams(-1, dp(42));
        wrapParams.setMargins(dp(18), dp(2), dp(18), dp(8));
        sheet.addView(segmentWrap, wrapParams);
        updateTabColors();
    }

    private void buildList() {
        listScroll = new ScrollView(activity());
        listScroll.setFillViewport(false);
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listContent = UiKit.column(activity());
        listContent.setPadding(dp(18), dp(4), dp(18), dp(16));
        listScroll.addView(listContent, new ScrollView.LayoutParams(-1, -2));
        sheet.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    private void buildFooter() {
        LinearLayout footer = UiKit.column(activity());
        footer.setBackgroundColor(t.surfaceAlt);
        footer.addView(UiKit.hairline(activity(), t),
                new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.7f))));
        diagnostic = UiKit.label(activity(), t, "", 10.5f, t.muted, 0);
        diagnostic.setSingleLine(true);
        diagnostic.setEllipsize(TextUtils.TruncateAt.END);
        diagnostic.setGravity(Gravity.CENTER);
        diagnostic.setPadding(dp(16), dp(8), dp(16), dp(9));
        footer.addView(diagnostic, new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(footer, new LinearLayout.LayoutParams(-1, -2));
    }

    // ======================================================================
    //  Category / segment control
    // ======================================================================
    private void selectCategory(int index) {
        if (writing) return;
        if (index == category && pickerRow == null) return;
        UiKit.hapticTick(segmentWrap);
        category = index;
        pickerRow = null;
        updateHeaderForList();
        showCategory(index, null);
        moveSegmentIndicator(index, true);
        updateTabColors();
        animateListIn();
    }

    /** Short cross-fade + horizontal settle when the category content changes. */
    private void animateListIn() {
        if (listScroll == null) return;
        listScroll.animate().cancel();
        listScroll.setAlpha(0f);
        listScroll.setTranslationX(dp(14));
        listScroll.animate().alpha(1f).translationX(0f).setDuration(240)
                .setInterpolator(UiKit.DECELERATE_SOFT).start();
    }

    private void updateTabColors() {
        for (int i = 0; i < tabViews.length; i++) {
            TextView tab = tabViews[i];
            if (tab == null) continue;
            boolean selected = i == category;
            tab.setTextColor(selected ? t.onAccent : t.muted);
            tab.setTypeface(selected ? t.typeface(1) : t.typeface(0));
        }
    }

    private void moveSegmentIndicator(int index, boolean animate) {
        if (segmentRow == null || segmentIndicator == null) return;
        if (index < 0 || index >= segmentRow.getChildCount()) return;
        final View tab = segmentRow.getChildAt(index);
        if (tab.getWidth() <= 0) {
            if (segmentWrap.isAttachedToWindow()) {
                segmentWrap.post(() -> moveSegmentIndicator(index, false));
            }
            return;
        }
        final int targetLeft = tab.getLeft();
        final int targetWidth = tab.getWidth();
        if (!animate) {
            ViewGroup.LayoutParams params = segmentIndicator.getLayoutParams();
            params.width = targetWidth;
            segmentIndicator.setLayoutParams(params);
            segmentIndicator.setX(targetLeft);
            return;
        }
        final int startLeft = (int) segmentIndicator.getX();
        final int startWidth = segmentIndicator.getWidth();
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(260);
        animator.setInterpolator(UiKit.DECELERATE_SOFT);
        animator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            int width = (int) (startWidth + (targetWidth - startWidth) * fraction);
            int left = (int) (startLeft + (targetLeft - startLeft) * fraction);
            ViewGroup.LayoutParams params = segmentIndicator.getLayoutParams();
            params.width = Math.max(0, width);
            segmentIndicator.setLayoutParams(params);
            segmentIndicator.setX(left);
        });
        animator.start();
    }

    // ======================================================================
    //  Category rendering
    // ======================================================================
    private void showCategory(int cat, String fadeKey) {
        category = cat;
        listContent.removeAllViews();
        currentRows.clear();
        if (settings == null || !hasAnyData()) {
            showSyncEmptyCard();
            return;
        }
        if (cat == CAT_MODE) {
            renderMode(fadeKey);
        } else if (cat == CAT_EXPOSURE) {
            renderExposure(fadeKey);
        } else {
            renderImage(fadeKey);
        }
        if (currentRows.isEmpty()) showCategoryEmptyCard();
        if (listScroll != null) listScroll.scrollTo(0, 0);
    }

    private void renderMode(String fadeKey) {
        List<View> views = new ArrayList<>();
        CameraBridge.SettingInfo capture = captureInfo();
        if (capture != null && capture.choices != null && !capture.choices.isEmpty()) {
            Row row = new Row(PSEUDO_MODE, "拍摄模式", KIND_CAPTURE_MODE);
            views.add(buildRowView(row, fadeKey));
            currentRows.add(row);
        }
        addSettingRow(CameraBridge.S_EXPOSURE_PROGRAM, "曝光模式", views, fadeKey);
        if (!isMovie() && selfTimerSupported()) {
            Row row = new Row(PSEUDO_TIMER, "自拍计时", KIND_SELF_TIMER);
            views.add(buildRowView(row, fadeKey));
            currentRows.add(row);
        }
        addGroupWithRows("拍摄", views);
    }

    private void renderExposure(String fadeKey) {
        List<View> views = new ArrayList<>();
        addSettingRow(CameraBridge.S_ISO, "感光度 ISO", views, fadeKey);
        addSettingRow(CameraBridge.S_SHUTTER_SPEED, "快门速度", views, fadeKey);
        addSettingRow(CameraBridge.S_F_NUMBER, "光圈", views, fadeKey);
        addSettingRow(CameraBridge.S_EXPOSURE_COMPENSATION, "曝光补偿", views, fadeKey);
        addSettingRow(CameraBridge.S_METERING, "测光模式", views, fadeKey);
        addGroupWithRows("曝光", views);
    }

    private void renderImage(String fadeKey) {
        List<View> views = new ArrayList<>();
        addSettingRow(CameraBridge.S_WHITE_BALANCE, "白平衡", views, fadeKey);
        addSettingRow(CameraBridge.S_FLASH_MODE, "闪光灯", views, fadeKey);
        addSettingRow(CameraBridge.S_DIGITAL_FILTER, "数码滤镜", views, fadeKey);
        addSettingRow(CameraBridge.S_STILL_QUALITY, "画质", views, fadeKey);
        addSettingRow(CameraBridge.S_STILL_FORMAT, "图像格式", views, fadeKey);
        addGroupWithRows("画质与色彩", views);
    }

    private void addSettingRow(String name, String label, List<View> views, String fadeKey) {
        CameraBridge.SettingInfo info = settings == null ? null : settings.get(name);
        if (info == null || info.choices == null || info.choices.isEmpty()) return;
        Row row = new Row(name, label, KIND_SETTING);
        row.info = info;
        views.add(buildRowView(row, fadeKey));
        currentRows.add(row);
    }

    private void addGroupWithRows(String title, List<View> views) {
        if (views.isEmpty()) return;
        TextView header = UiKit.overline(activity(), t, title, t.muted);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = dp(4);
        params.topMargin = listContent.getChildCount() == 0 ? dp(2) : dp(16);
        params.bottomMargin = dp(8);
        listContent.addView(header, params);

        LinearLayout card = UiKit.column(activity());
        GradientDrawable background = new GradientDrawable();
        background.setColor(t.surfaceAlt);
        background.setCornerRadius(dp(t.radiusInner));
        card.setBackground(background);
        for (int i = 0; i < views.size(); i++) {
            card.addView(views.get(i));
            if (i < views.size() - 1) card.addView(UiKit.hairline(activity(), t));
        }
        listContent.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private View buildRowView(final Row row, String fadeKey) {
        LinearLayout header = UiKit.row(activity());
        header.setPadding(dp(14), dp(13), dp(12), dp(13));
        header.setBackground(UiKit.circularRipple(activity(), t.hairline));
        header.setClickable(true);
        header.setFocusable(true);

        TextView label = UiKit.label(activity(), t, row.label, 14.5f, t.text, 0);
        header.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

        boolean exposure = row.kind == KIND_SETTING
                && CameraBridge.S_EXPOSURE_COMPENSATION.equals(row.name);
        if (exposure) header.addView(stepButton("−", row, -1));

        row.spinner = new ProgressBar(activity(), null,
                android.R.attr.progressBarStyleSmall);
        row.spinner.setIndeterminateTintList(
                android.content.res.ColorStateList.valueOf(t.accent));
        row.spinner.setVisibility(View.GONE);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(15), dp(15));
        spinnerParams.rightMargin = dp(4);
        header.addView(row.spinner, spinnerParams);

        row.valueView = UiKit.label(activity(), t, "", 13.5f, t.muted, 1);
        row.valueView.setGravity(Gravity.END);
        row.valueView.setSingleLine(true);
        row.valueView.setEllipsize(TextUtils.TruncateAt.END);
        row.valueView.setPadding(dp(6), 0, dp(6), 0);
        header.addView(row.valueView, new LinearLayout.LayoutParams(-2, -2));

        if (exposure) header.addView(stepButton("+", row, 1));

        row.chevron = new Glyph(activity(), Glyph.Icon.CHEVRON_RIGHT);
        row.chevron.setTint(t.muted);
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(dp(18), dp(18));
        chevronParams.leftMargin = dp(4);
        header.addView(row.chevron, chevronParams);

        final boolean fade = fadeKey != null && (fadeKey.equals(row.name)
                || (CameraBridge.S_CAPTURE_METHOD.equals(fadeKey)
                && row.kind != KIND_SETTING));
        String value = valueFor(row);
        row.valueView.setText(value);
        if (fade) {
            row.valueView.setAlpha(0.2f);
            row.valueView.animate().alpha(1f).setDuration(220)
                    .setInterpolator(UiKit.DECELERATE).start();
        }

        header.setOnClickListener(v -> openPicker(row));
        row.rowView = header;
        return header;
    }

    private TextView stepButton(String text, final Row row, final int direction) {
        TextView button = UiKit.label(activity(), t, text, 18, t.text, 1);
        button.setGravity(Gravity.CENTER);
        button.setBackground(UiKit.circularRipple(activity(), t.hairline));
        button.setClickable(true);
        button.setFocusable(true);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(30), dp(30)));
        button.setOnClickListener(v -> stepExposure(row, direction));
        UiKit.pressFeedback(button);
        return button;
    }

    // ======================================================================
    //  Value picker (second level)
    // ======================================================================
    private void openPicker(Row row) {
        if (writing || reading || pickerRow != null || destroyed || closing) return;
        List<Choice> choices = choicesFor(row);
        if (choices.isEmpty()) {
            host.showSnack("该设置暂无可选值");
            return;
        }
        pickerRow = row;
        sheetTitle.setText(row.label);
        backButton.setVisibility(View.VISIBLE);
        UiKit.setVisibleAnimated(segmentWrap, false, 140);
        buildPickerList(row, choices);
        listScroll.scrollTo(0, 0);
        listScroll.setAlpha(0.4f);
        listScroll.animate().alpha(1f).setDuration(160).start();
        UiKit.hapticTick(headerRow);
    }

    private void closePicker(String fadeKey) {
        if (pickerRow == null && backButton.getVisibility() != View.VISIBLE) {
            dismiss();
            return;
        }
        pickerRow = null;
        updateHeaderForList();
        showCategory(category, fadeKey);
        listScroll.setAlpha(0.4f);
        listScroll.animate().alpha(1f).setDuration(160).start();
    }

    private void updateHeaderForList() {
        sheetTitle.setText("拍摄参数");
        backButton.setVisibility(View.GONE);
        UiKit.setVisibleAnimated(segmentWrap, true, 160);
        updateTabColors();
        moveSegmentIndicator(category, false);
        updateSyncHeader();
    }

    private void buildPickerList(final Row row, List<Choice> choices) {
        pickerItems.clear();
        listContent.removeAllViews();

        TextView header = UiKit.overline(activity(), t, "可选值", t.muted);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-2, -2);
        headerParams.leftMargin = dp(4);
        headerParams.topMargin = dp(2);
        headerParams.bottomMargin = dp(8);
        listContent.addView(header, headerParams);

        LinearLayout card = UiKit.column(activity());
        GradientDrawable background = new GradientDrawable();
        background.setColor(t.surfaceAlt);
        background.setCornerRadius(dp(t.radiusInner));
        card.setBackground(background);

        for (int i = 0; i < choices.size(); i++) {
            final Choice choice = choices.get(i);
            LinearLayout item = UiKit.row(activity());
            item.setPadding(dp(16), dp(14), dp(14), dp(14));
            item.setBackground(UiKit.circularRipple(activity(), t.hairline));
            item.setClickable(true);
            item.setFocusable(true);

            TextView label = UiKit.label(activity(), t, choice.label, 14.5f,
                    choice.selected ? t.accent : t.text, choice.selected ? 1 : 0);
            item.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            if (choice.selected) {
                Glyph check = new Glyph(activity(), Glyph.Icon.CHECK);
                check.setTint(t.accent);
                check.setStrokeWidthDp(2.2f);
                item.addView(check, new LinearLayout.LayoutParams(dp(20), dp(20)));
            }
            item.setOnClickListener(v -> choose(choice));
            pickerItems.add(item);
            card.addView(item);
            if (i < choices.size() - 1) card.addView(UiKit.hairline(activity(), t));
        }
        listContent.addView(card, new LinearLayout.LayoutParams(-1, -2));
        setPickerEnabled(!writing);
    }

    private void setPickerEnabled(boolean enabled) {
        for (View item : pickerItems) {
            item.setEnabled(enabled);
            item.setAlpha(enabled ? 1f : 0.45f);
        }
    }

    private void choose(Choice choice) {
        final Row row = pickerRow;
        if (row == null || writing || reading || destroyed || closing) return;
        if (choice.selected) {
            UiKit.hapticTick(listContent);
            closePicker(null);
            return;
        }
        writing = true;
        updateSyncHeader();
        setPickerEnabled(false);
        UiKit.hapticTick(listContent);
        if (row.kind == KIND_CAPTURE_MODE) {
            boolean movie = "Movie".equals(choice.raw);
            host.bridge().setCaptureMode(movie, (success, message) ->
                    afterWrite(row, success, message));
        } else {
            host.bridge().writeSetting(row.name, choice.raw, (success, message) ->
                    afterWrite(row, success, message));
        }
    }

    private List<Choice> choicesFor(Row row) {
        List<Choice> result = new ArrayList<>();
        if (row.kind == KIND_SETTING) {
            CameraBridge.SettingInfo info = settings.get(row.name);
            if (info == null || info.choices == null) return result;
            String current = info.value;
            for (String raw : info.choices) {
                if (raw == null) continue;
                result.add(new Choice(displayValue(raw), raw,
                        current != null && current.equals(raw)));
            }
            return result;
        }
        if (row.kind == KIND_CAPTURE_MODE) {
            result.add(new Choice("照片", "StillImage", !isMovie()));
            if (captureChoices().contains("Movie")) {
                result.add(new Choice("视频", "Movie", isMovie()));
            }
            return result;
        }
        // Self timer
        result.add(new Choice("关闭", "StillImage", !isSelfTimer()));
        for (String[] timer : SELF_TIMERS) {
            if (captureChoices().contains(timer[1])) {
                boolean selected = timer[1].equals(captureValue());
                boolean duplicate = false;
                for (Choice existing : result) {
                    if (existing.raw.equals(timer[1])) duplicate = true;
                }
                if (!duplicate) result.add(new Choice(timer[0], timer[1], selected));
            }
        }
        return result;
    }

    // ======================================================================
    //  Writes
    // ======================================================================
    private void stepExposure(final Row row, final int direction) {
        if (writing || reading || destroyed || closing) return;
        writing = true;
        setRowBusy(row, true);
        updateSyncHeader();
        UiKit.hapticTick(row.rowView);
        host.bridge().stepExposureCompensation(direction, (success, message) ->
                afterWrite(row, success, message));
    }

    private void afterWrite(final Row row, boolean success, String message) {
        writing = false;
        if (destroyed || closing) return;
        setRowBusy(row, false);
        if (!success) {
            setPickerEnabled(true);
            updateSyncHeader();
            host.showSnack(message == null || message.isEmpty()
                    ? "该机型不支持此设置值" : message);
            return;
        }
        UiKit.hapticTick(sheet);
        // The picker (if open) stays disabled until the re-read completes and
        // closes it, so a slow camera cannot accept two overlapping writes.
        refreshAfterWrite(row);
    }

    /** Re-reads only the setting that was written and updates the UI. */
    private void refreshAfterWrite(final Row row) {
        final String name = row.kind == KIND_SETTING ? row.name : CameraBridge.S_CAPTURE_METHOD;
        reading = true;
        updateSyncHeader();
        host.bridge().readSettings(Arrays.asList(name), new CameraBridge.SettingsCallback() {
            @Override
            public void onSettings(LinkedHashMap<String, CameraBridge.SettingInfo> snapshot) {
                reading = false;
                if (destroyed || closing) return;
                settings.putAll(snapshot);
                updateSyncHeader();
                updateDiagnostics();
                if (pickerRow == row) {
                    closePicker(name);
                } else {
                    if (row.kind == KIND_SETTING) {
                        applyRowValue(row, true);
                    } else {
                        updateAllCaptureRows(true);
                    }
                }
            }

            @Override
            public void onError(String error) {
                reading = false;
                if (destroyed || closing) return;
                updateSyncHeader();
                if (pickerRow == row) {
                    closePicker(name);
                } else {
                    applyRowValue(row, true);
                }
                host.showSnack("已写入，但重新读取失败");
            }
        });
    }

    private void applyRowValue(Row row, boolean fade) {
        if (row == null || row.valueView == null) return;
        String text = valueFor(row);
        if (text.contentEquals(row.valueView.getText())) return;
        if (fade) {
            crossFadeValue(row.valueView, text);
        } else {
            row.valueView.setText(text);
        }
    }

    private void updateAllCaptureRows(boolean fade) {
        for (Row row : currentRows) {
            if (row.kind == KIND_CAPTURE_MODE || row.kind == KIND_SELF_TIMER) {
                applyRowValue(row, fade);
            }
        }
    }

    private void crossFadeValue(final TextView view, final String text) {
        view.animate().cancel();
        view.animate().alpha(0.2f).setDuration(90).withEndAction(() -> {
            if (destroyed || closing) return;
            view.setText(text);
            view.animate().alpha(1f).setDuration(170).start();
        }).start();
    }

    private void setRowBusy(Row row, boolean busy) {
        if (row == null) return;
        if (row.spinner != null) row.spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (row.valueView != null) row.valueView.setAlpha(busy ? 0.35f : 1f);
        if (row.chevron != null) row.chevron.setVisibility(busy ? View.INVISIBLE : View.VISIBLE);
    }

    // ======================================================================
    //  Value text
    // ======================================================================
    private String valueFor(Row row) {
        if (row.kind == KIND_CAPTURE_MODE) return isMovie() ? "视频" : "照片";
        if (row.kind == KIND_SELF_TIMER) return selfTimerLabel();
        CameraBridge.SettingInfo info = settings == null ? null : settings.get(row.name);
        return info == null ? "—" : displayValue(info.value);
    }

    private String selfTimerLabel() {
        String current = captureValue();
        if (current == null) return "关闭";
        for (String[] timer : SELF_TIMERS) {
            if (timer[1].equals(current)) return timer[0];
        }
        return current.startsWith("SelfTimer") ? "已开启" : "关闭";
    }

    // ======================================================================
    //  Camera capture-method helpers
    // ======================================================================
    private CameraBridge.SettingInfo captureInfo() {
        return settings == null ? null : settings.get(CameraBridge.S_CAPTURE_METHOD);
    }

    private List<String> captureChoices() {
        CameraBridge.SettingInfo info = captureInfo();
        if (info == null || info.choices == null) return new ArrayList<>();
        return info.choices;
    }

    private String captureValue() {
        CameraBridge.SettingInfo info = captureInfo();
        return info == null ? null : info.value;
    }

    private boolean isMovie() {
        String value = captureValue();
        return value != null && value.contains("Movie");
    }

    private boolean isSelfTimer() {
        String value = captureValue();
        return value != null && value.startsWith("SelfTimer");
    }

    private boolean selfTimerSupported() {
        for (String[] timer : SELF_TIMERS) {
            if (captureChoices().contains(timer[1])) return true;
        }
        return isSelfTimer();
    }

    private boolean hasAnyData() {
        if (settings == null) return false;
        for (CameraBridge.SettingInfo info : settings.values()) {
            if (info == null || info.choices == null) continue;
            for (String choice : info.choices) {
                if (choice != null) return true;
            }
        }
        return false;
    }

    // ======================================================================
    //  Sync / empty states
    // ======================================================================
    private void refreshSettings() {
        if (destroyed || closing) return;
        if (host.bridge().state() != CameraBridge.State.CONNECTED) {
            reading = false;
            updateSyncHeader();
            showSyncEmptyCard();
            return;
        }
        reading = true;
        updateSyncHeader();
        main.removeCallbacks(syncTimeout);
        main.postDelayed(syncTimeout, 6000L);
        host.bridge().readSettings(Arrays.asList(NAMES), new CameraBridge.SettingsCallback() {
            @Override
            public void onSettings(LinkedHashMap<String, CameraBridge.SettingInfo> snapshot) {
                reading = false;
                main.removeCallbacks(syncTimeout);
                if (destroyed || closing) return;
                if (snapshot != null) settings = snapshot;
                updateSyncHeader();
                updateDiagnostics();
                showCategory(category, null);
            }

            @Override
            public void onError(String message) {
                reading = false;
                main.removeCallbacks(syncTimeout);
                if (destroyed || closing) return;
                updateSyncHeader();
                showCategory(category, null);
                host.showSnack(message == null ? "读取相机参数失败" : message);
            }
        });
    }

    private void updateSyncHeader() {
        if (syncState == null) return;
        if (writing) {
            syncState.setText("写入中…");
            syncProgress.setVisibility(View.VISIBLE);
        } else if (reading) {
            syncState.setText("同步中…");
            syncProgress.setVisibility(View.VISIBLE);
        } else if (hasAnyData()) {
            syncState.setText("已同步");
            syncProgress.setVisibility(View.GONE);
        } else {
            syncState.setText("同步中…");
            syncProgress.setVisibility(View.VISIBLE);
        }
    }

    private void updateDiagnostics() {
        if (diagnostic == null) return;
        if (host.bridge().state() != CameraBridge.State.CONNECTED) {
            diagnostic.setText("相机未连接 · 参数不可用");
            return;
        }
        String model = host.bridge().model();
        String firmware = host.bridge().displayFirmware();
        diagnostic.setText("相机 " + safe(model) + " · 固件 " + safe(firmware)
                + " · 参数由相机实时上报");
    }

    private void showSyncEmptyCard() {
        listContent.removeAllViews();
        currentRows.clear();
        LinearLayout card = emptyState(Glyph.Icon.REFRESH,
                "相机参数同步中…",
                "相机可能仍在初始化拍摄参数；保持连接后重试即可。",
                "重试", this::refreshSettings);
        listContent.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showCategoryEmptyCard() {
        listContent.removeAllViews();
        currentRows.clear();
        LinearLayout card = emptyState(Glyph.Icon.SLIDERS,
                "此分类暂无可用参数",
                "相机没有为这一类设置上报可调整项目。切换到上方其他分类看看，"
                        + "或连接相机后重试。",
                null, null);
        listContent.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    /**
     * Calm themed empty state: soft accent icon medallion, short explanation and
     * an optional action. Never a bare grey box; the card matches sheet corners.
     */
    private LinearLayout emptyState(Glyph.Icon icon, String title, String body,
                                    String actionLabel, final Runnable action) {
        LinearLayout card = UiKit.column(activity());
        GradientDrawable background = new GradientDrawable();
        background.setColor(t.surface);
        background.setCornerRadius(dp(t.radiusInner));
        background.setStroke(Math.max(1, dp(0.7f)), t.hairline);
        card.setBackground(background);
        card.setPadding(dp(22), dp(26), dp(22), dp(24));

        FrameLayout iconWrap = new FrameLayout(activity());
        GradientDrawable iconBackground = new GradientDrawable();
        iconBackground.setShape(GradientDrawable.OVAL);
        iconBackground.setColor(t.accentSoft);
        iconWrap.setBackground(iconBackground);
        Glyph glyph = new Glyph(activity(), icon);
        glyph.setTint(t.accent);
        glyph.setStrokeWidthDp(1.8f);
        FrameLayout.LayoutParams glyphParams = new FrameLayout.LayoutParams(dp(22), dp(22));
        glyphParams.gravity = Gravity.CENTER;
        iconWrap.addView(glyph, glyphParams);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(52), dp(52));
        iconParams.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(iconWrap, iconParams);

        TextView titleView = UiKit.label(activity(), t, title, 15.5f, t.text, 2);
        titleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(14);
        card.addView(titleView, titleParams);

        TextView bodyView = UiKit.body(activity(), t, body, 12, t.muted);
        bodyView.setGravity(Gravity.CENTER);
        bodyView.setPadding(dp(6), dp(8), dp(6), 0);
        card.addView(bodyView, new LinearLayout.LayoutParams(-1, -2));

        if (action != null && actionLabel != null) {
            TextView button = UiKit.tonalButton(activity(), t, actionLabel);
            button.setOnClickListener(v -> {
                UiKit.hapticTick(v);
                action.run();
            });
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, -2);
            buttonParams.topMargin = dp(16);
            card.addView(button, buttonParams);
        }
        return card;
    }

    // ======================================================================
    //  Lifecycle
    // ======================================================================
    @Override
    public void onShow() {
        closing = false;
        destroyed = false;
        sheetTitle.setText("拍摄参数");
        backButton.setVisibility(View.GONE);
        updateTabColors();
        updateDiagnostics();
        updateSyncHeader();
        showCategory(category, null);

        root.post(() -> {
            if (destroyed) return;
            int height = root.getHeight();
            if (height > 0) {
                ViewGroup.LayoutParams params = sheet.getLayoutParams();
                params.height = (int) (height * 0.9f);
                sheet.setLayoutParams(params);
            }
            moveSegmentIndicator(category, false);
        });

        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(220).start();
        int start = sheet.getHeight() > 0 ? sheet.getHeight()
                : (int) (activity().getResources().getDisplayMetrics().heightPixels * 0.9f);
        sheet.setTranslationY(start);
        sheet.animate().translationY(0f).setDuration(320)
                .setInterpolator(UiKit.DECELERATE_SOFT).start();
        refreshSettings();
    }

    @Override
    public void onHide() {
        closing = true;
        destroyed = true;
        main.removeCallbacksAndMessages(null);
    }

    @Override
    public boolean onBackPressed() {
        if (pickerRow != null) {
            closePicker(null);
            return true;
        }
        dismiss();
        return true;
    }

    @Override
    public void onDestroyView() {
        destroyed = true;
        closing = true;
        main.removeCallbacksAndMessages(null);
        if (sheet != null) sheet.animate().cancel();
        if (scrim != null) scrim.animate().cancel();
    }

    private void dismiss() {
        if (closing) return;
        closing = true;
        main.removeCallbacksAndMessages(null);
        sheet.animate().translationY(sheet.getHeight()).setDuration(240)
                .setInterpolator(UiKit.DECELERATE).start();
        scrim.animate().alpha(0f).setDuration(200).start();
        root.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (host.hasOverlay()) host.closeOverlay();
            }
        }, 220L);
    }

    // ======================================================================
    //  Models / display helpers
    // ======================================================================
    private static final class Row {
        final String name;
        final String label;
        final int kind;
        CameraBridge.SettingInfo info;
        LinearLayout rowView;
        TextView valueView;
        ProgressBar spinner;
        Glyph chevron;

        Row(String name, String label, int kind) {
            this.name = name;
            this.label = label;
            this.kind = kind;
        }
    }

    private static final class Choice {
        final String label;
        final String raw;
        final boolean selected;

        Choice(String label, String raw, boolean selected) {
            this.label = label;
            this.raw = raw;
            this.selected = selected;
        }
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? "未知" : value;
    }

    private static String displayValue(String raw) {
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return "—";
        switch (raw) {
            case "Auto":
                return "自动";
            case "Auto-Hi":
                return "自动高感";
            case "Program":
                return "程序自动";
            case "AperturePriority":
                return "光圈优先";
            case "ShutterSpeedPriority":
                return "快门优先";
            case "Manual":
                return "手动";
            case "Bulb":
                return "B 门";
            case "Time":
                return "T 门";
            case "ISOPriority":
                return "ISO 优先";
            case "ProgramShift":
                return "程序偏移";
            case "Daylight":
                return "日光";
            case "Cloudy":
                return "阴天";
            case "Shade":
                return "阴影";
            case "Tungsten Light":
                return "白炽灯";
            case "Color Temperature Configuration":
                return "色温";
            case "MultiSegment":
                return "多分区测光";
            case "CenterWeighted":
                return "中央重点测光";
            case "Spot":
                return "点测光";
            case "FlashOff":
                return "关闭";
            case "FlashOn":
                return "开启";
            case "RedEye":
                return "防红眼";
            case "SlowSync":
                return "慢速同步";
            default:
                return raw;
        }
    }
}
