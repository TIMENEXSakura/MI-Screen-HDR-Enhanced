"""Exercise install-time property creation with isolated mock property storage."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
passed=0
with tempfile.TemporaryDirectory(prefix='hdr-property-') as temp:
    base=Path(temp)
    def fixture(name,before=None,behavior='ok',valid=True):
        folder=base/name;folder.mkdir();module=folder/'module';shutil.copytree(ROOT/'root-payload',module)
        target=folder/'visible.so';target.write_bytes(b'stock' if valid else b'wrong')
        for name,data in {
            'target.path':b'/system_ext/lib64/libsurfaceflinger.so\n',
            'payload.path':b'system/system_ext/lib64/libsurfaceflinger.so\n',
            'backup/original.so':b'stock','system/system_ext/lib64/libsurfaceflinger.so':b'patched',
            'original.sha256':hashlib.sha256(b'stock').hexdigest().encode(),
            'patched.sha256':hashlib.sha256(b'patched').hexdigest().encode(),
        }.items():
            file=module/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes(data)
        # No genuine executable is needed for hash-matching stock input. An
        # unknown input must fail migration and never reach property writes.
        (module/'bin').mkdir(exist_ok=True);(module/'bin/hdrpatch').write_text('#!/bin/sh\nexit 1\n')
        mock=folder/'mock';mock.mkdir()
        for name,body in {
            'getprop':'''[ "$1" = persist.sys.support_ultra_hdr ] || exit 0
[ "$BEHAVIOR" != read_error ] || exit 8
if [ -f "$TEST_FOLDER/value" ]; then cat "$TEST_FOLDER/value"; fi
''',
            'resetprop':'''printf '%s\n' "$*" >> "$TEST_FOLDER/writes"
[ "$*" = '-n -p persist.sys.support_ultra_hdr true' ] || exit 9
[ "$BEHAVIOR" != write_error ] || exit 7
[ "$BEHAVIOR" != no_effect ] || exit 0
printf true > "$TEST_FOLDER/value"
printf true > "$TEST_FOLDER/persistent"
''',
        }.items():
            file=mock/name;file.write_text('#!/bin/sh\n'+body);file.chmod(0o755)
        if before is not None:(folder/'value').write_text(before)
        env=dict(os.environ,PATH=str(mock)+':/usr/bin:/bin',MODPATH=str(module),HDR_TARGET=str(target),
                 HDR_ADB_ROOT=str(folder/'adb'),MAGISK_VER_CODE='30000',KSU='false',APATCH='false',
                 BOOTMODE='true',TEST_FOLDER=str(folder),BEHAVIOR=behavior)
        return folder,module,env
    def install(state,success):
        global passed
        folder,module,env=state
        command='''abort() { printf '%s\\n' "$*"; exit 33; }
set_perm_recursive() { :; }
set_perm() { :; }
ui_print() { printf '%s\\n' "$*"; }
. "$MODPATH/customize.sh"
'''
        r=subprocess.run(['/bin/sh','-c',command],env=env,capture_output=True,text=True)
        assert r.returncode==(0 if success else 33),(r.stdout,r.stderr)
        passed+=1
        return r.stdout
    for before in [None,'false','0','1','disabled']:
        state=fixture('enable-'+str(before),before)
        log=install(state,True);folder,module,_=state
        assert (folder/'value').read_text()=='true' and (folder/'persistent').read_text()=='true'
        assert (folder/'writes').read_text().splitlines()==['-n -p persist.sys.support_ultra_hdr true']
        assert 'readback=verified' in log and 'status=enabled' in (module/'ultra-hdr-install.txt').read_text()
        install(state,True)
        assert len((folder/'writes').read_text().splitlines())==1, 'repeat install must be idempotent'
    state=fixture('enabled','true');log=install(state,True)
    assert 'status=already_enabled' in log and not (state[0]/'writes').exists()
    for behavior in ['read_error','write_error','no_effect']:
        state=fixture(behavior,'false',behavior);log=install(state,False)
        assert 'status=failed' in log and '开关已确认开启' not in log
        assert (state[0]/'value').read_text()=='false'
    state=fixture('invalid-library',None,valid=False);install(state,False)
    assert not (state[0]/'writes').exists() and not (state[1]/'ultra-hdr-install.txt').exists()
    state=fixture('not-booted',None);state[2]['BOOTMODE']='false';install(state,False)
    assert not (state[0]/'writes').exists()
print('ULTRA_HDR_INSTALL_CHECKS='+str(passed))
