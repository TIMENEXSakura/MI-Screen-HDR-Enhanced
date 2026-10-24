from pathlib import Path
import hashlib, os, shutil, subprocess, tempfile
root=Path(__file__).resolve().parents[1]; source=root/'root-payload'
(root/'build').mkdir(exist_ok=True)
base=Path(tempfile.mkdtemp(prefix='recovery-',dir=root/'build'))
passed=[]
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def setup(name, scenario='stable'):
 d=base/name;shutil.copytree(source,d)
 (d/'backup').mkdir();(d/'backup/original.so').write_bytes(b'original')
 p=d/'system/system_ext/lib64/libsurfaceflinger.so';p.parent.mkdir(parents=True);p.write_bytes(b'patched')
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
print('ALL PASS',len(passed));print('FIXTURE_DIR',base)
