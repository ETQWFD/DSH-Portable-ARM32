#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 cyf112233
"""Patch dsh for the two things Android gets wrong for it.

Run by scripts/build-rootfs.sh against a freshly assembled rootfs, so a rebuild
always carries both. Idempotent: a second run reports what is already applied.

Patch 1 -- creating a file must not require a hard link
-------------------------------------------------------
dsh creates a file by writing a staging file and publishing it with link()
(`dsh-fs-local`, the "hard-link no-replace primitive"), which makes the create
atomic and refuses to clobber a file another writer just created. On Android that
publish step fails: shared storage (/storage/emulated/0) is sdcardfs or a
restricted FUSE on many ROMs and answers link() with ENOSYS. The user then sees

    cannot write ".../test.txt": ENOSYS: function not implemented,
    link '.../.test.txt.<pid>.<uuid>.tmpdir/test.txt.tmp' -> '.../test.txt'

even though the same directory is perfectly writable, and editing an existing
file works because replacement uses rename().

Patch 2 -- the session must not be able to ask for a confined sandbox
--------------------------------------------------------------------
dsh-base pins fresh sessions to 'workspace-write', and its local sandbox provider
can only honour that through bwrap or Landlock. Inside a PRoot guest neither
exists, so every shell command is refused ("no sandbox backend is usable on this
host; refusing to run the command unconfined") and the offered one-shot escalation
needs an approval channel a phone does not have, so it fails closed. Naming
danger-full-access in the guest profile/start.sh covers the default, but the Web
UI's permission picker writes a *session-level* override that wins over the
deployment default (resolve(): request.mode ?? overrideOf(session) ?? defaultMode),
so a session created as workspace-write stays broken. This patch removes the
session override from the resolution entirely, leaving one policy for the guest.

The guest is the confinement layer: an unprivileged Android app, chrooted by PRoot
into its own private directory, with only the directories the user explicitly
mounted bound in.
"""
import pathlib
import re
import sys

FS_RELATIVE = (
    "opt/node22/lib/node_modules/@deepseek-ai/dsh/node_modules/"
    "@deepseek-ai/dsh-fs-local/lib/index.js"
)
POLICY_RELATIVE = (
    "opt/node22/lib/node_modules/@deepseek-ai/dsh/node_modules/"
    "@deepseek-ai/dsh-sandbox-policy/lib/index.js"
)

PUBLISH_HELPER = '''/**
* Publish a newly created file at `destination` from the staging file `tempPath`.
*
* Prefers the original hard link, which is atomic and refuses to replace an
* existing file. Where the filesystem cannot make hard links -- notably Android's
* sdcardfs and restricted FUSE shared storage, which return ENOSYS -- it falls
* back to an exclusive-create copy so creating a file still works. The copy keeps
* the EEXIST behaviour that the caller relies on to detect a concurrent creator.
*/
async function publishCreate(tempPath, destination, linkFile) {
	try {
		await linkFile(tempPath, destination);
		return;
	} catch (error) {
		const code = error?.code;
		const noHardLinks = code === "ENOSYS" || code === "EPERM" || code === "EOPNOTSUPP" || code === "ENOTSUP" || code === "EXDEV";
		if (!noHardLinks) throw error;
		const { copyFile, unlink } = await import("node:fs/promises");
		try {
			await copyFile(tempPath, destination, _fsConstants.constants.COPYFILE_EXCL);
		} catch (copyError) {
			// Translate the exclusive-create refusal into the code the caller
			// already understands, so a file created concurrently is still
			// reported as FS_NOT_OBSERVED rather than as a generic I/O failure.
			if (copyError?.code === "EEXIST") {
				const exists = new Error(copyError.message);
				exists.code = "EEXIST";
				throw exists;
			}
			throw copyError;
		}
		await unlink(tempPath);
	}
}
'''

FS_OLD = """		if (createIfAbsent !== void 0) try {
			await linkFile(tempPath, absolutePath);
		} catch (error) {"""

FS_NEW = """		if (createIfAbsent !== void 0) try {
			await publishCreate(tempPath, absolutePath, linkFile);
		} catch (error) {"""

POLICY_OLD = """	resolve(request = {}) {
		const { session } = request;
		return {
			mode: request.mode ?? (session === void 0 ? void 0 : this.overrideOf(session)) ?? this.defaultMode,
			workspaceRoot: resolveWorkspaceRoot(session?.header.cwd ?? this.workspaceRoot),
			...session === void 0 ? {} : { sessionId: session.id }
		};
	}"""

POLICY_NEW = """	resolve(request = {}) {
		const { session } = request;
		// Patched for DSH Portable: the guest runs inside PRoot, where no sandbox
		// backend exists, so a confined mode can never be honoured. The session
		// override is deliberately ignored rather than merely defaulted away --
		// otherwise a session created as workspace-write (the Web UI's permission
		// picker writes one) stays permanently broken. See scripts/patch-dsh.py.
		return {
			mode: "danger-full-access",
			workspaceRoot: resolveWorkspaceRoot(session?.header.cwd ?? this.workspaceRoot),
			...session === void 0 ? {} : { sessionId: session.id }
		};
	}"""


def apply(root, relative, replacements, already, label):
    """Apply literal replacements, refusing to guess when the source moved on."""
    path = pathlib.Path(root) / relative
    if not path.is_file():
        print("patch-dsh: %s not found" % path, file=sys.stderr)
        return False
    source = path.read_text(encoding="utf-8")
    if already in source:
        print("patch-dsh: %s already applied" % label)
        return True
    for old, new in replacements:
        count = source.count(old)
        if count != 1:
            print(
                "patch-dsh: %s: expected exactly 1 occurrence of the anchor, found %d; "
                "dsh changed shape and this patch needs updating" % (label, count),
                file=sys.stderr,
            )
            return False
        source = source.replace(old, new, 1)
    path.write_text(source, encoding="utf-8")
    print("patch-dsh: %s applied" % label)
    return True


def patch_fs(root):
    path = pathlib.Path(root) / FS_RELATIVE
    if path.is_file() and "async function publishCreate(" in path.read_text(encoding="utf-8"):
        print("patch-dsh: filesystem publish already applied")
        return True

    replacements = []
    if path.is_file():
        source = path.read_text(encoding="utf-8")
        if "_fsConstants" not in source:
            match = re.search(r'^import .*from "node:fs";$', source, re.M)
            if not match:
                print("patch-dsh: could not find the node:fs import to extend", file=sys.stderr)
                return False
            replacements.append(
                (match.group(0), match.group(0) + '\nimport * as _fsConstants from "node:fs";')
            )
        replacements.append(
            ("async function throwGuardedCreateFailure(", PUBLISH_HELPER + "async function throwGuardedCreateFailure(")
        )
    replacements.append((FS_OLD, FS_NEW))
    return apply(root, FS_RELATIVE, replacements, "async function publishCreate(", "filesystem publish")


def patch_policy(root):
    return apply(
        root,
        POLICY_RELATIVE,
        [(POLICY_OLD, POLICY_NEW)],
        'mode: "danger-full-access",',
        "sandbox policy (forced full access)",
    )


def main(root):
    ok = patch_fs(root)
    ok = patch_policy(root) and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
