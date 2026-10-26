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
 'getprop':'case "$1" in persist.sys.support_ultra_hdr) echo true;; sys.boot_completed) [ "$TEST_CASE" = timeout ] && echo 0 || echo 1;; esac',
 'sleep':':',
 'pidof':'''if [ "$TEST_CASE" = crash ]; then n=0; [ ! -f "$TEST_DIR/pids" ] || n=$(cat "$TEST_DIR/pids"); n=$((n+1)); echo "$n" > "$TEST_DIR/pids"; echo "$n"; else echo 123; fi''',
 'mount':'echo "$*" >> "$TEST_DIR/mounts"; exit 0'
 }
 for name,body in commands.items():
  q=mock/name;q.write_text('#!/bin/sh\n'+body+'\n');q.chmod(0o755)
 env=dict(os.environ,PATH=str(mock)+':/usr/bin:/bin',HDR_TARGET=str(target),HDR_ADB_ROOT=str(d/'adb'),TEST_DIR=str(d),TEST_CASE=scenario)
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
for partition in ['system_ext','vendor','product']:
 for link in [False,True]:
  def moved_partition(partition=partition,link=link):
   d,e=setup('moved-'+partition+str(link),target_path='/'+partition+'/lib64/libsurfaceflinger.so')
   canonical=d/'system'/partition;shutil.move(str(canonical),d/partition)
   if link:canonical.symlink_to('../'+partition)
   run(d,e,'post-fs-data.sh');assert (d/'boot-pending').exists() and not (d/'disable').exists()
   (d/'target.so').write_bytes(b'patched');run(d,e,'service.sh');assert (d/'boot-check.txt').read_text().startswith('PASS')
  check('relocated partition '+partition+' compatibility_symlink='+str(link),moved_partition)
def duplicate_payload():
 d,e=setup('duplicate-payload');shutil.copytree(d/'system/system_ext',d/'system_ext');(d/'system_ext/lib64/libsurfaceflinger.so').write_bytes(b'other')
 run(d,e,'post-fs-data.sh');assert (d/'disable').exists();assert not (d/'props').exists()
check('conflicting canonical and relocated payloads are rejected',duplicate_payload)
def install_backend(name,framework,meta=None,legacy_mountify=False,mode=None,listed=False,skip=False):
 d,e=setup('backend-'+name);e.update(MODPATH=str(d),BOOTMODE='true',KSU='false',APATCH='false',MAGISK_VER_CODE='')
 if framework=='MAGISK':e['MAGISK_VER_CODE']='30000'
 else:e[framework]='true'
 adb=d/'adb';adb.mkdir()
 if meta or legacy_mountify:
  module=adb/'modules'/(meta or 'mountify');module.mkdir(parents=True)
  (module/'module.prop').write_text('id='+module.name+'\nmetamodule=true\n')
  (module/('post-fs-data.sh' if legacy_mountify else 'metamount.sh')).write_text('# mock provider\n')
  if meta:(adb/'metamodule').symlink_to(module)
 if mode is not None:
  config=adb/'mountify';config.mkdir();(config/'config.sh').write_text('mountify_mounts="'+str(mode)+'" # test\n')
  (config/'modules.txt').write_text('mi_screen_hdr_enhanced\n' if listed else 'xiaomi17u_hdr_clamp_test\n')
 if skip:(d/'skip_mount').touch()
 script='abort() { echo "$*"; exit 33; }; set_perm_recursive() { :; }; set_perm() { :; }; ui_print() { :; }; . "$MODPATH/customize.sh"'
 result=subprocess.run(['/bin/sh','-c',script],env=e,capture_output=True,text=True)
 expected_failure=mode==0 or (mode==1 and not listed)
 assert result.returncode==(33 if expected_failure else 0),(name,result.stdout,result.stderr)
 if expected_failure:assert 'MOUNT_BACKEND_' in result.stdout
 else:
  text=(d/'mount-backend.txt').read_text();assert 'ROOT_FRAMEWORK='+('KERNELSU' if framework=='KSU' else framework) in text
  assert 'METAMODULE='+(meta or ('mountify' if legacy_mountify else ''))+'\n' in text
 if skip:assert (d/'skip_mount').exists()
for name,framework,meta in [('magisk-magicmount','MAGISK',None),('ksu-built-in','KSU',None),('apatch-built-in','APATCH',None),('ksu-overlayfs','KSU','meta-overlayfs'),('ksu-magicmount','KSU','meta-magicmount'),('apatch-meta','APATCH','meta-overlayfs'),('sukisu-mountify','KSU','mountify')]:
 check('installer accepts '+name,lambda n=name,f=framework,m=meta:install_backend(n,f,m))
check('Magisk legacy Mountify retains provider-owned skip_mount',lambda:install_backend('magisk-mountify','MAGISK',legacy_mountify=True,skip=True))
check('Mountify manual selection accepted when listed',lambda:install_backend('manual-selected','KSU','mountify',mode=1,listed=True))
check('Mountify manual exclusion reported without editing configuration',lambda:install_backend('manual-excluded','KSU','mountify',mode=1))
check('Mountify disabled mode reported',lambda:install_backend('mountify-off','KSU','mountify',mode=0))
def not_mounted():
 d,e=setup('not-mounted');run(d,e,'post-fs-data.sh');run(d,e,'service.sh')
 assert (d/'disable').exists() and 'not mounted' in (d/'rollback.txt').read_text()
 assert not (d/'boot-check.txt').read_text().startswith('PASS')
check('successful preparation never reports activation without a visible mount',not_mounted)
def bind_failure():
 d,e=setup('bind-failure');(d/'target.so').write_bytes(b'patched');(d/'mock/mount').write_text('#!/bin/sh\nexit 1\n')
 e['MODDIR']=str(d)
 subprocess.run(['/bin/sh','-c','. "$MODDIR/common.sh"; hdr_rollback test'],env=e,check=True)
 assert (d/'disable').exists() and (d/'skip_mountify').exists()
 assert 'Live restore unavailable' in (d/'rollback.txt').read_text()
check('failed live bind restore retains next-boot disable markers',bind_failure)
for framework,relative in [('KSU','ksu/bin/ksud'),('APATCH','apd'),('APATCH','ap/bin/apd'),('MAGISK','magisk/magisk')]:
 def property_fallback(framework=framework,relative=relative):
  d,e=setup('properties-'+relative.replace('/','_'));e.update(MODDIR=str(d),KSU='false',APATCH='false',MAGISK_VER_CODE='')
  if framework=='MAGISK':e['MAGISK_VER_CODE']='30000'
  else:e[framework]='true'
  (d/'mock/resetprop').unlink();binary=d/'adb'/relative;binary.parent.mkdir(parents=True)
  binary.write_text('#!/bin/sh\nprintf "%s\\n" "$*" > "$TEST_DIR/property-call"\n');binary.chmod(0o755)
  subprocess.run(['/bin/sh','-c','. "$MODDIR/common.sh"; hdr_resetprop -n debug.sf.true_hdr_screenshots true'],env=e,check=True)
  assert (d/'property-call').read_text().strip()=='resetprop -n debug.sf.true_hdr_screenshots true'
 check('property helper fallback '+framework+' '+relative,property_fallback)

def recorder_backend():
 import uuid
 d,e=setup('recorder-backend');nonce=str(uuid.uuid4())
 (d/'installation.id').write_text(str(uuid.uuid4())+'\n')
 capabilities=d/'capabilities.so';capabilities.write_bytes(b'debug.renderengine.backend\0SkiaVkRenderEngine\0')
 e.update(HDR_TEST_LIBRARIES=str(capabilities),HDR_PROC_ROOT=str(d/'proc'))
 (d/'mock/id').write_text('#!/bin/sh\necho 0\n');(d/'mock/id').chmod(0o755)
 (d/'mock/dumpsys').write_text('#!/bin/sh\necho " ------------RE Vulkan (Ganesh)----------"\n');(d/'mock/dumpsys').chmod(0o755)
 def endpoint(action,*args,code=0):
  result=subprocess.run(['/bin/sh',str(d/'control.sh'),action,nonce,*args],env=e,capture_output=True,text=True)
  assert result.returncode==code,(result.stdout,result.stderr)
  return dict(line.split('=',1) for line in result.stdout.splitlines() if '=' in line)
 endpoint('select','vulkan');assert not (d/'props').exists()
 run(d,e,'post-fs-data.sh');assert 'skiavkthreaded' in (d/'props').read_text()
 (d/'target.so').write_bytes(b'patched');run(d,e,'service.sh')
 state=endpoint('status');assert state['stage']=='ready' and state['actual']=='vulkan' and state['result']=='verified'
 assert not (d/'disable').exists()
 process=d/'proc/8765';process.mkdir(parents=True);(process/'cmdline').write_bytes(b'com.miui.screenrecorder\0')
 state=endpoint('report','recording','vulkan','8765');assert state['child']=='online' and state['child_backend']=='vulkan'
 (process/'cmdline').write_bytes(b'other.app\0');assert endpoint('status')['child']=='offline'
 assert endpoint('report','recording','vulkan','8765',code=1)['error']=='not_recorder'
 message=(d/'boot-message').read_text();(d/'boot-message').write_text(message.replace('pid=123','pid=999'))
 assert endpoint('status')['stage']=='stale'
check('shared HDR module switches backend at boot and validates recorder process reports',recorder_backend)
def renderer_mismatch():
 d,e=setup('renderer-mismatch')
 capabilities=d/'capabilities.so';capabilities.write_bytes(b'debug.renderengine.backend\0SkiaVkRenderEngine\0');e['HDR_TEST_LIBRARIES']=str(capabilities)
 (d/'render-mode').write_text('vulkan\n')
 (d/'mock/dumpsys').write_text('#!/bin/sh\necho "RE GLES (Ganesh)"\n');(d/'mock/dumpsys').chmod(0o755)
 run(d,e,'post-fs-data.sh');(d/'target.so').write_bytes(b'patched');run(d,e,'service.sh')
 assert (d/'render-mode').read_text().strip()=='default' and (d/'render-result').read_text().strip()=='not_verified'
 assert not (d/'disable').exists(), 'backend mismatch must preserve successful HDR mounting'
check('failed Vulkan verification cancels backend override without disabling a valid HDR patch',renderer_mismatch)
print('ALL PASS',len(passed));print('FIXTURE_DIR',base)
