#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
[ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || exit 0
if [ -f "$MODDIR/pending" ]; then
 hdr_restore previous_boot_failed
 exit 0
fi
mode=$(hdr_mode)
[ "$mode" != default ] || exit 0
if hdr_conflict; then echo conflict > "$MODDIR/result"; exit 0; fi
if ! hdr_supported; then echo unsupported > "$MODDIR/result"; exit 0; fi
getprop debug.renderengine.backend > "$MODDIR/original-property"
printf '%s\n' "$mode" > "$MODDIR/attempted"
hdr_boot > "$MODDIR/pending"
case "$mode" in gles) value=skiaglthreaded;; vulkan) value=skiavkthreaded;; esac
if ! hdr_resetprop -n debug.renderengine.backend "$value"; then hdr_restore property_error; exit 0; fi
echo pending > "$MODDIR/result"
hdr_publish prepared
