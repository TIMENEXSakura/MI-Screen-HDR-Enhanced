"""Installer migration accepts only byte-exact normalization to its stock backup."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
def digest(data): return hashlib.sha256(data).hexdigest()
with tempfile.TemporaryDirectory(prefix='hdr-upgrade-') as folder:
    folder=Path(folder);module=folder/'module';shutil.copytree(ROOT/'root-payload',module)
    patcher=module/'bin/hdrpatch';patcher.parent.mkdir(exist_ok=True)
    patcher.write_text('''#!/bin/sh
test "$1" = restore || exit 2
case "$(cat "$2")" in
 legacy) printf stock > "$3";;
 corrupt) printf other > "$3";;
 unstable) printf stock > "$3"; printf changed > "$2";;
 *) exit 1;;
esac
''')
    visible=folder/'visible.so'
    command='. "$MODDIR/common.sh"; hdr_install_matches "$CHECK_FILE" "$CHECK_HASH" "$STOCK" "$PATCHED"'
    for value,accepted in [(b'stock',True),(b'combined',True),(b'legacy',True),(b'corrupt',False),(b'unknown',False),(b'unstable',False)]:
        visible.write_bytes(value)
        env=dict(os.environ,MODDIR=str(module),CHECK_FILE=str(visible),CHECK_HASH=digest(value),STOCK=digest(b'stock'),PATCHED=digest(b'combined'))
        result=subprocess.run(['/bin/sh','-c',command],env=env,capture_output=True,text=True)
        assert (result.returncode==0)==accepted,(value,result.stdout,result.stderr)
        assert not list(module.glob('.verify.*')), 'temporary verification files leaked'
        if value!=b'unstable':assert visible.read_bytes()==value
    patcher.unlink();visible.write_bytes(b'legacy');env.update(CHECK_HASH=digest(b'legacy'))
    assert subprocess.run(['/bin/sh','-c',command],env=env).returncode!=0
print('PASS installer migration: stock, combined, verified legacy, corrupt/unstable/unsupported/missing patcher rejection')
