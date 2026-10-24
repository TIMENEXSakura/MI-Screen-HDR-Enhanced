from pathlib import Path
import hashlib, os, shutil, subprocess, tempfile
root=Path(__file__).resolve().parents[1]; source=root/'root-payload'
(root/'build').mkdir(exist_ok=True)
base=Path(tempfile.mkdtemp(prefix='recovery-',dir=root/'build'))
passed=[]
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def setup(name, scenario='stable', target_path='/system_ext/lib64/libsurfaceflinger.so'):
 d=base/name;shutil.copytree(source,d)
 (d/'backup').mkdir();(d/'backup/original.so').write_bytes(b'original')
 relative=target_path.lstrip('/') if target_path.startswith('/system/') else 'system'+target_path
 (d/'target.path').write_text(target_path+'\n');(d/'payload.path').write_text(relative+'\n')
 p=d/relative;p.parent.mkdir(parents=True);p.write_bytes(b'patched')
 (d/'original.sha256').write_text(sha(d/'backup/original.so')+'\n');(d/'patched.sha256').write_text(sha(p)+'\n')
 target=d/'target.so';target.write_bytes(b'original')
 mock=d/'mock';mock.mkdir()
 commands={
 'resetprop':'echo "$*" >> "$TEST_DIR/props"',
 'getprop':'[ "$1" != sys.boot_completed ] || { [ "$TEST_CASE" = timeout ] && echo 0 || echo 1; }',
 'sleep':':',
 'pidof':'''if [ "$TEST_CASE" = crash ]; then n=0; [ ! -f "$TEST_DIR/pids" ] || n=$(cat "$TEST_DIR/pids"); n=$((n+1)); echo "$n" > "$TEST_DIR/pids"; echo "$n"; else echo 123; fi''',
 'mount':'echo "$*" >> "$TEST_DIR/mounts"; exit 0'
 }
 for name,body in commands.items():
  q=mock/name;q.write_text('#!/bin/sh\n'+body+'\n');q.chmod(0o755)
 env=dict(os.environ,PATH=str(mock)+':/usr/bin:/bin',HDR_TARGET=str(target),TEST_DIR=str(d),TEST_CASE=scenario)
 return d,env
def run(d,env,script):
 r=subprocess.run(['/bin/sh',str(d/script)],env=env,capture_output=True,text=True)
 assert r.returncode==0,(script,r.stdout,r.stderr)
def check(label,fn):
 fn();passed.append(label);print('PASS',label)
def stable():
 d,e=setup('stable');run(d,e,'post-fs-data.sh');assert (d/'boot-pending').exists();(d/'target.so').write_bytes(b'patched');run(d,e,'boot-completed.sh');assert (d/'boot-pending').exists();run(d,e,'service.sh');assert not (d/'boot-pending').exists();assert not (d/'disable').exists();assert (d/'boot-check.txt').read_text().startswith('PASS')
check('normal boot only confirms after stable process',stable)
def previous():
 d,e=setup('previous');(d/'boot-pending').write_text('previous');run(d,e,'post-fs-data.sh');assert (d/'disable').exists() and (d/'skip_mountify').exists();assert not (d/'props').exists()
check('unfinished previous boot blocks overlay before properties',previous)
def corrupt():
 d,e=setup('corrupt');(d/'backup/original.so').write_bytes(b'broken');run(d,e,'post-fs-data.sh');assert (d/'disable').exists();assert not (d/'props').exists()
check('corrupt backup blocks overlay',corrupt)
def changed():
 d,e=setup('changed');(d/'target.so').write_bytes(b'new-system');run(d,e,'post-fs-data.sh');assert (d/'disable').exists();assert not (d/'props').exists()
check('changed system library never mounts stale payload',changed)
for scenario in ['crash','timeout']:
 def f(scenario=scenario):
  d,e=setup(scenario,scenario);run(d,e,'post-fs-data.sh');(d/'target.so').write_bytes(b'patched');run(d,e,'service.sh');assert (d/'disable').exists();assert (d/'mounts').exists();assert (d/'boot-pending').exists()
 check(scenario+' marks disable and requests original for future loads',f)
def foreign():
 d,e=setup('foreign');run(d,e,'post-fs-data.sh');(d/'target.so').write_bytes(b'another-overlay');run(d,e,'service.sh');assert (d/'disable').exists();assert not (d/'mounts').exists()
check('unrecognized overlay never overwritten by rollback',foreign)
def installer(changed=False):
 d,e=setup('install_changed' if changed else 'install_ok');e.update(MODPATH=str(d),KSU='false',BOOTMODE='true')
 if changed:(d/'target.so').write_bytes(b'wrong-version')
 script='abort() { echo "$*"; exit 33; }; set_perm_recursive() { :; }; set_perm() { :; }; ui_print() { :; }; . "$MODPATH/customize.sh"'
 r=subprocess.run(['/bin/sh','-c',script],env=e,capture_output=True,text=True)
 assert r.returncode==(33 if changed else 0),(r.stdout,r.stderr)
check('generated installer accepts corresponding current library',lambda:installer(False))
check('generated installer rejects changed file as error',lambda:installer(True))
for target_path in ['/system/lib64/libsurfaceflinger.so','/system/lib/libsurfaceflinger.so','/vendor/lib64/libsurfaceflinger.so','/product/lib64/libsurfaceflinger.so','/system/system_ext/lib64/libsurfaceflinger.so']:
 def relocated(target_path=target_path):
  d,e=setup('relocated-'+target_path.strip('/').replace('/','_'),target_path=target_path)
  run(d,e,'post-fs-data.sh');assert (d/'boot-pending').exists();(d/'target.so').write_bytes(b'patched')
  run(d,e,'service.sh');assert (d/'boot-check.txt').read_text().startswith('PASS');assert not (d/'disable').exists()
 check('target mapping and boot validation '+target_path,relocated)
def missing_target():
 d,e=setup('missing_target');(d/'target.path').unlink();run(d,e,'post-fs-data.sh');assert (d/'disable').exists();assert not (d/'props').exists()
check('missing target metadata never falls back to a hardcoded path',missing_target)
def wrong_payload():
 d,e=setup('wrong_payload');(d/'payload.path').write_text('system/lib64/other.so\n');run(d,e,'post-fs-data.sh');assert (d/'disable').exists();assert not (d/'props').exists()
check('target-payload mismatch blocks overlay',wrong_payload)
def injection():
 d,e=setup('injection');(d/'target.path').write_text('/system/lib64/$(touch bad)/libsurfaceflinger.so\n');run(d,e,'post-fs-data.sh');assert (d/'disable').exists();assert not (d/'props').exists()
check('target metadata is data and never sourced as a shell program',injection)
print('ALL PASS',len(passed));print('FIXTURE_DIR',base)
