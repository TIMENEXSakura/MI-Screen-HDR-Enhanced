#!/system/bin/sh
hdr_boot() { cat "${HDR_BOOT_ID_FILE:-/proc/sys/kernel/random/boot_id}"; }
hdr_mode() {
 local mode; mode=$(cat "$MODDIR/render-mode" 2>/dev/null)
 case "$mode" in gles|vulkan) echo "$mode";; *) echo default;; esac
}
hdr_resetprop() {
 if command -v resetprop >/dev/null 2>&1; then resetprop "$@"; return $?; fi
 local root="${HDR_ADB_ROOT:-/data/adb}" binary
 for binary in "$root/ksu/bin/ksud" "$root/ap/bin/apd" "$root/apd" "$root/magisk/magisk"; do
  [ -x "$binary" ] || continue
  "$binary" resetprop "$@" && return 0
 done
 return 1
}
hdr_supported() {
 local library property=0 vulkan=0
 # No model allowlist; inspect the actual libraries and known partition layouts.
 for library in ${HDR_TEST_LIBRARIES:-/system_ext/lib64/libsurfaceflinger.so /system/system_ext/lib64/libsurfaceflinger.so /system/lib64/libsurfaceflinger.so /system/lib/libsurfaceflinger.so /system_ext/lib/libsurfaceflinger.so /vendor/lib64/libsurfaceflinger.so /product/lib64/libsurfaceflinger.so /system/lib64/librenderengine.so /system/lib/librenderengine.so /system_ext/lib64/librenderengine.so}; do
  [ -f "$library" ] || continue
  grep -a -q 'debug.renderengine.backend' "$library" && property=1
  grep -a -q 'SkiaVkRenderEngine' "$library" && vulkan=1
 done
 [ "$property" = 1 ] && [ "$vulkan" = 1 ]
}
hdr_conflict() {
 local directory root="${HDR_ADB_ROOT:-/data/adb}"
 for directory in "$root/modules"/*; do
  [ -d "$directory" ] && [ "$directory" != "$MODDIR" ] || continue
  [ ! -e "$directory/disable" ] && [ ! -e "$directory/remove" ] || continue
  case "$(cat "$directory/communication.protocol" 2>/dev/null)" in
   mi-recorder-render/1|mi-screen-hdr-enhanced/1)
    case "$(cat "$directory/render-mode" 2>/dev/null)" in gles|vulkan) return 0;; esac;;
  esac
  if [ -f "$directory/system.prop" ] && grep -q '^[[:space:]]*debug.renderengine.backend=' "$directory/system.prop"; then return 0; fi
 done
 return 1
}
hdr_actual() {
 local dump; dump=$(timeout 8 dumpsys SurfaceFlinger 2>/dev/null) || { echo unknown; return; }
 local value; value=$(printf '%s\n' "$dump" | sed -n '/RE GLES /{s/.*/gles/p;q;};/RE Vulkan /{s/.*/vulkan/p;q;};/RE VK /{s/.*/vulkan/p;q;}')
 case "$value" in gles|vulkan) echo "$value";; *) echo unknown;; esac
}
hdr_publish() {
 (umask 077; printf 'boot=%s\ninstallation=%s\npid=%s\nstage=%s\n' "$(hdr_boot)" "$(cat "$MODDIR/installation.id")" "$(pidof surfaceflinger 2>/dev/null)" "$1" > "$MODDIR/boot-message.tmp") && mv -f "$MODDIR/boot-message.tmp" "$MODDIR/boot-message"
}
hdr_restore() {
 if [ -f "$MODDIR/pending" ] && [ "$(cat "$MODDIR/pending")" = "$(hdr_boot)" ]; then
  local original; original=$(cat "$MODDIR/original-property" 2>/dev/null)
  if [ -n "$original" ]; then hdr_resetprop -n debug.renderengine.backend "$original"; else hdr_resetprop --delete debug.renderengine.backend; fi
 fi
 printf 'default\n' > "$MODDIR/render-mode"
 printf '%s\n' "$1" > "$MODDIR/result"
 rm -f "$MODDIR/pending"
}
hdr_status() {
 local stage=waiting pid boot="$(hdr_boot)"
 pid=$(pidof surfaceflinger 2>/dev/null)
 if [ -f "$MODDIR/boot-message" ] && [ "$(sed -n 's/^boot=//p' "$MODDIR/boot-message")" = "$boot" ] && [ "$(sed -n 's/^installation=//p' "$MODDIR/boot-message")" = "$(cat "$MODDIR/installation.id")" ]; then
  stage=$(sed -n 's/^stage=//p' "$MODDIR/boot-message")
  [ -n "$pid" ] && [ "$(sed -n 's/^pid=//p' "$MODDIR/boot-message")" = "$pid" ] || stage=stale
 fi
 [ ! -e "$MODDIR/disable" ] || stage=disabled
 [ ! -e "$MODDIR/remove" ] || stage=removing
 printf 'protocol=mi-recorder-render/1\nchallenge=%s\nboot=%s\ninstallation=%s\n' "$1" "$boot" "$(cat "$MODDIR/installation.id")"
 printf 'stage=%s\nselected=%s\nactual=%s\nresult=%s\n' "$stage" "$(hdr_mode)" "$(hdr_actual)" "$(cat "$MODDIR/result" 2>/dev/null)"
 if hdr_supported; then echo supported=true; else echo supported=false; fi
}
