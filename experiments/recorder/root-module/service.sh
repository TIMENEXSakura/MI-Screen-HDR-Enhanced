#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
last= changes=0 stable=0 elapsed=0
while [ "$elapsed" -lt 180 ]; do
 [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || { hdr_restore disabled; exit 0; }
 pid=$(pidof surfaceflinger 2>/dev/null)
 if [ -n "$pid" ] && [ "$pid" = "$last" ]; then stable=$((stable+1)); else
  [ -z "$last" ] || changes=$((changes+1))
  stable=0
 fi
 last=$pid
 if [ "$changes" -ge 3 ]; then hdr_restore unstable; hdr_publish failed; exit 0; fi
 if [ "$stable" -ge 15 ] && [ "$(getprop sys.boot_completed)" = 1 ]; then
  if [ -f "$MODDIR/pending" ]; then
   actual=$(hdr_actual)
   if [ "$actual" = "$(cat "$MODDIR/attempted")" ]; then echo verified > "$MODDIR/result"; rm -f "$MODDIR/pending"
   else hdr_restore not_verified; fi
  fi
  hdr_publish ready
  exit 0
 fi
 sleep 2; elapsed=$((elapsed+2))
done
hdr_restore timeout
hdr_publish failed
