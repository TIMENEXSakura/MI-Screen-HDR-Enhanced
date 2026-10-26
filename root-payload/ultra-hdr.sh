#!/system/bin/sh
# Installation-time configuration only. Generating/exporting a ZIP never calls
# this function. Use the active provider to create missing persistent properties.
hdr_enable_ultra_hdr() {
 local property=persist.sys.support_ultra_hdr before after
 before=$(getprop "$property") || {
  echo 'ULTRA_HDR_PROPERTY status=failed reason=read_failed'; return 1;
 }
 if [ "$before" = true ]; then
  echo 'ULTRA_HDR_PROPERTY status=already_enabled value=true'
  return 0
 fi
 printf 'ULTRA_HDR_PROPERTY before=%s action=enable_persistent\n' "${before:-missing}"
 if ! hdr_resetprop -n -p "$property" true; then
  echo 'ULTRA_HDR_PROPERTY status=failed reason=write_failed'; return 1
 fi
 after=$(getprop "$property") || {
  echo 'ULTRA_HDR_PROPERTY status=failed reason=readback_failed'; return 1;
 }
 if [ "$after" != true ]; then
  printf 'ULTRA_HDR_PROPERTY status=failed reason=verification_failed value=%s\n' "${after:-missing}"
  return 1
 fi
 echo 'ULTRA_HDR_PROPERTY status=enabled value=true persistent_write=true readback=verified'
}
