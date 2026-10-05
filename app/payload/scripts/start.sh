#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 cyf112233
# Runs inside the PRoot guest as its init. Everything this script needs about the
# outside world arrives through the environment, so the same file works whether
# PRoot translated the paths or not.
#
#   DSH_PORT      port the web surface should listen on
#   DSH_WORKSPACE guest path handed to the agent as its workspace
#   DEEPSEEK_API_KEY  optional; an already-stored credential wins inside DSH
#
# The token printed by `dsh web` is parsed by the launcher out of stdout, so this
# script must never swallow it.

set -e

export PATH=/opt/node22/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/root
export LANG=C.UTF-8
export LC_ALL=C.UTF-8

# dsh loads one native addon (node-addon-require-builtin) through a loader that
# materialises the prebuilt .node into os.tmpdir() and re-uses it only when its
# sha256 still matches the source. Under PRoot that cache is bound to the app's
# cache directory, so it survives restarts and a single bad copy -- an
# interrupted write, a killed process -- makes every later boot fail with
# "No usable prebuilt binary". Nothing needs the copy: loading the prebuilt
# from its package directory works, so the cache is disabled outright.
export NARB_DISABLE_NATIVE_CACHE=1

# dsh-base pins fresh sessions to 'workspace-write', which its local sandbox
# provider can only honour through bwrap or Landlock. Inside this guest neither
# exists, so the tool layer refuses every shell command and every write outside
# the workspace ("no sandbox backend is usable on this host; refusing to run the
# command unconfined"), and the one-shot escalation it offers needs an approval
# channel that a phone headless run does not have, so it fails closed.
#
# The guest IS the boundary: an unprivileged Android app, chrooted by PRoot into
# its own private directory, with only the directories the user explicitly
# mounted bound in. This variable is dsh's own documented deployment override and
# it moves the sandbox *and* the approval policy together ('never'), which is why
# it is set here rather than only in the profile patch.
export DSH_PERMISSION_MODE=danger-full-access

# PRoot guests inherit a read-only Android /proc; Node probes a few things there
# and a missing /dev/shm breaks larger heaps.
[ -d /dev/shm ] || mkdir -p /dev/shm 2>/dev/null || true

WORKSPACE="${DSH_WORKSPACE:-/root/workspace}"
[ -d "$WORKSPACE" ] || mkdir -p "$WORKSPACE" 2>/dev/null || true
cd "$WORKSPACE"

if ! command -v dsh >/dev/null 2>&1; then
  echo "dsh-portable: dsh not found on PATH" >&2
  exit 127
fi

# `exec` keeps PRoot's child count at one, so killing the PRoot process also
# reaps the server instead of leaving it orphaned in the guest.
exec dsh --profile web --no-open --port "${DSH_PORT:-3080}"
