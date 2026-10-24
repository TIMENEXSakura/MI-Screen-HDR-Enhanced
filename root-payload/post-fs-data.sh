#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
if [ -f "$MODDIR/boot-pending" ]; then
 hdr_error 'Previous boot did not complete validation; stopped patch mounting.'
 exit 0
fi
if ! hdr_files_ok; then hdr_error 'Patch or backup damaged; stopped patch mounting.'; exit 0; fi
hdr_visible=$(hdr_sha "$HDR_TARGET")
case "$hdr_visible" in "$HDR_ORIGINAL"|"$HDR_PATCHED") ;;
 *) hdr_error 'System library changed since generation; stopped stale overlay.'; exit 0;;
esac
cat /proc/sys/kernel/random/boot_id > "$MODDIR/boot-pending"
resetprop -n debug.sf.local_tonemap_screenshots true
resetprop -n debug.sf.true_hdr_screenshots true
printf 'PATCH_PREPARED; waiting for boot completion and process stability.\n' > "$MODDIR/boot-check.txt"
