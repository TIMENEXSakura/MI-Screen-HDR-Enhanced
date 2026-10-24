#!/bin/bash
set -euo pipefail
HDR_MOD=$(cd "$(dirname "$0")/.." && pwd)
HDR_JPEG_SRC="$HDR_MOD/build/jpeg-source/libjpeg-turbo-3.1.2"
HDR_JPEG_OUT="$HDR_MOD/build/jpeg-static"
HDR_CMAKE=${HDR_CMAKE:-cmake}
python3 - "$HDR_MOD/build/jpeg-source" <<'PY'
from pathlib import Path
import hashlib,urllib.request,tarfile,sys
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
archive=out/'libjpeg-turbo-3.1.2.tar.gz'
if not archive.exists():
 data=urllib.request.urlopen('https://codeload.github.com/libjpeg-turbo/libjpeg-turbo/tar.gz/refs/tags/3.1.2',timeout=60).read()
 archive.write_bytes(data)
if hashlib.sha256(archive.read_bytes()).hexdigest()!='560f6338b547544c4f9721b18d8b87685d433ec78b3c644c70d77adad22c55e6':raise SystemExit('libjpeg source checksum mismatch')
if not (out/'libjpeg-turbo-3.1.2/CMakeLists.txt').exists():
 with tarfile.open(archive) as t:t.extractall(out,filter='data')
PY
HDR_JPEG_FLAGS=(-DENABLE_SHARED=OFF -DENABLE_STATIC=ON -DWITH_TURBOJPEG=OFF -DWITH_SIMD=ON -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON)
if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
 HDR_JPEG_FLAGS+=(-DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-34)
else
 HDR_JPEG_FLAGS+=(-DCMAKE_C_COMPILER=/data/data/com.termux/files/usr/bin/clang -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY -DCMAKE_C_FLAGS=--target=aarch64-linux-android34)
fi
"$HDR_CMAKE" -S "$HDR_JPEG_SRC" -B "$HDR_JPEG_OUT" "${HDR_JPEG_FLAGS[@]}"
"$HDR_CMAKE" --build "$HDR_JPEG_OUT" --target jpeg-static -j "${HDR_BUILD_JOBS:-4}"
