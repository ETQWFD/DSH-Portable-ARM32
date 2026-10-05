# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 cyf112233
#!/usr/bin/env python3
"""Append files to an APK at exact archive paths.

`zip` derives the stored path from the argument it is given, which makes it
awkward to place a file under a different prefix than it has on disk, and its
`-j` flag drops the directory structure entirely. A zipfile append keeps the
entry name explicit, and ZIP_STORED leaves an already-compressed payload alone.

Usage: addzip.py <archive> <arcname>=<source> [...]
"""
import sys
import zipfile

if len(sys.argv) < 3:
    sys.stderr.write(__doc__)
    sys.exit(2)

archive = sys.argv[1]
pairs = []
for spec in sys.argv[2:]:
    if "=" not in spec:
        sys.stderr.write("expected arcname=source, got: %s\n" % spec)
        sys.exit(2)
    arcname, source = spec.split("=", 1)
    pairs.append((arcname, source))

with zipfile.ZipFile(archive, "a", zipfile.ZIP_STORED, allowZip64=True) as zf:
    existing = set(zf.namelist())
    for arcname, source in pairs:
        if arcname in existing:
            # Replacing an entry in place is not supported by the zip format;
            # rebuilding the archive would be required.
            sys.stderr.write("refusing to duplicate entry: %s\n" % arcname)
            sys.exit(1)
        zf.write(source, arcname)
        sys.stdout.write("    + %s\n" % arcname)
