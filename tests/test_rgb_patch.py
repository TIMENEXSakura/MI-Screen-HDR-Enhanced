"""SHA-256 vectors, RGB profile validation and optional real-library round trips.

CI uses synthetic ELF bytes, never uploads a proprietary system library.
HDR_SF_LIBRARY enables the same patch/restore tests against a local audited SO.
"""
from pathlib import Path
import hashlib
import os
import shlex
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CC = shlex.split(os.environ.get('CC', 'cc'))
subprocess.run(['python3', str(ROOT/'tools/generate-rgb-shader.py'), '--check'], check=True)
original = (ROOT/'experiments/hdr-rgb/original_gainmap.sksl').read_bytes()
shader = (ROOT/'native/shaders/rgb_gainmap.sksl').read_bytes().ljust(len(original), b' ')

with tempfile.TemporaryDirectory(prefix='hdr-rgb-') as folder:
    folder = Path(folder)
    harness = folder/'harness.c'
    harness.write_text(r'''
#include <elf.h>
#include <stdio.h>
#include <stdlib.h>
#include "rgb_patch.h"
int main(int argc,char **argv){
 if(argc<2)return 3;
 FILE *f=fopen(argv[1],"rb");if(!f)return 4;
 fseek(f,0,SEEK_END);long size=ftell(f);rewind(f);
 unsigned char *data=malloc(size?size:1);if(!data)return 5;
 if(fread(data,1,size,f)!=(size_t)size)return 6;fclose(f);
 if(argc==2){HdrSha256 s;char hex[65];hdr_sha_init(&s);
  for(long i=0;i<size;i+=13)hdr_sha_update(&s,data+i,size-i<13?size-i:13);
  hdr_sha_final(&s,hex);puts(hex);
 }else{
  Elf64_Ehdr eh;memcpy(&eh,data,sizeof(eh));
  HdrRgbProfile p={256,711,1024,argv[2]};
  printf("%d\n",hdr_rgb_inspect(data,size,&eh,&p,1024,0x360001a0,0x1400000d));
 }
 free(data);return 0;
}
''')
    executable = folder/'harness'
    subprocess.run(CC+['-std=c11', '-O2', '-I'+str(ROOT/'native'), str(harness), '-o', str(executable)], check=True)
    source = folder/'input'
    for length in [0, 1, 3, 55, 56, 63, 64, 65, 119, 120, 127, 128, 129, 4097, 1000000]:
        data = bytes((i*17+13)&255 for i in range(length))
        source.write_bytes(data)
        assert subprocess.check_output([str(executable), str(source)], text=True).strip() == hashlib.sha256(data).hexdigest(), length
    data = bytearray(2048)
    data[:64] = struct.pack('<16sHHIQQQIHHHHHH', b'\x7fELF'+bytes([2,1,1])+bytes(9),3,183,1,0,64,0,0,64,56,2,0,0,0)
    data[64:120] = struct.pack('<IIQQQQQQ',1,4,0,0,0,1024,1024,4096)
    data[120:176] = struct.pack('<IIQQQQQQ',1,5,1024,1024,0,1024,1024,4096)
    data[256:967] = original
    struct.pack_into('<I',data,1024,0x360001a0)
    digest = hashlib.sha256(data).hexdigest()
    def inspect(blob):
        source.write_bytes(blob)
        result = subprocess.check_output([str(executable),str(source),digest],text=True).strip()
        assert source.read_bytes() == blob, 'probe changed input'
        return int(result)
    for clamp in [0x360001a0, 0x1400000d]:
        for state, contents in [(1,original),(2,shader)]:
            copy=bytearray(data);copy[256:967]=contents;struct.pack_into('<I',copy,1024,clamp)
            assert inspect(copy)==state, (clamp,state)
    for offset in [256, 967, 1024, 2047]:
        copy=bytearray(data);copy[offset]^=1;assert inspect(copy)==0, offset
    for flags in [0,5,6,7]:
        copy=bytearray(data);struct.pack_into('<I',copy,68,flags);assert inspect(copy)==0
    for offset,value in [(72,257),(96,966),(128,512),(136,512)]:
        copy=bytearray(data);struct.pack_into('<Q',copy,offset,value);assert inspect(copy)==0
    assert inspect(data[:1026])==0
    print('PASS SHA-256 padding/chunk vectors; RGB scalar/clamp/RGB/combined states; fingerprint and segment rejection')

    library = os.environ.get('HDR_SF_LIBRARY')
    if library:
        patcher = folder/'hdrpatch'
        subprocess.run(CC+['-O2', str(ROOT/'native/hdrpatch.c'), '-o', str(patcher)], check=True)
        source.write_bytes(Path(library).read_bytes())
        def transform(mode, path, destination):
            result=subprocess.run([str(patcher),mode,str(path),str(destination)],capture_output=True,text=True,check=True)
            assert 'RGB_PATCH_DETAIL supported=true' in result.stdout
            return destination.read_bytes()
        stock=folder/'stock';combined=folder/'combined';roundtrip=folder/'roundtrip'
        restored=transform('restore',source,stock)
        assert hashlib.sha256(restored).hexdigest()=='43598f06ca2c3c96b9eb243e4acb177a5ee77c01794d1cbac92c3934ae32c40e'
        patched=transform('patch',source,combined)
        assert hashlib.sha256(patched).hexdigest()=='8e4d105b4417805a14b1082cf283801431e99423f6cd1e6783e221513cc4545f'
        assert transform('patch',stock,roundtrip)==patched
        assert transform('patch',combined,roundtrip)==patched
        assert transform('restore',combined,roundtrip)==restored
        assert transform('restore',stock,roundtrip)==restored
        assert Path(library).read_bytes()==source.read_bytes()
        print('PASS real audited library: legacy upgrade, exact prototype bytes, idempotence and complete stock restore')
