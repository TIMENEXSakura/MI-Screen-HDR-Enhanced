#!/usr/bin/env bash
# Isolated host tests only: no device access, system writes, module install, or reboot.
set -euo pipefail
HDR_REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$HDR_REPO"
python3 tests/test_patch_probe.py
python3 tests/test_loaded_symbol.py
python3 tests/test_mount_recovery.py
mkdir -p build/ci-tests/locator build/ci-tests/installer
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -source 17 -target 17 -d build/ci-tests/locator \
  src/dev/local/miuihdr/LibraryLocator.java tests/LibraryLocatorTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp build/ci-tests/locator dev.local.miuihdr.LibraryLocatorTest
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -source 17 -target 17 -d build/ci-tests/installer \
  src/dev/local/miuihdr/RootInstaller.java tests/RootInstallerTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp build/ci-tests/installer dev.local.miuihdr.RootInstallerTest
