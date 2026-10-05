#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 cyf112233
# Builds the Debian arm64 rootfs that ships inside the APK.
#
# Everything the app needs at runtime is baked in here: the Debian userland, a
# Node.js runtime, a globally installed dsh, and the profile that enables the
# mobile layout plugin. The result is written to app/payload/rootfs/.
#
# Must run as root on an aarch64 host: debootstrap installs arm64 packages
# directly, and the chroot steps need mount privileges. A non-arm64 host would
# need qemu-user-static plus --foreign, which this script does not attempt.
#
# Usage:  sudo ./scripts/build-rootfs.sh [output-dir]
set -euo pipefail

# ---- pinned inputs -----------------------------------------------------------
DEBIAN_SUITE="trixie"                      # Debian 13, current stable
DEBIAN_MIRROR="https://mirrors.tuna.tsinghua.edu.cn/debian/"
NODE_VERSION="22.23.3"                     # must satisfy dsh's engine range
NODE_MIRROR="https://mirrors.tuna.tsinghua.edu.cn/nodejs-release"
DSH_SPEC="@deepseek-ai/dsh@0.2.0-rc.2"     # pin explicitly; never float here
MOBILE_PLUGIN="dsh-web-mobile@3.0.4"       # portrait/touch layout for the WebView
NPM_REGISTRY="https://registry.npmmirror.com"
# -----------------------------------------------------------------------------

HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${1:-$HERE/app/payload/rootfs}"
WORK="$(mktemp -d /tmp/dsh-rootfs.XXXXXX)"
ROOT="$WORK/debian"

log() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

cleanup() {
    for m in dev/pts dev proc sys; do
        mountpoint -q "$ROOT/$m" 2>/dev/null && umount "$ROOT/$m" || true
    done
    rm -rf "$WORK"
}
trap cleanup EXIT

[ "$(id -u)" = "0" ] || die "must run as root (debootstrap + chroot mounts)"
[ "$(uname -m)" = "aarch64" ] || die "must run on aarch64; got $(uname -m)"
command -v debootstrap >/dev/null || die "install debootstrap first"

# ---- 1. base system ----------------------------------------------------------
log "debootstrap $DEBIAN_SUITE (minbase)"
mkdir -p "$ROOT"
debootstrap --arch=arm64 --variant=minbase \
    --include=ca-certificates,curl,xz-utils,procps,less,nano,locales \
    "$DEBIAN_SUITE" "$ROOT" "$DEBIAN_MIRROR"

# The chroot needs working DNS and the pseudo-filesystems npm touches.
cp /etc/resolv.conf "$ROOT/etc/resolv.conf"
for d in proc sys dev dev/pts; do mkdir -p "$ROOT/$d"; done
mount -t proc proc "$ROOT/proc"
mount --bind /dev "$ROOT/dev"
mount --bind /dev/pts "$ROOT/dev/pts"

in_root() { chroot "$ROOT" /bin/bash -lc "export PATH=/opt/node22/bin:\$PATH; export HOME=/root; $*"; }

# ---- 2. Node.js --------------------------------------------------------------
# Taken from the official tarball rather than Debian's nodejs: the version is
# then explicit, and the APK carries exactly what the update notes claim.
log "Node.js $NODE_VERSION"
mkdir -p "$ROOT/opt"
curl -fsSL --retry 3 -o "$ROOT/opt/node.tar.xz" \
    "$NODE_MIRROR/v$NODE_VERSION/node-v$NODE_VERSION-linux-arm64.tar.xz"
tar -xJf "$ROOT/opt/node.tar.xz" -C "$ROOT/opt"
mv "$ROOT/opt/node-v$NODE_VERSION-linux-arm64" "$ROOT/opt/node22"
rm -f "$ROOT/opt/node.tar.xz"
in_root "node -v && npm -v"

# ---- 3. dsh and the mobile plugin --------------------------------------------
log "installing $DSH_SPEC"
in_root "npm config set registry $NPM_REGISTRY"
in_root "npm i -g $(printf '%q' "$DSH_SPEC") --no-audit --no-fund"
in_root "npm i -g pnpm --no-audit --no-fund"
in_root "dsh --version"

# The profile is baked in so a fresh install already has the portrait layout.
# Without it the WebView would render the desktop three-column shell.
log "enabling $MOBILE_PLUGIN"
in_root "dsh plugin add $(printf '%q' "$MOBILE_PLUGIN") --profile web"
mkdir -p "$ROOT/root/.dsh/profiles/web"
cat > "$ROOT/root/.dsh/profiles/web/cordis.patch.yml" <<'YAML'
# Your patch layer for this dsh profile, applied after every bundle layer:
# a top-level YAML array of loader patch entries (id-targeted config
# overrides, disables, and insert lists; `!!js` expressions allowed).
#
# dsh-web-mobile: the portrait/touch adaptation of the web surface. It is what
# makes the ordinary dsh web UI usable on a phone screen inside the WebView.
- insert:
    - id: dsh-web-mobile
      name: 'dsh-web-mobile'
YAML

# ---- 4. trim -----------------------------------------------------------------
# This is the difference between a 520 MB rootfs and a 900 MB one. Two optional
# heavy dependencies are dropped outright; everything else removed here is
# developer-only material that no runtime path reads.
log "trimming"
NM="$ROOT/opt/node22/lib/node_modules/@deepseek-ai/dsh/node_modules"
rm -rf "$NM/@deepseek-ai/libreoffice-kit-wasm"   # ~146 MB, office preview only
rm -rf "$NM/sherpa-onnx-linux-arm64"             # ~39 MB, speech runtime only
rm -rf "$ROOT/opt/node22/include"                # ~65 MB, native addon headers

find "$NM" -name '*.map' -delete 2>/dev/null || true
find "$NM" -name '*.d.ts' -delete 2>/dev/null || true
find "$NM" -name '*.d.mts' -delete 2>/dev/null || true
find "$NM" -name 'README*.md' -delete 2>/dev/null || true
find "$NM" -name 'CHANGELOG*.md' -delete 2>/dev/null || true

in_root "apt-get clean; rm -rf /var/lib/apt/lists/* /var/cache/apt/* /var/log/*.log /tmp/*"
rm -rf "$ROOT"/usr/share/doc/* "$ROOT"/usr/share/man/* "$ROOT"/usr/share/locale/* 2>/dev/null || true

# Leftovers from the build itself must not reach the device.
rm -rf "$ROOT/root/.npm" "$ROOT/root/.cache" "$ROOT/root/.local/share/pnpm/store"
rm -f  "$ROOT/root/.dsh/.credentials.yaml" "$ROOT/root/.dsh/.anonymous-user-id"
rm -rf "$ROOT/root/.dsh/sessions" "$ROOT/root/.dsh/storages"
mkdir -p "$ROOT/root/workspace"

# ---- 5. device nodes ---------------------------------------------------------
# mknod needs privileges the guest will not have; the app bind-mounts a real
# /dev over these at launch, so placeholders are all that is required.
mkdir -p "$ROOT/dev"
for n in null zero random urandom; do
    [ -e "$ROOT/dev/$n" ] || : > "$ROOT/dev/$n"
done

# ---- 6. pack -----------------------------------------------------------------
log "packing"
for m in dev/pts dev proc sys; do
    mountpoint -q "$ROOT/$m" 2>/dev/null && umount "$ROOT/$m" || true
done

mkdir -p "$OUT_DIR"
TAR="$WORK/debian-arm64.tar"
# --numeric-owner keeps the archive free of host user lookups. GNU tar defaults
# to PAX, which uses long-name records for paths over 100 bytes; the app's
# extractor handles those, so the format is intentionally left on default.
tar --numeric-owner --exclude=./proc/* --exclude=./sys/* -C "$ROOT" -cf "$TAR" .
gzip -6 -c "$TAR" > "$OUT_DIR/debian-arm64.tar.gz"

log "done"
du -h "$OUT_DIR/debian-arm64.tar.gz"
cat <<'NOTE'

Next steps:
  1. Bump ROOTFS_REVISION in app/src/io/github/cyf112233/portable/core/RootfsInstaller.java
     so existing installs re-extract instead of keeping the old tree.
  2. ./build.sh
  3. Verify on a device: fresh launch must extract without error, and the UI must
     come up narrow (the mobile plugin loads from ./root/.dsh/profiles/web).
NOTE
