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
data[64:120]=struct.pack('<IIQQQQQQ',1,5,256,0x710000,0,768,768,4096)
data[320:320+len(sequence)]=sequence
def event(output,kind):
    lines=[line for line in output.splitlines() if line.startswith(kind+' ')]
    assert len(lines)==1,(kind,output)
    return dict(token.split('=',1) for token in lines[0].split()[1:])
def build_id_fixture(malformed=False, section=False):
    buf=bytearray(data)
    note=struct.pack('<III',4,0xfffffff0 if malformed else 20,3)+b'GNU\0'+bytes(range(20))
    buf[192:192+len(note)]=note
    if section:
        struct.pack_into('<Q',buf,40,0x300)
        struct.pack_into('<HH',buf,58,64,2)
        buf[0x340:0x380]=struct.pack('<IIQQQQIIQQ',0,7,0,0,192,len(note),0,0,4,0)
    else:
        struct.pack_into('<H',buf,56,2)
        buf[120:176]=struct.pack('<IIQQQQQQ',4,4,192,0,0,len(note),len(note),4)
    return buf
with tempfile.TemporaryDirectory(prefix='hdr-probe-') as temp:
    temp=Path(temp)
    exe=temp/'hdrpatch'
    subprocess.run(shlex.split(os.environ.get('CC','cc'))+['-O2','-o',str(exe),str(root/'native/hdrpatch.c')],check=True)
    results={}
    def run(name,contents,success,mode='probe'):
        path=temp/(name+'.so');path.write_bytes(contents);out=temp/(name+'-patched.so')
        args=[str(exe),mode,str(path)]+([] if mode=='probe' else [str(out)])
        result=subprocess.run(args,capture_output=True,text=True)
        assert (result.returncode==0)==success,(name,result.stdout,result.stderr)
        assert path.read_bytes()==contents
        if mode=='probe' or not success:assert not out.exists()
        results[name]=result
        assert ('PATCH_OK ' in result.stdout)==(success and mode!='probe')
        assert ('PATCH_MATCH ' in result.stdout)==(success and mode=='probe')
        print('PASS',name.replace('\n','\\n'))
        return out
    run('read-only-probe',data,True)
    report=results['read-only-probe'].stdout
    assert event(report,'ELF_INFO')=={'class':'64','endian':'little','machine':'AARCH64','machine_id':'183','type':'ET_DYN','type_id':'3','size':'1024'}
    assert event(report,'ELF_SEGMENT')=={'index':'0','file_offset':'0x100','file_end':'0x400','elf_va':'0x710000','elf_va_end':'0x710300','elf_mem_end':'0x710300','flags':'R-X'}
    assert event(report,'PATCH_SCAN')['candidates']=='1'
    assert event(report,'ELF_SYMBOL')['status']=='unavailable'
    detail=event(report,'PATCH_DETAIL')
    assert detail['file_offset']=='0x14c' and detail['elf_va']=='0x71004c'
    assert detail['current_word']==detail['original_word']=='0x360001a0'
    assert detail['patched_word']=='0x1400000d'
    assert detail['branch_target_file_offset']=='0x180' and detail['branch_target_elf_va']=='0x710080'
    assert detail['effect']=='skip_capture_brightness_clamp' and detail['runtime_address']=='not_resolved'
    assert event(report,'PATCH_MATCH')['offset']=='0x14c'
    assert 'phase=write ' not in report and 'phase=commit ' not in report
    out=run('patch',data,True,'patch')
    patched=out.read_bytes();assert patched[332:336]==array('patched')
    report=results['patch'].stdout
    stages=[dict(token.split('=',1) for token in line.split()[1:]) for line in report.splitlines() if line.startswith('PATCH_STAGE ') and 'operation=' in line]
    assert [(stage['phase'],stage['status']) for stage in stages]==[(phase,state) for phase in ('prepare','write','fsync','commit') for state in ('started','complete')]
    assert stages[3]['bytes']==stages[3]['total']=='1024'
    assert report.index('phase=commit status=complete')<report.index('PATCH_OK ')
    run('already-patched-probe',patched,True)
    assert event(results['already-patched-probe'].stdout,'PATCH_DETAIL')['current_word']=='0x1400000d'
    out=run('restore',patched,True,'restore');assert out.read_bytes()==data
    assert 'effect=restore_capture_brightness_clamp' in results['restore'].stdout
    missing=bytearray(data);missing[320:332]=bytes(12);run('no-match',missing,False)
    run('no-match-no-output',missing,False,'patch')
    duplicate=bytearray(data);duplicate[512:512+len(sequence)]=sequence;run('ambiguous',duplicate,False)
    for name,count,reason in [('no-match',0,'unrecognized_capture_implementation'),('ambiguous',2,'ambiguous_clamp_sequence')]:
        fields=event(results[name].stdout,'PATCH_SCAN')
        assert fields['candidates']==str(count) and fields['decision']=='reject' and fields['reason']==reason
    elf32=bytearray(data);elf32[4]=1;struct.pack_into('<H',elf32,18,40);run('32-bit-diagnostic',elf32,False)
    assert event(results['32-bit-diagnostic'].stdout,'ELF_INFO')['machine']=='ARM'
    assert '仅支持小端 64 位 ARM64' in results['32-bit-diagnostic'].stderr
    bad=bytearray(data);bad[:4]=b'BAD!';run('malformed',bad,False)
    assert 'ELF 文件头标识损坏' in results['malformed'].stderr
    for section in (False,True):
        name='section-build-id' if section else 'segment-build-id'
        run(name,build_id_fixture(section=section),True)
        assert event(results[name].stdout,'ELF_BUILD_ID')=={'value':bytes(range(20)).hex(),'bytes':'20','truncated':'false'}
    run('truncated-build-id',build_id_fixture(malformed=True),True)
    assert event(results['truncated-build-id'].stdout,'ELF_BUILD_ID')['status']=='unavailable'
    run('escaped\nPATCH_OK fake=1',data,True)
    report=results['escaped\nPATCH_OK fake=1'].stdout
    assert '%0APATCH_OK%20fake%3D1.so' in report and '\nPATCH_OK' not in report
    big=bytearray(data);big[5]=2;struct.pack_into('>HH',big,16,3,183)
    run('big-endian-diagnostic',big,False)
    assert event(results['big-endian-diagnostic'].stdout,'ELF_INFO')['machine_id']=='183'
    assert event(results['big-endian-diagnostic'].stdout,'ELF_INFO')['endian']=='big'
    overflow=bytearray(data);struct.pack_into('<Q',overflow,64+16,0xfffffffffffffff0)
    run('overflow-address-diagnostic',overflow,True)
    assert event(results['overflow-address-diagnostic'].stdout,'ELF_SEGMENT')['elf_va_end']=='unavailable'
    assert event(results['overflow-address-diagnostic'].stdout,'PATCH_DETAIL')['elf_va']=='unavailable'
    # A failed atomic rename must not announce a successful commit or leave a
    # partial generated file; the supplied original remains byte-identical.
    source_path=temp/'commit-source.so';source_path.write_bytes(data)
    destination=temp/'existing-directory';destination.mkdir()
    failed=subprocess.run([str(exe),'patch',str(source_path),str(destination)],capture_output=True,text=True)
    assert failed.returncode!=0 and source_path.read_bytes()==data
    assert 'phase=commit status=failed' in failed.stdout and 'PATCH_OK ' not in failed.stdout
    assert '具体失败阶段见上方日志' in failed.stderr
    assert not list(destination.iterdir()) and not list(temp.glob('existing-directory.tmp.*'))
    print('PASS failed-commit-retains-original')
    print('ALL PASS',len(results)+1)
