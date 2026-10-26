from pathlib import Path
import os, shutil, subprocess, tempfile, uuid

source=Path(__file__).resolve().parents[1]/'root-module'
nonce=str(uuid.uuid4())
with tempfile.TemporaryDirectory(prefix='recorder-control-') as directory:
    base=Path(directory)
    def fixture(name, actual='vulkan', scenario='stable'):
        root=base/name; module=root/'adb/modules/any-id'; shutil.copytree(source,module)
        (module/'installation.id').write_text(str(uuid.uuid4())+'\n')
        (module/'render-mode').write_text('default\n')
        (root/'boot-id').write_text(str(uuid.uuid4())+'\n')
        library=root/'library.so'; library.write_bytes(b'debug.renderengine.backend\0SkiaVkRenderEngine\0')
        mock=root/'mock';mock.mkdir()
        commands={
            'resetprop':'printf "%s\\n" "$*" >> "$TEST_ROOT/props"',
            'getprop':'case "$1" in sys.boot_completed) [ "$SCENARIO" = timeout ] && echo 0 || echo 1;; esac',
            'pidof':'''if [ "$SCENARIO" = crash ]; then n=0; [ ! -e "$TEST_ROOT/pid" ] || n=$(cat "$TEST_ROOT/pid"); n=$((n+1)); echo "$n" > "$TEST_ROOT/pid"; echo "$n"; else echo "${TEST_PID:-321}"; fi''',
            'dumpsys':'case "$ACTUAL" in vulkan) echo " ------------RE Vulkan (Ganesh)----------";; gles) echo " ------------RE GLES (Ganesh)----------";; *) echo unrelated;; esac',
            'sleep':':', 'id':'echo 0'
        }
        for name,body in commands.items():
            path=mock/name;path.write_text('#!/bin/sh\n'+body+'\n');path.chmod(0o755)
        env=dict(os.environ,PATH=str(mock)+':/usr/bin:/bin',MODDIR=str(module),HDR_ADB_ROOT=str(root/'adb'),HDR_BOOT_ID_FILE=str(root/'boot-id'),HDR_TEST_LIBRARIES=str(library),TEST_ROOT=str(root),ACTUAL=actual,SCENARIO=scenario)
        return root,module,env
    def run(module,env,script,*args,code=0):
        p=subprocess.run(['/bin/sh',str(module/script),*args],env=env,capture_output=True,text=True,timeout=10)
        assert p.returncode==code,(script,p.stdout,p.stderr)
        return dict(line.split('=',1) for line in p.stdout.splitlines() if '=' in line)
    root,module,env=fixture('default')
    run(module,env,'post-fs-data.sh');run(module,env,'service.sh')
    assert not (root/'props').exists()
    assert run(module,env,'control.sh','status',nonce)['stage']=='ready'
    run(module,env,'control.sh','select',nonce,'vulkan')
    assert not (root/'props').exists(), 'selection must never switch the live display backend'
    run(module,env,'post-fs-data.sh');assert 'skiavkthreaded' in (root/'props').read_text()
    run(module,env,'service.sh')
    fields=run(module,env,'control.sh','status',nonce)
    assert fields['actual']=='vulkan' and fields['result']=='verified' and fields['stage']=='ready'
    old=(module/'boot-message').read_text(); (root/'boot-id').write_text(str(uuid.uuid4())+'\n')
    assert run(module,env,'control.sh','status',nonce)['stage']=='waiting'
    (root/'boot-id').write_text(old.split('boot=')[1].splitlines()[0]+'\n')
    assert run(module,dict(env,TEST_PID='444'),'control.sh','status',nonce)['stage']=='stale'
    for actual,reason in [('gles','not_verified'),('unknown','not_verified')]:
        root,module,env=fixture('mismatch-'+actual,actual)
        run(module,env,'control.sh','select',nonce,'vulkan');run(module,env,'post-fs-data.sh');run(module,env,'service.sh')
        assert (module/'render-mode').read_text().strip()=='default'
        assert (module/'result').read_text().strip()==reason
        assert '--delete debug.renderengine.backend' in (root/'props').read_text()
    for scenario in ['crash','timeout']:
        root,module,env=fixture(scenario,scenario=scenario)
        run(module,env,'control.sh','select',nonce,'vulkan');run(module,env,'post-fs-data.sh');run(module,env,'service.sh')
        assert (module/'render-mode').read_text().strip()=='default'
        assert not (module/'pending').exists()
    root,module,env=fixture('previous')
    (module/'pending').write_text(str(uuid.uuid4())+'\n');(module/'render-mode').write_text('vulkan\n')
    run(module,env,'post-fs-data.sh')
    assert not (root/'props').exists() and (module/'result').read_text().strip()=='previous_boot_failed'
    root,module,env=fixture('conflict')
    other=root/'adb/modules/other';other.mkdir();(other/'system.prop').write_text('debug.renderengine.backend=skiaglthreaded\n')
    assert run(module,env,'control.sh','select',nonce,'vulkan',code=1)['error']=='conflict'
    (other/'disable').touch();run(module,env,'control.sh','select',nonce,'vulkan')
    run(module,env,'control.sh','select',nonce,'$(touch bad)',code=1)
    assert not (module/'bad').exists()
    (module/'disable').touch()
    assert run(module,env,'control.sh','status',nonce)['stage']=='disabled'
    assert run(module,env,'control.sh','select',nonce,'gles',code=1)['error']=='disabled'
    root,module,env=fixture('unsupported');(root/'library.so').write_bytes(b'unrelated')
    assert run(module,env,'control.sh','select',nonce,'vulkan',code=1)['error']=='unsupported'
print('PASS default stays unchanged; deferred selection; runtime verification; fresh boot/PID messages; rollback; conflicts; malformed/unsupported inputs')
