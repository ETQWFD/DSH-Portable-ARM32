// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 cyf112233
package io.github.cyf112233.portable;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import io.github.cyf112233.portable.core.ProotLauncher;
import io.github.cyf112233.portable.core.RootfsInstaller;

/**
 * Process-wide owner of the long-lived pieces. An Activity can be recreated at
 * any moment (rotation, theme change, low memory); the guest server must not be,
 * so it lives here instead.
 */
public class DshApp extends Application {

    public static final String PREFS = "dsh-portable";
    public static final String KEY_PORT = "port";
    public static final String KEY_MOUNTS = "mounts";
    public static final String KEY_KEEP_ALIVE = "keep_alive";
    public static final String KEY_HINT_SHOWN = "hint_shown";
    public static final int DEFAULT_PORT = 3080;

    private RootfsInstaller installer;
    private ProotLauncher launcher;

    @Override
    public void onCreate() {
        super.onCreate();
        installer = new RootfsInstaller(this);
        launcher = new ProotLauncher(this, installer);
    }

    public static DshApp get(Context context) {
        return (DshApp) context.getApplicationContext();
    }

    public RootfsInstaller installer() {
        return installer;
    }

    public ProotLauncher launcher() {
        return launcher;
    }

    public SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    public int port() {
        return prefs().getInt(KEY_PORT, DEFAULT_PORT);
    }

    /** Bind mounts configured by the user, in the order they were added. */
    public java.util.List<io.github.cyf112233.portable.core.Mount> mounts() {
        return io.github.cyf112233.portable.core.Mount.decode(prefs().getString(KEY_MOUNTS, ""));
    }

    public void setMounts(java.util.List<io.github.cyf112233.portable.core.Mount> mounts) {
        prefs().edit().putString(KEY_MOUNTS, io.github.cyf112233.portable.core.Mount.encode(mounts)).apply();
    }

    /**
     * Keep the guest server alive while the screen is off. Implemented as a
     * foreground service, which is the only mechanism Android reliably honours
     * for a background process the user has asked to keep running.
     */
    public boolean keepAlive() {
        return prefs().getBoolean(KEY_KEEP_ALIVE, true);
    }

    public void setKeepAlive(boolean enabled) {
        prefs().edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply();
    }

    /** Whether the long-press-for-settings hint has been shown once. */
    public boolean hintShown() {
        return prefs().getBoolean(KEY_HINT_SHOWN, false);
    }

    public void markHintShown() {
        prefs().edit().putBoolean(KEY_HINT_SHOWN, true).apply();
    }
}
