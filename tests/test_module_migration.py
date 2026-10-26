"""Exercise formal-ID migration without touching installed modules."""
from pathlib import Path
import os, shutil, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
checks=0
with tempfile.TemporaryDirectory(prefix='hdr-module-migration-') as temp:
    base=Path(temp)
    def fixture(name):
        work=base/name;work.mkdir();module=work/'new'
        shutil.copytree(ROOT/'root-payload',module)
        old=[]
        for parent in ['modules','modules_update']:
            for name in ['xiaomi17u_hdr_clamp_test','hdr_screenshot_tonemap']:
                path=work/'adb'/parent/name;path.mkdir(parents=True)
                (path/'module.prop').write_text('id='+name+'\n')
                (path/'backup.so').write_bytes(b'old backup')
                old.append(path)
        return work,module,old
    def run(state,success=True):
        global checks
        work,module,_=state
        env=dict(os.environ,MODDIR=str(module),HDR_ADB_ROOT=str(work/'adb'))
        result=subprocess.run(['/bin/sh','-c','. "$MODDIR/backend.sh"; . "$MODDIR/migrate.sh"; hdr_migrate_legacy_modules'],env=env,capture_output=True,text=True)
        assert (result.returncode==0)==success,(result.stdout,result.stderr)
        assert not list(module.glob('.migration.*'))
        checks+=1
    state=fixture('upgrade');run(state)
    assert 'id=mi_screen_hdr_enhanced\n' in (state[1]/'module.prop').read_text()
    for old in state[2]:
        assert all((old/name).is_file() for name in ['disable','skip_mount','skip_mountify'])
        assert (old/'backup.so').read_bytes()==b'old backup'
    assert not (state[1]/'disable').exists()
    marker=state[2][0]/'disable';stamp=marker.stat().st_mtime_ns
    run(state);assert marker.stat().st_mtime_ns==stamp
    state=fixture('conflicting-marker');(state[2][-1]/'skip_mount').mkdir()
    (state[2][0]/'disable').write_text('user disabled')
    run(state,False)
    assert (state[2][0]/'disable').read_text()=='user disabled'
    assert all(not (p/'skip_mountify').exists() for p in state[2])
    assert all(not (p/'disable').exists() for p in state[2][1:])
    state=fixture('symlink-marker');outside=state[0]/'outside';outside.write_text('keep')
    (state[2][-1]/'disable').symlink_to(outside);run(state,False)
    assert outside.read_text()=='keep' and (state[2][-1]/'disable').is_symlink()
    assert all(not (p/'disable').exists() for p in state[2][:-1])
    state=fixture('foreign-id')
    for p in state[2]:(p/'module.prop').write_text('id=unrelated\n')
    run(state);assert all(not (p/'disable').exists() for p in state[2])
    state=fixture('fresh-install');shutil.rmtree(state[0]/'adb');run(state)
print('MODULE_ID_MIGRATION_CHECKS='+str(checks))
