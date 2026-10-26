#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common.sh"
[ "$(id -u)" = 0 ] || exit 1
case "${2:-}" in ''|*[!a-f0-9-]*) exit 1;; esac
[ "${#2}" = 36 ] || exit 1
case "${1:-}" in
 select)
  case "${3:-}" in default|gles|vulkan) ;; *) echo error=invalid_mode; exit 1;; esac
  [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || { echo error=disabled; exit 1; }
  [ ! -f "$MODDIR/render-pending" ] || { echo error=boot_busy; exit 1; }
  if [ "$3" != default ]; then
   render_supported || { echo error=unsupported; exit 1; }
   if render_conflict; then echo error=conflict; exit 1; fi
  fi
  (umask 077; printf '%s\n' "$3" > "$MODDIR/render-mode.tmp") && mv -f "$MODDIR/render-mode.tmp" "$MODDIR/render-mode" || exit 1
  printf 'queued\n' > "$MODDIR/render-result";;
 report) render_report "${3:-}" "${4:-}" "${5:-}" || exit 1;;
 status) ;;
 *) exit 1;;
esac
render_status "$2"
