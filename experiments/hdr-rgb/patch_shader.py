#!/usr/bin/env python3
"""Build an offline RGB gainmap shader candidate. Never installs or restarts SF.

Only the audited nezha library builds are supported. The shader uses the existing
same-frame linear SDR/HDR inputs and existing uniform/metadata ABI. Its gain
interval stays [1, native headroom]; gains below 1 remain unrepresentable.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct

KNOWN = {
    '43598f06ca2c3c96b9eb243e4acb177a5ee77c01794d1cbac92c3934ae32c40e': 'stock',
    '1d268533b1c5f0268f36c57d967d044525b01aef802b47fece637c0e35e701d0': 'existing_clamp_patch',
}
OFFSET = 0x164682
LENGTH = 711
ROOT = Path(__file__).resolve().parents[2]
ORIGINAL = Path(__file__).with_name('original_gainmap.sksl').read_bytes()
SHADER = (ROOT/'native/shaders/rgb_gainmap.sksl').read_bytes()

def digest(data):
    return hashlib.sha256(data).hexdigest()

def inspect(data):
    if len(ORIGINAL) != LENGTH or len(SHADER) > LENGTH:
        raise ValueError('shader template length mismatch')
    if len(data) < OFFSET + LENGTH + 1 or data[:7] != b'\x7fELF\x02\x01\x01':
        raise ValueError('not a supported ELF64 library')
    if struct.unpack_from('<HH', data, 16) != (3, 183):
        raise ValueError('requires AArch64 ET_DYN')
    if data[OFFSET + LENGTH] != 0:
        raise ValueError('shader terminator mismatch')
    candidate = SHADER.ljust(LENGTH, b' ')
    before = data[OFFSET:OFFSET + LENGTH]
    if before == ORIGINAL:
        state = 'original_luminance'
    elif before == candidate:
        state = 'rgb_candidate'
    else:
        raise ValueError('unexpected shader bytes')
    normalized = data[:OFFSET] + ORIGINAL + data[OFFSET + LENGTH:]
    base_hash = digest(normalized)
    if base_hash not in KNOWN:
        raise ValueError('unreviewed library fingerprint; no output written')
    # No executable/writable data or relocations may share the replacement.
    phoff = struct.unpack_from('<Q', data, 32)[0]
    entsize, count = struct.unpack_from('<HH', data, 54)
    if entsize != 56 or not 0 < count <= 1024 or phoff + count*56 > len(data):
        raise ValueError('invalid program headers')
    matches = []
    for i in range(count):
        kind, flags, off, va, _, size, _, _ = struct.unpack_from('<IIQQQQQQ', data, phoff + i*56)
        if kind == 1 and off <= OFFSET and OFFSET + LENGTH + 1 <= off + size:
            if flags != 4 or off + size > len(data):
                raise ValueError('shader must occupy a read-only PT_LOAD segment')
            matches.append(va + OFFSET - off)
    if len(matches) != 1:
        raise ValueError('ambiguous shader mapping')
    return {'state': state, 'base': KNOWN[base_hash], 'base_sha256': base_hash,
            'input_sha256': digest(data), 'file_offset': OFFSET, 'elf_va': matches[0],
            'replacement_span': LENGTH, 'shader_length': len(SHADER)}

def transform(data, restore=False):
    report = inspect(data)
    replacement = ORIGINAL if restore else SHADER.ljust(LENGTH, b' ')
    result = data[:OFFSET] + replacement + data[OFFSET + LENGTH:]
    checked = inspect(result)
    report.update(output_sha256=digest(result), output_state=checked['state'],
                  installed=False, limitations=['Existing gain bounds remain unchanged.',
                      'GPU fixture validation does not prove real SurfaceFlinger execution.',
                      'Cannot recover vendor effects absent from the HDR input buffer.'])
    return result, report

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('operation', choices=['probe', 'patch', 'restore'])
    p.add_argument('input', type=Path)
    p.add_argument('--out', type=Path)
    a = p.parse_args()
    data = a.input.read_bytes()
    if a.operation == 'probe':
        if a.out:
            p.error('probe does not write an output')
        report = inspect(data)
    else:
        if not a.out or a.out.resolve() == a.input.resolve():
            p.error('supply a distinct new output file')
        result, report = transform(data, a.operation == 'restore')
        # Refuse any existing file, including a symlink; never overwrite a library.
        try:
            with a.out.open('xb') as f:
                f.write(result)
                f.flush()
                os.fsync(f.fileno())
        except FileExistsError:
            raise
        except Exception:
            a.out.unlink(missing_ok=True)
            raise
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    main()
