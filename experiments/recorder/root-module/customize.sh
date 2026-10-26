[ "$BOOTMODE" = true ] || abort "请在运行中的 Android 内安装。"
[ "${KSU_LATE_LOAD:-0}" != 1 ] || abort "此测试需要开机阶段运行，不支持临时加载。"
[ "${APATCH_LATE_LOAD:-0}" != 1 ] || abort "此测试需要开机阶段运行，不支持临时加载。"
set_perm_recursive "$MODPATH" 0 0 0755 0644
for file in post-fs-data.sh service.sh control.sh; do set_perm "$MODPATH/$file" 0 0 0755; done
printf 'default\n' > "$MODPATH/render-mode"
cat /proc/sys/kernel/random/uuid > "$MODPATH/installation.id" || abort "无法建立通讯标识。"
chmod 0600 "$MODPATH/render-mode" "$MODPATH/installation.id"
ui_print "- 已安装系统渲染后端测试支持，默认遵循系统配置。"
ui_print "- 不替换 SO，不修改小米录屏，不要求挂载引擎。"
ui_print "- 请在录屏测试 APK 中选择 GLES / Vulkan，再手动重启。"
