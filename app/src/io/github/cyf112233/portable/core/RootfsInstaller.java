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

    /** Bump when the bundled rootfs changes so upgrades re-extract. */
    private static final int ROOTFS_REVISION = 1;

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
        try {
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

            // The archive is built from a Linux rootfs, so every directory is 0755
            // already; only the runtime-written paths need to be guaranteed here.
            File tmp = new File(dest, "tmp");
            if (!tmp.isDirectory() && !tmp.mkdirs()) {
                throw new IOException("无法创建 /tmp");
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
