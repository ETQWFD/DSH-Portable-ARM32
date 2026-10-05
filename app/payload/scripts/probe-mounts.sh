#!/bin/sh
# Verifies that the configured bind mounts actually carry the phone's files.
#
# This runs inside the guest as a throwaway process. It has to: Android gives
# each process its own storage view, so the app cannot see whether a bind over
# /storage/emulated/... resolves to the real directory or to an empty shadow.
# Only a process inside the PRoot namespace can answer that.
#
# Input:  DSH_MOUNT_PATHS, base64 of a newline-separated guest-path list.
# Output: one line per path, prefixed so the app can parse it unambiguously:
#   MOUNT_OK <guest path> <count> <first entry>
#   MOUNT_EMPTY <guest path>
#   MOUNT_FAIL <guest path> <reason>

set -u

PATHS=$(printf '%s' "${DSH_MOUNT_PATHS:-}" | base64 -d 2>/dev/null)

if [ -z "$PATHS" ]; then
    echo "MOUNT_FAIL - 没有传入路径"
    exit 0
fi

printf '%s\n' "$PATHS" | while IFS= read -r guest; do
    [ -n "$guest" ] || continue
    if [ ! -d "$guest" ]; then
        echo "MOUNT_FAIL $guest 目录不存在"
        continue
    fi
    count=$(ls -A "$guest" 2>/dev/null | wc -l)
    if [ "$count" -eq 0 ]; then
        # Could be a genuinely empty phone folder, or a bind that did not take.
        echo "MOUNT_EMPTY $guest"
    else
        first=$(ls -A "$guest" 2>/dev/null | head -n 1)
        echo "MOUNT_OK $guest $count $first"
    fi

    # Hard-link capability decides whether dsh's write tool can create a NEW file
    # here. It publishes a new file with link() (dsh-fs-local, "hard-link no-replace
    # primitive") while merely updating an existing one uses rename(). Android's
    # shared storage is sdcardfs or a restricted FUSE on many ROMs, and those return
    # ENOSYS for link(), so new files fail with the staging path in the message
    # while edits keep working. Probe it once here so the app can say so plainly.
    probe_dir="$guest/.dsh-mount-probe-$$"
    if mkdir -p "$probe_dir" 2>/dev/null; then
        if echo x > "$probe_dir/a" 2>/dev/null && ln "$probe_dir/a" "$probe_dir/b" 2>/dev/null; then
            echo "MOUNT_LINK_OK $guest"
        else
            echo "MOUNT_NO_LINK $guest"
        fi
        rm -rf "$probe_dir" 2>/dev/null
    fi
done
