// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 cyf112233
package io.github.cyf112233.portable;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import io.github.cyf112233.portable.core.Abi;
import io.github.cyf112233.portable.core.Mount;
import io.github.cyf112233.portable.core.RootfsInstaller;
import io.github.cyf112233.portable.term.Pty;
import io.github.cyf112233.portable.term.TerminalBuffer;
import io.github.cyf112233.portable.term.TerminalView;

/**
 * An interactive Debian shell.
 *
 * The session is a PTY (app/jni/pty.c) whose foreground process is a guest launcher
 * script; inside it the user is talking to a real bash with job control, not to a
 * line-buffered pipe. That distinction is why this screen exists instead of the log
 * panel it replaces: `top`, `apt`, `vim` and Ctrl-C all behave normally here.
 *
 * The session deliberately outlives the activity. Rotating the screen or stepping
 * back to the WebView keeps the shell and its history, which is what a terminal is
 * expected to do; the session is closed from the action bar instead.
 */
public class TerminalActivity extends Activity implements TerminalView.Session {

    private static final String TAG = "DshTerminal";

    private static final int TERM_ROWS = 24;
    private static final int TERM_COLS = 80;

    /** Kept across activity instances so the shell survives a rotation. */
    private static Pty sharedPty;
    private static TerminalBuffer sharedBuffer;
    private static Thread sharedReader;
    private static volatile boolean sharedRunning;

    /**
     * Ends any live terminal session.
     *
     * Called when the server restarts: a session holds the bind mounts captured when
     * it started, so leaving it alive would keep showing the previous mount set while
     * the new server runs with the new one -- two different views of the same guest.
     */
    public static void closeAnySession() {
        sharedRunning = false;
        Pty session = sharedPty;
        sharedPty = null;
        sharedBuffer = null;
        if (session != null) {
            session.close();
        }
    }

    private DshApp app;
    private RootfsInstaller installer;
    private Pty pty;
    private TerminalBuffer buffer;
    private TerminalView terminalView;
    private TextView statusView;
    private Button ctrlButton;
    private Button altButton;
    private Thread reader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);

        app = DshApp.get(this);
        installer = app.installer();

        terminalView = (TerminalView) findViewById(R.id.terminalView);
        statusView = (TextView) findViewById(R.id.terminalStatus);
        ctrlButton = (Button) findViewById(R.id.btnCtrl);
        altButton = (Button) findViewById(R.id.btnAlt);

        if (sharedBuffer == null) {
            sharedBuffer = new TerminalBuffer(TERM_ROWS, TERM_COLS);
        }
        buffer = sharedBuffer;
        terminalView.attach(buffer, this);

        findViewById(R.id.btnTerminalClose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSession();
                finish();
            }
        });
        findViewById(R.id.btnTerminalBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        ctrlButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                terminalView.toggleCtrl();
                ctrlButton.setSelected(terminalView.isCtrlArmed());
            }
        });
        altButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Alt as a latch, for keyboards that lack the key entirely.
                boolean now = !altButton.isSelected();
                altButton.setSelected(now);
                terminalView.setAlt(now);
            }
        });
        wireKey(R.id.btnKeyEsc, "esc");
        wireKey(R.id.btnKeyTab, "tab");
        wireKey(R.id.btnKeyUp, "up");
        wireKey(R.id.btnKeyDown, "down");
        wireKey(R.id.btnKeyLeft, "left");
        wireKey(R.id.btnKeyRight, "right");
        wireKey(R.id.btnKeyCtrlC, "interrupt");

        if (sharedRunning && sharedPty != null) {
            // A session was already running; reattach rather than start a second one.
            pty = sharedPty;
            reader = sharedReader;
            terminalView.post(new Runnable() {
                @Override
                public void run() {
                    terminalView.reportSize();
                    setStatus("已连接到进行中的会话");
                }
            });
        } else {
            startSession();
        }
    }

    private void wireKey(int id, final String name) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                terminalView.sendKey(name);
            }
        });
    }

    private void startSession() {
        if (!installer.isInstalled()) {
            setStatus("Debian 环境尚未安装，请先回到主界面启动一次");
            return;
        }
        try {
            File script = writeAsset("scripts/terminal.sh", "terminal.sh");
            pty = new Pty();
            pty.open(script.getAbsolutePath(), null, shellEnvironment(),
                    TERM_ROWS, TERM_COLS);
        } catch (Exception e) {
            Log.e(TAG, "could not start the session", e);
            setStatus("无法启动终端：" + e.getMessage());
            return;
        }
        sharedPty = pty;
        sharedRunning = true;
        buffer.clearScrollback();
        buffer.clear();
        setStatus("会话已启动");
        startReader();
        terminalView.post(new Runnable() {
            @Override
            public void run() {
                terminalView.reportSize();
                terminalView.requestFocus();
                terminalView.showKeyboard();
            }
        });
    }

    /**
     * Pumps the PTY into the buffer on its own thread.
     *
     * A short poll timeout keeps the loop responsive to {@link #sharedRunning} going
     * false, so closing the session does not wait on a blocking read.
     */
    private void startReader() {
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] chunk = new byte[8192];
                final Pty session = pty;
                while (sharedRunning && session == pty && session.isOpen()) {
                    int n = session.read(chunk, 200);
                    if (n < 0) {
                        break;
                    }
                    if (n > 0) {
                        buffer.write(chunk, n);
                    }
                }
                sharedRunning = false;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("会话已结束");
                    }
                });
            }
        }, "dsh-term-reader");
        reader.setDaemon(true);
        reader.start();
        sharedReader = reader;
    }

    private void closeSession() {
        // The screen is worth keeping in the log: the interesting failures here are
        // inside the guest, where the app cannot see them live.
        if (buffer != null) {
            Log.i(TAG, "session screen at close:\n" + buffer.snapshot());
        }
        sharedRunning = false;
        Pty session = pty;
        pty = null;
        sharedPty = null;
        sharedBuffer = null;
        if (session != null) {
            session.close();
        }
    }

    @Override
    protected void onDestroy() {
        // The session is intentionally left running; see the class comment.
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- TerminalView.Session ------------------------------------------------

    @Override
    public void onInput(byte[] data) {
        Pty session = pty;
        if (session != null && session.isOpen()) {
            session.write(data, data.length);
        }
    }

    @Override
    public void onResize(final int rows, final int cols) {
        Pty session = pty;
        if (session != null && session.isOpen()) {
            session.resize(rows, cols);
        }
    }

    @Override
    public void onSignal(int signalNumber) {
        Pty session = pty;
        if (session != null && session.isOpen()) {
            session.signal(signalNumber);
        }
    }

    // ---- helpers -------------------------------------------------------------

    private void setStatus(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (statusView != null) {
                    statusView.setText(text);
                }
            }
        });
    }

    /** Copies one packaged script into app storage and returns it, executable. */
    private File writeAsset(String assetPath, String name) throws IOException {
        File destination = new File(getFilesDir(), name);
        InputStream in = getAssets().open(assetPath);
        try {
            OutputStream out = new FileOutputStream(destination);
            try {
                byte[] copy = new byte[8192];
                int read;
                while ((read = in.read(copy)) > 0) {
                    out.write(copy, 0, read);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        destination.setReadable(true, false);
        destination.setExecutable(true, false);
        return destination;
    }

    /**
     * The environment for the session's launcher script.
     *
     * Everything the script needs to assemble the proot command line, so that logic
     * stays in the script and out of the native library. The bind mounts are passed
     * as one newline-separated value for the same reason.
     */
    private String[] shellEnvironment() {
        File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
        File rootfs = installer.rootfsDir();
        File libs = new File(getFilesDir(), "libs");
        File tmp = new File(getCacheDir(), "proot");

        StringBuilder mounts = new StringBuilder();
        for (Mount mount : app.mounts()) {
            if (mount.isUsable()) {
                mounts.append(mount.hostPath).append(':').append(mount.guestPath).append('\n');
            }
        }

        List<String> environment = new ArrayList<String>();
        environment.add("PATH=/system/bin:/system/xbin");
        environment.add("HOME=/");
        environment.add("TERM=xterm-256color");
        environment.add("DSH_ROOT=" + rootfs.getAbsolutePath());
        environment.add("DSH_PROOT=" + new File(nativeDir, "libproot.so").getAbsolutePath());
        environment.add("DSH_LOADER=" + new File(nativeDir, "libproot_loader.so").getAbsolutePath());
        environment.add("DSH_LIBS=" + libs.getAbsolutePath());
        environment.add("DSH_TMP=" + tmp.getAbsolutePath());
        environment.add("DSH_MOUNTS=" + mounts.toString().trim());
        // 32-bit ARM devices almost always run old kernels/ROMs whose seccomp-bpf
        // breaks Termux's proot (it dies or hangs the moment it starts). Force the
        // pure-ptrace path there; 64-bit keeps the faster seccomp path and the
        // launcher script still falls back automatically if that ever fails.
        environment.add("DSH_FORCE_NO_SECCOMP=" + (Abi.is64Bit() ? "0" : "1"));
        return environment.toArray(new String[0]);
    }
}
