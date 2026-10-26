#!/system/bin/sh
. "$MODDIR/backend.sh"
. "$MODDIR/render-control.sh"
hdr_payload_for() {
 local target="$1"
 case "$target" in *[!A-Za-z0-9_./+@-]*|*'/../'*|*'/./'*|*'//'*) return 1;; esac
 case "$target" in */lib*.so) ;; *) return 1;; esac
 case "$target" in
  /system/lib/*|/system/lib64/*|/system/system_ext/lib/*|/system/system_ext/lib64/*|/system/product/lib/*|/system/product/lib64/*|/system/vendor/lib/*|/system/vendor/lib64/*) printf '%s\n' "${target#/}";;
  /system_ext/lib/*|/system_ext/lib64/*|/product/lib/*|/product/lib64/*|/vendor/lib/*|/vendor/lib64/*) printf 'system%s\n' "$target";;
  *) return 1;;
 esac
}
hdr_load_target() {
 [ -f "$MODDIR/target.path" ] && [ -f "$MODDIR/payload.path" ] || return 1
 local target payload expected
 target=$(cat "$MODDIR/target.path")
 payload=$(cat "$MODDIR/payload.path")
 expected=$(hdr_payload_for "$target") || return 1
 [ "$payload" = "$expected" ] || return 1
 # Explicit HDR_TARGET overrides are used by isolated host tests.
 HDR_TARGET=${HDR_TARGET:-$target}
 HDR_PACKAGE_PAYLOAD_REL=$payload
 HDR_PAYLOAD_REL=$payload
}
HDR_CONFIG_OK=0
if hdr_load_target; then HDR_CONFIG_OK=1; fi
hdr_sha() { local value; value=$(sha256sum "$1" 2>/dev/null) || return 1; printf '%s\n' "${value%% *}"; }
# Installation-only migration from an older compatible patch. Restore a copy,
# then require byte-exact stock SHA-256; no live write or hash-only exemption.
hdr_install_matches() {
 local file="$1" digest="$2" original="$3" patched="$4" work normalized after
 case "$digest" in "$original"|"$patched") return 0;; esac
 [ -f "$MODDIR/bin/hdrpatch" ] && [ ! -L "$MODDIR/bin/hdrpatch" ] || return 1
 chmod 0755 "$MODDIR/bin/hdrpatch" || return 1
 work=$(mktemp -d "$MODDIR/.verify.XXXXXX") || return 1
 normalized=
 if "$MODDIR/bin/hdrpatch" restore "$file" "$work/original.so" > "$work/probe.txt" 2>&1; then
  normalized=$(hdr_sha "$work/original.so")
 fi
 after=$(hdr_sha "$file")
 rm -f "$work/original.so" "$work/probe.txt"; rmdir "$work"
 [ "$after" = "$digest" ] && [ "$normalized" = "$original" ]
}
hdr_error() {
 render_restore failed
 printf 'ERROR: %s\n' "$1" >> "$MODDIR/rollback.txt"
 touch "$MODDIR/disable" "$MODDIR/skip_mount" "$MODDIR/skip_mountify"
}
hdr_files_ok() {
 [ "$HDR_CONFIG_OK" = 1 ] || return 1
 [ -f "$MODDIR/original.sha256" ] && [ -f "$MODDIR/patched.sha256" ] || return 1
 HDR_ORIGINAL=$(cat "$MODDIR/original.sha256")
 HDR_PATCHED=$(cat "$MODDIR/patched.sha256")
 [ "${#HDR_ORIGINAL}" = 64 ] && [ "${#HDR_PATCHED}" = 64 ] || return 1
 [ "$(hdr_sha "$MODDIR/backup/original.so")" = "$HDR_ORIGINAL" ] || return 1
 # Magisk/Mountify retain system/<partition>; other installers may move it to
 # <partition> and optionally leave a compatibility symlink behind.
 local relative moved found
 relative=$HDR_PACKAGE_PAYLOAD_REL
 moved= found=
 case "$relative" in system/system_ext/*|system/product/*|system/vendor/*) moved=${relative#system/};; esac
 for relative in "$HDR_PACKAGE_PAYLOAD_REL" "$moved"; do
  [ -n "$relative" ] && [ -f "$MODDIR/$relative" ] || continue
  [ "$(hdr_sha "$MODDIR/$relative")" = "$HDR_PATCHED" ] || return 1
  [ -n "$found" ] || found=$relative
 done
 [ -n "$found" ] || return 1
 HDR_PAYLOAD_REL=$found
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
