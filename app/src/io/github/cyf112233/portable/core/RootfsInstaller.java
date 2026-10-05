// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 cyf112233
package io.github.cyf112233.portable.core;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the on-device Debian rootfs: first-run extraction from APK assets and the
 * paths the rest of the app derives from it.
 *
 * Installation is deliberately resumable-by-restart rather than incremental: the
 * archive is small enough that a clean retry is simpler and safer than trying to
 * patch a half-written tree.
 */
public final class RootfsInstaller {

    private static final String TAG = "DshRootfs";

    /**
     * Bump whenever the packaged rootfs changes.
     *
     * This integer is the only signal an installed device has that its extracted
     * tree is stale, so a rootfs change without a bump ships an APK whose new
     * payload is simply never unpacked. Reusing a number that a device already
     * carries is the same mistake in a different shape.
     *
     * History, newest first:
     *   9  apply the dsh patches (filesystem publish, forced full access) and keep
     *      /root across a reinstall. Earlier revisions are not distinguishable on
     *      a device that saw them only as intermediate builds.
     *   2  first shipping revision of the published APK.
     */
    private static final int ROOTFS_REVISION = 9;

    private static final String ASSET_ARCHIVE = "rootfs/debian-arm64.tar.gz";

    private final Context context;

    public RootfsInstaller(Context context) {
        this.context = context.getApplicationContext();
    }

    public interface Listener {
        void onProgress(String message, int entries);

        void onDone(boolean success, String error);
    }

    /** Directory that holds the extracted Debian tree. */
    public File rootfsDir() {
        return new File(context.getFilesDir(), "debian");
    }

    private File revisionFile() {
        return new File(context.getFilesDir(), "rootfs.revision");
    }

    /** True when a complete, current rootfs is present. */
    public boolean isInstalled() {
        File marker = revisionFile();
        if (!marker.isFile()) {
            return false;
        }
        if (!new File(rootfsDir(), "bin/bash").isFile()) {
            return false;
        }
        if (!new File(rootfsDir(), "opt/node22/bin/node").isFile()) {
            return false;
        }
        try {
            InputStream in = new java.io.FileInputStream(marker);
            try {
                byte[] buf = new byte[16];
                int n = in.read(buf);
                String text = n > 0 ? new String(buf, 0, n).trim() : "";
                return Integer.toString(ROOTFS_REVISION).equals(text);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Extract the bundled rootfs. Safe to call from a worker thread; the callback
     * is invoked on that same thread.
     */
    public void install(Listener listener) {
        File dest = rootfsDir();
        // Everything under /root belongs to the user, not to the image: API keys,
        // sessions, the workspace, and any profile tweaks they made. Re-extracting
        // the rootfs must not throw those away, so the directory is moved aside and
        // put back over the fresh tree afterwards.
        File preserved = new File(context.getFilesDir(), "preserved-root");
        try {
            listener.onProgress("备份用户数据（/root）…", 0);
            deleteRecursively(preserved);
            File oldRoot = new File(dest, "root");
            if (oldRoot.isDirectory() && !oldRoot.renameTo(preserved)) {
                // A failed move is not fatal; the user data simply stays in place
                // until the wipe below, which the log records.
                Log.w(TAG, "could not set aside " + oldRoot);
                preserved = null;
            }

            listener.onProgress("清理旧的根文件系统…", 0);
            deleteRecursively(dest);
            if (!dest.mkdirs() && !dest.isDirectory()) {
                throw new IOException("无法创建 " + dest);
            }

            // Write to a scratch name first so an interrupted install is never
            // mistaken for a good one.
            File pending = new File(context.getFilesDir(), "rootfs.revision.pending");
            if (pending.exists() && !pending.delete()) {
                Log.w(TAG, "could not clear stale pending marker");
            }

            listener.onProgress("正在解压 Debian 根文件系统…", 0);
            long started = System.currentTimeMillis();
            AssetManager assets = context.getAssets();
            InputStream raw = assets.open(ASSET_ARCHIVE, AssetManager.ACCESS_STREAMING);
            final Listener target = listener;
            int entries;
            try {
                // The archive is stored in the APK verbatim (no second deflate) and
                // gzip-wrapped, so one native Java stream is the whole decoder.
                entries = TarExtractor.extract(
                        new java.util.zip.GZIPInputStream(raw, 1 << 16), dest,
                        new TarExtractor.Progress() {
                            @Override
                            public void onEntry(String path, int count) {
                                target.onProgress("正在解压… " + count + " 个文件", count);
                            }
                        });
            } finally {
                raw.close();
            }
            long elapsed = System.currentTimeMillis() - started;
            Log.i(TAG, "extracted " + entries + " entries in " + elapsed + "ms");

            // Only the paths the runtime writes to are touched here. mkdirs() alone
            // would create /tmp as 0700 and silently undo the 1777 the archive
            // carries, which breaks every tool that expects a shared temp dir.
            File tmp = new File(dest, "tmp");
            if (!tmp.isDirectory() && !tmp.mkdirs() && !tmp.isDirectory()) {
                throw new IOException("无法创建 /tmp");
            }
            try {
                android.system.Os.chmod(tmp.getAbsolutePath(), 01777);
            } catch (Throwable e) {
                Log.w(TAG, "could not set /tmp to 1777: " + e.getMessage());
            }
            // Put the user's home back, then make sure the paths the guest expects
            // exist even when there was nothing to restore.
            File newRoot = new File(dest, "root");
            if (preserved != null && preserved.isDirectory()) {
                mergeInto(preserved, newRoot);
                deleteRecursively(preserved);
                listener.onProgress("已恢复用户数据（/root）", 0);
            }
            for (String dir : new String[] { "root", "root/workspace" }) {
                File dirFile = new File(dest, dir);
                if (!dirFile.isDirectory() && !dirFile.mkdirs() && !dirFile.isDirectory()) {
                    throw new IOException("无法创建 /" + dir);
                }
                try {
                    android.system.Os.chmod(dirFile.getAbsolutePath(), 0700);
                } catch (Throwable e) {
                    Log.w(TAG, "could not set /" + dir + " to 0700: " + e.getMessage());
                }
            }

            OutputStream out = new FileOutputStream(pending);
            try {
                out.write(Integer.toString(ROOTFS_REVISION).getBytes("UTF-8"));
            } finally {
                out.close();
            }
            if (revisionFile().exists() && !revisionFile().delete()) {
                Log.w(TAG, "could not replace revision marker");
            }
            if (!pending.renameTo(revisionFile())) {
                throw new IOException("无法写入安装标记");
            }

            listener.onDone(true, null);
        } catch (Exception e) {
            Log.e(TAG, "rootfs install failed", e);
            listener.onDone(false, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** Remove the installed rootfs, freeing roughly the unpacked archive size. */
    public void uninstall() {
        deleteRecursively(rootfsDir());
        if (revisionFile().exists()) {
            revisionFile().delete();
        }
    }

    public long installedBytes() {
        return sizeOf(rootfsDir());
    }

    /**
     * Copy {@code source} over {@code target}, keeping whatever the target already
     * has and letting the preserved copy win on conflict.
     *
     * Merge rather than replace: the fresh tree carries image content the user's
     * home never had (the shipped profile and its plugin), while the preserved copy
     * carries everything the user accumulated. Neither side is complete on its own.
     */
    private static void mergeInto(File source, File target) {
        if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
            Log.w(TAG, "could not create " + target);
            return;
        }
        File[] children = source.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            File destination = new File(target, child.getName());
            if (child.isDirectory()) {
                mergeInto(child, destination);
            } else if (!destination.exists() || destination.isFile()) {
                if (destination.exists() && !destination.delete()) {
                    Log.w(TAG, "could not replace " + destination);
                    continue;
                }
                copyFile(child, destination);
                // Preserve the stored mode: the credential file must stay 0600 or
                // dsh refuses to start.
                try {
                    android.system.Os.chmod(destination.getAbsolutePath(),
                            readModeOf(child));
                } catch (Throwable ignored) {
                    // Best effort; a wrong mode only affects the credential check.
                }
            }
        }
    }

    /** A file's permission bits, or 0600 when they cannot be read. */
    private static int readModeOf(File file) {
        try {
            return android.system.Os.stat(file.getAbsolutePath()).st_mode & 07777;
        } catch (Throwable e) {
            Log.w(TAG, "could not read mode of " + file + ": " + e.getMessage());
            return 0600;
        }
    }

    private static void copyFile(File source, File destination) {
        try {
            java.io.InputStream in = new java.io.FileInputStream(source);
            try {
                java.io.OutputStream out = new java.io.FileOutputStream(destination);
                try {
                    byte[] buffer = new byte[1 << 16];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "could not preserve " + source + ": " + e.getMessage());
        }
    }

    private static long sizeOf(File file) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        if (file.isFile()) {
            return file.length();
        }
        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += sizeOf(child);
            }
        }
        return total;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            Log.w(TAG, "could not delete " + file);
        }
    }

    /** Convenience for the launcher: the absolute path PRoot should chroot into. */
    public String rootfsPath() {
        return rootfsDir().getAbsolutePath();
    }

    /** All bind targets that must exist inside the rootfs before launch. */
    public List<File> requiredGuestDirs() {
        List<File> dirs = new ArrayList<File>();
        dirs.add(new File(rootfsDir(), "dev"));
        dirs.add(new File(rootfsDir(), "proc"));
        dirs.add(new File(rootfsDir(), "sys"));
        dirs.add(new File(rootfsDir(), "tmp"));
        dirs.add(new File(rootfsDir(), "root"));
        dirs.add(new File(rootfsDir(), "root/workspace"));
        return dirs;
    }
}
