// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 cyf112233
package io.github.cyf112233.portable.term;

import android.util.Log;

import java.io.IOException;

/**
 * A pseudo-terminal and the session running on it.
 *
 * The native half lives in app/jni/pty.c and is deliberately tiny: openpty, a
 * fork/exec of /system/bin/sh on the slave, TIOCSWINSZ, and read/write. Everything
 * about launching the guest (proot, its binds, the loader variables) stays in a
 * shell script on the Java side, where it can be changed without recompiling.
 *
 * Instances are not thread-safe: one reader thread owns {@link #read}, and writes
 * come from the UI thread.
 */
public final class Pty {

    private static final String TAG = "DshPty";

    static {
        System.loadLibrary("dshpty");
    }

    /** Native handle; 0 means "not open". */
    private long handle;

    /**
     * Create the PTY and start {@code scriptPath} on it.
     *
     * @param scriptPath sh script to run as the session (usually the guest launcher)
     * @param workDir    working directory for that script, or null
     * @param environment "KEY=VALUE" entries for the session; the JVM's own
     *                    environment is deliberately not inherited
     * @param rows        initial terminal height in character cells
     * @param cols        initial terminal width in character cells
     */
    public void open(String scriptPath, String workDir, String[] environment, int rows, int cols)
            throws IOException {
        if (handle != 0) {
            throw new IOException("PTY 已打开");
        }
        long created = nativeOpen(scriptPath, workDir, environment, rows, cols);
        if (created == 0) {
            throw new IOException("无法创建 PTY 会话");
        }
        handle = created;
        Log.i(TAG, "session started: " + scriptPath + " " + cols + "x" + rows);
    }

    public boolean isOpen() {
        return handle != 0;
    }

    /** True while the session process is still running. */
    public boolean isAlive() {
        return handle != 0 && nativeIsAlive(handle);
    }

    /**
     * Read whatever the session has produced within {@code timeoutMs}.
     *
     * @return the byte count, or -1 once the session ended
     */
    public int read(byte[] buffer, int timeoutMs) {
        if (handle == 0) {
            return -1;
        }
        return nativeRead(handle, buffer, timeoutMs);
    }

    /** @return the number of bytes accepted, or -1 on a closed session */
    public int write(byte[] buffer, int length) {
        if (handle == 0) {
            return -1;
        }
        return nativeWrite(handle, buffer, length);
    }

    public void resize(int rows, int cols) {
        if (handle != 0) {
            nativeResize(handle, rows, cols);
        }
    }

    /** Deliver a signal to the session's process group, e.g. SIGINT for Ctrl-C. */
    public void signal(int signalNumber) {
        if (handle != 0) {
            nativeSignal(handle, signalNumber);
        }
    }

    /** Close the session, asking politely first and escalating if it lingers. */
    public void close() {
        if (handle != 0) {
            nativeClose(handle);
            handle = 0;
            Log.i(TAG, "session closed");
        }
    }

    private static native long nativeOpen(String scriptPath, String workDir, String[] environment,
                                          int rows, int cols);

    private static native int nativeRead(long handle, byte[] buffer, int timeoutMs);

    private static native int nativeWrite(long handle, byte[] buffer, int length);

    private static native void nativeResize(long handle, int rows, int cols);

    private static native boolean nativeIsAlive(long handle);

    private static native void nativeClose(long handle);

    private static native void nativeSignal(long handle, int signalNumber);
}
