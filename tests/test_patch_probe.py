"""Exercise ELF discovery with synthetic data, without distributing a system SO."""
from pathlib import Path
import os, re, shlex, struct, subprocess, tempfile

root=Path(__file__).resolve().parents[1]
source=(root/'native/hdrpatch.c').read_text()
def array(name):
    body=re.search(r'\b'+name+r'\[\]=\{([^}]+)\}',source).group(1)
    return bytes(int(v.strip(),16) for v in body.split(','))
sequence=array('prefix')+array('original')+array('suffix')
data=bytearray(1024)
ident=b'\x7fELF'+bytes([2,1,1])+bytes(9)
data[:64]=struct.pack('<16sHHIQQQIHHHHHH',ident,3,183,1,0,64,0,0,64,56,1,0,0,0)
data[64:120]=struct.pack('<IIQQQQQQ',1,5,256,256,0,768,768,4096)
data[320:320+len(sequence)]=sequence
with tempfile.TemporaryDirectory(prefix='hdr-probe-') as temp:
    temp=Path(temp)
    exe=temp/'hdrpatch'
    subprocess.run(shlex.split(os.environ.get('CC','cc'))+['-O2','-o',str(exe),str(root/'native/hdrpatch.c')],check=True)
    def run(name,contents,success,mode='probe'):
        path=temp/(name+'.so');path.write_bytes(contents);out=temp/(name+'-patched.so')
        args=[str(exe),mode,str(path)]+([] if mode=='probe' else [str(out)])
        result=subprocess.run(args,capture_output=True,text=True)
        assert (result.returncode==0)==success,(name,result.stdout,result.stderr)
        assert path.read_bytes()==contents
        if mode=='probe' or not success:assert not out.exists()
        print('PASS',name,result.stdout.strip() or result.stderr.strip())
        return out
    run('read-only-probe',data,True)
    out=run('patch',data,True,'patch')
    patched=out.read_bytes();assert patched[332:336]==array('patched')
    run('already-patched-probe',patched,True)
    out=run('restore',patched,True,'restore');assert out.read_bytes()==data
    missing=bytearray(data);missing[320:332]=bytes(12);run('no-match',missing,False)
    run('no-match-no-output',missing,False,'patch')
    duplicate=bytearray(data);duplicate[512:512+len(sequence)]=sequence;run('ambiguous',duplicate,False)
    elf32=bytearray(data);elf32[4]=1;struct.pack_into('<H',elf32,18,40);run('32-bit-diagnostic',elf32,False)
    bad=bytearray(data);bad[:4]=b'BAD!';run('malformed',bad,False)
print('ALL PASS 9')
