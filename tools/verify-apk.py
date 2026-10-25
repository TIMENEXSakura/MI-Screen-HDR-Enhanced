"""Check the distributable, including modern Xposed metadata and native alignment."""
import io
import struct
import sys
from pathlib import Path
from zipfile import ZipFile, ZIP_STORED

apk = Path(sys.argv[1])
with ZipFile(apk) as archive:
    metadata = {
        'java_init.list': 'dev.local.miuihdr.ModuleEntry',
        'native_init.list': 'libmiuihdr.so',
        'scope.list': 'com.miui.screenshot',
    }
    for name, expected in metadata.items():
        assert archive.read('META-INF/xposed/' + name).decode().strip() == expected, name
    properties = dict(line.split('=', 1) for line in archive.read('META-INF/xposed/module.prop').decode().splitlines() if '=' in line)
    assert properties['minApiVersion'] == properties['targetApiVersion'] == '102'
    assert 'assets/xposed_init' not in archive.namelist()
    for name in archive.namelist():
        if name.endswith('.dex'):
            data = archive.read(name)
            assert b'de/robv/android/xposed' not in data and b'AndroidAppHelper' not in data
    print('PASS LSPosed API 102 entries; no legacy bridge references')
    entry = archive.getinfo('lib/arm64-v8a/libmiuihdr.so')
    with apk.open('rb') as stream:
        stream.seek(entry.header_offset + 26)
        names, extra = struct.unpack('<HH', stream.read(4))
    assert entry.compress_type == ZIP_STORED
    assert (entry.header_offset + 30 + names + extra) % 16384 == 0
    data = archive.read(entry)
    assert data[:6] == b'\x7fELF\x02\x01' and struct.unpack_from('<H', data, 18)[0] == 183
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    loads = 0
    for index in range(count):
        record = offset + index * size
        if struct.unpack_from('<I', data, record)[0] == 1:
            assert struct.unpack_from('<Q', data, record + 48)[0] >= 16384
            loads += 1
    assert loads and b'native_init\0' in data
    print('PASS ARM64 native library and 16 KB ELF / APK alignment')
    with ZipFile(io.BytesIO(archive.read('assets/mount-template.zip'))) as template:
        assert 'bin/hdrpatch' in template.namelist()
        assert not any(name.endswith('.so') for name in template.namelist())
        assert 'version=1.5.1' in template.read('module.prop').decode()
    print('PASS generated-module template; no vendor system libraries bundled')
