// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 cyf112233
package io.github.cyf112233.portable.core;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import io.github.cyf112233.portable.DshApp;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the guest shell under PRoot and reports everything the server prints.
 *
 * PRoot is a userspace tracer, not a container runtime: the guest reaches the
 * outside only through the binds assembled here, and every guest path named in
 * the argument list is written as an absolute host path because PRoot rewrites
 * them as it starts.
 */
public final class ProotLauncher {

    private static final String TAG = "DshProot";

    /** `dsh web: http://127.0.0.1:3080/?token=...` */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s]*token=[A-Za-z0-9_-]+");

    /** Shipped as a native library so Android extracts it as an executable. */
    private static final String LIB_PROOT = "libproot.so";
    private static final String LIB_LOADER = "libproot_loader.so";

    private final Context context;
    private final RootfsInstaller rootfs;
    private DshApp app;

    private Process process;
    private Thread stdoutThread;
    private Thread stderrThread;
    private volatile String uiUrl;
    private Listener listener;
    private final List<MountReport> mountReports = new ArrayList<MountReport>();

    public interface Listener {
        void onLine(String line);

        void onUrl(String url);

        void onExit(int code);

        /**
         * One entry per configured mount, reported once the guest process is up.
         * The applied mounts cannot be checked from the app side: Android gives
         * each process its own storage view, so only the guest can see whether the
         * bind actually carries the phone's files.
         */
        void onMounts(List<MountReport> reports);
    }

    /** Outcome of applying one configured mount. */
    public static final class MountReport {
        public final String hostPath;
        public final String guestPath;
        public final boolean applied;
        public final String detail;

        MountReport(String hostPath, String guestPath, boolean applied, String detail) {
            this.hostPath = hostPath;
            this.guestPath = guestPath;
            this.applied = applied;
            this.detail = detail;
        }
    }

    public ProotLauncher(Context context, RootfsInstaller rootfs) {
        this.context = context.getApplicationContext();
        this.rootfs = rootfs;
        if (context.getApplicationContext() instanceof DshApp) {
            this.app = (DshApp) context.getApplicationContext();
        }
    }

    public boolean isRunning() {
        if (process == null) {
            return false;
        }
        try {
            // exitValue throws while the process is still alive.
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        }
    }

    /** The tokenised URL the WebView must open, or null before the server is up. */
    public String uiUrl() {
        return uiUrl;
    }

    /**
     * Start the guest server. Returns immediately; progress arrives through the
     * listener on a background thread.
     *
     * @return the port actually used, which may differ from {@code port} when the
     *         preferred one is already taken.
     */
    /**
     * A line describing exactly how storage access was judged.
     *
     * Written to the console on every launch because "the mount button is disabled
     * and I do not know why" and "the mount is enabled but the guest sees nothing"
     * are otherwise indistinguishable from inside the app: the device's own
     * permission UI, the app-op state and the platform API can disagree on some
     * ROMs, and this line says which of them the app actually saw.
     */
    public String storageAccessReport() {
        StringBuilder report = new StringBuilder();
        report.append("存储权限判定（API ").append(Build.VERSION.SDK_INT).append("）：");
        report.append("checkSelfPermission(MANAGE_EXTERNAL_STORAGE)=")
                .append(permissionState(Manifest.permission.MANAGE_EXTERNAL_STORAGE));
        report.append("，isExternalStorageManager=");
        try {
            report.append(Environment.isExternalStorageManager() ? "有" : "无");
        } catch (Throwable e) {
            report.append("异常(").append(e.getClass().getSimpleName()).append(")");
        }
        report.append("，读外部存储=")
                .append(permissionState(Manifest.permission.READ_EXTERNAL_STORAGE));
        report.append("；本 API 的实际判据=")
                .append(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? "isExternalStorageManager（另两条仅供诊断，不代表授权状态）"
                        : "READ_EXTERNAL_STORAGE");
        boolean allowed = app != null && app.hasStorageAccess();
        report.append("，列举共享存储=").append(storageProbeFailure() == null ? "成功" : "失败");
        report.append(allowed ? " → 挂载可用" : " → 挂载停用");
        // Also dropped next to the extracted tree: when the UI cannot be inspected
        // (no uiautomator, restricted dumpsys) this file is the only way to see what
        // the app itself concluded.
        try {
            java.io.FileWriter writer = new java.io.FileWriter(
                    new File(context.getFilesDir(), "storage-access.txt"));
            try {
                writer.write(report.toString());
            } finally {
                writer.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "could not record the storage verdict: " + e.getMessage());
        }
        return report.toString();
    }

    /**
     * Whether the app can actually enumerate the phone's shared storage.
     *
     * The permission APIs disagree with each other on some ROMs, so the verdict the
     * app acts on is this one: a real listing of the directory it would bind into
     * the guest. Anything else is an opinion.
     *
     * @return null when the directory lists fine, otherwise the failure text
     */
    public String storageProbeFailure() {
        File shared = Environment.getExternalStorageDirectory();
        if (shared == null || !shared.isDirectory()) {
            return "共享存储路径不可用";
        }
        String[] entries = shared.list();
        if (entries == null) {
            return "无法读取 " + shared.getAbsolutePath() + "（权限不足）";
        }
        return null;
    }

    /** "有" / "无" / "异常(类型)" for one permission, never throwing. */
    private String permissionState(String permission) {
        try {
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
                    ? "有" : "无";
        } catch (Throwable e) {
            return "异常(" + e.getClass().getSimpleName() + ")";
        }
    }

    public int start(int port, Listener listener) throws IOException {
        if (isRunning()) {
            throw new IOException("服务已在运行");
        }
        this.listener = listener;
        this.uiUrl = null;

        int actualPort = findFreePort(port);

        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File prootBin = new File(nativeDir, LIB_PROOT);
        File loaderBin = new File(nativeDir, LIB_LOADER);
        if (!prootBin.isFile()) {
            throw new IOException("缺少 PRoot 可执行文件：" + prootBin);
        }

        File root = rootfs.rootfsDir();
        File workspace = new File(context.getFilesDir(), "workspace");
        if (!workspace.isDirectory() && !workspace.mkdirs()) {
            throw new IOException("无法创建 workspace");
        }
        for (File dir : rootfs.requiredGuestDirs()) {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                Log.w(TAG, "guest dir missing: " + dir);
            }
        }

        // PRoot keeps its translated-path scratch space here; /data/local/tmp is
        // not writable for an app, so it must be app-private.
        File prootTmp = new File(context.getCacheDir(), "proot");
        if (!prootTmp.isDirectory() && !prootTmp.mkdirs()) {
            throw new IOException("无法创建 PRoot 临时目录");
        }

        File script = new File(context.getFilesDir(), "start.sh");
        writeAsset("scripts/start.sh", script);

        // PRoot is linked against libtalloc.so.2, but Android extracts native
        // libraries only when their name matches lib*.so, so the packaged copy is
        // called libtalloc.so. The linker resolves by the SONAME recorded in the
        // binary, so a correctly named private copy has to exist on disk.
        File libDir = prepareLinkerLibs(nativeDir);

        List<String> cmd = new ArrayList<String>();
        cmd.add(prootBin.getAbsolutePath());
        // No --link2symlink on purpose. PRoot offers it to emulate hard links on
        // filesystems that lack them, but here it does the opposite of what the
        // guest needs: link() is redirected into a symlink pointing at PRoot's own
        // temp area, so a file published that way is not really at its path.
        // dsh's filesystem backend publishes new files through a hard link
        // (dsh-fs-local, "hard-link no-replace primitive"), so with the flag on
        // every such write leaves a dangling symlink and the content disappears
        // with the staging directory. Both ext4 and Android's FUSE storage support
        // real hard links, so the flag only causes harm here.
        // -0 (fake root) is kept: -0 is exactly "-i 0:0", and the rootfs and the
        // tools inside it were built expecting a root identity. Dropping it made
        // the guest a stranger in its own tree. The uid mismatch this creates is
        // handled on the write side instead (see the note on the extractor).
        cmd.add("-0");
        cmd.add("-r");
        cmd.add(root.getAbsolutePath());
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-b");
        cmd.add("/dev/urandom:/dev/random");
        cmd.add("-b");
        cmd.add(prootTmp.getAbsolutePath() + ":/tmp");
        cmd.add("-b");
        cmd.add(workspace.getAbsolutePath() + ":/root/workspace");
        cmd.add("-b");
        cmd.add(script.getAbsolutePath() + ":/root/start.sh");
        // User-configured mounts come last so they win over the built-ins, and
        // each guest path is created (and checked empty) first.
        mountReports.clear();
        if (app != null && !app.hasStorageAccess() && !app.mounts().isEmpty()) {
            // Do not attempt the binds at all: without all-files access they would
            // resolve to unreadable directories and the guest would see empty trees,
            // which looks like a broken mount rather than a missing permission.
            for (Mount mount : app.mounts()) {
                mountReports.add(new MountReport(mount.hostPath, mount.guestPath,
                        false, "缺少「所有文件访问权限」，未挂载"));
            }
        } else if (app != null) {
            for (Mount mount : app.mounts()) {
                if (!mount.enabled) {
                    mountReports.add(new MountReport(mount.hostPath, mount.guestPath,
                            false, "已禁用"));
                    continue;
                }
                File host = new File(mount.hostPath);
                if (!host.isDirectory()) {
                    mountReports.add(new MountReport(mount.hostPath, mount.guestPath,
                            false, "手机上的目录不存在或不可读"));
                    continue;
                }
                StringBuilder reason = new StringBuilder();
                StringBuilder notice = new StringBuilder();
                if (Mount.prepareGuestDir(root, mount.guestPath, reason, notice) == null) {
                    mountReports.add(new MountReport(mount.hostPath, mount.guestPath,
                            false, reason.toString()));
                    continue;
                }
                cmd.add("-b");
                cmd.add(mount.hostPath + ":" + mount.guestPath);
                mountReports.add(new MountReport(mount.hostPath, mount.guestPath,
                        true, notice.length() > 0 ? notice.toString() : ""));
                Log.i(TAG, "bind " + mount.hostPath + " -> " + mount.guestPath);
            }
        }

        cmd.add("-w");
        cmd.add("/root");
        cmd.add("/bin/sh");
        cmd.add("/root/start.sh");

        ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.redirectErrorStream(false);
        builder.environment().clear();
        builder.environment().put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
        if (loaderBin.isFile()) {
            builder.environment().put("PROOT_LOADER", loaderBin.getAbsolutePath());
        }
        // The bundled PRoot is linked against two libraries that ship beside it.
        builder.environment().put("LD_LIBRARY_PATH",
                libDir.getAbsolutePath() + ":" + nativeDir.getAbsolutePath());
        builder.environment().put("DSH_PORT", Integer.toString(actualPort));
        builder.environment().put("DSH_WORKSPACE", "/root/workspace");
        builder.environment().put("TERM", "xterm-256color");

        Log.i(TAG, "launching: " + cmd);
        process = builder.start();
        stdoutThread = pump(process.getInputStream(), "dsh-out");
        stderrThread = pump(process.getErrorStream(), "dsh-err");
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                int code;
                try {
                    code = ProotLauncher.this.process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Listener l = ProotLauncher.this.listener;
                if (l != null) {
                    l.onExit(code);
                }
            }
        }, "dsh-wait");
        waiter.setDaemon(true);
        waiter.start();

        if (listener != null && !mountReports.isEmpty()) {
            listener.onMounts(new ArrayList<MountReport>(mountReports));
        }
        return actualPort;
    }

    /**
     * Pick a loopback port that nothing is listening on.
     *
     * The app shares the device with whatever else runs there, and on this phone
     * the desktop DSH already holds the default port. Binding then closing is
     * enough to detect the common case of an occupied port.
     */
    public static int findFreePort(int preferred) {
        for (int candidate = preferred; candidate < preferred + 40; candidate++) {
            ServerSocket probe = null;
            try {
                probe = new ServerSocket();
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), candidate));
                return candidate;
            } catch (IOException taken) {
                Log.i(TAG, "port " + candidate + " unavailable, trying next");
            } finally {
                if (probe != null) {
                    try {
                        probe.close();
                    } catch (IOException ignored) {
                        // Closing a probe socket cannot fail in a way that matters.
                    }
                }
            }
        }
        return preferred;
    }

    private Thread pump(final InputStream stream, String name) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(
                            new InputStreamReader(stream, Charset.forName("UTF-8")), 8192);
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Listener l = listener;
                        if (l != null) {
                            l.onLine(line);
                        }
                        Matcher matcher = URL_PATTERN.matcher(line);
                        if (matcher.find() && uiUrl == null) {
                            uiUrl = matcher.group();
                            if (l != null) {
                                l.onUrl(uiUrl);
                            }
                        }
                    }
                } catch (IOException e) {
                    Log.w(TAG, name + " reader stopped: " + e.getMessage());
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (IOException ignored) {
                            // Nothing useful to do while shutting down.
                        }
                    }
                }
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** Terminate the server, escalating to a forced kill if it does not stop. */
    public void stop() {
        Process current = process;
        if (current == null) {
            return;
        }
        process = null;
        uiUrl = null;
        current.destroy();
        final Process target = current;
        Thread killer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    target.exitValue();
                } catch (IllegalThreadStateException stillAlive) {
                    Log.w(TAG, "forcing server termination");
                    target.destroyForcibly();
                }
            }
        });
        killer.setDaemon(true);
        killer.start();
    }


    /**
     * Re-enter the guest in a throwaway process and list each mount point.
     *
     * This is the only trustworthy check: the app's own view of shared storage
     * differs from the guest's, so a bind that looks fine from here can still be
     * an empty shadow over there. Results arrive as MOUNT_* lines on the callback.
     */
    public void probeMounts(List<MountReport> reports, final LineSink sink) {
        if (reports.isEmpty()) {
            return;
        }
        StringBuilder paths = new StringBuilder();
        for (MountReport report : reports) {
            if (report.applied) {
                paths.append(report.guestPath).append('\n');
            }
        }
        if (paths.length() == 0) {
            return;
        }

        try {
            File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
            File prootBin = new File(nativeDir, LIB_PROOT);
            File loaderBin = new File(nativeDir, LIB_LOADER);
            File root = rootfs.rootfsDir();
            File prootTmp = new File(context.getCacheDir(), "proot");
            File libDir = prepareLinkerLibs(nativeDir);
            File probe = new File(context.getFilesDir(), "probe-mounts.sh");
            writeAsset("scripts/probe-mounts.sh", probe);

            List<String> cmd = new ArrayList<String>();
            cmd.add(prootBin.getAbsolutePath());
            cmd.add("-0");
            cmd.add("-r");
            cmd.add(root.getAbsolutePath());
            cmd.add("-b");
            cmd.add("/dev");
            cmd.add("-b");
            cmd.add("/proc");
            cmd.add("-b");
            cmd.add(prootTmp.getAbsolutePath() + ":/tmp");
            cmd.add("-b");
            cmd.add(probe.getAbsolutePath() + ":/root/probe-mounts.sh");
            for (Mount mount : (app == null ? java.util.Collections.<Mount>emptyList() : app.mounts())) {
                if (mount.isUsable()) {
                    cmd.add("-b");
                    cmd.add(mount.hostPath + ":" + mount.guestPath);
                }
            }
            cmd.add("/bin/sh");
            cmd.add("/root/probe-mounts.sh");

            ProcessBuilder builder = new ProcessBuilder(cmd);
            builder.environment().clear();
            builder.environment().put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
            if (loaderBin.isFile()) {
                builder.environment().put("PROOT_LOADER", loaderBin.getAbsolutePath());
            }
            builder.environment().put("LD_LIBRARY_PATH",
                    libDir.getAbsolutePath() + ":" + nativeDir.getAbsolutePath());
            builder.environment().put("DSH_MOUNT_PATHS", base64(paths.toString()));

            final Process process = builder.start();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    java.io.BufferedReader in = null;
                    try {
                        in = new java.io.BufferedReader(new java.io.InputStreamReader(
                                process.getInputStream(), Charset.forName("UTF-8")));
                        String line;
                        while ((line = in.readLine()) != null) {
                            if (sink != null && line.startsWith("MOUNT_")) {
                                sink.onLine(line);
                            }
                        }
                    } catch (IOException e) {
                        Log.w(TAG, "probe reader stopped: " + e.getMessage());
                    } finally {
                        if (in != null) {
                            try {
                                in.close();
                            } catch (IOException ignored) {
                                // Nothing useful to do while tearing down.
                            }
                        }
                    }
                }
            }, "dsh-probe");
            reader.setDaemon(true);
            reader.start();
        } catch (Exception e) {
            Log.w(TAG, "mount probe failed to start: " + e.getMessage());
            if (sink != null) {
                sink.onLine("MOUNT_FAIL - 无法启动校验进程：" + e.getMessage());
            }
        }
    }

    /** Receives one line of probe output. */
    public interface LineSink {
        void onLine(String line);
    }

    private static String base64(String text) {
        try {
            return android.util.Base64.encodeToString(
                    text.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }

    /**
     * Materialise the shared libraries PRoot needs under the names its own
     * ELF headers ask for, in a directory the app can write to.
     */
    private File prepareLinkerLibs(File nativeDir) throws IOException {
        File libDir = new File(context.getFilesDir(), "libs");
        if (!libDir.isDirectory() && !libDir.mkdirs()) {
            throw new IOException("无法创建链接库目录");
        }
        // packaged name -> SONAME requested by libproot.so
        String[][] wanted = {
                { "libtalloc.so", "libtalloc.so.2" },
                { "libandroid-shmem.so", "libandroid-shmem.so" },
        };
        for (String[] pair : wanted) {
            File source = new File(nativeDir, pair[0]);
            File target = new File(libDir, pair[1]);
            if (!source.isFile()) {
                throw new IOException("缺少原生库：" + source);
            }
            if (target.isFile() && target.length() == source.length()) {
                continue;
            }
            copyFile(source, target);
        }
        return libDir;
    }

    private static void copyFile(File source, File target) throws IOException {
        InputStream in = new java.io.FileInputStream(source);
        try {
            OutputStream out = new java.io.FileOutputStream(target);
            try {
                byte[] buffer = new byte[1 << 16];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        target.setReadable(true, false);
        target.setExecutable(true, false);
    }

    private void writeAsset(String assetPath, File dest) throws IOException {
        InputStream in = context.getAssets().open(assetPath);
        try {
            OutputStream out = new java.io.FileOutputStream(dest);
            try {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        dest.setReadable(true, false);
        dest.setExecutable(true, false);
    }

    /** Recent output kept for the diagnostics view, oldest first. */
    public static final class Ring {
        private final int capacity;
        private final ArrayDeque<String> lines = new ArrayDeque<String>();

        public Ring(int capacity) {
            this.capacity = capacity;
        }

        public synchronized void add(String line) {
            if (lines.size() >= capacity) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }

        public synchronized String join() {
            StringBuilder builder = new StringBuilder();
            for (String line : lines) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        }
    }
}
