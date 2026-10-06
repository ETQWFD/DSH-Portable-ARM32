#!/bin/bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 cyf112233
# Builds the DSH Portable APK without Gradle.
#
# Dual-ABI edition: one APK that carries BOTH arm64-v8a and armeabi-v7a.
#   * arm64-v8a    -> full dsh AI service + terminal (rootfs debian-arm64.tar.gz)
#   * armeabi-v7a  -> Termux-style Debian terminal + Node 22 (rootfs debian-arm.tar.gz)
# Android installs the native-library directory matching the device ABI itself;
# the matching rootfs archive is chosen at runtime by core/Abi.java.
#
# Designed to run on an x86_64 Linux host using the JVM-only SDK pieces plus the
# official Android NDK (which ships clang for every target ABI):
#   aapt (Debian) compiles resources, d8.jar dexes the Java, the NDK builds the
#   PTY JNI library for both ABIs, then zipalign + apksigner finish the APK.
#
# Override inputs with env vars if your layout differs:
#   ANDROID_HOME  dir containing platforms/android-34 and build-tools/34.0.0
#   NDK_DIR       an unpacked Android NDK (r26 tested)
#   JDK8          JDK 8 home (javac 8 required by this d8 build)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/app"
BUILD="$HERE/build"
SDK="${ANDROID_HOME:-$HERE/sdk}"
NDK_DIR="${NDK_DIR:-$HERE/../android-ndk-r26d}"
PLATFORM="$SDK/platforms/android-34/android.jar"

# javac 8 is the last compiler whose class-file output this d8 build accepts,
# while d8 itself needs Java 11+. Locate both, tolerating 11/17/21 for d8.
JDK8="${JDK8:-/usr/lib/jvm/java-8-openjdk-amd64}"
[ -x "$JDK8/bin/javac" ] || JDK8="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
if [ -x /usr/lib/jvm/java-21-openjdk-amd64/bin/java ]; then
    JAVA_RUN=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
elif [ -x /usr/lib/jvm/java-17-openjdk-amd64/bin/java ]; then
    JAVA_RUN=/usr/lib/jvm/java-17-openjdk-amd64/bin/java
else
    JAVA_RUN="java"
fi
D8_JAR="$SDK/build-tools/34.0.0/lib/d8.jar"

MIN_SDK=24
TARGET_SDK=34
VERSION_CODE=4
VERSION_NAME=1.1.1
OUT="$BUILD/dsh-portable-unsigned.apk"
ALIGNED="$BUILD/dsh-portable-aligned.apk"
SIGNED="$BUILD/dsh-portable.apk"
KEYSTORE="$BUILD/debug.keystore"

JNI_INCLUDE="$JDK8/include"
TC="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"

for tool in "$D8_JAR" "$PLATFORM" "$JDK8/bin/javac"; do
  [ -e "$tool" ] || { echo "missing build tool: $tool" >&2; exit 1; }
done
[ -x "$TC/bin/aarch64-linux-android24-clang" ] || { echo "missing NDK clang under $TC (set NDK_DIR)" >&2; exit 1; }
for tool in aapt zipalign apksigner keytool zip; do
  command -v "$tool" >/dev/null || { echo "missing host tool: $tool" >&2; exit 1; }
done
[ -f "$JNI_INCLUDE/jni.h" ] || { echo "missing jni.h under $JNI_INCLUDE" >&2; exit 1; }
export PATH="$(dirname "$JAVA_RUN"):$PATH"

# ABI -> (clang prefix, staged lib dir, prebuilt payload dir)
ARM64_CC="$TC/bin/aarch64-linux-android24-clang"
ARM64_LIB="$BUILD/stage/lib/arm64-v8a"
ARM64_PRE="$BUILD/native"
ARM7_CC="$TC/bin/armv7a-linux-androideabi24-clang"
ARM7_LIB="$BUILD/stage/lib/armeabi-v7a"
ARM7_PRE="$BUILD/native-armeabi-v7a"

echo "==> cleaning"
rm -rf "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/stage" "$BUILD/res-linked.apk"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$ARM64_LIB" "$ARM7_LIB"

echo "==> packaging resources and assets (aapt)"
aapt package -f -m \
  -M "$APP/AndroidManifest.xml" \
  -S "$APP/res" \
  -I "$PLATFORM" \
  -J "$BUILD/gen" \
  -F "$BUILD/stage/base.apk" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

echo "==> compiling java (JDK 8)"
find "$APP/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
"$JDK8/bin/javac" -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$PLATFORM" \
  -d "$BUILD/classes" \
  -Xlint:-options \
  @"$BUILD/sources.txt"
if ! find "$BUILD/classes" -name 'MainActivity.class' -print -quit | grep -q .; then
  echo "javac produced no MainActivity; aborting" >&2
  exit 1
fi

echo "==> dexing (d8)"
find "$BUILD/classes" -name '*.class' > "$BUILD/classlist.txt"
"$JAVA_RUN" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --min-api "$MIN_SDK" --lib "$PLATFORM" \
  --output "$BUILD/dex" @"$BUILD/classlist.txt"
[ -f "$BUILD/dex/classes.dex" ] || { echo "d8 produced no dex; aborting" >&2; exit 1; }

# build_native <cc> <staged-lib-dir> <prebuilt-dir> <human ABI>
build_native() {
  local cc="$1" libdir="$2" predir="$3" abi="$4"
  echo "==> native ($abi): PTY JNI library"
  "$cc" -shared -fPIC -O2 -Wno-unused-parameter \
    -I"$JNI_INCLUDE" -I"$JNI_INCLUDE/linux" \
    -o "$libdir/libdshpty.so" "$APP/jni/pty.c"
  echo "==> native ($abi): staging proot/talloc/shmem"
  for name in libproot.so libproot_loader.so libtalloc.so libandroid-shmem.so; do
    [ -f "$predir/$name" ] || { echo "missing native payload ($abi): $predir/$name" >&2; exit 1; }
    cp "$predir/$name" "$libdir/$name"
  done
  chmod 755 "$libdir/"*
}
build_native "$ARM64_CC" "$ARM64_LIB" "$ARM64_PRE" "arm64-v8a"
build_native "$ARM7_CC"  "$ARM7_LIB"  "$ARM7_PRE" "armeabi-v7a"

echo "==> assembling apk"
cp "$BUILD/stage/base.apk" "$OUT"
( cd "$BUILD/dex" && zip -q -X "$OUT" classes.dex )
( cd "$BUILD/stage" && zip -q -X -r "$OUT" lib )

# Both rootfs archives (arm64 + arm) live under app/payload/rootfs and are stored
# verbatim so the already-gzipped payload is not deflated a second time.
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
aapt dump badging "$SIGNED" | grep -E 'package:|native-code|application-label:'
echo
echo "==> $(ls -la "$SIGNED" | awk '{print $5" bytes"}')  $SIGNED"
