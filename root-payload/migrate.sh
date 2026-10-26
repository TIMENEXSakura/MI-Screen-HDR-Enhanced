#!/system/bin/sh
# Installation only, after all payload/property checks. Keep old backups;
# retire both active and queued legacy overlays for the next boot.
hdr_migrate_legacy_modules() (
 local journal root id directory marker path
 journal=$(mktemp "$MODDIR/.migration.XXXXXX") || return 1
 hdr_migration_failed() {
  while IFS= read -r path; do unlink "$path" 2>/dev/null || :; done < "$journal"
  rm -f "$journal"
  echo 'MODULE_MIGRATION failed; newly created legacy markers rolled back'
  exit 1
 }
 for root in "$HDR_ADB_ROOT/modules" "$HDR_ADB_ROOT/modules_update"; do
  for id in xiaomi17u_hdr_clamp_test hdr_screenshot_tonemap; do
   directory="$root/$id"
   [ -f "$directory/module.prop" ] || continue
   [ "$(hdr_prop "$directory/module.prop" id)" = "$id" ] || continue
   [ ! -L "$directory" ] && [ ! -L "$directory/module.prop" ] || hdr_migration_failed
   for marker in disable skip_mount skip_mountify; do
    path="$directory/$marker"
    [ ! -L "$path" ] || hdr_migration_failed
    if [ -e "$path" ]; then [ -f "$path" ] || hdr_migration_failed; continue; fi
    printf '%s\n' "$path" >> "$journal" || hdr_migration_failed
    touch "$path" || hdr_migration_failed
   done
   echo "MODULE_MIGRATION legacy=$directory state=disabled backups=retained"
  done
 done
 rm -f "$journal"
)
