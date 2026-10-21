package com.ricohimaging.imagesync.modern;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Last-resort screen so one broken module never crashes the modern shell. */
public final class FallbackScreen extends Screen {
    private final String title;
    private final String message;

    public FallbackScreen(ShellActivity host, String title, String message) {
        super(host);
        this.title = title;
        this.message = message;
    }

    @Override
    protected View onCreateView() {
        FrameLayout root = new FrameLayout(activity());
        LinearLayout column = UiKit.column(activity());
        column.setGravity(Gravity.CENTER);
        column.setPadding(dp(28), dp(28), dp(28), dp(28));

        FrameLayout badge = new FrameLayout(activity());
        badge.setBackground(UiKit.rounded(activity(), t.surfaceAlt, 26));
        Glyph glyph = new Glyph(activity(), Glyph.Icon.WARNING);
        glyph.setTint(t.muted);
        FrameLayout.LayoutParams glyphParams = new FrameLayout.LayoutParams(dp(28), dp(28));
        glyphParams.gravity = Gravity.CENTER;
        badge.addView(glyph, glyphParams);
        column.addView(badge, new LinearLayout.LayoutParams(dp(52), dp(52)));

        TextView heading = UiKit.title(activity(), t, title, 22);
        heading.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-2, -2);
        headingParams.topMargin = dp(18);
        column.addView(heading, headingParams);

        TextView body = UiKit.body(activity(), t, message, 13.5f, t.muted);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-2, -2);
        bodyParams.topMargin = dp(8);
        column.addView(body, bodyParams);

        TextView legacy = UiKit.primaryButton(activity(), t, "重试");
        legacy.setOnClickListener(v -> host.retryCurrentTab());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-2, -2);
        buttonParams.topMargin = dp(22);
        column.addView(legacy, buttonParams);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2);
        params.gravity = Gravity.CENTER;
        root.addView(column, params);
        root.setBackgroundColor(t.background);
        return root;
    }
}
