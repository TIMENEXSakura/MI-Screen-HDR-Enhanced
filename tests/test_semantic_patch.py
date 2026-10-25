"""Independent A64/ELF fixtures: relocated functions, registers, ABI fields,
branch distances and decoy implementations. No vendor binaries are included."""
import os, shlex, struct, subprocess, tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
def fixture(code_at=0x380, virtual=0x710000, symbol=True, nops=False, bad=None, duplicate=False):
    words=[0xf940000a,0xf940000b|(91<<10)|(10<<5),0xd63f0160,0,
           0x1e2e1002,0xbd400003|(87<<10)|(19<<5),0x1e222060,0,
           0x39400009|(401<<10)|(19<<5),0,
           0xbd400004|(86<<10)|(19<<5),0x1e232080,0x1e234c85,0,
           0xbd400005|(87<<10)|(19<<5),0xbd000005|(86<<10)|(19<<5)]
    # FCSEL s5,s4,s3,MI; fields are deliberately different from the legacy fingerprint.
    encoded=[]; positions=[]
    for i,w in enumerate(words):
        if nops and i in (6,10,15):encoded.append(0xd503201f)
        positions.append(len(encoded)*4);encoded.append(w)
    end=len(encoded)*4
    for idx,kind,to in [(3,0x36000000,end),(7,0x5400000d,end),(9,0x37000009,end),(13,0x14000000,positions[15])]:
        delta=(to-positions[idx])//4
        encoded[positions[idx]//4]=kind|(delta if idx==13 else delta<<5)
    if bad=='store':encoded[positions[15]//4]^=1<<10
    if bad=='condition':encoded[positions[12]//4]^=1<<12
    if bad=='exit':encoded[positions[3]//4]-=1<<5
    if bad=='register':encoded[positions[11]//4]^=1<<5
    code=struct.pack('<'+'I'*len(encoded),*encoded)
    buf=bytearray(4096);ident=b'\x7fELF'+bytes([2,1,1])+bytes(9)
    buf[:64]=struct.pack('<16sHHIQQQIHHHHHH',ident,3,183,1,0,64,0xc00,0,64,56,1,64,3,0)
    buf[64:120]=struct.pack('<IIQQQQQQ',1,5,0x200,virtual,0,0x800,0x800,4096)
    buf[code_at:code_at+len(code)]=code
    if duplicate:buf[code_at+0x100:code_at+0x100+len(code)]=code
    name=b'\x00_ZN7android14SurfaceFlinger16renderScreenImplE_variant\x00' if symbol else b'\x00_ZN7android11OtherClass16renderScreenImplE\x00'
    buf[0xd00:0xd00+len(name)]=name
    buf[0xc40:0xc80]=struct.pack('<IIQQQQIIQQ',0,3,0,0,0xd00,len(name),0,0,1,0)
    buf[0xc80:0xcc0]=struct.pack('<IIQQQQIIQQ',0,11,0,0,0xe00,48,1,0,8,24)
    buf[0xe18:0xe30]=struct.pack('<IBBHQQ',1,0x12,0,1,virtual+(code_at-0x200),0x180 if duplicate else len(code))
    return bytes(buf)
with tempfile.TemporaryDirectory() as tmp:
    tmp=Path(tmp);exe=tmp/'patch'
    subprocess.run(shlex.split(os.environ.get('CC','cc'))+['-O2',str(root/'native/hdrpatch.c'),'-o',str(exe)],check=True)
    passed=0
    def check(name,data,ok):
        global passed
        src=tmp/'input.so';out=tmp/'output.so';back=tmp/'original.so';src.write_bytes(data);out.unlink(missing_ok=True)
        p=subprocess.run([str(exe),'probe',str(src)],capture_output=True,text=True)
        assert (p.returncode==0)==ok,(name,p.stdout,p.stderr)
        p=subprocess.run([str(exe),'patch',str(src),str(out)],capture_output=True,text=True)
        assert (p.returncode==0)==ok,(name,p.stdout,p.stderr)
        assert src.read_bytes()==data
        if ok:
            assert len(out.read_bytes())==len(data)
            assert sum(a!=b for a,b in zip(data,out.read_bytes()))<=4
            subprocess.run([str(exe),'restore',str(out),str(back)],check=True,capture_output=True)
            assert back.read_bytes()==data
        else:assert not out.exists()
        passed+=1;print('PASS',name)
    check('relocated symbol and allocation',fixture(),True)
    check('different address and file offset',fixture(0x600,0x90000000),True)
    check('variable branch distances',fixture(nops=True),True)
    check('no function identity',fixture(symbol=False),False)
    for bad in ('store','condition','exit','register'):check('reject wrong '+bad,fixture(bad=bad),False)
    check('reject duplicate implementation',fixture(duplicate=True),False)
    data=bytearray(fixture());struct.pack_into('<Q',data,0xe18+16,0xffffffffffffffff);check('reject invalid symbol range',data,False)
    print('ALL PASS',passed)
