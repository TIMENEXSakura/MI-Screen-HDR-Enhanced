#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
HDR_PLATFORM="${ANDROID_HOME:?}/platforms/android-37.0/android.jar"
mkdir -p build/device-tests/classes build/device-tests/dex
javac -source 17 -target 17 -cp "$HDR_PLATFORM" -d build/device-tests/classes src/dev/local/miuihdr/CropGainmap.java tests/CropGainmapTest.java
mapfile -t HDR_TEST_CLASSES < <(find build/device-tests/classes -name '*.class')
java -cp "$ANDROID_HOME/build-tools/37.0.0/lib/d8.jar" com.android.tools.r8.D8 --min-api 34 --lib "$HDR_PLATFORM" --output build/device-tests/dex "${HDR_TEST_CLASSES[@]}"
cp build/device-tests/dex/classes.dex dist/crop-device-tests.dex
