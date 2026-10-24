#!/bin/bash
set -euo pipefail
HDR_EXP=$(cd "$(dirname "$0")" && pwd)
HDR_OUT=${HDR_PRECISION_OUT:-$HDR_EXP/out}
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
HDR_PLATFORM=${HDR_PLATFORM_JAR:-$ANDROID_HOME/platforms/android-37/android.jar}
HDR_D8=${HDR_D8_JAR:-$ANDROID_HOME/build-tools/37.0.0/lib/d8.jar}
HDR_JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac
HDR_JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
mkdir -p "$HDR_OUT/classes" "$HDR_OUT/dex"
if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
 HDR_CC=$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -path '*/bin/aarch64-linux-android34-clang' -print -quit)
 "$HDR_CC" -shared -fPIC -O2 -fvisibility=hidden -Wl,-z,max-page-size=16384 -Wl,--no-undefined -o "$HDR_OUT/libhdrprecision.so" "$HDR_EXP/native/precision.c" -ljnigraphics -lm
else
 /data/data/com.termux/files/usr/bin/clang -shared -fPIC -O2 -fvisibility=hidden -nostdlib -Wl,-z,max-page-size=16384 -Wl,--no-undefined -o "$HDR_OUT/libhdrprecision.so" "$HDR_EXP/native/precision.c" /apex/com.android.runtime/lib64/bionic/libc.so /system/lib64/libjnigraphics.so /apex/com.android.runtime/lib64/bionic/libm.so
fi
"$HDR_JAVAC" -source 17 -target 17 -cp "$HDR_PLATFORM" -d "$HDR_OUT/classes" "$HDR_EXP"/src/dev/local/miuihdr/*.java
mapfile -t HDR_CLASSES < <(find "$HDR_OUT/classes" -name '*.class')
"$HDR_JAVA" -cp "$HDR_D8" com.android.tools.r8.D8 --min-api 34 --lib "$HDR_PLATFORM" --output "$HDR_OUT/dex" "${HDR_CLASSES[@]}"
