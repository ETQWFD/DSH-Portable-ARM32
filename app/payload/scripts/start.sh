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
