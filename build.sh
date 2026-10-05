#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 cyf112233
# Builds the DSH Portable APK without Gradle.
#
# Why not the usual toolchain: the Android Gradle plugin wants an x86-64 JDK and
# a Gradle daemon, and the official build-tools ship x86-64 ELF binaries. This
# host is aarch64, so the build uses Debian's native aarch64 `aapt` for resource
# compilation plus the JVM-only parts of the SDK (d8) and Debian's zipalign and
# apksigner. Everything below is the same work AGP would do, invoked directly.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/app"
BUILD="$HERE/build"
SDK="${ANDROID_HOME:-$HERE/sdk}"
PLATFORM="$SDK/platforms/android-34/android.jar"

# Two JDKs on purpose. javac 8 is the last compiler whose class-file output this
# R8 build accepts (javac 9+ output trips an internal R8 error), while d8 itself
# is compiled for Java 11+, so it must run on the modern JVM.
JDK8="${JDK8:-/opt/jdk8}"
JAVA21="${JAVA21:-/usr/lib/jvm/java-21-openjdk-arm64}"
D8_JAR="$SDK/build-tools/34.0.0/lib/d8.jar"
D8="$SDK/build-tools/34.0.0/d8"

MIN_SDK=24
TARGET_SDK=34
OUT="$BUILD/dsh-portable-unsigned.apk"
ALIGNED="$BUILD/dsh-portable-aligned.apk"
SIGNED="$BUILD/dsh-portable.apk"
KEYSTORE="$BUILD/debug.keystore"

for tool in "$D8_JAR" "$PLATFORM" "$JDK8/bin/javac" "$JAVA21/bin/java"; do
  [ -e "$tool" ] || { echo "missing build tool: $tool" >&2; exit 1; }
done
for tool in aapt zipalign apksigner keytool zip; do
  command -v "$tool" >/dev/null || { echo "missing host tool: $tool" >&2; exit 1; }
done
export PATH="$JAVA21/bin:$PATH"

echo "==> cleaning"
rm -rf "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/stage" "$BUILD/res-linked.apk"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/stage/lib/arm64-v8a"

echo "==> packaging resources and assets (aapt)"
# No -A here on purpose: aapt embeds whatever it finds in the assets directory
# verbatim, and an empty one makes it fail silently (no R.java, no error). The
# rootfs and start.sh are added later by tools/addzip.py instead.
aapt package -f -m \
  -M "$APP/AndroidManifest.xml" \
  -S "$APP/res" \
  -I "$PLATFORM" \
  -J "$BUILD/gen" \
  -F "$BUILD/stage/base.apk" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code 1 --version-name 1.0

echo "==> compiling java"
find "$APP/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
"$JDK8/bin/javac" -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$PLATFORM" \
  -d "$BUILD/classes" \
  -Xlint:-options \
  @"$BUILD/sources.txt"
# Matched by name rather than by fully qualified path: the package has moved once
# already, and a stale path here fails in a confusing way after a rename.
if ! find "$BUILD/classes" -name 'MainActivity.class' -print -quit | grep -q .; then
  echo "javac produced no MainActivity; aborting" >&2
  exit 1
fi

echo "==> dexing (d8)"
find "$BUILD/classes" -name '*.class' > "$BUILD/classlist.txt"
"$JAVA21/bin/java" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --min-api "$MIN_SDK" --lib "$PLATFORM" \
  --output "$BUILD/dex" @"$BUILD/classlist.txt"
[ -f "$BUILD/dex/classes.dex" ] || { echo "d8 produced no dex; aborting" >&2; exit 1; }

echo "==> building native PTY library"
# Termux's clang already targets Android (aarch64-linux-android24) and brings its own
# bionic sysroot, so the NDK is not needed for one small shared library. The static
# libraries are absent from that toolchain, which is fine: a JNI library is loaded by
# the app process and must stay dynamic anyway.
JNI_INCLUDE="${JNI_INCLUDE:-/opt/jdk8/include}"
if [ ! -f "$JNI_INCLUDE/jni.h" ]; then
  echo "missing jni.h under $JNI_INCLUDE" >&2
  exit 1
fi
clang -shared -fPIC -O2 -Wno-unused-parameter \
  -I"$JNI_INCLUDE" -I"$JNI_INCLUDE/linux" \
  -o "$BUILD/stage/lib/arm64-v8a/libdshpty.so" "$APP/jni/pty.c"

echo "==> staging native executables"
for name in libproot.so libproot_loader.so libtalloc.so libandroid-shmem.so; do
  src="$BUILD/native/$name"
  [ -f "$src" ] || { echo "missing native payload: $src" >&2; exit 1; }
  cp "$src" "$BUILD/stage/lib/arm64-v8a/$name"
done
chmod 755 "$BUILD/stage/lib/arm64-v8a/"*

echo "==> assembling apk"
cp "$BUILD/stage/base.apk" "$OUT"
( cd "$BUILD/dex" && zip -q -X "$OUT" classes.dex )
( cd "$BUILD/stage" && zip -q -X -r "$OUT" lib )

# The payload is added here rather than passed to aapt through -A: aapt embeds
# whatever sits in the assets directory verbatim, which would place the rootfs in
# the archive before zipalign ever sees it. ZIP_STORED keeps the already-gzipped
# rootfs from being deflated a second time.
if [ -d "$APP/payload" ]; then
  PAYLOAD_ARGS=()
  while IFS= read -r f; do
    PAYLOAD_ARGS+=("assets/$f=$APP/payload/$f")
  done < <(cd "$APP/payload" && find . -type f | sed 's|^\./||')
  if [ "${#PAYLOAD_ARGS[@]}" -gt 0 ]; then
    echo "    embedding ${#PAYLOAD_ARGS[@]} payload file(s)"
    python3 "$HERE/tools/addzip.py" "$OUT" "${PAYLOAD_ARGS[@]}"
  fi
else
  echo "    WARNING: no payload directory; APK will not self-install" >&2
fi

echo "==> aligning"
zipalign -f -p 4 "$OUT" "$ALIGNED"

echo "==> signing"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias dshportable \
    -storepass android -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=DSH Portable, OU=dev, O=dshportable, C=CN" \
    >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias dshportable --v1-signing-enabled true --v2-signing-enabled true \
  --out "$SIGNED" "$ALIGNED"

echo "==> verifying"
apksigner verify --print-certs "$SIGNED" | head -4
aapt dump badging "$SIGNED" | head -5
echo
echo "==> $(ls -la "$SIGNED" | awk '{print $5" bytes"}')  $SIGNED"
