# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 cyf112233
#!/usr/bin/env python3
"""Move every pinned version forward in one step.

The versions that matter live in three places and have to agree:

  * scripts/build-rootfs.sh  - the dsh and mobile-plugin specs baked into the rootfs
  * RootfsInstaller.java     - ROOTFS_REVISION, which decides whether an installed
                               device re-extracts the rootfs or keeps its old tree
  * README.md                - the table users read

Bumping the first without the second ships a new rootfs that nobody installs.

Usage:
  update-versions.py --dsh 0.2.1 --plugin 3.1.0
  update-versions.py --node 22.24.0
  update-versions.py --show
"""
import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
BUILD_ROOTFS = ROOT / "scripts" / "build-rootfs.sh"
INSTALLER = (
    ROOT / "app/src/io/github/cyf112233/portable/core/RootfsInstaller.java"
)
README = ROOT / "README.md"


def read(path):
    if not path.is_file():
        sys.exit("missing file: %s" % path)
    return path.read_text(encoding="utf-8")


def write(path, text):
    path.write_text(text, encoding="utf-8")
    print("  updated %s" % path.relative_to(ROOT))


def show():
    build = read(BUILD_ROOTFS)
    installer = read(INSTALLER)
    print("current pins")
    for label, pattern, text in (
        ("node", r'NODE_VERSION="([^"]+)"', build),
        ("dsh", r'DSH_SPEC="@deepseek-ai/dsh@([^"]+)"', build),
        ("plugin", r'MOBILE_PLUGIN="dsh-web-mobile@([^"]+)"', build),
        ("rootfs revision", r"ROOTFS_REVISION = (\d+)", installer),
    ):
        match = re.search(pattern, text)
        print("  %-16s %s" % (label, match.group(1) if match else "?"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dsh", help="dsh version, e.g. 0.2.1")
    parser.add_argument("--plugin", help="dsh-web-mobile version, e.g. 3.1.0")
    parser.add_argument("--node", help="Node.js version, e.g. 22.24.0")
    parser.add_argument("--show", action="store_true", help="print pins and exit")
    args = parser.parse_args()

    if args.show or not (args.dsh or args.plugin or args.node):
        show()
        return

    build = read(BUILD_ROOTFS)
    installer = read(INSTALLER)
    readme = read(README)

    if args.node:
        build = re.sub(r'NODE_VERSION="[^"]+"', 'NODE_VERSION="%s"' % args.node, build)
        build = re.sub(
            r"node-v\$NODE_VERSION-linux-arm64", "node-v$NODE_VERSION-linux-arm64", build
        )
    if args.dsh:
        build = re.sub(
            r'DSH_SPEC="@deepseek-ai/dsh@[^"]+"',
            'DSH_SPEC="@deepseek-ai/dsh@%s"' % args.dsh,
            build,
        )
        readme = re.sub(r"\| `@deepseek-ai/dsh` \| [^|]+ \|", "| `@deepseek-ai/dsh` | %s |" % args.dsh, readme)
    if args.plugin:
        build = re.sub(
            r'MOBILE_PLUGIN="dsh-web-mobile@[^"]+"',
            'MOBILE_PLUGIN="dsh-web-mobile@%s"' % args.plugin,
            build,
        )

    # Any rootfs change invalidates what is already installed on a device.
    match = re.search(r"ROOTFS_REVISION = (\d+)", installer)
    if not match:
        sys.exit("could not find ROOTFS_REVISION")
    installer = installer.replace(
        match.group(0), "ROOTFS_REVISION = %d" % (int(match.group(1)) + 1)
    )

    write(BUILD_ROOTFS, build)
    write(INSTALLER, installer)
    if args.dsh:
        write(README, readme)

    print("\nnew pins")
    show()
    print(
        "\nnext: ./scripts/build-rootfs.sh && ./build.sh"
        "\n      then verify on a device that a fresh install extracts and comes up narrow"
    )


if __name__ == "__main__":
    main()
