#!/bin/bash
# Build a diagnostic DEX without packaging Xposed or starting any UI.
set -euo pipefail
HDR_REPO=$(cd "$(dirname "$0")/.." && pwd)
HDR_TEST_OUT=${HDR_TEST_OUT:-$HDR_REPO/build/image-tests}
HDR_PLATFORM_JAR=${HDR_PLATFORM_JAR:-${ANDROID_HOME:?Set ANDROID_HOME}/platforms/android-37.0/android.jar}
HDR_D8_JAR=${HDR_D8_JAR:-$ANDROID_HOME/build-tools/37.0.0/lib/d8.jar}
mkdir -p "$HDR_TEST_OUT/src/dev/local/miuihdr" "$HDR_TEST_OUT/classes" "$HDR_TEST_OUT/dex"
for name in NativeBridge GainmapPrecision HdrJpeg444 HdrSignal PreviewHdr ShellGainmap EditorGainmap CaptureRatio HdrCaptureImage; do
 cp "$HDR_REPO/src/dev/local/miuihdr/$name.java" "$HDR_TEST_OUT/src/dev/local/miuihdr/"
done
for name in PrecisionCodecTest PrecisionBenchmark PreviewHdrTest ShellTest EditorGainmapTest HdrCaptureImageTest HdrCaptureFallbackTest RgbGainmapPipelineTest; do
 cp "$HDR_REPO/tests/$name.java" "$HDR_TEST_OUT/src/dev/local/miuihdr/"
done
cat > "$HDR_TEST_OUT/src/dev/local/miuihdr/MiuiHook.java" <<'JAVA'
package dev.local.miuihdr;
final class MiuiHook { static void log(String s) { android.util.Log.i("MiuiHdrHook",s); } }
JAVA
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -source 17 -target 17 -cp "$HDR_PLATFORM_JAR" -d "$HDR_TEST_OUT/classes" "$HDR_TEST_OUT"/src/dev/local/miuihdr/*.java
mapfile -d '' HDR_CLASSES < <(find "$HDR_TEST_OUT/classes" -name '*.class' -print0)
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$HDR_D8_JAR" com.android.tools.r8.D8 --min-api 34 --lib "$HDR_PLATFORM_JAR" --output "$HDR_TEST_OUT/dex" "${HDR_CLASSES[@]}"
echo "Test DEX: $HDR_TEST_OUT/dex/classes.dex"
