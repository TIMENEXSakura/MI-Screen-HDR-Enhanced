#!/system/bin/sh
# Resolve an exported SO on the installation device. Never use the exporting
# device's model, system path, process maps or mount-provider configuration.
hdr_offline_fail() {
 HDR_OFFLINE_ERROR="$1"
 printf 'ERROR: %s\n' "$1" >> "$MODDIR/offline-install.txt"
 rm -f "$MODDIR/.offline-candidates" "$MODDIR/.offline-directories" "$MODDIR/.offline-matches"
 return 1
}
hdr_offline_identity() { stat -L -c '%d:%i' "$1" 2>/dev/null; }
hdr_offline_prepare() {
 local root partition abi directory identity candidate target payload size digest canonical
 local original patched original_size patched_size matched found count=0
 local list="$MODDIR/.offline-candidates" directories="$MODDIR/.offline-directories" matches="$MODDIR/.offline-matches"
 HDR_OFFLINE_ERROR=
 : > "$MODDIR/offline-install.txt" || { HDR_OFFLINE_ERROR='无法写入离线安装记录。'; return 1; }
 [ "$(cat "$MODDIR/offline-export" 2>/dev/null)" = 1 ] || { hdr_offline_fail 'OFFLINE_INVALID: 离线模块标记无效，请重新导出。'; return 1; }
 for candidate in target.path payload.path mount-backend.txt; do
  [ ! -e "$MODDIR/$candidate" ] || { hdr_offline_fail 'OFFLINE_INVALID: 离线包包含预设的设备信息，请重新导出。'; return 1; }
 done
 for candidate in backup/original.so offline/patched.so original.sha256 patched.sha256 patch.txt generated.txt; do
  [ -f "$MODDIR/$candidate" ] && [ ! -L "$MODDIR/$candidate" ] || { hdr_offline_fail "OFFLINE_INVALID: 模块缺少或含有无效文件 $candidate。"; return 1; }
 done
 original=$(cat "$MODDIR/original.sha256")
 patched=$(cat "$MODDIR/patched.sha256")
 case "$original$patched" in *[!0-9a-f]*) hdr_offline_fail 'OFFLINE_INVALID: 库校验值格式错误。'; return 1;; esac
 [ "${#original}" = 64 ] && [ "${#patched}" = 64 ] && [ "$original" != "$patched" ] || { hdr_offline_fail 'OFFLINE_INVALID: 原版与修补库校验值无效。'; return 1; }
 [ "$(hdr_sha "$MODDIR/backup/original.so")" = "$original" ] && [ "$(hdr_sha "$MODDIR/offline/patched.so")" = "$patched" ] || { hdr_offline_fail 'OFFLINE_DAMAGED: 模块内的原版或修补库校验失败，请重新导出。'; return 1; }
 original_size=$(stat -L -c '%s' "$MODDIR/backup/original.so") || { hdr_offline_fail 'OFFLINE_INVALID: 无法读取原版库大小。'; return 1; }
 patched_size=$(stat -L -c '%s' "$MODDIR/offline/patched.so") || { hdr_offline_fail 'OFFLINE_INVALID: 无法读取修补库大小。'; return 1; }
 [ "$original_size" -gt 0 ] && [ "$original_size" = "$patched_size" ] || { hdr_offline_fail 'OFFLINE_INVALID: 修补前后库大小不一致。'; return 1; }
 # Only the isolated host tests set this variable; it is never read from the ZIP.
 root=${HDR_OFFLINE_SCAN_ROOT:-/}
 root=$(readlink -f "$root") || { hdr_offline_fail 'OFFLINE_SCAN_FAILED: 无法读取系统根目录。'; return 1; }
 [ -d "$root" ] || { hdr_offline_fail 'OFFLINE_SCAN_FAILED: 系统根目录不存在。'; return 1; }
 [ "$root" != / ] || root=
 : > "$directories" && : > "$matches" || { hdr_offline_fail 'OFFLINE_SCAN_FAILED: 无法创建安装定位记录。'; return 1; }
 ui_print '- 正在目标设备查找与所选 SO 完全对应的系统运行库…'
 for partition in /system /system_ext /product /vendor /system/system_ext /system/product /system/vendor /system_root/system /system_root/system_ext /system_root/product /system_root/vendor; do
  for abi in lib64 lib; do
   directory="$root$partition/$abi"
   [ -d "$directory" ] || continue
   identity=$(hdr_offline_identity "$directory") || { hdr_offline_fail "OFFLINE_SCAN_FAILED: 无法读取目录 $partition/$abi。"; return 1; }
   grep -Fqx "$identity" "$directories" && continue
   printf '%s\n' "$identity" >> "$directories"
   printf 'SEARCH directory=%s\n' "$partition/$abi" >> "$MODDIR/offline-install.txt"
   find -L "$directory" -type f -name 'lib*.so' -print > "$list" 2>> "$MODDIR/offline-install.txt" || { hdr_offline_fail "OFFLINE_SCAN_FAILED: 无法完整扫描 $partition/$abi，已停止安装。"; return 1; }
   while IFS= read -r candidate; do
    [ -f "$candidate" ] || { hdr_offline_fail 'OFFLINE_SCAN_FAILED: 扫描期间库文件发生变化，已停止安装。'; return 1; }
    size=$(stat -L -c '%s' "$candidate" 2>/dev/null) || { hdr_offline_fail 'OFFLINE_SCAN_FAILED: 无法读取候选库大小。'; return 1; }
    [ "$size" = "$original_size" ] || continue
    digest=$(hdr_sha "$candidate") || { hdr_offline_fail 'OFFLINE_SCAN_FAILED: 无法完整读取候选库。'; return 1; }
    case "$digest" in "$original"|"$patched") ;; *) continue;; esac
    target=${candidate#"$root"}
    canonical=$(readlink -f "$candidate") || { hdr_offline_fail 'OFFLINE_SCAN_FAILED: 无法确认匹配库的真实路径。'; return 1; }
    case "$canonical" in "$root"/*) canonical=${canonical#"$root"};; *) hdr_offline_fail 'OFFLINE_TARGET_UNSUPPORTED: 匹配库指向扫描根目录之外。'; return 1;; esac
    # Prefer the physical, mountable path for file/directory symlinks, so an
    # alias under /system cannot hide the implementation loaded from /vendor.
    if hdr_payload_for "$canonical" >/dev/null; then
     target=$canonical
    else
     case "$canonical" in /system_root/*) ;; *) hdr_offline_fail 'OFFLINE_TARGET_UNSUPPORTED: 匹配库的真实位置不支持标准系统挂载。'; return 1;; esac
    fi
    # system_root is an alias only when the standard mountable path resolves to
    # the same underlying file. Never invent a path for an unsupported layout.
    case "$target" in /system_root/*)
     matched=${target#/system_root}
     identity=$(hdr_offline_identity "$candidate") || { hdr_offline_fail 'OFFLINE_SCAN_FAILED: 无法读取匹配库标识。'; return 1; }
     [ "$identity" = "$(hdr_offline_identity "$root$matched")" ] || { hdr_offline_fail 'OFFLINE_TARGET_UNSUPPORTED: 匹配库没有可用的标准系统挂载路径。'; return 1; }
     target=$matched;;
    esac
    payload=$(hdr_payload_for "$target") || { hdr_offline_fail "OFFLINE_TARGET_UNSUPPORTED: 匹配库路径不能用于安全挂载：$target"; return 1; }
    identity=$(hdr_offline_identity "$candidate") || { hdr_offline_fail 'OFFLINE_SCAN_FAILED: 无法读取匹配库标识。'; return 1; }
    if grep -Fqx "$identity" "$matches"; then
     printf 'ALIAS target=%s identity=%s\n' "$target" "$identity" >> "$MODDIR/offline-install.txt"
     continue
    fi
    printf '%s\n' "$identity" >> "$matches"
    count=$((count+1))
    found=$target
    printf 'MATCH target=%s payload=%s sha256=%s identity=%s\n' "$target" "$payload" "$digest" "$identity" >> "$MODDIR/offline-install.txt"
   done < "$list"
  done
 done
 [ "$count" -gt 0 ] || { hdr_offline_fail 'OFFLINE_LIBRARY_NOT_FOUND: 此设备未找到与所选 SO 完全对应的系统库；可能来自其他系统版本，请用目标设备当前系统的原始库重新导出。'; return 1; }
 [ "$count" = 1 ] || { hdr_offline_fail 'OFFLINE_LIBRARY_AMBIGUOUS: 多个独立系统库与所选 SO 匹配，无法唯一确定挂载位置，已停止安装。'; return 1; }
 payload=$(hdr_payload_for "$found") || { hdr_offline_fail 'OFFLINE_TARGET_UNSUPPORTED: 无法确定标准挂载路径。'; return 1; }
 digest=$(hdr_sha "$root$found") || { hdr_offline_fail 'OFFLINE_LIBRARY_CHANGED: 安装定位后无法读取目标库。'; return 1; }
 case "$digest" in "$original"|"$patched") ;; *) hdr_offline_fail 'OFFLINE_LIBRARY_CHANGED: 定位期间系统库已变化，已停止安装。'; return 1;; esac
 [ ! -e "$MODDIR/$payload" ] || { hdr_offline_fail 'OFFLINE_INVALID: 挂载目录中存在多余库文件，请重新导出。'; return 1; }
 mkdir -p "$MODDIR/${payload%/*}" && mv "$MODDIR/offline/patched.so" "$MODDIR/$payload" || { hdr_offline_fail 'OFFLINE_WRITE_FAILED: 无法准备挂载库文件。'; return 1; }
 printf '%s\n' "$found" > "$MODDIR/target.path" && printf '%s\n' "$payload" > "$MODDIR/payload.path" || { hdr_offline_fail 'OFFLINE_WRITE_FAILED: 无法保存目标设备的挂载位置。'; return 1; }
 # HDR_TARGET differs from the stored Android path only in isolated fixtures.
 HDR_TARGET="$root$found"
 rm -f "$MODDIR/offline-export" "$list" "$directories" "$matches" || { hdr_offline_fail 'OFFLINE_WRITE_FAILED: 无法清理离线定位状态。'; return 1; }
 rmdir "$MODDIR/offline" 2>/dev/null || :
 printf 'RESOLVED target=%s payload=%s\n' "$found" "$payload" >> "$MODDIR/offline-install.txt"
 ui_print "- 已找到对应库：$found"
 ui_print '- 校验通过，正在按目标设备的 Root 挂载方式安装。'
}
