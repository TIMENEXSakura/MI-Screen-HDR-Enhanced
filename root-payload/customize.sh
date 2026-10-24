# No device/model/Android/firmware allowlist.
[ "$BOOTMODE" = true ] || abort "Install from the running Android system."
[ "${KSU_LATE_LOAD:-0}" != 1 ] || abort "Boot-time mounting unavailable in late-load mode."
[ "${APATCH_LATE_LOAD:-0}" != 1 ] || abort "Boot-time mounting unavailable in late-load mode."
MODDIR=$MODPATH
. "$MODPATH/common.sh"
[ "$HDR_CONFIG_OK" = 1 ] || abort "Missing or invalid library target; regenerate in the Xposed APK."
HDR_BACKEND_CHECK=$(hdr_backend_check) || abort "$HDR_BACKEND_CHECK"
hdr_backend_report > "$MODPATH/mount-backend.txt"
[ -f "$HDR_TARGET" ] || abort "SurfaceFlinger library unavailable; existing setup retained."
hdr_files_ok || abort "Generated module is incomplete or damaged; regenerate in the Xposed APK."
HDR_VISIBLE=$(hdr_sha "$HDR_TARGET")
case "$HDR_VISIBLE" in "$HDR_ORIGINAL"|"$HDR_PATCHED") ;;
 *) abort "System library changed after generation; regenerate in the Xposed APK.";;
esac
set_perm_recursive "$MODPATH" 0 0 0755 0644
for script in post-fs-data.sh service.sh boot-completed.sh action.sh; do set_perm "$MODPATH/$script" 0 0 0755; done
set_perm "$MODPATH/bin/hdrpatch" 0 0 0755
set_perm "$MODPATH/$HDR_PAYLOAD_REL" 0 0 0644 u:object_r:system_file:s0
set_perm "$MODPATH/backup/original.so" 0 0 0644 u:object_r:system_file:s0
# Skip markers may be owned by a provider; the fresh generated ZIP has none.
for marker in boot-pending rollback.txt; do
 [ ! -f "$MODPATH/$marker" ] || unlink "$MODPATH/$marker"
done
case "$(getprop persist.sys.locale)" in zh*) sed -i 's/^name=.*/name=小米截屏HDR增强/' "$MODPATH/module.prop";; esac
ui_print "- Device library patched; original library backed up."
ui_print "- Target: $HDR_TARGET"
ui_print "- Root: $HDR_FRAMEWORK; mount provider: $HDR_BACKEND $HDR_META_ID"
ui_print "- Pending boot validation; no SurfaceFlinger restart performed."
