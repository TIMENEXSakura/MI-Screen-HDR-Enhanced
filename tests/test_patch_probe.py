"""Semantic patch regression tests. Fixtures describe AArch64 control flow;
no bytes are extracted from the patcher's implementation or a vendor SO.
Optional: HDR_PATCH_SAMPLE=/path/to/owned/libsurfaceflinger.so (read only).
"""
from pathlib import Path
import os, shlex, struct, subprocess, tempfile

ROOT = Path(__file__).resolve().parents[1]
SYMBOL = b'_ZN7android14SurfaceFlinger16renderScreenImplEv'
NOP = 0xd503201f

def block(base=21, table=8, method=8, flag=8, limit=0, unit=1,
          brightness=1, result=0, fields=(0x144, 0x140, 0x178),
          slot=0x470, padding=0, reorder=False):
    """Independent instruction fixture with symbolic branch labels."""
    code, labels, fixups = [], {}, []
    def emit(name, instruction):
        labels[name] = len(code) * 4
        code.append(instruction)
        code.extend([NOP] * padding)
    def branch(name, instruction, target, bits, shift):
        fixups.append((len(code), target, bits, shift))
        emit(name, instruction)
    lim, val, opt = fields
    emit('impl_call', 0x94000000)  # BL; the following x0 test consumes its result
    branch('null', 0xb4000000, 'done', 19, 5)  # cbz x0,done
    emit('vtable', 0xf9400000 | table)
    emit('method', 0xf9400000 | (slot//8)<<10 | table<<5 | method)
    emit('call', 0xd63f0000 | method<<5)
    branch('gate', 0x36000000, 'done', 14, 5)
    pair = [('one', 0x1e2e1000 | unit),
            ('limit', 0xbd400000 | (lim//4)<<10 | base<<5 | limit)]
    for name, instruction in pair[::-1] if reorder else pair:
        emit(name, instruction)
    emit('cmp_limit', 0x1e202000 | unit<<16 | limit<<5)
    branch('limit_exit', 0x5400000d, 'done', 19, 5)
    emit('option', 0x39400000 | opt<<10 | base<<5 | flag)
    branch('option_exit', 0x37000000 | flag, 'done', 14, 5)
    emit('brightness', 0xbd400000 | (val//4)<<10 | base<<5 | brightness)
    emit('cmp_min', 0x1e202000 | limit<<16 | brightness<<5)
    emit('min', 0x1e204c00 | limit<<16 | brightness<<5 | result)
    branch('store_jump', 0x14000000, 'store', 26, 0)
    emit('alternative', 0xbd400000 | (lim//4)<<10 | base<<5 | result)
    emit('store', 0xbd000000 | (val//4)<<10 | base<<5 | result)
    emit('done', 0xd65f03c0)  # ret, only the common join matters to the matcher
    for index, target, bits, shift in fixups:
        delta = (labels[target] - index*4)//4
        code[index] |= (delta & ((1<<bits)-1)) << shift
    return bytearray(struct.pack('<'+'I'*len(code), *code)), labels

def elf(code, start=0x240, bias=0x8000, symbol=SYMBOL, function_size=None, aliases=False):
    code_start=0x200
    symoff=(start+len(code)+0x3f)&~0x3f
    stroff=symoff+48
    strings=b'\0'+symbol+b'\0'
    shoff=(stroff+len(strings)+0x3f)&~0x3f
    nsections=5 if aliases else 4
    data=bytearray(shoff+64*nsections)
    data[:64]=struct.pack('<16sHHIQQQIHHHHHH',b'\x7fELF'+bytes([2,1,1])+bytes(9),
        3,183,1,0,64,shoff,0,64,56,1,64,nsections,0)
    code_size=start+len(code)-code_start
    data[64:120]=struct.pack('<IIQQQQQQ',1,5,code_start,code_start+bias,0,code_size,code_size,0x1000)
    data[start:start+len(code)]=code
    data[symoff+24:symoff+48]=struct.pack('<IBBHQQ',1,0x12,0,1,start+bias,len(code) if function_size is None else function_size)
    data[stroff:stroff+len(strings)]=strings
    def section(index, kind, flags, addr, off, size, link=0, entsize=0):
        struct.pack_into('<IIQQQQIIQQ',data,shoff+64*index,0,kind,flags,addr,off,size,link,0,4,entsize)
    section(1,1,6,code_start+bias,code_start,code_size)
    section(2,11,0,0,symoff,48,3,24)
    section(3,3,0,0,stroff,len(strings))
    if aliases: section(4,2,0,0,symoff,48,3,24)
    return data, {'shoff':shoff,'symoff':symoff,'stroff':stroff,'start':start,'bias':bias}

def mutate_word(data, offset, value):
    copy=bytearray(data);struct.pack_into('<I',copy,offset,value);return copy

def getword(data, offset): return struct.unpack_from('<I',data,offset)[0]

with tempfile.TemporaryDirectory(prefix='hdr-semantic-') as temp:
    temp=Path(temp);exe=temp/'hdrpatch';passes=0
    subprocess.run(shlex.split(os.environ.get('CC','cc'))+['-std=c11','-D_GNU_SOURCE','-Wall','-Wextra','-Werror','-O2',
        '-o',str(exe),str(ROOT/'native/hdrpatch.c')],check=True)
    def run(name, contents, success=True, mode='probe', existing=False):
        global passes
        path=temp/(name+'.so');path.write_bytes(contents);out=temp/(name+'-out.so')
        if existing: out.write_bytes(b'KEEP EXISTING OUTPUT')
        args=[str(exe),mode,str(path)]+([] if mode=='probe' else [str(out)])
        p=subprocess.run(args,capture_output=True,text=True)
        assert (p.returncode==0)==success,(name,p.stdout,p.stderr)
        assert path.read_bytes()==contents, name+' mutated input'
        if not success or mode=='probe':
            assert out.read_bytes()==b'KEEP EXISTING OUTPUT' if existing else not out.exists()
        assert not list(temp.glob(out.name+'.tmp.*')), name+' leaked temp'
        passes+=1;print('PASS',name,p.stdout.strip() or p.stderr.strip())
        return out,p.stdout

    code,labels=block();data,meta=elf(code)
    out,info=run('virtual-address-translation',data)
    assert 'offset=0x254 ' in info and 'vaddr=0x8254 ' in info
    out,_=run('patch',data,mode='patch');patched=out.read_bytes()
    gate=meta['start']+labels['gate'];target=meta['start']+labels['done']
    expected=mutate_word(data,gate,0x14000000+(target-gate)//4)
    assert patched==expected
    run('already-patched',patched)
    out,_=run('idempotent-patch',patched,mode='patch');assert out.read_bytes()==patched
    out,_=run('restore-exact-original',patched,mode='restore');assert out.read_bytes()==data
    out,_=run('idempotent-restore',data,mode='restore');assert out.read_bytes()==data
    # Register/field/vtable relocation and NOPs change both layout and branch distances.
    for i,options in enumerate([
        {'base':19,'table':10,'method':12,'flag':9,'limit':3,'unit':4,'brightness':5,'result':6},
        {'fields':(0x218,0x204,0x299),'slot':0x510},
        {'padding':2,'reorder':True,'base':22,'limit':7,'unit':9,'brightness':8,'result':11},
    ]):
        variant,positions=block(**options);v,m=elf(variant,start=0x400+i*0x80,bias=0x34000)
        out,_=run('relocated-'+str(i),v,mode='patch');vp=out.read_bytes()
        delta=(positions['done']-positions['gate'])//4
        assert vp==mutate_word(v,m['start']+positions['gate'],0x14000000+delta)
        out,_=run('relocated-restore-'+str(i),vp,mode='restore');assert out.read_bytes()==v
    alias,_=elf(code,aliases=True);run('symbol-table-aliases',alias)
    wrongname,_=elf(code,symbol=b'_ZN7android14SurfaceFlinger17unrelatedFunctionEv')
    run('same-code-wrong-function',wrongname,False)
    short,_=elf(code,function_size=labels['store']);run('truncated-function-boundary',short,False)
    duplicate,_=elf(code+code);run('ambiguous-clamp',duplicate,False,'patch',True)
    for name,label,change in [
        ('wrong-predicate-producer','impl_call',lambda w:NOP),
        ('wrong-predicate-bit','gate',lambda w:w|(1<<19)),
        ('wrong-predicate-register','gate',lambda w:w|2),
        ('wrong-exit-target','limit_exit',lambda w:w+32),
        ('wrong-min-condition','min',lambda w:w^(1<<12)),
        ('wrong-min-dataflow','cmp_min',lambda w:w^(1<<5)),
        ('wrong-store-field','store',lambda w:w+(1<<10)),
        ('wrong-store-base','store',lambda w:w^(1<<5)),
        ('wrong-virtual-call','call',lambda w:w^(1<<5)),
        ('wrong-constant','one',lambda w:w^(1<<13)),
        ('unknown-operation','cmp_limit',lambda w:0xd5033fdf),
    ]:
        off=meta['start']+labels[label]
        run(name,mutate_word(data,off,change(getword(data,off))),False,'patch',True)
    outside=bytearray(data);struct.pack_into('<Q',outside,meta['symoff']+32,0x123400)
    run('symbol-outside-segment',outside,False)
    nonexec=mutate_word(data,68,4);run('non-executable-segment',nonexec,False)
    malformed=bytearray(data);malformed[:4]=b'BAD!';run('malformed-magic',malformed,False)
    elf32=bytearray(data);elf32[4]=1;run('unsupported-architecture',elf32,False)
    no_sections=bytearray(data);struct.pack_into('<Q',no_sections,40,0);run('no-function-symbols',no_sections,False)
    bad=bytearray(data);struct.pack_into('<Q',bad,meta['shoff']+128+24,len(data)+1);run('bad-symbol-table-range',bad,False)
    bad=bytearray(data);struct.pack_into('<I',bad,meta['symoff']+24,0xffffffff);run('bad-string-index',bad,False)
    bad=bytearray(data);bad[meta['stroff']+len(SYMBOL)+1]=65;run('unterminated-symbol',bad,False)
    bad=bytearray(data);struct.pack_into('<Q',bad,64+32,0xffffffffffffffff);run('load-size-overflow',bad,False)
    bad=bytearray(data);struct.pack_into('<Q',bad,64+16,0xfffffffffffffff0);run('load-address-overflow',bad,False)
    bad=bytearray(data);struct.pack_into('<Q',bad,meta['symoff']+40,0xffffffffffffffff);run('function-size-overflow',bad,False)
    # Identical-looking bytes elsewhere cannot create ambiguity outside the symbol.
    decoy,_=elf(code+code,function_size=len(code));run('ignore-outside-function',decoy)
    sample=os.environ.get('HDR_PATCH_SAMPLE')
    if sample:
        owned=Path(sample).read_bytes()
        run('owned-library-probe',owned)
        out,_=run('owned-library-patch',owned,mode='patch');p=out.read_bytes()
        out,_=run('owned-library-restore',owned,mode='restore');o=out.read_bytes()
        assert len(p)==len(o)==len(owned)
        assert sum(a!=b for a,b in zip(p,o))<=4 and p!=o
        out,_=run('owned-library-roundtrip',o,mode='patch');assert out.read_bytes()==p
        assert Path(sample).read_bytes()==owned
    print('ALL PASS',passes)
