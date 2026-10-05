// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 cyf112233
package io.github.cyf112233.portable;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

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

    /**
     * Whether the app may read the phone's shared storage.
     *
     * Mounting a device directory into the guest is pointless without this: the
     * bind would resolve to a directory the app cannot open, so the guest would see
     * an empty tree. API 30+ requires "all files access" for this, which the runtime
     * permission alone does not grant.
     */
    public boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // On API 30+ "all files access" is exactly isExternalStorageManager().
            // The other candidates cannot be used here: checkSelfPermission answers
            // "denied" for MANAGE_EXTERNAL_STORAGE even when granted (it is a special
            // app-op, not a runtime permission), and READ_EXTERNAL_STORAGE is not
            // obtainable at all on some ROMs while all-files access is already in
            // effect. Requiring them too closed the feature for users who had
            // genuinely granted everything the platform asks for.
            try {
                return Environment.isExternalStorageManager();
            } catch (Throwable e) {
                return false;
            }
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Which storage permissions are still missing, in the words the grant flow
     * needs: MANAGE_EXTERNAL_STORAGE is a special access that only the system
     * settings screen can grant, READ_EXTERNAL_STORAGE is an ordinary runtime
     * permission this app can ask for itself.
     *
     * @return an empty list when everything needed is in place
     */
    public java.util.List<String> missingStoragePermissions() {
        java.util.List<String> missing = new java.util.ArrayList<String>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean manager;
            try {
                manager = Environment.isExternalStorageManager();
            } catch (Throwable e) {
                manager = false;
            }
            if (!manager) {
                missing.add("所有文件访问权限");
            }
            return missing;
        }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add("读取存储权限");
        }
        return missing;
    }

    /** Whether the long-press-for-settings hint has been shown once. */
    public boolean hintShown() {
        return prefs().getBoolean(KEY_HINT_SHOWN, false);
    }

    public void markHintShown() {
        prefs().edit().putBoolean(KEY_HINT_SHOWN, true).apply();
    }
}
