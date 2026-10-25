#!/bin/bash
set -euo pipefail
HDR_MOD=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$HDR_MOD/lib/arm64-v8a"
mkdir -p "$HDR_MOD/root-payload/bin"
bash "$HDR_MOD/tools/build-jpeg.sh"
HDR_JPEG_FLAGS=(-I"$HDR_MOD/build/jpeg-source/libjpeg-turbo-3.1.2/src" -I"$HDR_MOD/build/jpeg-static" "$HDR_MOD/build/jpeg-static/libjpeg.a" -Wl,--exclude-libs,ALL)
if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
 HDR_NDK_CLANG=$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -path '*/bin/aarch64-linux-android34-clang' -print -quit)
 "$HDR_NDK_CLANG" -shared -fPIC -O2 -fvisibility=hidden -Wl,-z,max-page-size=16384 -Wl,--no-undefined -Wl,-soname,libmiuihdr.so -o "$HDR_MOD/lib/arm64-v8a/libmiuihdr.so" "$HDR_MOD/native/miuihdr.c" "${HDR_JPEG_FLAGS[@]}" -llog -ljnigraphics -lm -ldl
 "$HDR_NDK_CLANG" -fPIE -pie -O2 -Wl,-z,max-page-size=16384 -o "$HDR_MOD/root-payload/bin/hdrpatch" "$HDR_MOD/native/hdrpatch.c"
 exit 0
fi
/data/data/com.termux/files/usr/bin/clang -shared -fPIC -O2 -fvisibility=hidden -nostdlib -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--no-undefined -Wl,-soname,libmiuihdr.so -o "$HDR_MOD/lib/arm64-v8a/libmiuihdr.so" "$HDR_MOD/native/miuihdr.c" "${HDR_JPEG_FLAGS[@]}" /apex/com.android.runtime/lib64/bionic/libc.so /system/lib64/liblog.so /system/lib64/libjnigraphics.so /apex/com.android.runtime/lib64/bionic/libm.so /apex/com.android.runtime/lib64/bionic/libdl.so
/data/data/com.termux/files/usr/bin/clang -fPIE -pie -O2 -Wl,-z,max-page-size=16384 -o "$HDR_MOD/root-payload/bin/hdrpatch" "$HDR_MOD/native/hdrpatch.c"
python3 - "$HDR_MOD/lib/arm64-v8a/libmiuihdr.so" "$HDR_MOD/root-payload/bin/hdrpatch" <<'PY'
import sys,struct
from pathlib import Path
for name in sys.argv[1:]:
 p=Path(name);b=bytearray(p.read_bytes());shoff=struct.unpack_from('<Q',b,40)[0];ents,n=struct.unpack_from('<HH',b,58)
 for k in range(n):
  v=struct.unpack_from('<IIQQQQIIQQ',b,shoff+ents*k)
  if v[1]!=6:continue
  start,size=v[4],v[5];i=start
  while i<start+size:
   tag=struct.unpack_from('<Q',b,i)[0]
   if tag in (15,29):b[i:start+size]=b[i+16:start+size]+bytes(16)
   else:i+=16
 p.write_bytes(b)
PY
