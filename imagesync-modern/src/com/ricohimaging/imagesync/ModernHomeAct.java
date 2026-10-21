package com.ricohimaging.imagesync;

import com.ricohimaging.imagesync.modern.ShellActivity;

/**
 * Launcher Activity declared by the patched AndroidManifest.xml.
 *
 * <p>The real work lives in {@link ShellActivity}: the launcher name must stay
 * exactly at this fully qualified name because integrate.py relocates the
 * original launcher intent-filter onto it without touching resources.</p>
 */
public final class ModernHomeAct extends ShellActivity {
}
