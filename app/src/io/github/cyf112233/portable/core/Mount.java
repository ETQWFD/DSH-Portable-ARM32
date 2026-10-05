// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 cyf112233
package io.github.cyf112233.portable.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * One bind mount handed to PRoot: a device directory made visible inside the
 * guest at a path that must be an empty directory beforehand.
 *
 * PRoot binds are not a fuse layer &mdash; the guest sees the host directory
 * directly, so a mount is both cheap and fully read/write. That also means the
 * guest path has to be empty, otherwise the bind would hide files the rootfs
 * expects to find there.
 */
public final class Mount {

    public final String hostPath;
    public final String guestPath;
    public final boolean enabled;

    public Mount(String hostPath, String guestPath, boolean enabled) {
        this.hostPath = hostPath;
        this.guestPath = guestPath;
        this.enabled = enabled;
    }

    public boolean isUsable() {
        if (!enabled || hostPath == null || guestPath == null) {
            return false;
        }
        File host = new File(hostPath);
        if (!host.isDirectory()) {
            return false;
        }
        if (!guestPath.startsWith("/") || guestPath.contains("..")) {
            return false;
        }
        return !"/".equals(guestPath);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("host", hostPath);
        object.put("guest", guestPath);
        object.put("enabled", enabled);
        return object;
    }

    public static Mount fromJson(JSONObject object) {
        return new Mount(
                object.optString("host", ""),
                object.optString("guest", ""),
                object.optBoolean("enabled", true));
    }

    public static String encode(List<Mount> mounts) {
        JSONArray array = new JSONArray();
        for (Mount mount : mounts) {
            try {
                array.put(mount.toJson());
            } catch (JSONException ignored) {
                // A mount that cannot be serialised is simply not persisted.
            }
        }
        return array.toString();
    }

    public static List<Mount> decode(String json) {
        List<Mount> mounts = new ArrayList<Mount>();
        if (json == null || json.length() == 0) {
            return mounts;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object != null) {
                    mounts.add(fromJson(object));
                }
            }
        } catch (JSONException ignored) {
            // Corrupt preferences fall back to no mounts.
        }
        return mounts;
    }

    /**
     * The directory a guest mount point must be created as, verified to be empty.
     *
     * @return null when the path cannot be used, with {@code reason} explaining why.
     */
    public static File prepareGuestDir(File rootfs, String guestPath, StringBuilder reason) {
        File dir = new File(rootfs, guestPath.startsWith("/") ? guestPath.substring(1) : guestPath);
        if (dir.exists()) {
            if (!dir.isDirectory()) {
                reason.append(guestPath).append(" 在 Debian 里已存在且不是目录");
                return null;
            }
            String[] children = dir.list();
            if (children != null && children.length > 0) {
                reason.append(guestPath).append(" 在 Debian 里不是空目录，无法挂载");
                return null;
            }
        } else if (!dir.mkdirs() && !dir.isDirectory()) {
            reason.append("无法创建 ").append(guestPath);
            return null;
        }
        return dir;
    }
}
