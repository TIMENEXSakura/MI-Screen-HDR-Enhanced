#!/bin/bash
set -euo pipefail
HDR_MOD=$(cd "$(dirname "$0")" && pwd)
HDR_BASE=$(dirname "$HDR_MOD")
cd "$HDR_MOD"
if [[ -z "${JAVA_HOME:-}" && -d /usr/lib/jvm/java-17-openjdk ]]; then export JAVA_HOME=/usr/lib/jvm/java-17-openjdk; fi
bash ./build-native.sh
python3 tools/package-root.py
HDR_GRADLE=${HDR_GRADLE:-./gradlew}
if [[ ! -x "$HDR_GRADLE" && -x /root/tools/siliconid/gradle-9.7.1/bin/gradle ]]; then HDR_GRADLE=/root/tools/siliconid/gradle-9.7.1/bin/gradle; fi
HDR_ARGS=()
if [[ -n "${HDR_AAPT2:-}" ]]; then HDR_ARGS+=("-Pandroid.aapt2FromMavenOverride=$HDR_AAPT2");
elif [[ -x "$HDR_BASE/tools/compose-aapt2/aapt2" ]]; then HDR_ARGS+=("-Pandroid.aapt2FromMavenOverride=$HDR_BASE/tools/compose-aapt2/aapt2"); fi
"$HDR_GRADLE" --no-daemon --console=plain "${HDR_ARGS[@]}" assembleRelease
mkdir -p dist
cp build/outputs/apk/release/MiuiScreenshotHdr-release-unsigned.apk dist/unsigned.apk
HDR_KEYSTORE=${HDR_KEYSTORE:-$HDR_BASE/module/local-signing.p12}
HDR_PASSWORD_FILE=${HDR_PASSWORD_FILE:-$HDR_BASE/module/signing-password.txt}
if [[ -f "$HDR_KEYSTORE" && -f "$HDR_PASSWORD_FILE" ]]; then
 HDR_APKSIG=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/com.android.tools.build/apksig/9.4.0" -name '*.jar' -print -quit)
 "${JAVA_HOME:+$JAVA_HOME/bin/}javac" -cp "$HDR_APKSIG" -d build/signing tools/SignApk.java
 "${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "build/signing:$HDR_APKSIG" SignApk "$HDR_KEYSTORE" "$HDR_PASSWORD_FILE" dist/unsigned.apk dist/MI-Screen-HDR-Enhanced-1.4.5.apk
else
 echo 'Unsigned APK: dist/unsigned.apk. Sign with your own Android signing key.'
fi
