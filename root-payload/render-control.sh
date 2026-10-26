#!/system/bin/sh
render_boot() { cat "${HDR_BOOT_ID_FILE:-/proc/sys/kernel/random/boot_id}"; }
render_mode() {
 local mode; mode=$(cat "$MODDIR/render-mode" 2>/dev/null)
 case "$mode" in gles|vulkan) echo "$mode";; *) echo default;; esac
}
render_resetprop() { hdr_resetprop "$@"; }
render_supported() {
 local library property=0 vulkan=0
 # No model allowlist; inspect the actual libraries and known partition layouts.
 for library in ${HDR_TEST_LIBRARIES:-/system_ext/lib64/libsurfaceflinger.so /system/system_ext/lib64/libsurfaceflinger.so /system/lib64/libsurfaceflinger.so /system/lib/libsurfaceflinger.so /system_ext/lib/libsurfaceflinger.so /vendor/lib64/libsurfaceflinger.so /product/lib64/libsurfaceflinger.so /system/lib64/librenderengine.so /system/lib/librenderengine.so /system_ext/lib64/librenderengine.so}; do
  [ -f "$library" ] || continue
  grep -a -q 'debug.renderengine.backend' "$library" && property=1
  grep -a -q 'SkiaVkRenderEngine' "$library" && vulkan=1
 done
 [ "$property" = 1 ] && [ "$vulkan" = 1 ]
}
render_conflict() {
 local directory root="${HDR_ADB_ROOT:-/data/adb}"
 for directory in "$root/modules"/*; do
  [ -d "$directory" ] && [ "$directory" != "$MODDIR" ] || continue
  [ ! -e "$directory/disable" ] && [ ! -e "$directory/remove" ] || continue
  case "$(cat "$directory/communication.protocol" 2>/dev/null)" in
   mi-recorder-render/1|mi-screen-hdr-enhanced/1|mi-screen-hdr-enhanced/2)
    case "$(cat "$directory/render-mode" 2>/dev/null)" in gles|vulkan) return 0;; esac;;
  esac
  if [ -f "$directory/system.prop" ] && grep -q '^[[:space:]]*debug.renderengine.backend=' "$directory/system.prop"; then return 0; fi
 done
 return 1
}
render_actual() {
 local dump; dump=$(timeout 8 dumpsys SurfaceFlinger 2>/dev/null) || { echo unknown; return; }
 local value; value=$(printf '%s\n' "$dump" | sed -n '/RE GLES /{s/.*/gles/p;q;};/RE Vulkan /{s/.*/vulkan/p;q;};/RE VK /{s/.*/vulkan/p;q;}')
 case "$value" in gles|vulkan) echo "$value";; *) echo unknown;; esac
}
render_publish() {
 (umask 077; printf 'boot=%s\ninstallation=%s\npid=%s\nstage=%s\nactual=%s\n' "$(render_boot)" "$(cat "$MODDIR/installation.id")" "$(pidof surfaceflinger 2>/dev/null)" "$1" "${2:-unknown}" > "$MODDIR/boot-message.tmp") && mv -f "$MODDIR/boot-message.tmp" "$MODDIR/boot-message"
}
render_restore() {
 if [ -f "$MODDIR/render-pending" ] && [ "$(cat "$MODDIR/render-pending")" = "$(render_boot)" ]; then
  local original; original=$(cat "$MODDIR/render-original-property" 2>/dev/null)
  if [ -n "$original" ]; then render_resetprop -n debug.renderengine.backend "$original"; else render_resetprop --delete debug.renderengine.backend; fi
 fi
 printf 'default\n' > "$MODDIR/render-mode"
 printf '%s\n' "$1" > "$MODDIR/render-result"
 rm -f "$MODDIR/render-pending"
}
render_status() {
 local stage=waiting actual=unknown pid boot="$(render_boot)"
 pid=$(pidof surfaceflinger 2>/dev/null)
 if [ -f "$MODDIR/boot-message" ] && [ "$(sed -n 's/^boot=//p' "$MODDIR/boot-message")" = "$boot" ] && [ "$(sed -n 's/^installation=//p' "$MODDIR/boot-message")" = "$(cat "$MODDIR/installation.id")" ]; then
  stage=$(sed -n 's/^stage=//p' "$MODDIR/boot-message")
  actual=$(sed -n 's/^actual=//p' "$MODDIR/boot-message")
  [ -n "$pid" ] && [ "$(sed -n 's/^pid=//p' "$MODDIR/boot-message")" = "$pid" ] || { stage=stale; actual=unknown; }
 fi
 [ ! -e "$MODDIR/disable" ] || stage=disabled
 [ ! -e "$MODDIR/remove" ] || stage=removing
 printf 'protocol=mi-screen-hdr-enhanced/2\nchallenge=%s\nboot=%s\ninstallation=%s\n' "$1" "$boot" "$(cat "$MODDIR/installation.id")"
 printf 'stage=%s\nselected=%s\nactual=%s\nresult=%s\n' "$stage" "$(render_mode)" "$actual" "$(cat "$MODDIR/render-result" 2>/dev/null)"
 printf 'supported=%s\n' "$(cat "$MODDIR/render-capability" 2>/dev/null || echo false)"
 render_child_status
}

render_prepare() {
 local mode value
 if [ -f "$MODDIR/render-pending" ]; then render_restore previous_boot_failed; return; fi
 mode=$(render_mode)
 [ "$mode" != default ] || return 0
 if render_conflict; then echo conflict > "$MODDIR/render-result"; return; fi
 if ! render_supported; then echo unsupported > "$MODDIR/render-result"; return; fi
 getprop debug.renderengine.backend > "$MODDIR/render-original-property"
 printf '%s\n' "$mode" > "$MODDIR/render-attempted"
 render_boot > "$MODDIR/render-pending"
 case "$mode" in gles) value=skiaglthreaded;; vulkan) value=skiavkthreaded;; esac
 if ! hdr_resetprop -n debug.renderengine.backend "$value"; then render_restore property_error; return; fi
 echo pending > "$MODDIR/render-result"
 render_publish prepared
}
render_finish() {
 local actual; actual=$(render_actual)
 if render_supported; then echo true > "$MODDIR/render-capability"; else echo false > "$MODDIR/render-capability"; fi
 if [ -f "$MODDIR/render-pending" ]; then
  if [ "$actual" = "$(cat "$MODDIR/render-attempted")" ]; then
   echo verified > "$MODDIR/render-result"; rm -f "$MODDIR/render-pending"
  else render_restore not_verified; fi
 fi
 render_publish ready "$actual"
}

render_report() {
 local phase="$1" backend="$2" pid="$3" name
 case "$phase" in loaded|prepared|recording|idle|error|unsupported) ;; *) echo error=invalid_phase; return 1;; esac
 case "$backend" in gles|vulkan|idle) ;; *) echo error=invalid_backend; return 1;; esac
 case "$pid" in ''|*[!0-9]*) echo error=invalid_pid; return 1;; esac
 name=$(tr '\000' '\n' < "${HDR_PROC_ROOT:-/proc}/$pid/cmdline" 2>/dev/null | head -n 1)
 case "$name" in com.miui.screenrecorder|com.miui.screenrecorder:*) ;; *) echo error=not_recorder; return 1;; esac
 (umask 077; printf 'boot=%s\npid=%s\nphase=%s\nbackend=%s\nat=%s\n' "$(render_boot)" "$pid" "$phase" "$backend" "$(date +%s)" > "$MODDIR/child-message.tmp") && mv -f "$MODDIR/child-message.tmp" "$MODDIR/child-message"
}
render_child_status() {
 local pid at now state=offline backend=idle phase=unknown name
 if [ -f "$MODDIR/child-message" ] && [ "$(sed -n 's/^boot=//p' "$MODDIR/child-message")" = "$(render_boot)" ]; then
  pid=$(sed -n 's/^pid=//p' "$MODDIR/child-message"); at=$(sed -n 's/^at=//p' "$MODDIR/child-message"); now=$(date +%s)
  case "$pid:$at" in *[!0-9:]*|:*) ;; *)
   name=$(tr '\000' '\n' < "${HDR_PROC_ROOT:-/proc}/$pid/cmdline" 2>/dev/null | head -n 1)
   case "$name" in com.miui.screenrecorder|com.miui.screenrecorder:*)
    if [ "$now" -ge "$at" ] && [ "$((now-at))" -le 35 ]; then
     state=online; phase=$(sed -n 's/^phase=//p' "$MODDIR/child-message"); backend=$(sed -n 's/^backend=//p' "$MODDIR/child-message")
    fi;; esac;;
  esac
 fi
 printf 'child=%s\nchild_phase=%s\nchild_backend=%s\n' "$state" "$phase" "$backend"
}
