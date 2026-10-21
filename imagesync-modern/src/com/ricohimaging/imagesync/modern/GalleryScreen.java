package com.ricohimaging.imagesync.modern;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.ricoh.camera.sdk.wireless.api.CameraImage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rebuilt photo gallery.
 *
 * <p>Two albums live behind one custom-drawn, self-animating segmented control:</p>
 * <ul>
 *   <li><b>相机</b> — the Ricoh camera album through {@link CameraBridge}; grid
 *       cells use the camera's fast compressed preview rendition
 *       ({@link CameraBridge#loadPreview(CameraImage, CameraBridge.PreviewCallback)})
 *       with a small bounded bitmap cache. Full files are only downloaded for
 *       save/share.</li>
 *   <li><b>本机</b> — recent MediaStore photos (JPEG and RAW via their embedded
 *       preview); thumbnails are decoded on workers into a bounded memory cache
 *       so tab switches and scroll-back are instant. Tapping a local photo opens
 *       the system gallery/editor; long-press opens the action sheet.</li>
 * </ul>
 *
 * <p>Everything is choreographed with 200–360 ms view transitions and streamed
 * off the main thread; the camera being disconnected is a first-class empty
 * state, never an error toast storm.</p>
 */
public final class GalleryScreen extends Screen implements CameraBridge.StateListener {

    private static final int TAB_CAMERA = 0;
    private static final int TAB_LOCAL = 1;
    private static final int PAGE_CAP = 600;
    private static final int REQUEST_READ = 4517;
    private static final int REQUEST_NOTIFICATIONS = 4518;
    private static final int DOWNLOAD_NOTIFICATION_ID = 4301;

    private static final int VIEW_GRID = 0;
    private static final int VIEW_SKELETON = 1;
    private static final int VIEW_EMPTY = 2;
    private static final int VIEW_ERROR = 3;

    private static final int EMPTY_DISCONNECTED = 0;
    private static final int EMPTY_BLE = 1;
    private static final int EMPTY_CAMERA = 2;
    private static final int EMPTY_LOCAL = 3;

    private static final float GRID_RADIUS = 16f;
    private static final int LOCAL_THUMB_MIN_PX = 360;
    private static final int LOCAL_PRELOAD_COUNT = 12;

    // ------------------------------------------------------------------ shell
    private FrameLayout root;
    private FrameLayout contentFrame;
    private GridView grid;
    private GridAdapter adapter;
    private int gridSide;
    private int cameraFirstVisible;
    private int localFirstVisible;

    private TextView subtitleView;
    private SegmentView segment;
    private int tab = TAB_CAMERA;

    // ----------------------------------------------------------------- states
    private View skeletonView;
    private final List<View> skeletonCells = new ArrayList<>();
    private ValueAnimator shimmer;
    private LinearLayout emptyView;
    private FrameLayout emptyArt;
    private TextView emptyTitle;
    private TextView emptyBody;
    private LinearLayout emptyHintRow;
    private TextView emptyHint;
    private TextView emptyPrimary;
    private TextView emptySecondary;
    private LinearLayout errorView;
    private TextView errorTitle;
    private TextView errorBody;
    private TextView errorRetry;

    // ----------------------------------------------------------- selection bar
    private final java.util.Set<String> selectedKeys = new java.util.HashSet<>();
    private LinearLayout selectionBar;
    private TextView selectionCount;
    private FrameLayout selectionDownload;
    private FrameLayout selectionDelete;
    private boolean selectionBarShown;
    private boolean batchBusy;
    private boolean downloadBatchRunning;
    private View refreshPull;
    private TextView refreshPullText;
    private View refreshSpinner;
    private boolean pullTracking;
    private boolean pullRefreshing;
    private float pullOffset;
    private float pullStartY;
    private volatile String lastDownloadError;
    private boolean downloading;

    // ------------------------------------------------- local delete confirm
    private View confirmScrim;
    private LinearLayout confirmCard;
    private TextView confirmTitle;
    private TextView confirmMessage;
    private Runnable confirmAction;
    private boolean confirmShown;

    // ------------------------------------------------------------------- data
    private final List<CameraImage> cameraImages = new ArrayList<>();
    private final List<LocalMedia> localItems = new ArrayList<>();
    private boolean cameraLoaded;
    private boolean cameraLoading;
    private boolean localLoaded;
    private boolean localLoading;
    private boolean localPartial;
    private String cameraError;
    private String localError;
    private boolean connecting;
    private boolean skipImageLoads;
    private long lastCameraRefreshAt;
    private long lastSnackAt;
    private int currentViewState = -1;
    private int currentEmptyMode = -1;

    private boolean visible;
    private boolean shownOnce;
    private boolean destroyed;
    private boolean listenerAdded;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<android.animation.Animator> emptyAnimators = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService localImagePool = Executors.newFixedThreadPool(2);

    // Small bitmap caches keep camera/local grids instant while scrolling and
    // when switching between the two albums. Bounded by decoded byte size.
    private final LruCache<String, Bitmap> cameraThumbs =
            new LruCache<String, Bitmap>(12 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return Math.max(1, value.getByteCount() / 1024);
                }
            };
    private final LruCache<String, Bitmap> localThumbs =
            new LruCache<String, Bitmap>(36 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return Math.max(1, value.getByteCount() / 1024);
                }
            };

    public GalleryScreen(ShellActivity host) {
        super(host);
    }

    // =====================================================================
    // Construction
    // =====================================================================
    @Override
    protected View onCreateView() {
        root = new FrameLayout(activity());
        root.setBackgroundColor(t.background);
        root.setClipChildren(true);

        LinearLayout page = UiKit.column(activity());
        int side = dp(18);
        page.setPadding(side, dp(12), side, 0);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        page.addView(buildHeader());
        page.addView(buildSegment());
        contentFrame = new FrameLayout(activity());
        contentFrame.setClipChildren(true);
        page.addView(contentFrame, new LinearLayout.LayoutParams(-1, 0, 1f));

        buildGrid();
        buildSkeleton();
        buildEmpty();
        buildError();
        currentViewState = -1;

        buildSelectionBar();
        buildConfirmDialog();

        adapter = new GridAdapter();
        grid.setAdapter(adapter);
        // First layout pass: re-bind once so rounded clipping and cell sizing are
        // applied to the views that were bound before the grid had bounds.
        grid.post(() -> {
            if (destroyed || adapter == null || grid == null) return;
            adapter.notifyDataSetChanged();
            grid.invalidate();
        });
        grid.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        try {
                            grid.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        } catch (Throwable ignored) {
                            // Already detached.
                        }
                        if (destroyed || adapter == null) return;
                        adapter.notifyDataSetChanged();
                        grid.invalidate();
                    }
                });

        if (!listenerAdded) {
            listenerAdded = true;
            host.bridge().addStateListener(this);
        }
        updateHeader();
        applyState();
        return root;
    }

    // ------------------------------------------------------------------ header
    private LinearLayout buildHeader() {
        LinearLayout header = UiKit.column(activity());
        TextView overline = UiKit.overline(activity(), t, "IMAGE SYNC · 相册", t.accent);
        header.addView(overline);

        LinearLayout top = UiKit.row(activity());
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = UiKit.column(activity());
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        titles.addView(UiKit.title(activity(), t, "照片", 29));
        subtitleView = UiKit.label(activity(), t, "", 12.5f, t.muted, 0);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-2, -2);
        subParams.topMargin = dp(4);
        titles.addView(subtitleView, subParams);
        FrameLayout gear = UiKit.iconButton(activity(), t, Glyph.Icon.SETTINGS, t.muted,
                this::showPhotoSettings);
        top.addView(gear, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(6);
        header.addView(top, titleParams);

        header.addView(UiKit.spacer(activity(), 14));
        return header;
    }

    // ---------------------------------------------------------- photo settings
    private FrameLayout photoSheet;
    private TextView photoFormatStatus;
    private TextView photoJpegTab;
    private TextView photoHeicTab;

    private void showPhotoSettings() {
        if (photoSheet != null) return;
        FrameLayout scrim = new FrameLayout(activity());
        scrim.setBackgroundColor(0x99000000);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> closePhotoSettings());

        LinearLayout sheet = UiKit.column(activity());
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(t.surfaceHigh);
        bg.setCornerRadius(dp(t.radiusCard));
        bg.setStroke(Math.max(1, dp(0.8f)), t.hairline);
        sheet.setBackground(bg);
        sheet.setPadding(dp(20), dp(16), dp(20), dp(22));
        sheet.setClickable(true);

        sheet.addView(UiKit.overline(activity(), t, "IMAGE SYNC · 照片设置", t.accent));
        sheet.addView(UiKit.title(activity(), t, "照片设置", 24));
        sheet.addView(UiKit.spacer(activity(), 14));

        LinearLayout row = UiKit.row(activity());
        final String current = activity().getSharedPreferences("modern_ui", 0)
                .getString("save_format", "jpeg");
        photoJpegTab = formatPill("JPEG", !"heic".equals(current));
        photoHeicTab = formatPill("HEIC · 压缩", "heic".equals(current));
        row.addView(photoJpegTab, weighted(0));
        row.addView(photoHeicTab, weighted(1));
        sheet.addView(row, new LinearLayout.LayoutParams(-1, -2));

        photoFormatStatus = UiKit.body(activity(), t, heicStatusText(), 11.5f, t.muted);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(10);
        sheet.addView(photoFormatStatus, statusParams);

        TextView convert = UiKit.tonalButton(activity(), t, "立即转换已有照片");
        convert.setOnClickListener(v -> startHeicMigration());
        LinearLayout.LayoutParams convertParams = new LinearLayout.LayoutParams(-1, -2);
        convertParams.topMargin = dp(12);
        sheet.addView(convert, convertParams);

        TextView close = UiKit.textButton(activity(), t, "完成");
        close.setOnClickListener(v -> closePhotoSettings());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, -2);
        closeParams.topMargin = dp(8);
        sheet.addView(close, closeParams);

        FrameLayout.LayoutParams sheetParams = new FrameLayout.LayoutParams(-1, -2);
        sheetParams.gravity = Gravity.BOTTOM;
        sheetParams.setMargins(dp(10), 0, dp(10), dp(10));
        scrim.addView(sheet, sheetParams);
        ((FrameLayout) root).addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(180).start();
        sheet.setTranslationY(dp(40));
        sheet.animate().translationY(0f).setDuration(260)
                .setInterpolator(UiKit.DECELERATE_SOFT).start();
        photoSheet = scrim;
    }

    private TextView formatPill(String label, boolean selected) {
        TextView pill = UiKit.label(activity(), t, label, 13,
                selected ? t.onAccent : t.muted, selected ? 2 : 1);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(0, dp(12), 0, dp(12));
        pill.setBackground(UiKit.rounded(activity(),
                selected ? t.accent : t.surfaceAlt, t.radiusPill));
        pill.setClickable(true);
        pill.setFocusable(true);
        final boolean heic = "HEIC".equals(label.substring(0, 4));
        pill.setOnClickListener(v -> selectPhotoFormat(heic));
        return pill;
    }

    private LinearLayout.LayoutParams weighted(int index) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        if (index == 0) params.rightMargin = dp(10);
        return params;
    }

    private void styleFormatPills(boolean heic) {
        if (photoJpegTab == null || photoHeicTab == null) return;
        TextView[] pills = {photoJpegTab, photoHeicTab};
        for (int i = 0; i < pills.length; i++) {
            boolean selected = (i == 1) == heic;
            pills[i].setTextColor(selected ? t.onAccent : t.muted);
            pills[i].setTypeface(t.typeface(selected ? 2 : 1));
            pills[i].setBackground(UiKit.rounded(activity(),
                    selected ? t.accent : t.surfaceAlt, t.radiusPill));
        }
    }

    private String heicStatusText() {
        if (!HeicConverter.isSupported(activity())) {
            return "当前系统没有可用的 HEVC 编码器，无法生成 HEIC；照片会继续保存为 JPEG。";
        }
        return "HEIC 约为原 JPEG 的 40% 体积，画质接近原图。选择后会自动把 "
                + "Pictures/Image Sync 中已有的 JPEG 转为 HEIC（原图会被替换）。";
    }

    private void selectPhotoFormat(boolean heic) {
        activity().getSharedPreferences("modern_ui", 0).edit()
                .putString("save_format", heic ? "heic" : "jpeg").apply();
        styleFormatPills(heic);
        if (!heic) {
            setPhotoStatus("已改为 JPEG，新保存的照片将保持 JPEG。");
            return;
        }
        if (!HeicConverter.isSupported(activity())) {
            activity().getSharedPreferences("modern_ui", 0).edit()
                    .putString("save_format", "jpeg").apply();
            styleFormatPills(false);
            setPhotoStatus("当前系统没有可用的 HEVC 编码器，已保持 JPEG。");
            return;
        }
        setPhotoStatus("已启用 HEIC，开始转换已有照片…");
        startHeicMigration();
    }

    private void startHeicMigration() {
        if (!HeicConverter.isSupported(activity())) {
            setPhotoStatus("当前系统没有可用的 HEVC 编码器。");
            return;
        }
        setPhotoStatus("转换中，请留意通知栏进度…");
        HeicMigration.migrate(activity(), new HeicMigration.ProgressCallback() {
            @Override
            public void onProgress(int done, int total) {
                setPhotoStatus("正在转换 " + done + " / " + total + " 张…");
            }

            @Override
            public void onFinished(int converted, int failed, long savedBytes,
                                   String firstError) {
                if (converted > 0) {
                    setPhotoStatus("转换完成： " + converted + " 张，节省 "
                            + Math.max(0, savedBytes / (1024 * 1024)) + " MB"
                            + (failed > 0 ? "（失败 " + failed + " 张）" : ""));
                } else if (firstError != null && !firstError.isEmpty()) {
                    setPhotoStatus("转换失败：" + firstError);
                } else {
                    setPhotoStatus("没有可转换的照片。");
                }
            }
        });
    }

    private void setPhotoStatus(String text) {
        if (photoFormatStatus != null) photoFormatStatus.setText(text);
    }

    private void closePhotoSettings() {
        final FrameLayout sheet = photoSheet;
        if (sheet == null) return;
        photoSheet = null;
        sheet.animate().alpha(0f).setDuration(160)
                .withEndAction(() -> {
                    if (sheet.getParent() instanceof ViewGroup) {
                        ((ViewGroup) sheet.getParent()).removeView(sheet);
                    }
                }).start();
    }

    // -------------------------------------------------------------- segment
    private SegmentView buildSegment() {
        segment = new SegmentView(activity(), t, new String[]{"相机", "本机"});
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(46));
        params.topMargin = dp(2);
        segment.setLayoutParams(params);
        segment.setSelected(tab, false);
        segment.setOnSegmentChangeListener(index -> {
            if (index != tab) switchTab(index);
        });
        return segment;
    }

    private void switchTab(int next) {
        if (next == tab) return;
        if (downloading || batchBusy) {
            snack("正在处理，请稍候", true);
            segment.setSelected(tab, true);
            return;
        }
        clearSelection(false);
        hideConfirm(false);
        resetPullUi();
        // Remember each album's scroll offset so refresh-on-entry never jumps.
        if (grid != null) {
            if (tab == TAB_CAMERA) cameraFirstVisible = grid.getFirstVisiblePosition();
            else localFirstVisible = grid.getFirstVisiblePosition();
        }
        tab = next;
        segment.setSelected(tab, true);
        updateHeader();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (tab == TAB_CAMERA && host.bridge().state() != CameraBridge.State.CONNECTED) {
            cameraImages.clear();
            cameraLoading = false;
            cameraError = null;
            cameraLoaded = true;
            currentViewState = -1;
            currentEmptyMode = -1;
        }
        applyState();
        if (grid != null && grid.getCount() > 0) {
            int restore = tab == TAB_CAMERA ? cameraFirstVisible : localFirstVisible;
            if (restore > 0) grid.setSelection(restore);
        }
        if (tab == TAB_CAMERA) {
            if (cameraLoaded) applyState();
            else refreshCamera(false);
        } else {
            // Re-scan on entry: the header refresh button is gone, and photos may
            // have been saved by the camera/viewer while we were away. The scan
            // only rebuilds the list when the data actually changed.
            loadLocal(true);
        }
    }

    // ------------------------------------------------------------------- grid
    private void buildGrid() {
        grid = new GridView(activity());
        grid.setNumColumns(3);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        int gutter = dp(3);
        grid.setHorizontalSpacing(gutter);
        grid.setVerticalSpacing(gutter);
        int screen = activity().getResources().getDisplayMetrics().widthPixels;
        int side = dp(14);
        gridSide = dp(14);
        grid.setColumnWidth(Math.max(dp(60),
                (screen - side * 2 - gridSide * 2 - gutter * 2) / 3));
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setDrawSelectorOnTop(false);
        grid.setVerticalScrollBarEnabled(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setClipToPadding(false);
        // Comfortable gap below the segment, and breathing room above the dock.
        grid.setPadding(gridSide, dp(14), gridSide, dp(28));
        grid.setVisibility(View.GONE);
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_FLING) {
                    skipImageLoads = true;
                } else if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                        && skipImageLoads) {
                    skipImageLoads = false;
                    if (adapter != null) adapter.notifyDataSetChanged();
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem,
                                 int visibleItemCount, int totalItemCount) {
                // Fling state above is enough to avoid a fetch storm.
            }
        });
        grid.setOnItemClickListener((parent, view, position, id) -> openItem(position));
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            UiKit.haptic(view);
            if (isSelecting()) {
                toggleSelection(position);
            } else {
                enterSelection(position);
            }
            return true;
        });
        // Tapping the empty area (padding/gaps outside cells) exits selection.
        grid.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP && isSelecting()
                    && grid.pointToPosition((int) event.getX(), (int) event.getY())
                    == AbsListView.INVALID_POSITION) {
                clearSelection(true);
            }
            return false;
        });
        contentFrame.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        buildPullToRefresh();
    }

    private void animateGridIn() {
        if (grid == null) return;
        grid.post(() -> {
            if (destroyed || grid == null) return;
            int count = Math.min(grid.getChildCount(), 12);
            for (int i = 0; i < count; i++) {
                View child = grid.getChildAt(i);
                child.setAlpha(0f);
                child.setTranslationY(dp(12));
                child.animate().alpha(1f).translationY(0f)
                        .setStartDelay(i * 22L).setDuration(310)
                        .setInterpolator(UiKit.DECELERATE_SOFT).start();
            }
        });
    }

    // --------------------------------------------------------------- skeleton
    private void buildSkeleton() {
        LinearLayout column = UiKit.column(activity());
        column.setPadding(dp(14), dp(14), dp(14), dp(28));
        int cellSize = columnSize();
        for (int rowIndex = 0; rowIndex < 4; rowIndex++) {
            LinearLayout row = UiKit.row(activity());
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams =
                    new LinearLayout.LayoutParams(-1, cellSize);
            if (rowIndex > 0) rowParams.topMargin = dp(3);
            column.addView(row, rowParams);
            for (int columnIndex = 0; columnIndex < 3; columnIndex++) {
                View cell = new View(activity());
                // Rounded drawable only; no outline-provider dependency anywhere.
                cell.setBackground(UiKit.rounded(activity(), t.surfaceAlt, GRID_RADIUS));
                cell.setAlpha(0.72f);
                LinearLayout.LayoutParams cellParams =
                        new LinearLayout.LayoutParams(0, -1, 1f);
                if (columnIndex > 0) cellParams.leftMargin = dp(3);
                row.addView(cell, cellParams);
                skeletonCells.add(cell);
            }
        }
        skeletonView = column;
        skeletonView.setVisibility(View.GONE);
        skeletonView.setClickable(true);
        contentFrame.addView(skeletonView, new FrameLayout.LayoutParams(-1, -1));
    }

    private void startShimmer() {
        if (shimmer != null || skeletonCells.isEmpty()) return;
        shimmer = ValueAnimator.ofFloat(0.52f, 0.92f);
        shimmer.setDuration(820);
        shimmer.setRepeatCount(ValueAnimator.INFINITE);
        shimmer.setRepeatMode(ValueAnimator.REVERSE);
        shimmer.setInterpolator(new LinearInterpolator());
        shimmer.addUpdateListener(animation -> {
            float value = (float) animation.getAnimatedValue();
            for (int i = 0; i < skeletonCells.size(); i++) {
                skeletonCells.get(i).setAlpha(value);
            }
        });
        shimmer.start();
    }

    private void stopShimmer() {
        if (shimmer != null) {
            shimmer.cancel();
            shimmer = null;
        }
    }

    // ------------------------------------------------------------ empty state
    private void buildEmpty() {
        emptyView = UiKit.column(activity());
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(30), dp(8), dp(30), dp(40));

        emptyArt = new FrameLayout(activity());
        emptyArt.setClipChildren(false);
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(dp(204), dp(204));
        artParams.bottomMargin = dp(10);
        emptyView.addView(emptyArt, artParams);

        emptyTitle = UiKit.title(activity(), t, "", 21);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyView.addView(emptyTitle);

        emptyBody = UiKit.body(activity(), t, "", 13, t.muted);
        emptyBody.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(9);
        emptyView.addView(emptyBody, bodyParams);

        emptyHintRow = UiKit.row(activity());
        emptyHintRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(14);
        emptyHintRow.setVisibility(View.GONE);
        emptyView.addView(emptyHintRow, hintParams);
        ProgressBar hintSpinner = new ProgressBar(activity(), null,
                android.R.attr.progressBarStyleSmall);
        hintSpinner.setIndeterminateTintList(ColorStateList.valueOf(t.accent));
        emptyHintRow.addView(hintSpinner, new LinearLayout.LayoutParams(dp(16), dp(16)));
        emptyHint = UiKit.label(activity(), t, "", 12, t.muted, 0);
        emptyHint.setPadding(dp(8), 0, 0, 0);
        emptyHintRow.addView(emptyHint);

        emptyPrimary = compactPrimary("快速连接");
        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(-1, -2);
        primaryParams.topMargin = dp(22);
        emptyView.addView(emptyPrimary, primaryParams);

        emptySecondary = UiKit.tonalButton(activity(), t, "官方连接向导");
        emptySecondary.setVisibility(View.GONE);
        LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(-1, -2);
        secondaryParams.topMargin = dp(10);
        emptyView.addView(emptySecondary, secondaryParams);

        emptyView.setVisibility(View.GONE);
        contentFrame.addView(emptyView, new FrameLayout.LayoutParams(-1, -1));
    }

    private TextView compactPrimary(String text) {
        TextView button = UiKit.label(activity(), t, text, 15, t.onAccent, 2);
        button.setGravity(Gravity.CENTER);
        button.setLetterSpacing(0.01f);
        button.setBackground(UiKit.pressable(activity(), t.accent, 18, 0x33FFFFFF));
        button.setClickable(true);
        button.setFocusable(true);
        button.setMinHeight(dp(52));
        button.setPadding(dp(20), dp(14), dp(20), dp(14));
        UiKit.pressFeedback(button);
        return button;
    }

    private void renderEmptyArt(Glyph.Icon icon, boolean accent) {
        clearEmptyAnimations();
        emptyArt.removeAllViews();
        View halo = new View(activity());
        halo.setBackground(UiKit.rounded(activity(), accent ? t.accentSoft : t.surfaceAlt, 68));
        FrameLayout.LayoutParams haloParams =
                new FrameLayout.LayoutParams(dp(136), dp(136));
        haloParams.gravity = Gravity.CENTER;
        emptyArt.addView(halo, haloParams);

        FrameLayout inner = new FrameLayout(activity());
        inner.setBackground(UiKit.stroke(activity(), t.surfaceHigh, 46, t.hairline));
        if (Build.VERSION.SDK_INT >= 21) inner.setElevation(dp(2));
        FrameLayout.LayoutParams innerParams =
                new FrameLayout.LayoutParams(dp(92), dp(92));
        innerParams.gravity = Gravity.CENTER;
        emptyArt.addView(inner, innerParams);

        Glyph glyph = new Glyph(activity(), icon);
        glyph.setTint(accent ? t.accent : t.muted);
        glyph.setStrokeWidthDp(1.6f);
        FrameLayout.LayoutParams glyphParams = new FrameLayout.LayoutParams(dp(42), dp(42));
        glyphParams.gravity = Gravity.CENTER;
        inner.addView(glyph, glyphParams);

        if (icon == Glyph.Icon.WIFI || icon == Glyph.Icon.GALLERY) {
            addWifiEmptyAnimation(halo);
        } else {
            View dot = new View(activity());
            dot.setBackground(UiKit.rounded(activity(), t.accent, 100));
            FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(dp(10), dp(10));
            dotParams.gravity = Gravity.TOP | Gravity.END;
            dotParams.topMargin = dp(14);
            dotParams.rightMargin = dp(10);
            emptyArt.addView(dot, dotParams);
        }
        UiKit.enterFromBottom(emptyArt, 0);
    }

    private void clearEmptyAnimations() {
        for (android.animation.Animator animator : emptyAnimators) {
            try {
                animator.cancel();
            } catch (Throwable ignored) {
                // Animation teardown is best effort.
            }
        }
        emptyAnimators.clear();
    }

    /** Pulsing halo + expanding signal rings + a small accent dot orbiting the Wi-Fi glyph. */
    private void addWifiEmptyAnimation(View halo) {
        int artSize = dp(136);
        ValueAnimator breathe = ValueAnimator.ofFloat(1f, 1.06f);
        breathe.setDuration(2100);
        breathe.setRepeatCount(ValueAnimator.INFINITE);
        breathe.setRepeatMode(ValueAnimator.REVERSE);
        breathe.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        breathe.addUpdateListener(animation -> {
            float scale = (float) animation.getAnimatedValue();
            halo.setScaleX(scale);
            halo.setScaleY(scale);
        });
        breathe.start();
        emptyAnimators.add(breathe);

        for (int i = 0; i < 2; i++) {
            View ring = new View(activity());
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(0x00000000);
            shape.setStroke(Math.max(1, dp(1.2f)), t.accentWithAlpha(0x66));
            ring.setBackground(shape);
            FrameLayout.LayoutParams ringParams = new FrameLayout.LayoutParams(artSize, artSize);
            ringParams.gravity = Gravity.CENTER;
            emptyArt.addView(ring, ringParams);
            ring.setScaleX(0.72f);
            ring.setScaleY(0.72f);
            ValueAnimator ripple = ValueAnimator.ofFloat(0f, 1f);
            ripple.setDuration(2400);
            ripple.setStartDelay(i * 900L);
            ripple.setRepeatCount(ValueAnimator.INFINITE);
            ripple.setInterpolator(new android.view.animation.LinearInterpolator());
            ripple.addUpdateListener(animation -> {
                float fraction = (float) animation.getAnimatedValue();
                float scale = 0.72f + fraction * 0.63f;
                ring.setScaleX(scale);
                ring.setScaleY(scale);
                ring.setAlpha((1f - fraction) * 0.7f);
            });
            ripple.start();
            emptyAnimators.add(ripple);
        }

        FrameLayout orbit = new FrameLayout(activity());
        FrameLayout.LayoutParams orbitParams = new FrameLayout.LayoutParams(artSize, artSize);
        orbitParams.gravity = Gravity.CENTER;
        emptyArt.addView(orbit, orbitParams);
        View satellite = new View(activity());
        satellite.setBackground(UiKit.rounded(activity(), t.accent, 100));
        FrameLayout.LayoutParams satelliteParams =
                new FrameLayout.LayoutParams(dp(10), dp(10));
        satelliteParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        satelliteParams.topMargin = dp(2);
        orbit.addView(satellite, satelliteParams);
        ObjectAnimator spin = ObjectAnimator.ofFloat(orbit, "rotation", 0f, 360f);
        spin.setDuration(6200);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new android.view.animation.LinearInterpolator());
        spin.start();
        emptyAnimators.add(spin);
    }

    private void configureEmpty(int mode) {
        if (emptyView == null) return;
        switch (mode) {
            case EMPTY_BLE:
                renderEmptyArt(Glyph.Icon.BLUETOOTH, true);
                emptyTitle.setText("蓝牙已连接");
                emptyBody.setText("正在等待相机 Wi-Fi 就绪。请在相机上打开无线局域网，然后点击快速连接。");
                emptyPrimary.setText(connecting ? "连接中…" : "快速连接");
                emptyPrimary.setOnClickListener(v -> startQuickConnect());
                emptySecondary.setText("官方连接向导");
                emptySecondary.setVisibility(View.VISIBLE);
                emptySecondary.setOnClickListener(v -> {
                    UiKit.haptic(v);
                    host.openRemote();
                });
                break;
            case EMPTY_CAMERA:
                renderEmptyArt(Glyph.Icon.CAMERA, false);
                emptyTitle.setText("相机里还没有照片");
                emptyBody.setText("用相机拍下第一张照片，再回到这里刷新即可看到。");
                emptyPrimary.setText("刷新");
                emptyPrimary.setOnClickListener(v -> refreshCamera(true));
                emptySecondary.setVisibility(View.GONE);
                break;
            case EMPTY_LOCAL:
                renderEmptyArt(Glyph.Icon.GALLERY, false);
                emptyTitle.setText("本机还没有照片");
                boolean canRequest = Build.VERSION.SDK_INT < 33 && !LocalMedia.hasFullAccess(activity());
                if (!LocalMedia.hasFullAccess(activity())) {
                    emptyBody.setText("当前只显示 Image Sync 保存的照片。"
                            + (canRequest ? "授予相册访问权限后，可以浏览手机中的全部照片。"
                            : "系统未授予本应用完整相册权限，其他照片不会显示。"));
                } else {
                    emptyBody.setText("手机相册中的照片会显示在这里。连接相机下载后，照片会保存到 Pictures/Image Sync。");
                }
                emptyPrimary.setText("刷新");
                emptyPrimary.setOnClickListener(v -> loadLocal(true));
                if (canRequest) {
                    emptySecondary.setText("授予访问权限");
                    emptySecondary.setVisibility(View.VISIBLE);
                    emptySecondary.setOnClickListener(v -> requestReadPermission());
                } else {
                    emptySecondary.setVisibility(View.GONE);
                }
                break;
            case EMPTY_DISCONNECTED:
            default:
                renderEmptyArt(Glyph.Icon.WIFI, true);
                emptyTitle.setText("相机还没有连接");
                emptyBody.setText("连接 RICOH 相机后，这里会显示相机存储卡中的照片，可直接下载到手机。");
                emptyPrimary.setText(connecting ? "连接中…" : "快速连接");
                emptyPrimary.setOnClickListener(v -> startQuickConnect());
                emptySecondary.setText("官方连接向导");
                emptySecondary.setVisibility(View.VISIBLE);
                emptySecondary.setOnClickListener(v -> {
                    UiKit.haptic(v);
                    host.openRemote();
                });
                break;
        }
        if (mode == EMPTY_DISCONNECTED || mode == EMPTY_BLE) {
            emptyPrimary.setAlpha(connecting ? 0.62f : 1f);
            emptyPrimary.setEnabled(!connecting);
            emptyHintRow.setVisibility(connecting ? View.VISIBLE : View.GONE);
            emptyHint.setText("正在通过 Wi-Fi 查找相机…");
        } else {
            emptyPrimary.setAlpha(1f);
            emptyPrimary.setEnabled(true);
            emptyHintRow.setVisibility(View.GONE);
        }
    }

    private void updateEmptyLoading() {
        if (emptyView == null || emptyHintRow == null || emptyPrimary == null) return;
        boolean busy = connecting
                || (tab == TAB_CAMERA ? cameraLoading : localLoading);
        emptyHintRow.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) {
            emptyHint.setText(tab == TAB_CAMERA
                    ? (connecting ? "正在通过 Wi-Fi 连接相机…" : "正在读取相机相册…")
                    : "正在扫描本机照片…");
        }
        emptyPrimary.setEnabled(!busy);
        emptyPrimary.setAlpha(busy ? 0.62f : 1f);
    }

    private void updateEmptyConnecting() {
        int mode = currentEmptyMode;
        if (mode != EMPTY_DISCONNECTED && mode != EMPTY_BLE) return;
        emptyPrimary.setText(connecting ? "连接中…" : "快速连接");
        emptyPrimary.setAlpha(connecting ? 0.62f : 1f);
        emptyPrimary.setEnabled(!connecting);
        emptyHintRow.setVisibility(connecting ? View.VISIBLE : View.GONE);
    }

    private void requestReadPermission() {
        try {
            host.requestPermissions(new String[]{LocalMedia.requestablePermission()}, REQUEST_READ);
        } catch (Throwable ignored) {
            snack("请到系统设置中开启照片权限", true);
        }
        main.postDelayed(() -> {
            if (!destroyed) loadLocal(true);
        }, 900);
    }

    // ------------------------------------------------------------ error state
    private void buildError() {
        errorView = UiKit.column(activity());
        errorView.setGravity(Gravity.CENTER);
        errorView.setPadding(dp(30), dp(8), dp(30), dp(40));

        FrameLayout art = new FrameLayout(activity());
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(dp(104), dp(104));
        artParams.bottomMargin = dp(16);
        errorView.addView(art, artParams);
        View circle = new View(activity());
        circle.setBackground(UiKit.rounded(activity(), t.surfaceAlt, 52));
        art.addView(circle, new FrameLayout.LayoutParams(-1, -1));
        Glyph warning = new Glyph(activity(), Glyph.Icon.WARNING);
        warning.setTint(t.danger);
        FrameLayout.LayoutParams glyphParams = new FrameLayout.LayoutParams(dp(40), dp(40));
        glyphParams.gravity = Gravity.CENTER;
        art.addView(warning, glyphParams);

        errorTitle = UiKit.title(activity(), t, "照片加载失败", 21);
        errorTitle.setGravity(Gravity.CENTER);
        errorView.addView(errorTitle);
        errorBody = UiKit.body(activity(), t, "", 13, t.muted);
        errorBody.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(9);
        errorView.addView(errorBody, bodyParams);
        errorRetry = compactPrimary("重试");
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-1, -2);
        retryParams.topMargin = dp(22);
        errorView.addView(errorRetry, retryParams);

        errorView.setVisibility(View.GONE);
        contentFrame.addView(errorView, new FrameLayout.LayoutParams(-1, -1));
    }

    private void configureError(String message) {
        boolean camera = tab == TAB_CAMERA;
        errorTitle.setText(camera ? "读取相机照片失败" : "扫描本机照片失败");
        errorBody.setText(message == null || message.isEmpty()
                ? (camera ? "请检查相机连接后重试。" : "请稍后重试。")
                : message);
        errorRetry.setOnClickListener(v -> {
            UiKit.haptic(v);
            if (camera) refreshCamera(true);
            else loadLocal(true);
        });
    }

    // ----------------------------------------------------------- selection bar
    private void buildSelectionBar() {
        selectionBar = UiKit.row(activity());
        selectionBar.setGravity(Gravity.CENTER_VERTICAL);
        selectionBar.setPadding(dp(18), dp(6), dp(8), dp(6));
        // Dimensional floating pill: subtle top-highlight gradient + hairline rim
        // over an 8dp elevation shadow.
        GradientDrawable capsule = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{lighten(t.surfaceHigh, 0.06f), t.surfaceHigh});
        capsule.setCornerRadius(dp(t.radiusPill));
        capsule.setStroke(Math.max(1, dp(1)), t.hairline);
        selectionBar.setBackground(capsule);
        selectionBar.setElevation(dp(8));
        selectionBar.setAlpha(0f);
        selectionBar.setTranslationY(dp(18));
        selectionBar.setVisibility(View.GONE);

        selectionCount = UiKit.label(activity(), t, "", 13, t.text, 2);
        selectionBar.addView(selectionCount);

        View gap = new View(activity());
        selectionBar.addView(gap, new LinearLayout.LayoutParams(dp(14), 1));

        selectionDownload = selectionButton(Glyph.Icon.DOWNLOAD, 0xFF4CAF50,
                "下载所选照片", this::downloadSelectedCamera);
        LinearLayout.LayoutParams downloadParams =
                new LinearLayout.LayoutParams(dp(42), dp(42));
        downloadParams.leftMargin = dp(2);
        selectionBar.addView(selectionDownload, downloadParams);

        selectionDelete = selectionButton(Glyph.Icon.DELETE, t.danger,
                "删除所选照片", this::onDeleteSelection);
        selectionBar.addView(selectionDelete,
                new LinearLayout.LayoutParams(dp(42), dp(42)));

        FrameLayout.LayoutParams barParams =
                new FrameLayout.LayoutParams(-2, dp(54));
        barParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        barParams.bottomMargin = dp(16);
        selectionBar.setLayoutParams(barParams);
        root.addView(selectionBar);
    }

    private FrameLayout selectionButton(Glyph.Icon icon, int tint,
                                        String description, final Runnable action) {
        FrameLayout holder = new FrameLayout(activity());
        holder.setBackground(UiKit.circularRipple(activity(), 0x22FFFFFF));
        holder.setClickable(true);
        holder.setFocusable(true);
        holder.setContentDescription(description);
        Glyph glyph = new Glyph(activity(), icon);
        glyph.setTint(tint);
        FrameLayout.LayoutParams glyphParams = new FrameLayout.LayoutParams(dp(20), dp(20));
        glyphParams.gravity = Gravity.CENTER;
        holder.addView(glyph, glyphParams);
        holder.setOnClickListener(v -> {
            UiKit.hapticTick(v);
            if (batchBusy) {
                snack("正在处理，请稍候", true);
                return;
            }
            action.run();
        });
        UiKit.pressFeedback(holder);
        return holder;
    }

    private void updateSelectionBar(boolean animate) {
        if (selectionBar == null || destroyed) return;
        int count = selectedKeys.size();
        boolean show = count > 0;
        if (grid != null) {
            // Keep the bottom row clear of the floating capsule while selecting.
            int bottom = show ? dp(98) : dp(28);
            if (grid.getPaddingBottom() != bottom) {
                grid.setPadding(gridSide, dp(14), gridSide, bottom);
            }
        }
        if (show) {
            selectionCount.setText(count + " 张已选");
            selectionDownload.setVisibility(tab == TAB_CAMERA ? View.VISIBLE : View.GONE);
            if (!selectionBarShown) {
                selectionBarShown = true;
                selectionBar.animate().cancel();
                selectionBar.setVisibility(View.VISIBLE);
                selectionBar.setAlpha(0f);
                selectionBar.setTranslationY(dp(18));
                if (animate) {
                    selectionBar.animate().alpha(1f).translationY(0f)
                            .setDuration(240).setInterpolator(UiKit.DECELERATE_SOFT)
                            .start();
                } else {
                    selectionBar.setAlpha(1f);
                    selectionBar.setTranslationY(0f);
                }
            }
        } else if (selectionBarShown) {
            selectionBarShown = false;
            selectionBar.animate().cancel();
            if (animate) {
                selectionBar.animate().alpha(0f).translationY(dp(18))
                        .setDuration(200).setInterpolator(UiKit.DECELERATE)
                        .withEndAction(() -> {
                            if (!selectionBarShown) selectionBar.setVisibility(View.GONE);
                        }).start();
            } else {
                selectionBar.setAlpha(0f);
                selectionBar.setVisibility(View.GONE);
            }
        }
    }

    // ------------------------------------------------------- local delete confirm
    private void buildConfirmDialog() {
        confirmScrim = new View(activity());
        confirmScrim.setBackgroundColor(0xA6000000);
        confirmScrim.setVisibility(View.GONE);
        confirmScrim.setClickable(true);
        confirmScrim.setOnClickListener(v -> hideConfirm(true));
        root.addView(confirmScrim, new FrameLayout.LayoutParams(-1, -1));

        confirmCard = UiKit.column(activity());
        GradientDrawable card = new GradientDrawable();
        card.setShape(GradientDrawable.RECTANGLE);
        card.setColor(t.surfaceHigh);
        card.setCornerRadius(dp(t.radiusCard));
        card.setStroke(Math.max(1, dp(0.8f)), t.hairline);
        confirmCard.setBackground(card);
        confirmCard.setElevation(dp(20));
        confirmCard.setPadding(dp(22), dp(22), dp(22), dp(18));
        confirmCard.setVisibility(View.GONE);

        confirmTitle = UiKit.title(activity(), t, "", 19);
        confirmCard.addView(confirmTitle);
        confirmMessage = UiKit.body(activity(), t, "", 13, t.muted);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(-1, -2);
        messageParams.topMargin = dp(8);
        confirmCard.addView(confirmMessage, messageParams);

        LinearLayout buttons = UiKit.row(activity());
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.topMargin = dp(20);
        confirmCard.addView(buttons, buttonsParams);
        TextView cancel = UiKit.tonalButton(activity(), t, "取消");
        cancel.setOnClickListener(v -> {
            UiKit.hapticTick(v);
            hideConfirm(true);
        });
        LinearLayout.LayoutParams cancelParams =
                new LinearLayout.LayoutParams(0, -2, 1f);
        buttons.addView(cancel, cancelParams);
        TextView delete = UiKit.label(activity(), t, "删除", 14, Color.WHITE, 2);
        delete.setGravity(Gravity.CENTER);
        delete.setMinHeight(dp(44));
        delete.setBackground(UiKit.pressable(activity(), t.danger, 18, 0x33FFFFFF));
        delete.setOnClickListener(v -> {
            UiKit.hapticTick(v);
            Runnable action = confirmAction;
            hideConfirm(true);
            if (action != null) main.postDelayed(action, 160L);
        });
        LinearLayout.LayoutParams deleteParams =
                new LinearLayout.LayoutParams(0, -2, 1f);
        deleteParams.leftMargin = dp(10);
        buttons.addView(delete, deleteParams);

        FrameLayout.LayoutParams cardParams =
                new FrameLayout.LayoutParams(-1, -2);
        cardParams.gravity = Gravity.CENTER;
        cardParams.leftMargin = dp(30);
        cardParams.rightMargin = dp(30);
        confirmCard.setLayoutParams(cardParams);
        root.addView(confirmCard);
    }

    private void showConfirm(String title, String message, Runnable action) {
        if (confirmCard == null) return;
        confirmTitle.setText(title);
        confirmMessage.setText(message);
        confirmAction = action;
        confirmShown = true;
        confirmScrim.animate().cancel();
        confirmScrim.setVisibility(View.VISIBLE);
        confirmScrim.setAlpha(0f);
        confirmScrim.animate().alpha(1f).setDuration(180).start();
        confirmCard.animate().cancel();
        confirmCard.setVisibility(View.VISIBLE);
        confirmCard.setAlpha(0f);
        confirmCard.setScaleX(0.94f);
        confirmCard.setScaleY(0.94f);
        confirmCard.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(220).setInterpolator(UiKit.DECELERATE_SOFT).start();
    }

    private void hideConfirm(boolean animate) {
        if (!confirmShown) return;
        confirmShown = false;
        confirmAction = null;
        confirmScrim.animate().cancel();
        confirmCard.animate().cancel();
        if (animate) {
            confirmScrim.animate().alpha(0f).setDuration(160)
                    .withEndAction(() -> {
                        if (!confirmShown) confirmScrim.setVisibility(View.GONE);
                    }).start();
            confirmCard.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f)
                    .setDuration(160).setInterpolator(UiKit.DECELERATE)
                    .withEndAction(() -> {
                        if (!confirmShown) confirmCard.setVisibility(View.GONE);
                    }).start();
        } else {
            confirmScrim.setVisibility(View.GONE);
            confirmCard.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------ selection
    private boolean isSelecting() {
        return !selectedKeys.isEmpty();
    }

    private String keyAt(int position) {
        if (tab == TAB_CAMERA) {
            return position >= 0 && position < cameraImages.size()
                    ? cameraKey(cameraImages.get(position)) : null;
        }
        return position >= 0 && position < localItems.size()
                ? localItems.get(position).key() : null;
    }

    private void enterSelection(int position) {
        String key = keyAt(position);
        if (key == null) return;
        UiKit.hapticTick(grid);
        selectedKeys.add(key);
        updateSelectionBar(true);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void toggleSelection(int position) {
        String key = keyAt(position);
        if (key == null) return;
        if (!selectedKeys.remove(key)) selectedKeys.add(key);
        updateSelectionBar(true);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void clearSelection(boolean animate) {
        if (selectedKeys.isEmpty()) {
            updateSelectionBar(false);
            return;
        }
        selectedKeys.clear();
        updateSelectionBar(animate);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    /** Drops selected keys whose photo disappeared after a refresh/delete. */
    private void pruneSelection() {
        if (selectedKeys.isEmpty()) return;
        Set<String> valid = new HashSet<>();
        for (CameraImage image : cameraImages) valid.add(cameraKey(image));
        for (LocalMedia item : localItems) valid.add(item.key());
        int before = selectedKeys.size();
        selectedKeys.retainAll(valid);
        if (selectedKeys.size() != before) {
            updateSelectionBar(false);
            if (adapter != null) adapter.notifyDataSetChanged();
        }
    }

    private void applySelectionVisual(Holder holder) {
        String key = holder.image.getTag() instanceof String
                ? (String) holder.image.getTag() : null;
        boolean selected = key != null && selectedKeys.contains(key);
        holder.selectOverlay.setVisibility(selected ? View.VISIBLE : View.GONE);
        holder.checkBadge.setVisibility(selected ? View.VISIBLE : View.GONE);
        holder.image.setAlpha(cellAlpha(holder));
    }

    private float cellAlpha(Holder holder) {
        if (!isSelecting()) return 1f;
        String key = holder.image.getTag() instanceof String
                ? (String) holder.image.getTag() : null;
        return key != null && selectedKeys.contains(key) ? 1f : 0.55f;
    }

    // ------------------------------------------------------------ item actions
    private void onDeleteSelection() {
        if (tab == TAB_CAMERA) {
            deleteSelectedCamera();
        } else {
            int count = selectedKeys.size();
            if (count <= 0) return;
            showConfirm("删除 " + count + " 张照片？",
                    "会从系统相册中移除所选照片，且无法撤销。",
                    this::deleteSelectedLocal);
        }
    }

    private List<CameraImage> selectedCameraImages() {
        List<CameraImage> result = new ArrayList<>();
        for (CameraImage image : cameraImages) {
            if (selectedKeys.contains(cameraKey(image))) result.add(image);
        }
        return result;
    }

    private List<LocalMedia> selectedLocalItems() {
        List<LocalMedia> result = new ArrayList<>();
        for (LocalMedia item : localItems) {
            if (selectedKeys.contains(item.key())) result.add(item);
        }
        return result;
    }

    /**
     * Starts a background camera-download batch. Selection is kept while the
     * batch runs (so the in-app capsule count stays visible), but navigation is
     * never blocked: the batch lives on the bridge's own executor and reports
     * through a system notification plus a final snack when this screen is alive.
     */
    private void downloadSelectedCamera() {
        if (tab != TAB_CAMERA) return;
        if (downloadBatchRunning) {
            snack("正在后台下载照片…", false);
            return;
        }
        final List<CameraImage> queue = selectedCameraImages();
        if (queue.isEmpty()) {
            snack("请先长按照片选择要下载的内容", true);
            return;
        }
        downloadBatchRunning = true;
        lastDownloadError = null;
        final Context appContext = activity().getApplicationContext();
        requestNotificationPermissionIfNeeded();
        NotificationHelper.ensureChannel(appContext);
        NotificationHelper.progress(appContext, DOWNLOAD_NOTIFICATION_ID, 0, queue.size(),
                "正在下载相机照片");
        snack("已开始后台下载 " + queue.size() + " 张照片…", true);
        startCameraDownload(queue, 0, new AtomicInteger(0), new AtomicInteger(0), appContext);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (activity().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                host.requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATIONS);
            }
        } catch (Throwable ignored) {
            // Downloading still works without notification permission.
        }
    }

    private void startCameraDownload(final List<CameraImage> queue, final int index,
                                     final AtomicInteger saved, final AtomicInteger failed,
                                     final Context appContext) {
        if (index >= queue.size()) {
            downloadBatchRunning = false;
            final int total = queue.size();
            final int success = saved.get();
            final int failures = failed.get();
            if (failures > 0) {
                NotificationHelper.finish(appContext, DOWNLOAD_NOTIFICATION_ID,
                        "照片下载部分失败", "成功 " + success + " 张，失败 " + failures + " 张");
            } else {
                NotificationHelper.finish(appContext, DOWNLOAD_NOTIFICATION_ID,
                        "照片下载完成", "照片下载完成 · " + success + " 张");
            }
            if (destroyed) return;
            if (success > 0) {
                snack(failures > 0
                        ? "已保存 " + success + " 张，失败 " + failures + " 张"
                        : "已保存 " + success + " 张到 Pictures/Image Sync", true);
            } else {
                snack("保存失败，请检查相机连接", true);
            }
            // Only clear a camera selection; the user may meanwhile have started
            // a local selection on the other tab.
            if (tab == TAB_CAMERA) clearSelection(true);
            return;
        }
        final CameraImage image = queue.get(index);
        final boolean[] fallback = {false};
        // Always advance the visible progress for the attempted item so the bar
        // moves even when this image fails before reporting.
        NotificationHelper.progress(appContext, DOWNLOAD_NOTIFICATION_ID,
                index, queue.size(), "正在下载相机照片");
        final Runnable next = () -> startCameraDownload(queue, index + 1, saved, failed,
                appContext);
        try {
            // Async acquisition exposes byte progress (drives the notification
            // bar); a failed async attempt falls back to the blocking path.
            final int base = index;
            final int totalItems = queue.size();
            host.bridge().downloadImage(image, safeName(image),
                    (current, total) -> {
                        float fraction = total > 0 ? Math.min(1f, current / (float) total) : 0f;
                        int done = (int) ((base + fraction) * 100f);
                        NotificationHelper.progress(appContext, DOWNLOAD_NOTIFICATION_ID,
                                done, totalItems * 100, "正在下载相机照片");
                    },
                    new CameraBridge.SaveCallback() {
                        @Override
                        public void onSaved(String displayPath) {
                            int done = saved.incrementAndGet();
                            NotificationHelper.progress(appContext, DOWNLOAD_NOTIFICATION_ID,
                                    done + failed.get(), queue.size(), "正在下载相机照片");
                            next.run();
                        }

                        @Override
                        public void onError(String message) {
                            if (!fallback[0]) {
                                // The async acquisition can be unsupported on some
                                // firmwares; retry once through the blocking path.
                                fallback[0] = true;
                                try {
                                    host.bridge().downloadImageSync(image, safeName(image), this);
                                    return;
                                } catch (Throwable ignored) {
                                    // Fall through to the failure path below.
                                }
                            }
                            if (lastDownloadError == null) lastDownloadError = message;
                            failed.incrementAndGet();
                            NotificationHelper.progress(appContext, DOWNLOAD_NOTIFICATION_ID,
                                    saved.get() + failed.get(), queue.size(),
                                    "正在下载相机照片");
                            next.run();
                        }
                    });
        } catch (Throwable error) {
            failed.incrementAndGet();
            NotificationHelper.progress(appContext, DOWNLOAD_NOTIFICATION_ID,
                    saved.get() + failed.get(), queue.size(), "正在下载相机照片");
            next.run();
        }
    }

    private void deleteSelectedCamera() {
        if (tab != TAB_CAMERA || batchBusy) return;
        final List<CameraImage> queue = selectedCameraImages();
        if (queue.isEmpty()) return;
        batchBusy = true;
        snack("正在删除 " + queue.size() + " 张照片…", true);
        deleteCameraBatch(queue, 0, new int[]{0});
    }

    private void deleteCameraBatch(final List<CameraImage> queue, final int index,
                                   final int[] deleted) {
        if (destroyed) {
            batchBusy = false;
            return;
        }
        if (index >= queue.size()) {
            batchBusy = false;
            if (adapter != null) adapter.notifyDataSetChanged();
            applyState();
            updateHeader();
            pruneSelection();
            if (deleted[0] > 0) snack("已删除 " + deleted[0] + " 张照片", true);
            clearSelection(true);
            return;
        }
        final CameraImage image = queue.get(index);
        try {
            host.bridge().deleteCameraImage(image, (success, message) -> {
                if (destroyed) {
                    batchBusy = false;
                    return;
                }
                if (success) {
                    deleted[0]++;
                    selectedKeys.remove(cameraKey(image));
                    int position = cameraImages.indexOf(image);
                    if (position >= 0) cameraImages.remove(position);
                    if (adapter != null) adapter.notifyDataSetChanged();
                } else {
                    snack(message == null || message.isEmpty() ? "删除失败" : message, true);
                }
                deleteCameraBatch(queue, index + 1, deleted);
            });
        } catch (Throwable error) {
            snack("删除失败，请重试", true);
            deleteCameraBatch(queue, index + 1, deleted);
        }
    }

    private void deleteSelectedLocal() {
        if (tab != TAB_LOCAL || batchBusy) return;
        final List<LocalMedia> queue = selectedLocalItems();
        if (queue.isEmpty()) return;
        batchBusy = true;
        snack("正在删除 " + queue.size() + " 张照片…", true);
        final Context context = activity().getApplicationContext();
        worker.execute(() -> {
            final List<LocalMedia> deletedItems = new ArrayList<>();
            for (LocalMedia item : queue) {
                try {
                    if (context.getContentResolver().delete(item.uri, null, null) > 0) {
                        deletedItems.add(item);
                    }
                } catch (Throwable ignored) {
                    // Continue with the rest of the batch.
                }
            }
            main.post(() -> {
                batchBusy = false;
                if (destroyed) return;
                for (LocalMedia item : deletedItems) {
                    localItems.remove(item);
                    selectedKeys.remove(item.key());
                    localThumbs.remove(item.key());
                }
                if (adapter != null) adapter.notifyDataSetChanged();
                applyState();
                updateHeader();
                pruneSelection();
                snack(deletedItems.size() > 0
                        ? "已删除 " + deletedItems.size() + " 张照片"
                        : "删除失败", true);
                clearSelection(true);
            });
        });
    }

    private void openItem(int position) {
        if (isSelecting()) {
            toggleSelection(position);
            return;
        }
        UiKit.haptic(grid);
        if (tab == TAB_CAMERA) {
            if (position >= 0 && position < cameraImages.size()) {
                openCameraItem(cameraImages.get(position));
            }
        } else if (position >= 0 && position < localItems.size()) {
            openLocalItem(localItems.get(position));
        }
    }

    // ------------------------------------------------------------ item actions
    private void openCameraItem(CameraImage image) {
        if (image == null) return;
        int position = cameraImages.indexOf(image);
        if (position < 0) return;
        int first = grid == null ? 0 : grid.getFirstVisiblePosition();
        View shared = grid == null ? null : grid.getChildAt(position - first);
        host.openViewer(new ArrayList<>(cameraImages), position, shared);
    }

    /** Local taps go to the system gallery/editor, never the in-app viewer. */
    private void openLocalItem(LocalMedia item) {
        if (item == null) return;
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            String mime = item.mimeType;
            view.setDataAndType(item.uri, mime == null || mime.isEmpty()
                    ? "image/*" : mime);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity().startActivity(view);
        } catch (Throwable error) {
            try {
                host.toast("没有可打开该照片的应用");
            } catch (Throwable ignored) {
                snack("没有可打开该照片的应用", true);
            }
        }
    }

    // ------------------------------------------------------------------- data
    private void refreshCamera(boolean manual) {
        if (destroyed) return;
        if (cameraLoading) return;
        if (downloading || batchBusy) {
            if (manual) snack("正在处理，请稍候", true);
            return;
        }
        if (host.bridge().state() != CameraBridge.State.CONNECTED) {
            cameraLoaded = true;
            if (!cameraImages.isEmpty()) {
                cameraImages.clear();
                if (adapter != null) adapter.notifyDataSetChanged();
            }
            applyState();
            updateHeader();
            if (manual) snack("相机未连接，请先连接相机", true);
            return;
        }
        cameraLoading = true;
        updateHeader();
        applyState();
        host.bridge().loadCameraImages(new CameraBridge.ImageListCallback() {
            @Override
            public void onImages(List<CameraImage> images) {
                if (destroyed) return;
                cameraLoading = false;
                boolean wasEmpty = cameraImages.isEmpty();
                cameraImages.clear();
                if (images != null) cameraImages.addAll(images);
                cameraLoaded = true;
                cameraError = null;
                lastCameraRefreshAt = SystemClock.elapsedRealtime();
                trimCameraCache();
                if (adapter != null) adapter.notifyDataSetChanged();
                pruneSelection();
                applyState();
                updateHeader();
                if (wasEmpty && !cameraImages.isEmpty()) animateGridIn();
            }

            @Override
            public void onError(String message) {
                if (destroyed) return;
                cameraLoading = false;
                cameraLoaded = true;
                cameraError = message == null || message.isEmpty()
                        ? "读取相机照片失败" : message;
                applyState();
                updateHeader();
                if (cameraImages.isEmpty() && manual) {
                    snack("刷新失败：" + cameraError, true);
                }
            }
        });
    }

    private void loadLocal(boolean force) {
        if (destroyed) return;
        if (localLoading) return;
        if (localLoaded && !force) {
            applyState();
            updateHeader();
            return;
        }
        localLoading = true;
        updateHeader();
        applyState();
        final Context context = activity().getApplicationContext();
        worker.execute(() -> {
            final LocalMedia.Result result = LocalMedia.query(context, PAGE_CAP);
            main.post(() -> {
                if (destroyed) return;
                localLoading = false;
                localLoaded = true;
                List<LocalMedia> fresh = result == null || result.items == null
                        ? new ArrayList<>() : result.items;
                localPartial = result == null || !result.fullAccess;
                // A permission-denied scan is not an error state: the empty view
                // explains the partial album and offers the grant button instead.
                localError = result != null && result.failed && result.fullAccess
                        ? result.message : null;
                boolean wasEmpty = localItems.isEmpty();
                boolean changed = !sameLocalItems(fresh);
                if (changed) {
                    int firstVisible = grid == null ? 0 : grid.getFirstVisiblePosition();
                    localItems.clear();
                    localItems.addAll(fresh);
                    trimLocalCache();
                    if (adapter != null) adapter.notifyDataSetChanged();
                    if (grid != null && firstVisible > 0 && grid.getCount() > 0) {
                        // Refresh in place without jumping the user to the top.
                        grid.setSelection(firstVisible);
                    }
                }
                if (changed) pruneSelection();
                applyState();
                updateHeader();
                if (changed && wasEmpty && !localItems.isEmpty()) animateGridIn();
                if (changed) scheduleLocalPreload();
            });
        });
    }

    /** Cheap equality used to avoid rebuilding the album when nothing changed. */
    private boolean sameLocalItems(List<LocalMedia> fresh) {
        if (fresh.size() != localItems.size()) return false;
        for (int i = 0; i < fresh.size(); i++) {
            LocalMedia left = localItems.get(i);
            LocalMedia right = fresh.get(i);
            if (left == null || right == null) return left == right;
            if (left.id != right.id
                    || left.dateMillis != right.dateMillis
                    || left.sizeBytes != right.sizeBytes
                    || left.raw != right.raw) {
                return false;
            }
            String leftName = left.displayName == null ? "" : left.displayName;
            String rightName = right.displayName == null ? "" : right.displayName;
            if (!leftName.equals(rightName)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------ state views
    private void applyState() {
        if (contentFrame == null || destroyed) return;
        boolean hasData = tab == TAB_CAMERA ? !cameraImages.isEmpty() : !localItems.isEmpty();
        boolean loading = tab == TAB_CAMERA ? cameraLoading : localLoading;
        CameraBridge.State state = host.bridge().state();
        String error = tab == TAB_CAMERA ? cameraError : localError;

        int desired;
        int emptyMode = -1;
        boolean keepEmptyWhileLoading = loading && !hasData
                && currentViewState == VIEW_EMPTY;
        if (hasData) {
            desired = VIEW_GRID;
        } else if (keepEmptyWhileLoading) {
            emptyMode = currentEmptyMode;
            desired = VIEW_EMPTY;
        } else if (loading) {
            desired = VIEW_SKELETON;
        } else if (tab == TAB_CAMERA && state != CameraBridge.State.CONNECTED) {
            emptyMode = state == CameraBridge.State.BLE_ONLY ? EMPTY_BLE : EMPTY_DISCONNECTED;
            desired = VIEW_EMPTY;
        } else if (error != null) {
            desired = VIEW_ERROR;
        } else if (tab == TAB_CAMERA) {
            emptyMode = EMPTY_CAMERA;
            desired = VIEW_EMPTY;
        } else {
            emptyMode = EMPTY_LOCAL;
            desired = VIEW_EMPTY;
        }

        if (desired == VIEW_EMPTY && emptyMode != currentEmptyMode) {
            configureEmpty(emptyMode);
        }
        if (desired == VIEW_EMPTY) {
            updateEmptyLoading();
        }
        if (desired == VIEW_ERROR) {
            configureError(error);
        }
        if (desired == VIEW_SKELETON && currentViewState != VIEW_SKELETON) {
            startShimmer();
        } else if (desired != VIEW_SKELETON) {
            stopShimmer();
        }
        if (desired != currentViewState) {
            currentViewState = desired;
            showOnly(desired);
        } else if (desired == VIEW_EMPTY) {
            updateEmptyConnecting();
        }
        currentEmptyMode = emptyMode;
        boolean stillLoading = tab == TAB_CAMERA ? cameraLoading : localLoading;
        if (pullRefreshing && !stillLoading) finishPullRefresh();
    }

    private void showOnly(int state) {
        setViewVisible(grid, state == VIEW_GRID);
        setViewVisible(skeletonView, state == VIEW_SKELETON);
        setViewVisible(emptyView, state == VIEW_EMPTY);
        setViewVisible(errorView, state == VIEW_ERROR);
    }

    private void setViewVisible(final View view, boolean visible) {
        if (view == null) return;
        if (visible) {
            if (view.getVisibility() != View.VISIBLE) {
                view.animate().cancel();
                view.setAlpha(0f);
                view.setVisibility(View.VISIBLE);
                view.animate().alpha(1f).setDuration(200)
                        .setInterpolator(UiKit.DECELERATE_SOFT).start();
            } else if (view.getAlpha() < 0.99f) {
                view.animate().cancel();
                view.animate().alpha(1f).setDuration(160)
                        .setInterpolator(UiKit.DECELERATE_SOFT).start();
            }
        } else if (view.getVisibility() == View.VISIBLE) {
            view.animate().cancel();
            view.animate().alpha(0f).setDuration(160)
                    .setInterpolator(UiKit.DECELERATE)
                    .withEndAction(() -> {
                        if (view.getAlpha() < 0.05f) view.setVisibility(View.GONE);
                    }).start();
        }
    }

    private void updateHeader() {
        if (subtitleView == null) return;
        subtitleView.setText(headerSubtitle());
    }

    private String headerSubtitle() {
        if (tab == TAB_CAMERA) {
            if (cameraLoading) return "正在读取相机相册…";
            CameraBridge bridge = host.bridge();
            CameraBridge.State state = bridge.state();
            if (state == CameraBridge.State.BLE_ONLY) {
                return "蓝牙已连接 · 等待相机 Wi-Fi";
            }
            if (state != CameraBridge.State.CONNECTED) {
                return "相机未连接 · 连接后浏览照片";
            }
            String model = bridge.model();
            String prefix = model == null || model.isEmpty() ? "相机相册" : model + " 相册";
            if (cameraError != null && cameraImages.isEmpty()) return prefix + " · 读取失败";
            return prefix + " · " + cameraImages.size() + " 张照片";
        }
        if (localLoading) return "正在扫描本机照片…";
        if (localError != null && localItems.isEmpty()) return "本机相册 · 读取失败";
        String suffix = localPartial ? "（仅本应用）" : "";
        return "本机相册 · " + localItems.size() + " 张照片" + suffix;
    }

    // -------------------------------------------------------------- responses
    private void startQuickConnect() {
        if (connecting) return;
        connecting = true;
        applyState();
        if (currentEmptyMode == EMPTY_DISCONNECTED || currentEmptyMode == EMPTY_BLE) {
            updateEmptyConnecting();
        }
        host.bridge().connectOverWifi(new CameraBridge.ConnectCallback() {
            @Override
            public void onConnected(com.ricoh.camera.sdk.wireless.api.CameraDevice device) {
                connecting = false;
                if (destroyed) return;
                updateHeader();
                snack("相机已连接", true);
                refreshCamera(false);
            }

            @Override
            public void onError(String message) {
                connecting = false;
                if (destroyed) return;
                updateHeader();
                applyState();
                snack(message == null || message.isEmpty() ? "连接失败，请重试" : message, true);
            }
        });
    }

    private void snack(String message, boolean force) {
        if (message == null || message.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastSnackAt < 2200L) return;
        lastSnackAt = now;
        host.showSnack(message);
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

    // =====================================================================
    // Lifecycle
    // =====================================================================
    @Override
    public void onShow() {
        visible = true;
        if (root == null) return;
        updateHeader();
        applyState();
        if (currentViewState == VIEW_SKELETON) startShimmer();
        if (!shownOnce) {
            shownOnce = true;
            animateScreenIn();
        }
        if (tab == TAB_CAMERA) {
            boolean connected = host.bridge().state() == CameraBridge.State.CONNECTED;
            long stale = SystemClock.elapsedRealtime() - lastCameraRefreshAt;
            if (!cameraLoaded || (connected && stale > 15000L && !downloading && !batchBusy)) {
                refreshCamera(false);
            } else {
                applyState();
            }
        } else if (!localLoaded) {
            loadLocal(false);
        }
    }

    @Override
    public void onHide() {
        visible = false;
        stopShimmer();
        clearSelection(false);
        hideConfirm(false);
    }

    @Override
    public void onHostResume() {
        visible = true;
        if (root == null) return;
        updateHeader();
        if (currentViewState == VIEW_SKELETON) startShimmer();
        if (tab == TAB_CAMERA) {
            boolean connected = host.bridge().state() == CameraBridge.State.CONNECTED;
            long stale = SystemClock.elapsedRealtime() - lastCameraRefreshAt;
            boolean overlayOpen = host.hasOverlay();
            if (!cameraLoaded || (connected && stale > 20000L && !downloading && !batchBusy && !overlayOpen)) {
                refreshCamera(false);
            } else {
                applyState();
            }
        } else if (!localLoading) {
            // Returning from the system gallery/editor may have added, edited or
            // deleted a file, so refresh the local album.
            loadLocal(true);
        }
    }

    @Override
    public void onHostPause() {
        visible = false;
        stopShimmer();
    }

    @Override
    public boolean onBackPressed() {
        if (confirmShown) {
            hideConfirm(true);
            return true;
        }
        if (isSelecting()) {
            clearSelection(true);
            return true;
        }
        return false;
    }

    @Override
    public void onDestroyView() {
        resetPullUi();
        clearEmptyAnimations();
        destroyed = true;
        stopShimmer();
        clearSelection(false);
        hideConfirm(false);
        main.removeCallbacksAndMessages(null);
        if (segment != null) segment.release();
        if (listenerAdded) {
            listenerAdded = false;
            try {
                host.bridge().removeStateListener(this);
            } catch (Throwable ignored) {
                // Best effort.
            }
        }
        try {
            worker.shutdownNow();
        } catch (Throwable ignored) {
            // Best effort.
        }
        try {
            localImagePool.shutdownNow();
        } catch (Throwable ignored) {
            // Best effort.
        }
        releaseThumbnailCaches();
    }

    @Override
    public void onStateChanged(CameraBridge.State state) {
        if (destroyed || root == null) return;
        if (batchBusy) return;
        updateHeader();
        if (tab != TAB_CAMERA) return;
        if (state == CameraBridge.State.CONNECTED) {
            if (!cameraLoaded || cameraImages.isEmpty()) {
                refreshCamera(false);
            } else if (visible && !downloading && !batchBusy && !host.hasOverlay()) {
                // A capture/transfer event re-announces the same state; refresh
                // so newly stored photos appear without a manual pull.
                refreshCamera(false);
            } else {
                applyState();
            }
        } else {
            cameraLoading = false;
            cameraError = null;
            currentViewState = -1;
            if (!cameraImages.isEmpty()) {
                cameraImages.clear();
                if (adapter != null) adapter.notifyDataSetChanged();
            }
            cameraLoaded = true;
            clearSelection(false);
            // Drop stale preview bitmaps without recycling: visible cells may
            // still reference one until the adapter rebinds.
            try {
                cameraThumbs.evictAll();
            } catch (Throwable ignored) {
                // Best effort.
            }
            applyState();
            updateHeader();
        }
    }

    private void animateScreenIn() {
        if (root == null || root.getChildCount() == 0) return;
        View page = root.getChildAt(0);
        page.setAlpha(0f);
        page.setTranslationY(dp(14));
        page.animate().alpha(1f).translationY(0f)
                .setDuration(330).setInterpolator(UiKit.DECELERATE_SOFT).start();
    }

    // =====================================================================
    // Grid adapter
    // =====================================================================
    private static final class Holder {
        FrameLayout root;
        RoundedImageView image;
        View press;
        View selectOverlay;
        FrameLayout checkBadge;
        TextView format;
    }

    private final class GridAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return tab == TAB_CAMERA ? cameraImages.size() : localItems.size();
        }

        @Override
        public Object getItem(int position) {
            if (tab == TAB_CAMERA) {
                return position >= 0 && position < cameraImages.size()
                        ? cameraImages.get(position) : null;
            }
            return position >= 0 && position < localItems.size()
                    ? localItems.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            if (tab == TAB_CAMERA) return position;
            Object item = getItem(position);
            return item instanceof LocalMedia ? ((LocalMedia) item).id : position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder holder;
            if (convertView == null) {
                holder = createHolder();
            } else {
                holder = (Holder) convertView.getTag();
            }
            int size = columnSize();
            holder.root.setLayoutParams(new AbsListView.LayoutParams(-1, size));
            // Recycling: drop the old bitmap and reset the press state so no
            // square/cropped frame from the previous cell can ever be shown.
            if (convertView != null) {
                holder.image.setImageDrawable(null);
                holder.image.setTag(null);
                holder.press.setPressed(false);
            }
            if (tab == TAB_CAMERA) {
                bindCamera(holder, position);
            } else {
                bindLocal(holder, position);
            }
            applySelectionVisual(holder);
            return holder.root;
        }
    }

    /** Pull-to-refresh that drags the grid itself down, then springs back. */
    private void buildPullToRefresh() {
        refreshPull = new LinearLayout(activity());
        LinearLayout row = (LinearLayout) refreshPull;
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        refreshSpinner = new ProgressBar(activity(), null,
                android.R.attr.progressBarStyleSmall);
        ((ProgressBar) refreshSpinner).setIndeterminateTintList(
                android.content.res.ColorStateList.valueOf(t.accent));
        row.addView(refreshSpinner, new LinearLayout.LayoutParams(dp(16), dp(16)));
        refreshPullText = UiKit.label(activity(), t, "下拉刷新", 11.5f, t.muted, 1);
        refreshPullText.setPadding(dp(7), 0, 0, 0);
        row.addView(refreshPullText);
        refreshPull.setAlpha(0f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, dp(40));
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(6);
        contentFrame.addView(refreshPull, params);
        refreshPull.setTranslationY(dp(-30));

        grid.setOnTouchListener((v, event) -> {
            if (isSelecting() || pullRefreshing) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pullTracking = !grid.canScrollVertically(-1);
                    pullStartY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!pullTracking) break;
                    float dy = event.getY() - pullStartY;
                    if (dy > 0 && !grid.canScrollVertically(-1)) {
                        float offset = Math.min(dp(104), dy * 0.55f);
                        applyPullOffset(offset);
                        refreshPullText.setText(offset > dp(64) ? "松开刷新" : "下拉刷新");
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (pullTracking) {
                        float delta = event.getY() - pullStartY;
                        pullTracking = false;
                        if (delta > 0 && pullOffset > dp(64) && !pullRefreshing) {
                            startPullRefresh();
                        } else if (pullOffset > 0) {
                            springBack();
                        }
                    }
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private void applyPullOffset(float offset) {
        pullOffset = offset;
        if (grid != null) grid.setTranslationY(offset);
        if (refreshPull != null) {
            refreshPull.setTranslationY(-dp(26) + offset);
            refreshPull.setAlpha(Math.min(1f, offset / dp(30)));
        }
    }

    private void springBack() {
        if (grid == null) return;
        grid.animate().translationY(0f).setDuration(340)
                .setInterpolator(UiKit.OVERSHOOT).start();
        if (refreshPull != null) {
            refreshPull.animate().alpha(0f).translationY(-dp(26))
                    .setDuration(200).start();
        }
        pullOffset = 0f;
    }

    private void startPullRefresh() {
        pullRefreshing = true;
        refreshPullText.setText("正在刷新…");
        if (grid != null) {
            grid.animate().translationY(dp(64)).setDuration(180)
                    .setInterpolator(UiKit.DECELERATE_SOFT).start();
        }
        if (refreshPull != null) {
            refreshPull.animate().alpha(1f).translationY(dp(64) - dp(26))
                    .setDuration(180).start();
        }
        if (tab == TAB_CAMERA) {
            refreshCamera(true);
        } else {
            loadLocal(true);
        }
        grid.postDelayed(this::finishPullRefresh, 4000L);
    }

    private void finishPullRefresh() {
        if (!pullRefreshing) return;
        pullRefreshing = false;
        if (grid != null) {
            grid.animate().translationY(0f).setDuration(420)
                    .setInterpolator(UiKit.OVERSHOOT).start();
        }
        if (refreshPull != null) {
            refreshPull.animate().alpha(0f).translationY(-dp(26))
                    .setDuration(220).start();
        }
        pullOffset = 0f;
    }

    /** Resets any in-flight pull state (tab switch, state change, destroy). */
    private void resetPullUi() {
        pullTracking = false;
        pullRefreshing = false;
        pullOffset = 0f;
        if (grid != null) {
            grid.animate().cancel();
            grid.setTranslationY(0f);
        }
        if (refreshPull != null) {
            refreshPull.animate().cancel();
            refreshPull.setAlpha(0f);
            refreshPull.setTranslationY(-dp(26));
        }
    }

    private int columnSize() {
        int width = grid != null ? grid.getWidth() : 0;
        if (width <= 0) {
            width = activity().getResources().getDisplayMetrics().widthPixels
                    - dp(18) * 2;
        }
        int usable = width - (grid != null ? grid.getPaddingLeft() + grid.getPaddingRight() : 0)
                - dp(3) * 2;
        return Math.max(dp(60), usable / 3);
    }

    /** Local grid thumbnails decode at 2x the cell size for a crisp preview. */
    private int localThumbPx() {
        return Math.max(LOCAL_THUMB_MIN_PX, columnSize() * 2);
    }

    private Holder createHolder() {
        Holder holder = new Holder();
        holder.root = new FrameLayout(activity());
        // The cell background is itself a rounded drawable, so placeholder pixels
        // are never square either.
        holder.root.setBackground(UiKit.rounded(activity(), t.surfaceAlt, GRID_RADIUS));

        // The bitmap is clipped inside this view's own draw path (clipPath on a
        // rounded rect), independent of setClipToOutline/outline providers.
        holder.image = new RoundedImageView(activity(), dp(GRID_RADIUS));
        holder.image.setAlpha(cellAlpha(holder));
        holder.root.addView(holder.image, new FrameLayout.LayoutParams(-1, -1));

        // Pressed-state scrim, clipped by its own rounded path as well.
        holder.press = new RoundedScrimView(activity(), dp(GRID_RADIUS));
        holder.root.addView(holder.press, new FrameLayout.LayoutParams(-1, -1));

        // Selection is an accent ring drawn as a rounded stroke (no square pixels)
        // plus a check badge; both live above the thumbnail.
        holder.selectOverlay = new View(activity());
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.RECTANGLE);
        ring.setColor(0x00000000);
        ring.setCornerRadius(dp(GRID_RADIUS));
        ring.setStroke(Math.max(1, dp(2)), t.accent);
        holder.selectOverlay.setBackground(ring);
        holder.selectOverlay.setVisibility(View.GONE);
        holder.root.addView(holder.selectOverlay,
                new FrameLayout.LayoutParams(-1, -1));

        holder.checkBadge = new FrameLayout(activity());
        holder.checkBadge.setBackground(UiKit.rounded(activity(), t.accent, 100));
        Glyph checkGlyph = new Glyph(activity(), Glyph.Icon.CHECK);
        checkGlyph.setTint(t.onAccent);
        checkGlyph.setStrokeWidthDp(2.2f);
        FrameLayout.LayoutParams checkGlyphParams =
                new FrameLayout.LayoutParams(dp(13), dp(13));
        checkGlyphParams.gravity = Gravity.CENTER;
        holder.checkBadge.addView(checkGlyph, checkGlyphParams);
        FrameLayout.LayoutParams checkParams =
                new FrameLayout.LayoutParams(dp(22), dp(22));
        checkParams.gravity = Gravity.TOP | Gravity.END;
        checkParams.topMargin = dp(6);
        checkParams.rightMargin = dp(6);
        holder.checkBadge.setVisibility(View.GONE);
        holder.root.addView(holder.checkBadge, checkParams);

        holder.format = UiKit.label(activity(), t, "", 9.5f, Color.WHITE, 2);
        holder.format.setBackground(UiKit.rounded(activity(), 0x99000000, 6));
        holder.format.setPadding(dp(5), dp(2), dp(5), dp(2));
        FrameLayout.LayoutParams formatParams =
                new FrameLayout.LayoutParams(-2, -2);
        formatParams.gravity = Gravity.BOTTOM | Gravity.START;
        formatParams.leftMargin = dp(6);
        formatParams.bottomMargin = dp(6);
        holder.format.setVisibility(View.GONE);
        holder.root.addView(holder.format, formatParams);

        holder.root.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                view.setPressed(true);
                holder.press.setPressed(true);
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                view.setPressed(false);
                holder.press.setPressed(false);
            }
            return false;
        });

        holder.root.setTag(holder);
        return holder;
    }

    private void bindCamera(Holder holder, int position) {
        CameraImage image = position >= 0 && position < cameraImages.size()
                ? cameraImages.get(position) : null;
        if (image == null) {
            holder.image.setAlpha(cellAlpha(holder));
            holder.image.setImageBitmap(null);
            holder.image.setTag(null);
            holder.format.setVisibility(View.GONE);
            return;
        }

        // The fast preview API only serves camera-generated JPEG renditions
        // (extra-small or thumbnail). Formats without one still get a compressed
        // view-data frame below when the camera supports it, and a badge meanwhile.
        boolean hasFastPreview = false;
        try {
            hasFastPreview = image.hasExtraSmallData() || image.hasThumbnail();
        } catch (Throwable ignored) {
            // Treat as preview-less below.
        }
        String key = cameraKey(image);
        String format = cameraFormatLabel(image);
        boolean showFormat = !"JPEG".equals(format) && !"JPG".equals(format);
        if (!hasFastPreview) {
            holder.image.setAlpha(cellAlpha(holder));
            holder.image.setImageBitmap(null);
            holder.image.setTag(key);
            holder.format.setText(format);
            holder.format.setVisibility(View.VISIBLE);
            bindCameraFallbackPreview(holder, image, key);
            return;
        }
        holder.format.setText(format);
        holder.format.setVisibility(showFormat ? View.VISIBLE : View.GONE);
        bindCameraThumb(holder, image, key);
    }

    // ------------------------------------------------- camera grid previews
    /**
     * Grid thumbnails go through the bridge's compressed preview API
     * ({@link CameraBridge#loadPreview(CameraImage, CameraBridge.PreviewCallback)}):
     * the camera returns its own extra-small JPEG/thumbnail, which is far faster
     * than pulling view data. Results are tagged per cell (a recycled view never
     * receives a stale bitmap) and cached by camera key for instant scroll-back.
     */
    private void bindCameraThumb(Holder holder, final CameraImage image, final String key) {
        if (!key.equals(holder.image.getTag())) {
            holder.image.setImageDrawable(null);
        }
        holder.image.setTag(key);
        Bitmap cached = cameraThumbs.get(key);
        if (cached != null && !cached.isRecycled()) {
            holder.image.setAlpha(cellAlpha(holder));
            holder.image.setImageBitmap(cached);
            return;
        }
        if (skipImageLoads) {
            holder.image.setAlpha(cellAlpha(holder));
            return;
        }
        holder.image.setAlpha(cellAlpha(holder) * 0.88f);
        try {
            host.bridge().loadPreview(image, new CameraBridge.PreviewCallback() {
                @Override
                public void onPreview(Bitmap bitmap, String message) {
                    if (bitmap != null) {
                        applyCameraThumb(holder, key, image, bitmap, true);
                        return;
                    }
                    bindCameraFallbackPreview(holder, image, key);
                }
            });
        } catch (Throwable ignored) {
            // Placeholder background remains; a later rebind can retry.
        }
    }

    /** Legacy compressed view-data preview for formats without a fast rendition. */
    private void bindCameraFallbackPreview(Holder holder, final CameraImage image,
                                            final String key) {
        if (skipImageLoads || destroyed) return;
        boolean hasViewData = false;
        try {
            hasViewData = image.hasViewData();
        } catch (Throwable ignored) {
            // No preview available.
        }
        if (!hasViewData) return;
        try {
            host.bridge().loadPreview(image, 720, new CameraBridge.BitmapCallback() {
                @Override
                public void onBitmap(Bitmap bitmap) {
                    // The legacy preview already applies camera orientation.
                    applyCameraThumb(holder, key, image, bitmap, false);
                }

                @Override
                public void onError(String message) {
                    // Keep the placeholder; a later rebind may retry.
                }
            });
        } catch (Throwable ignored) {
            // Best effort.
        }
    }

    private void applyCameraThumb(Holder holder, String key, CameraImage image,
                                  Bitmap bitmap, boolean orient) {
        if (bitmap == null) return;
        if (destroyed) {
            recycleQuietly(bitmap);
            return;
        }
        if (orient) {
            try {
                bitmap = CameraBridge.orient(bitmap, image);
            } catch (Throwable ignored) {
                // Keep the un-oriented bitmap rather than dropping the frame.
            }
        }
        Bitmap stored = putCameraThumb(key, bitmap);
        if (stored == null) return;
        if (!key.equals(holder.image.getTag())) return;
        holder.image.setAlpha(cellAlpha(holder));
        holder.image.setImageBitmap(stored);
    }

    private Bitmap putCameraThumb(String key, Bitmap bitmap) {
        if (bitmap == null) return null;
        Bitmap existing = cameraThumbs.get(key);
        if (existing != null && !existing.isRecycled()) {
            if (existing != bitmap) recycleQuietly(bitmap);
            return existing;
        }
        cameraThumbs.put(key, bitmap);
        return bitmap;
    }

    private void bindLocal(Holder holder, int position) {
        LocalMedia item = position >= 0 && position < localItems.size()
                ? localItems.get(position) : null;
        if (item == null) {
            holder.image.setAlpha(cellAlpha(holder));
            holder.image.setImageBitmap(null);
            holder.image.setTag(null);
            holder.format.setVisibility(View.GONE);
            return;
        }
        String format = item.formatLabel();
        holder.format.setText(format);
        holder.format.setVisibility("JPG".equals(format) || "JPEG".equals(format)
                ? View.GONE : View.VISIBLE);
        bindLocalThumb(holder, item);
    }

    // ------------------------------------------------------ local grid thumbs
    private void bindLocalThumb(Holder holder, final LocalMedia item) {
        final String key = item.key();
        if (!key.equals(holder.image.getTag())) {
            holder.image.setImageDrawable(null);
        }
        holder.image.setTag(key);
        Bitmap cached = localThumbs.get(key);
        if (cached != null && !cached.isRecycled()) {
            holder.image.setAlpha(cellAlpha(holder));
            holder.image.setImageBitmap(cached);
            return;
        }
        if (skipImageLoads) {
            holder.image.setAlpha(cellAlpha(holder));
            return;
        }
        holder.image.setAlpha(cellAlpha(holder) * 0.88f);
        final Context context = activity().getApplicationContext();
        // Resolve the 2x target on the UI thread; View geometry is not safe to
        // read from a decode worker.
        final int thumbPx = localThumbPx();
        try {
            localImagePool.execute(() -> {
                if (destroyed) return;
                final Bitmap bitmap = LocalMedia.loadThumbnailBitmap(
                        context, item, thumbPx);
                main.post(() -> {
                    if (destroyed || bitmap == null) {
                        recycleQuietly(bitmap);
                        return;
                    }
                    Bitmap stored = putLocalThumb(key, bitmap);
                    if (stored == null) return;
                    if (!key.equals(holder.image.getTag())) return;
                    holder.image.setAlpha(cellAlpha(holder));
                    holder.image.setImageBitmap(stored);
                });
            });
        } catch (Throwable ignored) {
            // Pool rejected (view destroyed); the placeholder is fine.
        }
    }

    private Bitmap putLocalThumb(String key, Bitmap bitmap) {
        if (bitmap == null) return null;
        Bitmap existing = localThumbs.get(key);
        if (existing != null && !existing.isRecycled()) {
            if (existing != bitmap) recycleQuietly(bitmap);
            return existing;
        }
        localThumbs.put(key, bitmap);
        return bitmap;
    }

    /**
     * Warm the first rows' local thumbnails on workers right after a scan, so
     * switching tabs or scrolling back binds from memory without decoding.
     */
    private void scheduleLocalPreload() {
        final List<LocalMedia> snapshot = new ArrayList<>(localItems);
        if (snapshot.isEmpty()) return;
        final Context context = activity().getApplicationContext();
        final int thumbPx = localThumbPx();
        try {
            localImagePool.execute(() -> {
                int count = Math.min(snapshot.size(), LOCAL_PRELOAD_COUNT);
                boolean added = false;
                int rawDecoded = 0;
                for (int i = 0; i < count; i++) {
                    if (destroyed) return;
                    LocalMedia item = snapshot.get(i);
                    if (item == null) continue;
                    if (item.isRaw() && rawDecoded >= 4) {
                        // RAW previews require a container scan; cap eager work.
                        continue;
                    }
                    String key = item.key();
                    Bitmap cached = localThumbs.get(key);
                    if (cached != null && !cached.isRecycled()) continue;
                    Bitmap bitmap = LocalMedia.loadThumbnailBitmap(
                            context, item, thumbPx);
                    if (bitmap == null) continue;
                    if (item.isRaw()) rawDecoded++;
                    if (destroyed) {
                        recycleQuietly(bitmap);
                        return;
                    }
                    if (!putLocalThumbIfAbsent(key, bitmap)) {
                        recycleQuietly(bitmap);
                        continue;
                    }
                    added = true;
                }
                if (added) {
                    main.post(() -> {
                        if (!destroyed && adapter != null) adapter.notifyDataSetChanged();
                    });
                }
            });
        } catch (Throwable ignored) {
            // Best-effort preload.
        }
    }

    private boolean putLocalThumbIfAbsent(String key, Bitmap bitmap) {
        Bitmap existing = localThumbs.get(key);
        if (existing != null && !existing.isRecycled()) return false;
        localThumbs.put(key, bitmap);
        return true;
    }

    /** Drops cache entries whose photo is no longer in the current album. */
    private void trimCameraCache() {
        try {
            Set<String> valid = new HashSet<>();
            for (CameraImage image : cameraImages) valid.add(cameraKey(image));
            Map<String, Bitmap> snapshot = cameraThumbs.snapshot();
            for (String key : snapshot.keySet()) {
                if (!valid.contains(key)) cameraThumbs.remove(key);
            }
        } catch (Throwable ignored) {
            // Cache maintenance is best effort.
        }
    }

    private void trimLocalCache() {
        try {
            Set<String> valid = new HashSet<>();
            for (LocalMedia item : localItems) valid.add(item.key());
            Map<String, Bitmap> snapshot = localThumbs.snapshot();
            for (String key : snapshot.keySet()) {
                if (!valid.contains(key)) localThumbs.remove(key);
            }
        } catch (Throwable ignored) {
            // Best effort.
        }
    }

    private void releaseThumbnailCaches() {
        try {
            if (grid != null) grid.setAdapter(null);
        } catch (Throwable ignored) {
            // Adapter may already be detached.
        }
        recycleCache(cameraThumbs);
        recycleCache(localThumbs);
    }

    private static void recycleCache(LruCache<String, Bitmap> cache) {
        if (cache == null) return;
        try {
            Map<String, Bitmap> snapshot = cache.snapshot();
            cache.evictAll();
            for (Bitmap bitmap : snapshot.values()) recycleQuietly(bitmap);
        } catch (Throwable ignored) {
            // Best effort.
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

    private static String cameraKey(CameraImage image) {
        try {
            String folder = image.getFolderName();
            String name = image.getName();
            return "cam:" + (folder == null ? "" : folder) + "/"
                    + (name == null ? String.valueOf(System.identityHashCode(image)) : name);
        } catch (Throwable ignored) {
            return "cam:" + System.identityHashCode(image);
        }
    }

    private static String cameraFormatLabel(CameraImage image) {
        try {
            Object format = image.getFormat();
            if (format == null) return "IMG";
            String name = String.valueOf(format);
            return name.length() > 6 ? name.substring(0, 6) : name;
        } catch (Throwable ignored) {
            return "IMG";
        }
    }

    // =====================================================================
    // Custom-drawn segmented control
    // =====================================================================
    /**
     * Self-contained segmented control, drawn entirely on one Canvas: a capsule
     * track, a capsule pill positioned by an animated float progress, and the two
     * labels whose colours blend with that progress. Driving the animation inside
     * {@link #onDraw} (instead of translating a child View) makes the slide
     * deterministic on device.
     */
    public static final class SegmentView extends View {

        public interface OnSegmentChangeListener {
            void onSegmentChanged(int index);
        }

        private final Theme theme;
        private final String[] labels;
        private final float density;
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final RectF pillRect = new RectF();
        private final ArgbEvaluator argb = new ArgbEvaluator();
        private float progress;
        private int selectedIndex;
        private ValueAnimator animator;
        private OnSegmentChangeListener listener;
        private float downX;
        private float downY;
        private boolean pressed;

        public SegmentView(Context context, Theme theme, String[] labels) {
            super(context);
            this.theme = theme;
            this.labels = labels == null ? new String[]{"相机", "本机"} : labels;
            this.density = context.getResources().getDisplayMetrics().density;
            setClickable(true);
            setFocusable(true);
            setContentDescription("相机 / 本机 切换");
            trackPaint.setStyle(Paint.Style.FILL);
            trackPaint.setColor(theme.surface);
            pillPaint.setStyle(Paint.Style.FILL);
            pillPaint.setColor(theme.accent);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(Math.max(1f, dp(1f)));
            borderPaint.setColor(theme.hairline);
            textPaint.setTextSize(dp(14f));
            textPaint.setTypeface(theme.typeface(1));
            textPaint.setTextAlign(Paint.Align.CENTER);
            activeTextPaint.setTextSize(dp(14f));
            activeTextPaint.setTypeface(theme.typeface(2));
            activeTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        public void setOnSegmentChangeListener(OnSegmentChangeListener listener) {
            this.listener = listener;
        }

        /** Animates the pill; 0 = camera, 1 = local. */
        public void setSelected(int index, boolean animate) {
            int target = index == 1 ? 1 : 0;
            selectedIndex = target;
            float targetProgress = target;
            float from = progress;
            if (animate && Math.abs(targetProgress - from) > 0.0001f) {
                // Detach listeners before cancelling so a running animation cannot
                // snap progress to its old target and kill the new slide.
                if (animator != null) {
                    animator.removeAllListeners();
                    animator.cancel();
                    animator = null;
                }
                progress = from;
                animator = ValueAnimator.ofFloat(from, targetProgress);
                animator.setDuration(280);
                animator.setInterpolator(UiKit.DECELERATE_SOFT);
                animator.addUpdateListener(animation -> {
                    progress = (float) animation.getAnimatedValue();
                    invalidate();
                });
                animator.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        animator = null;
                        progress = targetProgress;
                        invalidate();
                    }

                    @Override
                    public void onAnimationCancel(android.animation.Animator animation) {
                        animator = null;
                    }
                });
                animator.start();
            } else {
                release();
                progress = targetProgress;
                invalidate();
            }
        }

        public void release() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            release();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float radius = h / 2f;
            trackRect.set(0f, 0f, w, h);
            canvas.drawRoundRect(trackRect, radius, radius, trackPaint);
            canvas.drawRoundRect(trackRect, radius, radius, borderPaint);

            float pad = dp(4f);
            float pillWidth = Math.max(1f, (w - pad * 2f) / 2f);
            float pillHeight = Math.max(1f, h - pad * 2f);
            float pillLeft = pad + progress * pillWidth;
            float pillRadius = pillHeight / 2f;
            pillRect.set(pillLeft, pad, pillLeft + pillWidth, pad + pillHeight);
            canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillPaint);

            // Labels are centred exactly within the two halves of the capsule.
            float leftCenter = w / 4f;
            float rightCenter = w * 3f / 4f;
            float baseline = h / 2f
                    - (textPaint.ascent() + textPaint.descent()) / 2f;
            int inactive = theme.muted;
            int active = theme.onAccent;
            // progress 0 -> camera active; progress 1 -> local active.
            int cameraColor = (Integer) argb.evaluate(progress, active, inactive);
            int localColor = (Integer) argb.evaluate(progress, inactive, active);
            textPaint.setColor(cameraColor);
            canvas.drawText(labels[0], leftCenter, baseline, textPaint);
            textPaint.setColor(localColor);
            canvas.drawText(labels[1], rightCenter, baseline, textPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    pressed = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - downX) > dp(12f)
                            || Math.abs(event.getY() - downY) > dp(12f)) {
                        pressed = false;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean tapped = pressed
                            && Math.abs(event.getX() - downX) <= dp(12f)
                            && Math.abs(event.getY() - downY) <= dp(12f);
                    pressed = false;
                    if (tapped) {
                        int index = event.getX() < getWidth() / 2f ? 0 : 1;
                        if (index != selectedIndex) {
                            UiKit.hapticTick(this);
                            setSelected(index, true);
                            if (listener != null) listener.onSegmentChanged(index);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private float dp(float value) {
            return value * density + 0.5f;
        }
    }

    /** Lightens a colour toward white by the given fraction (0..1). */
    private static int lighten(int color, float amount) {
        float f = Math.max(0f, Math.min(1f, amount));
        int r = (int) (android.graphics.Color.red(color) + (255 - android.graphics.Color.red(color)) * f);
        int g = (int) (android.graphics.Color.green(color) + (255 - android.graphics.Color.green(color)) * f);
        int b = (int) (android.graphics.Color.blue(color) + (255 - android.graphics.Color.blue(color)) * f);
        return android.graphics.Color.argb(android.graphics.Color.alpha(color), r, g, b);
    }

    // ------------------------------------------------------------ rounded views
    /** ImageView that clips its own draw path so thumbnails are rounded from frame one. */
    private static final class RoundedImageView extends android.widget.ImageView {
        private final float radius;
        private final Path clip = new Path();

        RoundedImageView(android.content.Context context, float radius) {
            super(context);
            this.radius = radius;
            setScaleType(ScaleType.CENTER_CROP);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (getWidth() <= 0 || getHeight() <= 0) {
                super.onDraw(canvas);
                return;
            }
            clip.reset();
            clip.addRoundRect(0, 0, getWidth(), getHeight(), radius, radius,
                    Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(clip);
            super.onDraw(canvas);
            canvas.restoreToCount(save);
        }
    }

    /** Rounded pressed-state scrim drawn inside its own clipped path. */
    private static final class RoundedScrimView extends View {
        private final float radius;
        private final Path clip = new Path();
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);

        RoundedScrimView(android.content.Context context, float radius) {
            super(context);
            this.radius = radius;
        }

        @Override
        public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            // Plain View.setPressed may not invalidate without a state drawable.
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!isPressed() || getWidth() <= 0 || getHeight() <= 0) return;
            clip.reset();
            clip.addRoundRect(0, 0, getWidth(), getHeight(), radius, radius,
                    Path.Direction.CW);
            int save = canvas.save();
            canvas.clipPath(clip);
            paint.setColor(0x33FFFFFF);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            canvas.restoreToCount(save);
        }
    }
}
