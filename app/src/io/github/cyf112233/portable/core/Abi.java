// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 cyf112233
package io.github.cyf112233.portable.core;

import android.os.Build;

/**
 * Resolves which ABI-specific payload this device must use.
 *
 * The APK ships two native-library directories ({@code arm64-v8a} and
 * {@code armeabi-v7a}) and two matching Debian rootfs archives. Android picks
 * the extracted native-lib directory by ABI on its own, but the rootfs is an
 * app asset, so the matching choice has to be made here.
 *
 * The 64-bit build carries the full dsh AI service. The 32-bit (ARMv7) build is
 * the Termux-style Linux terminal with Node.js 22: dsh's only required native
 * addon publishes no linux-arm prebuild and intentionally fails closed there, so
 * the AI web service is 64-bit-only while the terminal works on both.
 */
public final class Abi {

    public static final String ARM64_V8A = "arm64-v8a";
    public static final String ARMEABI_V7A = "armeabi-v7a";

    private Abi() {
    }

    /** True when the process is running a 64-bit ARM (arm64-v8a) userspace. */
    public static boolean is64Bit() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (ARM64_V8A.equals(abi)) {
                return true;
            }
        }
        return false;
    }

    /** The ABI name whose lib directory and rootfs this device actually uses. */
    public static String primaryAbi() {
        return is64Bit() ? ARM64_V8A : ARMEABI_V7A;
    }

    /** Asset path of the rootfs matching the running ABI. */
    public static String rootfsAsset() {
        return is64Bit()
                ? "rootfs/debian-arm64.tar.gz"
                : "rootfs/debian-arm.tar.gz";
    }
}
