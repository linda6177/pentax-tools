package com.ricohimaging.imagesync.modern;

import android.app.Activity;
import android.view.View;

/**
 * One top-level section of the rebuilt shell.
 *
 * <p>Screens are cheap, self-contained views owned by {@link ShellActivity}.
 * The activity animates between them; each screen only builds its hierarchy,
 * reacts to host lifecycle and can claim the back gesture.</p>
 */
public abstract class Screen {
    protected final ShellActivity host;
    protected final Theme t;

    private View root;
    private boolean alive;

    protected Screen(ShellActivity host) {
        this.host = host;
        this.t = host.theme();
    }

    /** Builds the screen hierarchy once. */
    protected abstract View onCreateView();

    public final View view() {
        if (root == null) {
            root = onCreateView();
            alive = true;
        }
        return root;
    }

    public final boolean hasView() {
        return root != null;
    }

    /** Called after the view was attached and the enter animation started. */
    public void onShow() {}

    /** Called when another screen replaces this one (view stays cached). */
    public void onHide() {}

    public void onHostResume() {}

    public void onHostPause() {}

    /** @return true when the back gesture was consumed. */
    public boolean onBackPressed() {
        return false;
    }

    /** Releases heavyweight state (live view stream, listeners). View may be rebuilt. */
    public void onDestroyView() {}

    protected final Activity activity() {
        return host;
    }

    protected final int dp(float value) {
        return UiKit.dp(host, value);
    }

    protected final int color(int value) {
        return value;
    }

    protected final boolean alive() {
        return alive;
    }
}
