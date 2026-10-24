#!/system/bin/sh
# service.sh owns validation; do not clear a pending boot prematurely.
MODDIR=${0%/*}
printf 'Android boot-completed event received.\n' > "$MODDIR/boot-event.txt"
