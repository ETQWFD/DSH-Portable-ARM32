// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 cyf112233
package io.github.cyf112233.portable.core;

import android.content.Context;

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

    public interface Listener {
        void onLine(String line);

        void onUrl(String url);

        void onExit(int code);
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
        cmd.add("--link2symlink");
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
        if (app != null) {
            for (Mount mount : app.mounts()) {
                if (!mount.isUsable()) {
                    continue;
                }
                StringBuilder reason = new StringBuilder();
                if (Mount.prepareGuestDir(root, mount.guestPath, reason) == null) {
                    Log.w(TAG, "skipping mount " + mount.hostPath + ": " + reason);
                    continue;
                }
                cmd.add("-b");
                cmd.add(mount.hostPath + ":" + mount.guestPath);
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
