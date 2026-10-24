#!/system/bin/sh
# Use the user's existing mount provider. Never install or reconfigure one here.
HDR_ADB_ROOT=${HDR_ADB_ROOT:-/data/adb}
hdr_framework() {
 if [ "${APATCH:-false}" = true ]; then echo APATCH
 elif [ "${KSU:-false}" = true ]; then echo KERNELSU
 elif [ -n "${MAGISK_VER_CODE:-}" ]; then echo MAGISK
 else
  case "$(su -v 2>/dev/null)" in
   *APatch*|*APATCH*|*KernelPatch*) echo APATCH;;
   *KernelSU*|*SukiSU*|*YukiSU*) echo KERNELSU;;
   *MAGISK*|*Magisk*|*magisk*) echo MAGISK;;
   *) echo UNKNOWN;;
  esac
 fi
}
hdr_prop() { sed -n "s/^$2=//p" "$1" 2>/dev/null | head -n 1 | tr -d '\r'; }
hdr_module_active() { [ -f "$1/module.prop" ] && [ ! -e "$1/disable" ] && [ ! -e "$1/remove" ]; }
hdr_backend() {
 HDR_FRAMEWORK=$(hdr_framework)
 HDR_BACKEND=framework-default
 HDR_META_ID=
 HDR_BACKEND_NOTE='Mounting is delegated to the existing provider; activation requires boot verification.'
 case "$HDR_FRAMEWORK" in KERNELSU|APATCH)
  if hdr_module_active "$HDR_ADB_ROOT/metamodule"; then
   HDR_META_ID=$(hdr_prop "$HDR_ADB_ROOT/metamodule/module.prop" id)
   HDR_BACKEND=metamodule
  fi;;
 esac
 if [ "$HDR_BACKEND" = framework-default ] && hdr_module_active "$HDR_ADB_ROOT/modules/mountify"; then
  if [ -f "$HDR_ADB_ROOT/modules/mountify/post-fs-data.sh" ] || [ -f "$HDR_ADB_ROOT/modules/mountify/metamount.sh" ]; then
   HDR_BACKEND=mountify; HDR_META_ID=mountify
  fi
 fi
 case "$HDR_META_ID" in *[!A-Za-z0-9._-]*) HDR_META_ID=unknown;; esac
}
hdr_backend_check() {
 hdr_backend
 # Mountify's documented manual selection is respected, never silently changed.
 if [ "$HDR_META_ID" = mountify ]; then
  local mode
  mode=$(sed 's/#.*//;s/[[:space:]]//g' "$HDR_ADB_ROOT/mountify/config.sh" 2>/dev/null | tr -d "\"'" | sed -n 's/^mountify_mounts=\([012]\)$/\1/p' | tail -n 1)
  if [ "$mode" = 0 ]; then echo 'MOUNT_BACKEND_DISABLED: Mountify mounting is disabled.'; return 1; fi
  if [ "$mode" = 1 ] && ! sed 's/#.*//' "$HDR_ADB_ROOT/mountify/modules.txt" 2>/dev/null | grep -q '^[[:space:]]*xiaomi17u_hdr_clamp_test[[:space:]]*$'; then
   echo 'MOUNT_BACKEND_EXCLUDED: Add xiaomi17u_hdr_clamp_test to the Mountify selected modules first.'; return 1
  fi
 fi
}
hdr_backend_report() {
 hdr_backend
 printf 'ROOT_FRAMEWORK=%s\nMOUNT_PROVIDER=%s\nMETAMODULE=%s\n' "$HDR_FRAMEWORK" "$HDR_BACKEND" "$HDR_META_ID"
 printf 'MOUNT_NOTE=%s\n' "$HDR_BACKEND_NOTE"
 if [ -n "${HDR_TARGET:-}" ]; then
  awk -v target="$HDR_TARGET" '
   { point=$5; if (point=="/" || target==point || index(target,point "/")==1) {
      if(length(point)>best) { best=length(point); for(i=7;i<=NF;i++)if($i=="-"){ info="VISIBLE_MOUNT=" point " FS=" $(i+1) " SOURCE=" $(i+2); break } }
   }} END { if(best)print info }' /proc/self/mountinfo 2>/dev/null
 fi
}
hdr_resetprop() {
 if command -v resetprop >/dev/null 2>&1; then resetprop "$@"; return $?; fi
 local base binary name
 case "$(hdr_framework)" in
  APATCH) base="$HDR_ADB_ROOT/ap/bin"; binary="$HDR_ADB_ROOT/apd"; name=apd;;
  KERNELSU) base="$HDR_ADB_ROOT/ksu/bin"; binary="$base/ksud"; name=ksud;;
  MAGISK) base="$HDR_ADB_ROOT/magisk"; binary="$base/magisk"; name=magisk;;
  *) return 1;;
 esac
 if [ -x "$base/resetprop" ]; then "$base/resetprop" "$@"; return $?; fi
 [ -x "$binary" ] || binary="$base/$name"
 [ -x "$binary" ] || binary=$(command -v "$name" 2>/dev/null)
 if [ "$name" = magisk ] && [ ! -x "$binary" ]; then
  for binary in /sbin/magisk /debug_ramdisk/magisk; do [ ! -x "$binary" ] || break; done
 fi
 [ -x "$binary" ] || return 1
 "$binary" resetprop "$@"
}
case "${1:-}" in describe) hdr_backend_report;; esac
