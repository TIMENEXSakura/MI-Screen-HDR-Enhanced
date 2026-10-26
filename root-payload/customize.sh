# Generic clamp matching; private RGB support is fingerprint-locked in hdrpatch.
[ "$BOOTMODE" = true ] || abort "Install from the running Android system."
[ "${KSU_LATE_LOAD:-0}" != 1 ] || abort "Boot-time mounting unavailable in late-load mode."
[ "${APATCH_LATE_LOAD:-0}" != 1 ] || abort "Boot-time mounting unavailable in late-load mode."
MODDIR=$MODPATH
. "$MODPATH/common.sh"
if [ -e "$MODPATH/offline-export" ]; then
 [ -f "$MODPATH/offline.sh" ] || abort "离线模块缺少安装定位脚本，请重新导出。"
 . "$MODPATH/offline.sh"
 hdr_offline_prepare || abort "$HDR_OFFLINE_ERROR"
 HDR_CONFIG_OK=0
 if hdr_load_target; then HDR_CONFIG_OK=1; fi
fi
[ "$HDR_CONFIG_OK" = 1 ] || abort "Missing or invalid library target; regenerate in the Xposed APK."
HDR_BACKEND_CHECK=$(hdr_backend_check) || abort "$HDR_BACKEND_CHECK"
hdr_backend_report > "$MODPATH/mount-backend.txt"
[ -f "$HDR_TARGET" ] || abort "SurfaceFlinger library unavailable; existing setup retained."
hdr_files_ok || abort "Generated module is incomplete or damaged; regenerate in the Xposed APK."
HDR_VISIBLE=$(hdr_sha "$HDR_TARGET")
hdr_install_matches "$HDR_TARGET" "$HDR_VISIBLE" "$HDR_ORIGINAL" "$HDR_PATCHED" || abort "System library changed after generation; regenerate in the Xposed APK."
set_perm_recursive "$MODPATH" 0 0 0755 0644
for script in post-fs-data.sh service.sh boot-completed.sh action.sh control.sh; do set_perm "$MODPATH/$script" 0 0 0755; done
set_perm "$MODPATH/bin/hdrpatch" 0 0 0755
set_perm "$MODPATH/$HDR_PAYLOAD_REL" 0 0 0644 u:object_r:system_file:s0
set_perm "$MODPATH/backup/original.so" 0 0 0644 u:object_r:system_file:s0
printf 'default\n' > "$MODPATH/render-mode"
cat /proc/sys/kernel/random/uuid > "$MODPATH/installation.id" || abort "无法建立底层模块通讯标识。"
chmod 0600 "$MODPATH/render-mode" "$MODPATH/installation.id"
# Skip markers may be owned by a provider; the fresh generated ZIP has none.
for marker in boot-pending rollback.txt boot-message render-pending render-result render-attempted render-original-property; do
 [ ! -f "$MODPATH/$marker" ] || unlink "$MODPATH/$marker"
done
case "$(getprop persist.sys.locale)" in zh*) sed -i 's/^name=.*/name=小米截屏HDR增强/' "$MODPATH/module.prop";; esac
ui_print '- 正在检查 Ultra HDR 系统开关…'
[ -f "$MODPATH/ultra-hdr.sh" ] || abort "模块缺少 Ultra HDR 设置脚本，请重新生成。"
. "$MODPATH/ultra-hdr.sh"
if ! hdr_enable_ultra_hdr > "$MODPATH/ultra-hdr-install.txt" 2>&1; then
 cat "$MODPATH/ultra-hdr-install.txt"
 abort "无法启用或确认 persist.sys.support_ultra_hdr=true，安装未完成；请查看属性诊断。"
fi
cat "$MODPATH/ultra-hdr-install.txt"
ui_print '- Ultra HDR 系统开关已确认开启。'
ui_print "- Device library patched; original library backed up."
ui_print "- Target: $HDR_TARGET"
ui_print "- Root: $HDR_FRAMEWORK; mount provider: $HDR_BACKEND $HDR_META_ID"
ui_print "- Pending boot validation; no SurfaceFlinger restart performed."
