"""Exercise exported-module resolution against isolated target filesystems only."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import tempfile

REPO = Path(__file__).resolve().parents[1]
(REPO / 'build').mkdir(exist_ok=True)
BASE = Path(tempfile.mkdtemp(prefix='offline-export-', dir=REPO / 'build'))
ORIGINAL = b'original-system-library'
PATCHED = b'patched--system-library'
assert len(ORIGINAL) == len(PATCHED)
PASSED = []


def digest(data):
    return hashlib.sha256(data).hexdigest()


def setup(name, framework='MAGISK', provider=None):
    directory = BASE / name
    directory.mkdir()
    module = directory / 'module'
    shutil.copytree(REPO / 'root-payload', module)
    for relative, data in {
        'offline-export': b'1\n',
        'offline/patched.so': PATCHED,
        'backup/original.so': ORIGINAL,
        'original.sha256': (digest(ORIGINAL) + '\n').encode(),
        'patched.sha256': (digest(PATCHED) + '\n').encode(),
        'patch.txt': b'PATCH_OK source=fixture\n',
        'generated.txt': b'mode=offline-export\n',
    }.items():
        path = module / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    filesystem = directory / 'target-device'
    filesystem.mkdir()
    mock = directory / 'mock'
    mock.mkdir()
    for name, body in {
        'getprop': 'exit 0',
        'mount': 'echo unexpected-mount >> "$TEST_EVENTS"; exit 92',
        'resetprop': 'echo unexpected-resetprop >> "$TEST_EVENTS"; exit 92',
    }.items():
        binary = mock / name
        binary.write_text('#!/bin/sh\n' + body + '\n')
        binary.chmod(0o755)
    adb = directory / 'adb'
    adb.mkdir()
    env = dict(os.environ, PATH=str(mock) + ':/usr/bin:/bin', MODPATH=str(module),
               BOOTMODE='true', KSU='false', APATCH='false', MAGISK_VER_CODE='',
               HDR_ADB_ROOT=str(adb), HDR_OFFLINE_SCAN_ROOT=str(filesystem),
               TEST_EVENTS=str(directory / 'events'))
    env.pop('HDR_TARGET', None)
    env.pop('KSU_LATE_LOAD', None)
    env.pop('APATCH_LATE_LOAD', None)
    if framework == 'MAGISK':
        env['MAGISK_VER_CODE'] = '30000'
    else:
        env[framework] = 'true'
    if provider:
        meta = adb / 'modules' / provider
        meta.mkdir(parents=True)
        (meta / 'module.prop').write_text('id=' + provider + '\nmetamodule=true\n')
        (meta / 'metamount.sh').write_text('# provider fixture\n')
        (adb / 'metamodule').symlink_to(meta)
    return directory, module, filesystem, env


def library(filesystem, path, data=ORIGINAL):
    result = filesystem / path.lstrip('/')
    result.parent.mkdir(parents=True, exist_ok=True)
    result.write_bytes(data)
    return result


def install(fixture, error=None):
    directory, module, filesystem, env = fixture
    before = {str(path): path.read_bytes() for path in filesystem.rglob('*.so') if path.is_file()}
    command = '''abort() { echo "$*"; exit 33; }
set_perm_recursive() { :; }
set_perm() { :; }
ui_print() { printf '%s\\n' "$*"; }
. "$MODPATH/customize.sh"
'''
    result = subprocess.run(['/bin/sh', '-c', command], env=env, capture_output=True, text=True)
    assert result.returncode == (33 if error else 0), (result.stdout, result.stderr)
    if error:
        assert error in result.stdout, (result.stdout, result.stderr)
    for path, data in before.items():
        assert Path(path).read_bytes() == data, 'installer altered a target system library'
    assert not (directory / 'events').exists(), 'installer attempted a live mount or property change'
    return result


def accepted(fixture, target, framework='MAGISK', provider=''):
    install(fixture)
    _, module, _, _ = fixture
    payload = target.lstrip('/') if target.startswith('/system/') else 'system' + target
    assert (module / 'target.path').read_text() == target + '\n'
    assert (module / 'payload.path').read_text() == payload + '\n'
    assert (module / payload).read_bytes() == PATCHED
    assert (module / 'backup/original.so').read_bytes() == ORIGINAL
    assert not (module / 'offline/patched.so').exists()
    assert not (module / 'offline-export').exists()
    assert not list(module.glob('.offline-*'))
    report = (module / 'mount-backend.txt').read_text()
    assert 'ROOT_FRAMEWORK=' + framework + '\n' in report
    assert 'METAMODULE=' + provider + '\n' in report


def rejected(fixture, error):
    install(fixture, error)
    _, module, _, _ = fixture
    assert not (module / 'target.path').exists()
    assert not (module / 'payload.path').exists()
    assert not (module / 'mount-backend.txt').exists()
    assert (module / 'offline-export').exists()
    assert not list(module.glob('.offline-*'))


def check(label, operation):
    operation()
    PASSED.append(label)
    print('PASS', label)


for target in [
    '/system/lib64/libsurfaceflinger.so', '/system/lib/libsurfaceflinger.so',
    '/system_ext/lib64/libsurfaceflinger.so', '/system_ext/lib/libsurfaceflinger.so',
    '/product/lib64/libsurfaceflinger.so', '/product/lib/libsurfaceflinger.so',
    '/vendor/lib64/libsurfaceflinger.so', '/vendor/lib/libsurfaceflinger.so',
    '/system/system_ext/lib64/libsurfaceflinger.so',
    '/system/product/lib64/libsurfaceflinger.so', '/system/vendor/lib64/libsurfaceflinger.so',
    '/vendor/lib64/subdirectory/librenamed_display_impl.so',
]:
    def matching_path(target=target):
        fixture = setup('target-' + target.strip('/').replace('/', '_'))
        library(fixture[2], target)
        accepted(fixture, target)
    check('install resolves exact content at ' + target, matching_path)


def already_patched():
    fixture = setup('already-patched')
    target = '/system_ext/lib64/libsurfaceflinger.so'
    library(fixture[2], target, PATCHED)
    accepted(fixture, target)
check('existing patched target is accepted without overwriting the backup', already_patched)


def aliases():
    fixture = setup('partition-alias')
    root = fixture[2]
    library(root, '/system_ext/lib64/libsurfaceflinger.so')
    (root / 'system').mkdir()
    (root / 'system/system_ext').symlink_to('../system_ext')
    accepted(fixture, '/system_ext/lib64/libsurfaceflinger.so')
check('partition directory aliases resolve once', aliases)


def file_alias():
    fixture = setup('file-alias')
    root = fixture[2]
    library(root, '/vendor/lib64/libdisplay_impl.so')
    alias = root / 'system/lib64/libsurfaceflinger.so'
    alias.parent.mkdir(parents=True)
    alias.symlink_to('../../vendor/lib64/libdisplay_impl.so')
    accepted(fixture, '/vendor/lib64/libdisplay_impl.so')
check('file aliases prefer the physical supported partition path', file_alias)


def system_as_root():
    fixture = setup('system-as-root')
    root = fixture[2]
    library(root, '/system_root/system/lib64/libsurfaceflinger.so')
    (root / 'system').symlink_to('system_root/system')
    accepted(fixture, '/system/lib64/libsurfaceflinger.so')
check('system_root alias retains a standard mountable system path', system_as_root)


def hard_links():
    fixture = setup('hard-links')
    original = library(fixture[2], '/system/lib64/libsurfaceflinger.so')
    alias = fixture[2] / 'system_ext/lib64/librenamed.so'
    alias.parent.mkdir(parents=True)
    os.link(original, alias)
    accepted(fixture, '/system/lib64/libsurfaceflinger.so')
check('hard-linked aliases of one physical library are deduplicated', hard_links)


for second in [ORIGINAL, PATCHED]:
    def ambiguous(second=second):
        fixture = setup('ambiguous-' + digest(second)[:8])
        library(fixture[2], '/system/lib64/libsurfaceflinger.so')
        library(fixture[2], '/system_ext/lib64/libanother.so', second)
        rejected(fixture, 'OFFLINE_LIBRARY_AMBIGUOUS')
    check('distinct matching library identities are rejected ' + digest(second)[:8], ambiguous)


def wrong_version():
    fixture = setup('wrong-version')
    library(fixture[2], '/system/lib64/libsurfaceflinger.so', b'other----system-library')
    rejected(fixture, 'OFFLINE_LIBRARY_NOT_FOUND')
check('an OTA or foreign system version cannot receive the exported payload', wrong_version)


def no_library():
    rejected(setup('no-library'), 'OFFLINE_LIBRARY_NOT_FOUND')
check('no matching target never falls back to the exporting device path', no_library)


def unsupported_alias():
    fixture = setup('unmountable-system-root')
    library(fixture[2], '/system_root/system/lib64/libsurfaceflinger.so')
    rejected(fixture, 'OFFLINE_TARGET_UNSUPPORTED')
check('system_root without a verified standard alias is rejected', unsupported_alias)


for relative in ['backup/original.so', 'offline/patched.so']:
    def corrupt(relative=relative):
        fixture = setup('corrupt-' + relative.replace('/', '_'))
        (fixture[1] / relative).write_bytes(b'corrupted-package')
        rejected(fixture, 'OFFLINE_DAMAGED')
    check('damaged package rejected before scan: ' + relative, corrupt)


def invalid_digest():
    fixture = setup('invalid-digest')
    (fixture[1] / 'original.sha256').write_text('x' * 64 + '\n')
    rejected(fixture, 'OFFLINE_INVALID')
check('malformed SHA256 metadata is rejected', invalid_digest)


def invalid_marker():
    fixture = setup('invalid-marker')
    (fixture[1] / 'offline-export').write_text('2\n')
    rejected(fixture, 'OFFLINE_INVALID')
check('unknown offline contract version is rejected', invalid_marker)


def embedded_target():
    fixture = setup('embedded-target')
    (fixture[1] / 'target.path').write_text('/system/lib64/libsurfaceflinger.so\n')
    library(fixture[2], '/system_ext/lib64/libsurfaceflinger.so')
    install(fixture, 'OFFLINE_INVALID')
    assert not (fixture[1] / 'payload.path').exists()
    assert (fixture[1] / 'offline/patched.so').read_bytes() == PATCHED
check('an offline ZIP cannot preselect the exporting device target', embedded_target)


for framework, provider in [('KSU', None), ('APATCH', None), ('KSU', 'meta-overlayfs'),
                             ('KSU', 'meta-magicmount'), ('APATCH', 'meta-overlayfs'),
                             ('KSU', 'mountify')]:
    def backend(framework=framework, provider=provider):
        fixture = setup('backend-' + framework + '-' + str(provider), framework, provider)
        target = '/system_ext/lib64/libsurfaceflinger.so'
        library(fixture[2], target)
        accepted(fixture, target, 'KERNELSU' if framework == 'KSU' else framework, provider or '')
    check('offline install uses target backend ' + framework + ' ' + str(provider), backend)


def mountify_selection():
    fixture = setup('mountify-not-selected', 'KSU', 'mountify')
    library(fixture[2], '/system_ext/lib64/libsurfaceflinger.so')
    config = Path(fixture[3]['HDR_ADB_ROOT']) / 'mountify'
    config.mkdir()
    (config / 'config.sh').write_text('mountify_mounts="1"\n')
    (config / 'modules.txt').write_text('another_module\n')
    before = {path: path.read_bytes() for path in config.iterdir()}
    install(fixture, 'MOUNT_BACKEND_EXCLUDED')
    assert all(path.read_bytes() == data for path, data in before.items())
check('target Mountify exclusion is respected without editing its configuration', mountify_selection)

print('ALL PASS', len(PASSED))
print('FIXTURE_DIR', BASE)
