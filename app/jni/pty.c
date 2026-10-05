/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 cyf112233
 *
 * A pseudo-terminal small enough to audit.
 *
 * Why this exists: a real terminal needs a PTY, and the two usual sources do not
 * fit this project. Android has no PTY API, and node-pty -- already inside the
 * guest via dsh -- ships prebuilds for linux-x64 only, so it cannot serve an
 * arm64 guest. Linking the whole Termux terminal stack would mean a JNI tree, a
 * vendored view library and a second build system for what amounts to four
 * syscalls.
 *
 * So this file does exactly those four things and nothing else:
 *
 *   openpty()   -- create the pair
 *   fork+exec   -- start /system/bin/sh on the slave, which becomes its ctty
 *   resize      -- TIOCSWINSZ, so full-screen programs know the window size
 *   read/write  -- pump bytes
 *
 * The child deliberately runs /system/bin/sh rather than proot directly: sh is a
 * bionic binary that always starts, and the guest launch (proot, binds, loader
 * variables) lives in a shell script where it can be read and changed without
 * recompiling anything.
 */
#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

/** Everything the child needs, built once in open(). */
typedef struct {
    int master;
    pid_t child;
    int alive;
} Pty;

static Pty *as_pty(jlong handle) {
    return (Pty *) (intptr_t) handle;
}

JNIEXPORT jlong JNICALL
Java_io_github_cyf112233_portable_term_Pty_nativeOpen(
        JNIEnv *env, jclass cls, jstring scriptPath, jstring workDir,
        jobjectArray environment, jint rows, jint cols) {
    const char *script = (*env)->GetStringUTFChars(env, scriptPath, NULL);
    const char *cwd = workDir ? (*env)->GetStringUTFChars(env, workDir, NULL) : NULL;

    /* Build the child's environment here, while the JVM is still safe to call. */
    int envCount = environment ? (*env)->GetArrayLength(env, environment) : 0;
    char **envp = (char **) calloc((size_t) envCount + 1, sizeof(char *));
    if (envp == NULL) {
        goto fail;
    }
    for (int i = 0; i < envCount; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, environment, i);
        const char *text = (*env)->GetStringUTFChars(env, item, NULL);
        envp[i] = strdup(text);
        (*env)->ReleaseStringUTFChars(env, item, text);
        (*env)->DeleteLocalRef(env, item);
    }
    envp[envCount] = NULL;

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        goto fail;
    }
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        close(master);
        goto fail;
    }
    char *slave = ptsname(master);
    if (slave == NULL) {
        close(master);
        goto fail;
    }
    /* Copy: ptsname returns static storage that a later call would overwrite. */
    char slavePath[128];
    snprintf(slavePath, sizeof slavePath, "%s", slave);

    struct winsize ws;
    memset(&ws, 0, sizeof ws);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ioctl(master, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) {
        close(master);
        goto fail;
    }

    if (pid == 0) {
        /* Child. Only async-signal-safe calls from here on: this is a forked JVM
         * process, so anything that takes a lock (malloc, stdio, JNI) could
         * deadlock against a thread that no longer exists here. */
        setsid();
        int fd = open(slavePath, O_RDWR);
        if (fd < 0) {
            _exit(120);
        }
        /* Opening the slave made it this session's controlling terminal (verified
         * on device: isatty=yes, tty prints the slave path), so no TIOCSCTTY is
         * needed and job control works. */
        dup2(fd, STDIN_FILENO);
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        if (fd > STDERR_FILENO) {
            close(fd);
        }
        close(master);

        if (cwd != NULL && cwd[0] != '\0') {
            chdir(cwd);
        }

        /* A fixed environment supplied by the caller: the JVM's own (CLASSPATH,
         * ANDROID_*, LD_LIBRARY_PATH pointing at the app's lib dir) would confuse the
         * guest, and proot sets up everything else itself. */
        char *argv[] = { (char *) "/system/bin/sh", (char *) script, NULL };
        execve("/system/bin/sh", argv, envp);
        _exit(121);
    }

    Pty *pty = (Pty *) calloc(1, sizeof(Pty));
    if (pty == NULL) {
        close(master);
        kill(pid, SIGKILL);
        goto fail;
    }
    pty->master = master;
    pty->child = pid;
    pty->alive = 1;

    /* The child has its own copy after fork; the parent frees its strings. */
    for (int i = 0; i < envCount; i++) {
        free(envp[i]);
    }
    free(envp);

    (*env)->ReleaseStringUTFChars(env, scriptPath, script);
    if (workDir && cwd) {
        (*env)->ReleaseStringUTFChars(env, workDir, cwd);
    }
    return (jlong) (intptr_t) pty;

fail:
    (*env)->ReleaseStringUTFChars(env, scriptPath, script);
    if (workDir && cwd) {
        (*env)->ReleaseStringUTFChars(env, workDir, cwd);
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_cyf112233_portable_term_Pty_nativeRead(
        JNIEnv *env, jclass cls, jlong handle, jbyteArray buffer, jint timeoutMs) {
    Pty *pty = as_pty(handle);
    if (pty == NULL || !pty->alive) {
        return -1;
    }
    jsize capacity = (*env)->GetArrayLength(env, buffer);
    if (capacity <= 0) {
        return 0;
    }

    struct pollfd pfd;
    pfd.fd = pty->master;
    pfd.events = POLLIN;
    pfd.revents = 0;
    int ready = poll(&pfd, 1, timeoutMs);
    if (ready == 0) {
        return 0; /* nothing yet; the caller loops */
    }
    if (ready < 0) {
        return errno == EINTR ? 0 : -1;
    }

    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) {
        return -1;
    }
    ssize_t n = read(pty->master, bytes, (size_t) capacity);
    jint result;
    if (n < 0) {
        result = (errno == EINTR || errno == EAGAIN) ? 0 : -1;
    } else {
        result = (jint) n; /* 0 means the child closed the slave: session over */
    }
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, 0);
    return result;
}

JNIEXPORT jint JNICALL
Java_io_github_cyf112233_portable_term_Pty_nativeWrite(
        JNIEnv *env, jclass cls, jlong handle, jbyteArray buffer, jint length) {
    Pty *pty = as_pty(handle);
    if (pty == NULL || !pty->alive) {
        return -1;
    }
    jsize capacity = (*env)->GetArrayLength(env, buffer);
    if (length > capacity) {
        length = capacity;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) {
        return -1;
    }
    ssize_t written = write(pty->master, bytes, (size_t) length);
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
    return written < 0 ? -1 : (jint) written;
}

JNIEXPORT void JNICALL
Java_io_github_cyf112233_portable_term_Pty_nativeResize(
        JNIEnv *env, jclass cls, jlong handle, jint rows, jint cols) {
    Pty *pty = as_pty(handle);
    if (pty == NULL || !pty->alive) {
        return;
    }
    struct winsize ws;
    memset(&ws, 0, sizeof ws);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ioctl(pty->master, TIOCSWINSZ, &ws);
    /* SIGWINCH so the program inside repaints at the new size. */
    if (pty->child > 0) {
        kill(pty->child, SIGWINCH);
    }
}

JNIEXPORT jboolean JNICALL
Java_io_github_cyf112233_portable_term_Pty_nativeIsAlive(
        JNIEnv *env, jclass cls, jlong handle) {
    Pty *pty = as_pty(handle);
    if (pty == NULL || !pty->alive) {
        return JNI_FALSE;
    }
    int status = 0;
    pid_t done = waitpid(pty->child, &status, WNOHANG);
    if (done == pty->child) {
        pty->alive = 0;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_io_github_cyf112233_portable_term_Pty_nativeClose(
        JNIEnv *env, jclass cls, jlong handle) {
    Pty *pty = as_pty(handle);
    if (pty == NULL) {
        return;
    }
    if (pty->alive && pty->child > 0) {
        /* Ask the session leader to go away, then make sure: the shell inside may
         * have started a foreground job that ignores SIGHUP. */
        kill(-pty->child, SIGHUP);
        kill(pty->child, SIGTERM);
        for (int i = 0; i < 40; i++) {
            int status = 0;
            if (waitpid(pty->child, &status, WNOHANG) == pty->child) {
                pty->alive = 0;
                break;
            }
            usleep(50 * 1000);
        }
        if (pty->alive) {
            kill(-pty->child, SIGKILL);
            kill(pty->child, SIGKILL);
            waitpid(pty->child, NULL, 0);
            pty->alive = 0;
        }
    }
    if (pty->master >= 0) {
        close(pty->master);
        pty->master = -1;
    }
    free(pty);
}

/** Sends an arbitrary signal to the session, used for Ctrl-C style interrupts. */
JNIEXPORT void JNICALL
Java_io_github_cyf112233_portable_term_Pty_nativeSignal(
        JNIEnv *env, jclass cls, jlong handle, jint signalNumber) {
    Pty *pty = as_pty(handle);
    if (pty == NULL || !pty->alive) {
        return;
    }
    kill(-pty->child, signalNumber);
}
