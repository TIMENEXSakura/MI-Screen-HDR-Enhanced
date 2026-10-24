#!/system/bin/sh
HDR_TARGET=${HDR_TARGET:-/system_ext/lib64/libsurfaceflinger.so}
HDR_PAYLOAD_REL=system/system_ext/lib64/libsurfaceflinger.so
hdr_sha() { local value; value=$(sha256sum "$1" 2>/dev/null) || return 1; printf '%s\n' "${value%% *}"; }
hdr_error() {
 printf 'ERROR: %s\n' "$1" >> "$MODDIR/rollback.txt"
 touch "$MODDIR/disable" "$MODDIR/skip_mount" "$MODDIR/skip_mountify"
}
hdr_files_ok() {
 [ -f "$MODDIR/original.sha256" ] && [ -f "$MODDIR/patched.sha256" ] || return 1
 HDR_ORIGINAL=$(cat "$MODDIR/original.sha256")
 HDR_PATCHED=$(cat "$MODDIR/patched.sha256")
 [ "${#HDR_ORIGINAL}" = 64 ] && [ "${#HDR_PATCHED}" = 64 ] || return 1
 [ "$(hdr_sha "$MODDIR/backup/original.so")" = "$HDR_ORIGINAL" ] || return 1
 [ "$(hdr_sha "$MODDIR/$HDR_PAYLOAD_REL")" = "$HDR_PATCHED" ] || return 1
}
hdr_rollback() {
 hdr_error "$1"
 # Restore only our exact output. Never unmount shared directories or kill SF.
 if hdr_files_ok; then
  local visible; visible=$(hdr_sha "$HDR_TARGET")
  if [ "$visible" = "$HDR_PATCHED" ]; then
   if mount --bind "$MODDIR/backup/original.so" "$HDR_TARGET"; then
    printf 'Original restored for subsequent loads; existing mappings unchanged.\n' >> "$MODDIR/rollback.txt"
   else
    printf 'Live restore unavailable; disabled for next boot.\n' >> "$MODDIR/rollback.txt"
   fi
  fi
 fi
}
