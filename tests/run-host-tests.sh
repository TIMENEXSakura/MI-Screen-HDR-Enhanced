#!/bin/bash
set -euo pipefail
HDR_REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$HDR_REPO"
python3 tests/test_mount_recovery.py
python3 tests/test_offline_export.py
python3 tests/test_patch_probe.py
python3 tests/test_semantic_patch.py
python3 tests/test_rgb_patch.py
python3 tests/test_patch_migration.py
python3 tests/test_ultra_hdr_property.py
python3 tests/test_module_migration.py
mkdir -p build/host-tests
javac -d build/host-tests src/dev/local/miuihdr/LibraryLocator.java src/dev/local/miuihdr/RootInstaller.java tests/LibraryLocatorTest.java tests/RootInstallerTest.java
java -cp build/host-tests dev.local.miuihdr.LibraryLocatorTest
java -cp build/host-tests dev.local.miuihdr.RootInstallerTest
javac -d build/host-tests src/dev/local/miuihdr/InstallProgress.java tests/InstallProgressTest.java
java -cp build/host-tests dev.local.miuihdr.InstallProgressTest
HDR_API_AAR=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/io.github.libxposed/api/102.0.0" -name '*.aar' -print -quit)
python3 - "$HDR_API_AAR" <<'PY'
from pathlib import Path
from zipfile import ZipFile
import sys
with ZipFile(sys.argv[1]) as archive:
    Path('build/host-tests/libxposed-api.jar').write_bytes(archive.read('classes.jar'))
PY
HDR_TEST_CP="build/host-tests/libxposed-api.jar:${ANDROID_HOME:?}/platforms/android-37.0/android.jar"
javac -cp "$HDR_TEST_CP" -d build/host-tests src/dev/local/miuihdr/HookRuntime.java tests/HookRuntimeTest.java
java -cp "build/host-tests:$HDR_TEST_CP" dev.local.miuihdr.HookRuntimeTest
