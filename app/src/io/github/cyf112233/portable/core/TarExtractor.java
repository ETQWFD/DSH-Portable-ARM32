// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 cyf112233
package io.github.cyf112233.portable.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming TAR reader for the bundled Debian rootfs.
 *
 * The rootfs ships as an uncompressed tar inside a gzip stream, so extraction
 * needs exactly one decoder (the JDK's) and no native helper.
 *
 * Two archive details matter here and are handled explicitly:
 *
 *   - GNU tar splits a path longer than 100 bytes into a preceding 'L' entry
 *     that carries the real name, followed by the header holding a truncated
 *     one. Ignoring 'L' silently writes the wrong path; on a Debian tree that
 *     means a file and a directory can collide, and a later write lands on the
 *     directory (EISDIR). These records are therefore always applied.
 *   - PAX 'x' records carry the same information as key=value text and are
 *     parsed for the path and linkpath keys.
 *
 * Device nodes cannot be created without root, which is the whole reason the
 * guest runs under PRoot, so they become empty regular files and /dev is
 * bind-mounted over them at launch.
 */
public final class TarExtractor {

    private static final int BLOCK = 512;
    private static final int TYPE_FILE = '0';
    private static final int TYPE_FILE_OLD = 0;
    private static final int TYPE_HARDLINK = '1';
    private static final int TYPE_SYMLINK = '2';
    private static final int TYPE_CHARDEV = '3';
    private static final int TYPE_BLOCKDEV = '4';
    private static final int TYPE_DIR = '5';
    private static final int TYPE_FIFO = '6';
    private static final int TYPE_GNU_LONG_NAME = 'L';
    private static final int TYPE_GNU_LONG_LINK = 'K';
    private static final int TYPE_PAX_EXTENDED = 'x';
    private static final int TYPE_PAX_GLOBAL = 'g';

    /** Receives progress so the UI can show something reassuring on first launch. */
    public interface Progress {
        void onEntry(String path, int count);
    }

    private TarExtractor() {
    }

    /**
     * Extract {@code tarStream} into {@code destRoot}.
     *
     * @return the number of entries written.
     */
    public static int extract(InputStream tarStream, File destRoot, Progress progress)
            throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(tarStream, 1 << 16));
        byte[] header = new byte[BLOCK];
        byte[] copyBuffer = new byte[1 << 16];
        int count = 0;

        // Pending metadata from an extension record, consumed by the next header.
        String pendingName = null;
        String pendingLink = null;
        // Deferred hard links: the target may not exist yet when the link is read.
        List<String[]> deferredHardLinks = new ArrayList<String[]>();

        while (true) {
            readFully(in, header, BLOCK);
            if (isZeroBlock(header)) {
                break;
            }

            String name = readString(header, 0, 100);
            long size = readOctal(header, 124, 12);
            int type = header[156] & 0xFF;
            String linkName = readString(header, 157, 100);
            String prefix = readString(header, 345, 155);

            if (type == TYPE_GNU_LONG_NAME || type == TYPE_GNU_LONG_LINK
                    || type == TYPE_PAX_EXTENDED || type == TYPE_PAX_GLOBAL) {
                byte[] payload = new byte[(int) size];
                readFully(in, payload, payload.length);
                skipFully(in, roundUp(size) - size);
                String text = new String(payload, "UTF-8");
                if (type == TYPE_GNU_LONG_NAME) {
                    pendingName = trimNul(text);
                } else if (type == TYPE_GNU_LONG_LINK) {
                    pendingLink = trimNul(text);
                } else {
                    // PAX: newline-separated "len key=value" records.
                    String paxName = paxValue(text, "path");
                    String paxLink = paxValue(text, "linkpath");
                    if (paxName != null) {
                        pendingName = paxName;
                    }
                    if (paxLink != null) {
                        pendingLink = paxLink;
                    }
                }
                continue;
            }

            if (pendingName != null) {
                name = pendingName;
                pendingName = null;
            } else if (prefix.length() > 0) {
                name = prefix + "/" + name;
            }
            if (pendingLink != null) {
                linkName = pendingLink;
                pendingLink = null;
            }

            // Guard against path escapes rather than trusting the archive.
            if (name.startsWith("/") || name.contains("..")) {
                skipFully(in, roundUp(size));
                continue;
            }

            File target = new File(destRoot, name);

            if (type == TYPE_FILE || type == TYPE_FILE_OLD) {
                mkdirs(target.getParentFile());
                OutputStream out = new BufferedOutputStream(new FileOutputStream(target), 1 << 16);
                try {
                    copyFully(in, out, size, copyBuffer);
                } finally {
                    out.close();
                }
                applyMode(target, header);
                skipFully(in, roundUp(size) - size);
            } else {
                switch (type) {
                    case TYPE_DIR:
                        if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
                            throw new IOException("无法创建目录 " + name);
                        }
                        break;
                    case TYPE_SYMLINK:
                        mkdirs(target.getParentFile());
                        deleteQuietly(target);
                        link("-sf", linkName, target.getAbsolutePath());
                        break;
                    case TYPE_HARDLINK:
                        deferredHardLinks.add(new String[] {
                                new File(destRoot, linkName).getAbsolutePath(),
                                target.getAbsolutePath()
                        });
                        break;
                    case TYPE_CHARDEV:
                    case TYPE_BLOCKDEV:
                    case TYPE_FIFO:
                        // Unprivileged processes cannot mknod; PRoot only needs the
                        // path to exist. /dev is bind-mounted over it at launch.
                        mkdirs(target.getParentFile());
                        if (!target.exists()) {
                            new FileOutputStream(target).close();
                        }
                        break;
                    default:
                        break;
                }
                skipFully(in, roundUp(size));
            }

            count++;
            if (progress != null && (count & 0x1F) == 0) {
                progress.onEntry(name, count);
            }
        }

        for (String[] link : deferredHardLinks) {
            File linkFile = new File(link[1]);
            mkdirs(linkFile.getParentFile());
            deleteQuietly(linkFile);
            link("-f", link[0], link[1]);
        }

        if (progress != null) {
            progress.onEntry("", count);
        }
        return count;
    }

    /** Reads one key out of a PAX record block. */
    private static String paxValue(String block, String key) {
        int index = 0;
        while (index < block.length()) {
            int space = block.indexOf(' ', index);
            if (space < 0) {
                return null;
            }
            int length;
            try {
                length = Integer.parseInt(block.substring(index, space).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (length <= 0 || index + length > block.length()) {
                return null;
            }
            String record = block.substring(space + 1, index + length).trim();
            int equals = record.indexOf('=');
            if (equals > 0 && key.equals(record.substring(0, equals))) {
                return record.substring(equals + 1);
            }
            index += length;
        }
        return null;
    }

    private static String trimNul(String text) {
        int end = text.indexOf('\0');
        return end >= 0 ? text.substring(0, end) : text;
    }

    /**
     * Create a link through the shell. Java offers no hard-link API, and PRoot
     * resolves both kinds itself, so they have to exist on disk.
     */
    private static void link(String flag, String source, String dest) {
        try {
            Process p = Runtime.getRuntime().exec(new String[] { "ln", flag, source, dest });
            p.waitFor();
        } catch (Exception e) {
            // A missing link degrades a few tools but never breaks the shell.
        }
    }

    private static void copyFully(DataInputStream in, OutputStream out, long size, byte[] buffer)
            throws IOException {
        long remaining = size;
        while (remaining > 0) {
            int chunk = (int) Math.min(buffer.length, remaining);
            readFully(in, buffer, chunk);
            out.write(buffer, 0, chunk);
            remaining -= chunk;
        }
    }

    /**
     * Reproduce the archive's permission bits exactly.
     *
     * The java.io.File permission setters cannot express this: setReadable(true,
     * false) widens to group and other together, and setWritable(true, false)
     * does the same, so 0640 would come out 0666 -- world-writable. That is not
     * cosmetic: dsh refuses to start when its credential store is readable beyond
     * its owner, and a world-writable /etc is its own problem.
     *
     * android.system.Os.chmod takes the mode directly, including the sticky bit
     * that /tmp needs. The File API remains as a fallback for the (unreachable in
     * practice) case where Os is unavailable.
     */
    private static void applyMode(File file, byte[] header) {
        long mode = readOctal(header, 100, 8);
        // Restrict to the twelve permission bits; the archive's file-type bits
        // (S_IFREG and friends) are not chmod's business.
        int bits = (int) (mode & 07777);
        try {
            android.system.Os.chmod(file.getAbsolutePath(), bits);
            return;
        } catch (Throwable unavailable) {
            // Fall through to the coarse approximation below.
        }
        file.setReadable(true, true);
        file.setWritable(true, true);
        if ((bits & 0111) != 0) {
            file.setExecutable(true, true);
        }
    }

    private static long roundUp(long size) {
        return ((size + BLOCK - 1) / BLOCK) * BLOCK;
    }

    private static void readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int n = in.read(buffer, read, length - read);
            if (n < 0) {
                throw new IOException("tar 数据提前结束（已读 " + read + "/" + length + " 字节）");
            }
            read += n;
        }
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    return;
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static boolean isZeroBlock(byte[] block) {
        for (int i = 0; i < block.length; i++) {
            if (block[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static String readString(byte[] block, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && block[end] != 0) {
            end++;
        }
        return new String(block, offset, end - offset);
    }

    private static long readOctal(byte[] block, int offset, int length) {
        long value = 0;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            int c = block[i] & 0xFF;
            if (c == 0 || c == ' ') {
                if (value != 0) {
                    break;
                }
                continue;
            }
            if (c < '0' || c > '7') {
                break;
            }
            value = (value << 3) + (c - '0');
        }
        return value;
    }

    private static void mkdirs(File dir) {
        if (dir != null && !dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            // The caller fails loudly on the write that follows.
        }
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete() && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteQuietly(child);
                }
            }
            file.delete();
        }
    }
}
