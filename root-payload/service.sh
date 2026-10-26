#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
[ -f "$MODDIR/boot-pending" ] || exit 0
[ ! -e "$MODDIR/disable" ] || exit 0
if ! hdr_files_ok; then hdr_rollback 'Post-mount payload verification failed.'; exit 0; fi
if [ "$(hdr_sha "$HDR_TARGET")" != "$HDR_PATCHED" ]; then hdr_rollback 'Patched library was not mounted.'; exit 0; fi
hdr_last= hdr_changes=0 hdr_stable=0 hdr_elapsed=0
while [ "$hdr_elapsed" -lt 180 ]; do
 [ ! -e "$MODDIR/disable" ] || exit 0
 hdr_pid=$(pidof surfaceflinger 2>/dev/null)
 if [ -n "$hdr_pid" ] && [ "$hdr_pid" = "$hdr_last" ]; then
  hdr_stable=$((hdr_stable+1))
 else
  [ -z "$hdr_last" ] || hdr_changes=$((hdr_changes+1))
  hdr_stable=0
 fi
 hdr_last=$hdr_pid
 if [ "$hdr_changes" -ge 3 ]; then hdr_rollback 'SurfaceFlinger repeatedly restarted during boot.'; exit 0; fi
 if [ "$hdr_stable" -ge 15 ] && [ "$(getprop sys.boot_completed)" = 1 ]; then
  if [ "$(hdr_sha "$HDR_TARGET")" != "$HDR_PATCHED" ]; then hdr_rollback 'Overlay changed during startup.'; exit 0; fi
  render_finish
  unlink "$MODDIR/boot-pending"
  printf 'PASS: boot completed; SF stable; patch visible. HDR output still needs content testing.\n' > "$MODDIR/boot-check.txt"
  exit 0
 fi
 sleep 2
 hdr_elapsed=$((hdr_elapsed+2))
done
hdr_rollback 'Boot or SurfaceFlinger did not stabilize within 180 seconds.'
