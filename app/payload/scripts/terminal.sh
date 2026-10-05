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

PROOT_TMP_DIR="$DSH_TMP" \
PROOT_LOADER="$DSH_LOADER" \
LD_LIBRARY_PATH="$DSH_LIBS" \
exec "$DSH_PROOT" -0 -r "$DSH_ROOT" "$@" -w /root "$GUEST_SHELL" -i
