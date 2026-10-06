#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 cyf112233
#
# Runs as the session of an interactive terminal: the app gives this script a PTY
# and it execs proot on it, so the shell the user types into is a real Debian bash
# with job control.
#
# Environment arrives from the app:
#   DSH_ROOT        absolute path of the extracted Debian tree
#   DSH_PROOT       absolute path of the proot binary
#   DSH_LOADER      absolute path of proot's loader
#   DSH_LIBS        directory holding libtalloc.so.2 and libandroid-shmem.so
#   DSH_TMP         writable directory for proot's scratch files
#   DSH_MOUNTS      newline-separated "host:guest" bind mounts, may be empty
#
# Everything here is a plain shell script on purpose: changing how the guest starts
# should not require rebuilding the native library or the APK.

export PATH=/opt/node22/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/root
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export NARB_DISABLE_NATIVE_CACHE=1
export DSH_PERMISSION_MODE=danger-full-access

if [ -z "$DSH_ROOT" ] || [ ! -d "$DSH_ROOT" ]; then
    echo "dsh-terminal: DSH_ROOT is not set or missing" >&2
    exit 1
fi
if [ ! -x "$DSH_PROOT" ]; then
    echo "dsh-terminal: proot not found at $DSH_PROOT" >&2
    exit 1
fi
[ -d "$DSH_TMP" ] || mkdir -p "$DSH_TMP" 2>/dev/null

# Self-contained linker setup. On the dsh path ProotLauncher.prepareLinkerLibs
# materialises libtalloc.so.2 into $DSH_LIBS before launch, but a pure terminal
# session (notably on 32-bit, where the dsh service is unavailable) must not
# depend on that having run. Rebuild the two files proot links against from the
# extracted native-library directory, which always holds the per-ABI copies.
NATIVE_DIR=$(dirname "$DSH_PROOT")
mkdir -p "$DSH_LIBS" 2>/dev/null
if [ ! -f "$DSH_LIBS/libtalloc.so.2" ] && [ -f "$NATIVE_DIR/libtalloc.so" ]; then
    cp "$NATIVE_DIR/libtalloc.so" "$DSH_LIBS/libtalloc.so.2" 2>/dev/null || true
fi
if [ ! -f "$DSH_LIBS/libandroid-shmem.so" ] && [ -f "$NATIVE_DIR/libandroid-shmem.so" ]; then
    cp "$NATIVE_DIR/libandroid-shmem.so" "$DSH_LIBS/libandroid-shmem.so" 2>/dev/null || true
fi

# Build the bind list. /dev/pts is the important one: this session's PTY node lives
# there, and without it the guest cannot see (or the shell cannot re-open) the
# terminal it is running on.
set -- \
    -b /dev \
    -b /proc \
    -b /sys \
    -b /dev/pts \
    -b "$DSH_TMP:/tmp"

if [ -n "$DSH_MOUNTS" ]; then
    # Word splitting is intended: each line is one "host:guest" pair, and paths
    # under /storage contain no spaces.
    for pair in $DSH_MOUNTS; do
        host=${pair%%:*}
        guest=${pair#*:}
        if [ -n "$host" ] && [ -n "$guest" ] && [ -d "$host" ]; then
            set -- "$@" -b "$host:$guest"
        fi
    done
fi

# The shell inside the guest: /bin/bash when present, /bin/sh otherwise. `-i` is
# what makes it read .bashrc and give job control.
if [ -x "$DSH_ROOT/bin/bash" ] || [ -x "$DSH_ROOT/usr/bin/bash" ]; then
    GUEST_SHELL=/bin/bash
else
    GUEST_SHELL=/bin/sh
fi

# Launch proot, selecting the syscall tracer the device kernel can handle.
#
# Many 32-bit ARM phones run old kernels/vendor ROMs whose seccomp-bpf is
# incompatible with proot's accelerated tracer; proot then dies or hangs before
# the first prompt (a black/dead terminal). The app sets DSH_FORCE_NO_SECCOMP=1
# on armeabi-v7a, so those devices take the pure-ptrace path straight away.
#
# On 64-bit we keep the faster default tracer but, if it exits non-zero before
# the guest is usable, retry once with seccomp disabled, so a broken seccomp
# setup never strands the user on an empty screen. "$@" below is the bind list
# built above (this is the script's own positional parameters, not a function's).
RC=0
if [ "${DSH_FORCE_NO_SECCOMP:-0}" = "1" ]; then
    echo "dsh-terminal: 以兼容模式启动（32 位 / 关闭 seccomp，纯 ptrace）…"
    PROOT_NO_SECCOMP=1 \
    PROOT_TMP_DIR="$DSH_TMP" \
    PROOT_LOADER="$DSH_LOADER" \
    LD_LIBRARY_PATH="$DSH_LIBS" \
    "$DSH_PROOT" -0 -r "$DSH_ROOT" "$@" -w /root "$GUEST_SHELL" -i
    RC=$?
else
    PROOT_TMP_DIR="$DSH_TMP" \
    PROOT_LOADER="$DSH_LOADER" \
    LD_LIBRARY_PATH="$DSH_LIBS" \
    "$DSH_PROOT" -0 -r "$DSH_ROOT" "$@" -w /root "$GUEST_SHELL" -i
    RC=$?
    if [ "$RC" -ne 0 ]; then
        echo ""
        echo "dsh-terminal: 默认模式退出（exit=$RC），正在以兼容模式（关闭 seccomp）重试…"
        PROOT_NO_SECCOMP=1 \
        PROOT_TMP_DIR="$DSH_TMP" \
        PROOT_LOADER="$DSH_LOADER" \
        LD_LIBRARY_PATH="$DSH_LIBS" \
        "$DSH_PROOT" -0 -r "$DSH_ROOT" "$@" -w /root "$GUEST_SHELL" -i
        RC=$?
    fi
fi

if [ "$RC" -ne 0 ]; then
    echo ""
    echo "=============================================================="
    echo " Debian 终端启动失败（proot 退出码=$RC）。请截图本屏反馈，并附："
    echo "   手机型号 / Android 版本 / 剩余存储空间"
    echo " DSH_FORCE_NO_SECCOMP=${DSH_FORCE_NO_SECCOMP:-0}（1=32位兼容模式）"
    echo " ROOT=$DSH_ROOT"
    echo " 常见原因：内核禁用 ptrace、安全/省电软件拦截、存储空间不足。"
    echo "=============================================================="
    # Keep the PTY alive so the message stays on screen instead of vanishing.
    echo "按回车键关闭本窗口…"
    read _dsh_dummy 2>/dev/null || sleep 20
fi
exit "$RC"
