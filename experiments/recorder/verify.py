"""Check the independent APK and its native ABI without activating any device."""
from pathlib import Path
from zipfile import ZipFile
import struct,sys,io
with ZipFile(sys.argv[1]) as z:
    native = z.read('lib/arm64-v8a/librecorderlab.so')
    assert native[:6] == b'\x7fELF\x02\x01'
    assert struct.unpack_from('<H', native, 18)[0] == 183
    for required in [b'libvulkan.so', b'libEGL.so', b'libGLESv3.so', b'libmediandk.so',
                     b'Java_dev_local_recorderlab_NativeBridge_create', b'vkQueuePresentKHR',
                     b'AImageReader_acquireLatestImageAsync']:
        assert required in native, required
    offset = struct.unpack_from('<Q', native, 32)[0]
    size,count = struct.unpack_from('<HH', native, 54)
    for i in range(count):
        p=offset+i*size
        if struct.unpack_from('<I',native,p)[0]==1:
            assert struct.unpack_from('<Q',native,p+48)[0]>=16384
    with ZipFile(io.BytesIO(z.read('assets/backend-test.zip'))) as module:
        assert not any(name.endswith('.so') for name in module.namelist())
        assert module.read('communication.protocol').strip()==b'mi-recorder-render/1'
        for name in ['module.prop','customize.sh','post-fs-data.sh','service.sh','control.sh','common.sh']:
            assert name in module.namelist()
    assert not any('mount-template' in name or 'libsurfaceflinger' in name for name in z.namelist())
print('PASS independent ARM64 recorder; GLES/Vulkan engines; 16 KB native alignment; no system replacement payload')
