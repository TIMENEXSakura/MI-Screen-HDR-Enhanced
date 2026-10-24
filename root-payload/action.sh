#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
printf 'MI Screen HDR Enhanced / 小米截屏HDR增强\n'
printf 'TARGET=%s\nPAYLOAD=%s\nTARGET_VALID=%s\n' "$HDR_TARGET" "$HDR_PAYLOAD_REL" "$HDR_CONFIG_OK"
for file in mount-backend.txt patch.txt boot-check.txt rollback.txt; do [ ! -f "$MODDIR/$file" ] || cat "$MODDIR/$file"; done
printf 'VISIBLE_LIBRARY_SHA256=%s\n' "$(hdr_sha "$HDR_TARGET")"
printf 'SF_PID=%s\n' "$(pidof surfaceflinger)"
printf 'LOCAL_TONEMAP=%s\n' "$(getprop debug.sf.local_tonemap_screenshots)"
printf 'TRUE_HDR=%s\n' "$(getprop debug.sf.true_hdr_screenshots)"
for marker in disable remove boot-pending; do [ ! -e "$MODDIR/$marker" ] || printf 'STATE=%s\n' "$marker"; done
