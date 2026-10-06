#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 cyf112233
#
# Reproducibly builds the ARMv7 (armhf / armeabi-v7a) Debian rootfs asset:
#   app/payload/rootfs/debian-arm.tar.gz
#
# What the 32-bit image is
# ------------------------
# It is a Termux-style Debian 13 (trixie) Linux terminal with the OFFICIAL
# Node.js 22 linux-armv7l runtime. It deliberately does NOT install dsh: dsh's
# only required native addon, node-addon-require-builtin, publishes x64/arm64
# prebuilds only and intentionally "fails closed" on linux-arm (there is no
# linux_glibc_arm getter parser upstream). The terminal itself does not depend
# on that addon and is fully functional. The arm64 image keeps the whole dsh AI
# service unchanged.
#
# Cross build on an x86_64 host (no Android device, no root on the phone)
# -----------------------------------------------------------------------
#   stage 1  debootstrap --foreign .......... unpacks armhf .deb, runs nothing
#   stage 2  proot -q qemu-arm-static ....... configures the packages emulated
#
# Two host-side gotchas are handled below (both are build-host issues only; the
# Termux proot shipped inside the APK is unaffected):
#
#   * The host `proot` MUST understand statx. Debian trixie's glibc resolves
#     paths with statx; proot < 5.4 (e.g. Ubuntu 22.04's 5.1.0) does not trap
#     it, so `ls`/`stat`/`dpkg` see ENOENT while `cat` works. Ubuntu 24.04's
#     proot 5.4.0 works, or build current proot-me/proot (5.5.0):
#         sudo apt install -y libtalloc-dev pkg-config
#         git clone https://github.com/proot-me/proot && make -C proot/src proot
#     Point PROOT_BIN at that binary if your distro proot is too old.
#
#   * Inside an unprivileged container mknod is denied and debootstrap's
#     check_sane_mount() aborts; proot bind-mounts a working /dev at runtime, so
#     the check is bypassed and placeholder device files are written instead.
#
# Usage:  sudo PROOT_BIN=/path/to/proot ./scripts/build-rootfs-armhf.sh
set -euo pipefail

SUITE="trixie"
MIRROR="https://mirrors.tuna.tsinghua.edu.cn/debian/"
NODE_VERSION="22.23.3"
NODE_URL="https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-armv7l.tar.xz"
# armhf libatomic (the official armv7l Node build dlopen's libatomic.so.1).
LA_VER="14.2.0-19"
LA_POOL="pool/main/g/gcc-14/libatomic1_${LA_VER}_armhf.deb"
LA_MIRRORS=(
  "http://deb.debian.org/debian"
  "https://deb.debian.org/debian"
  "http://ftp.cn.debian.org/debian"
  "https://mirrors.aliyun.com/debian"
)

HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$HERE/app/payload/rootfs/debian-arm.tar.gz"
WORK="${WORK:-$(mktemp -d /tmp/dsh-armhf.XXXXXX)}"
ROOT="$WORK/debian"
QEMU="$(command -v qemu-arm-static)"
PROOT_BIN="${PROOT_BIN:-$(command -v proot)}"

log() { printf '\n==> %s\n' "$*"; }
[ "$(id -u)" = "0" ] || { echo "run with sudo" >&2; exit 1; }
[ -x "$QEMU" ]        || { echo "need qemu-user-static" >&2; exit 1; }
[ -x "$PROOT_BIN" ]   || { echo "need a statx-capable proot (set PROOT_BIN)" >&2; exit 1; }
command -v debootstrap >/dev/null || { echo "need debootstrap" >&2; exit 1; }

# Refuse the known-broken Ubuntu 22.04 proot up front with a clear message.
if "$PROOT_BIN" --version 2>&1 | grep -q '5\.1\.0'; then
  echo "proot 5.1.0 lacks statx support; build proot-me/proot >= 5.4 and set PROOT_BIN" >&2
  exit 1
fi

in_guest() {
  "$PROOT_BIN" -q "$QEMU" -0 -r "$ROOT" \
    -b /proc -b /sys -b /dev \
    -b /etc/resolv.conf -b /etc/hosts -b /etc/nsswitch.conf \
    -w / "$@"
}

log "stage 1: debootstrap $SUITE armhf (foreign / minbase)"
mkdir -p "$ROOT"
debootstrap --arch=armhf --variant=minbase --foreign \
  --include=ca-certificates,curl,xz-utils,procps,less,nano,locales,bash,gnupg \
  "$SUITE" "$ROOT" "$MIRROR"

# --- proot/container workarounds for second stage ----------------------------
# functions + scripts are architecture-independent shell; make sure both exist.
[ -f "$ROOT/debootstrap/functions" ] || cp /usr/share/debootstrap/functions "$ROOT/debootstrap/functions"
if [ ! -d "$ROOT/debootstrap/scripts" ]; then
  cp -a /usr/share/debootstrap/scripts "$ROOT/debootstrap/scripts"
fi
# Bypass check_sane_mount() (mknod is denied in a container; /dev is bind-mounted).
if ! grep -q 'patched: proot + unprivileged' "$ROOT/debootstrap/functions"; then
  sed -i 's/^check_sane_mount () {$/check_sane_mount () {\n\treturn 0  # patched: proot + unprivileged container/' \
    "$ROOT/debootstrap/functions"
fi

log "stage 2: configure packages under proot + qemu-arm-static"
in_guest /usr/bin/env DEBOOTSTRAP_DIR=/debootstrap \
  /debootstrap/debootstrap --second-stage

log "verify guest is armhf"
in_guest /usr/bin/dpkg --print-architecture | grep -x armhf
in_guest /bin/bash -lc 'echo "bash $BASH_VERSION"'

log "install Node.js $NODE_VERSION (linux-armv7l)"
mkdir -p "$WORK/node"; curl -fsSL --retry 3 -o "$WORK/node.tar.xz" "$NODE_URL"
tar -xJf "$WORK/node.tar.xz" -C "$WORK/node"
rm -rf "$ROOT/opt/node22"
cp -a "$WORK/node/node-v${NODE_VERSION}-linux-armv7l" "$ROOT/opt/node22"

log "inject armhf libatomic1"
got=
for base in "${LA_MIRRORS[@]}"; do
  if curl -fsSL --retry 2 -o "$WORK/libatomic.deb" "$base/$LA_POOL"; then got=1; break; fi
done
[ -n "$got" ] || { echo "cannot fetch libatomic1" >&2; exit 1; }
mkdir -p "$WORK/la"; dpkg-deb -x "$WORK/libatomic.deb" "$WORK/la"
find "$WORK/la" -name 'libatomic.so.1*' -exec cp -aP {} "$ROOT/usr/lib/arm-linux-gnueabihf/" \;
in_guest /sbin/ldconfig
in_guest /bin/bash -lc 'export LANG=C; /opt/node22/bin/node -v; /opt/node22/bin/npm -v'
for b in node npm npx corepack; do
  ln -sf "/opt/node22/bin/$b" "$ROOT/usr/local/bin/$b"
done

log "generate zh_CN.UTF-8"
in_guest /bin/bash -c 'sed -i "s/^# *zh_CN.UTF-8 UTF-8/zh_CN.UTF-8 UTF-8/" /etc/locale.gen && locale-gen zh_CN.UTF-8'

log "trim"
in_guest /bin/bash -c 'apt-get clean; rm -rf /var/lib/apt/lists/* /var/cache/apt/* /var/log/*.log /tmp/* 2>/dev/null || true'
rm -rf "$ROOT/opt/node22/include" "$ROOT/opt/node22/share/man" 2>/dev/null || true
rm -rf "$ROOT"/usr/share/doc/* "$ROOT"/usr/share/man/* "$ROOT"/usr/share/locale/* 2>/dev/null || true
rm -rf "$ROOT/root/.npm" "$ROOT/root/.cache" "$ROOT/debootstrap" 2>/dev/null || true

log "device placeholders + expected dirs"
for n in null zero random urandom; do [ -e "$ROOT/dev/$n" ] || : > "$ROOT/dev/$n"; done
mkdir -p "$ROOT/root/workspace" "$ROOT/tmp"; chmod 1777 "$ROOT/tmp"; chmod 0700 "$ROOT/root"

log "pack -> $OUT"
mkdir -p "$(dirname "$OUT")"
tar --numeric-owner --exclude=./proc/* --exclude=./sys/* -C "$ROOT" -cf "$WORK/debian-arm.tar" .
gzip -6 -c "$WORK/debian-arm.tar" > "$OUT"
chown "${SUDO_UID:-$(id -u)}:${SUDO_GID:-$(id -g)}" "$OUT"
ls -lh "$OUT"
echo "sha256: $(sha256sum "$OUT" | awk '{print $1}')"
