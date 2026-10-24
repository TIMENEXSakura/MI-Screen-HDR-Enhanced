#!/usr/bin/env python3
"""Build the embedded KSU/Magisk payload from the distributed source."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

module = Path(__file__).resolve().parent.parent
with ZipFile(module / 'assets/mount-template.zip', 'w', ZIP_DEFLATED) as archive:
    for path in sorted((module / 'root-payload').rglob('*')):
        if not path.is_file():
            continue
        entry = ZipInfo(path.relative_to(module / 'root-payload').as_posix(), (2026, 1, 1, 0, 0, 0))
        entry.create_system = 3
        entry.external_attr = (0o100755 if path.suffix == '.sh' or path.parent.name == 'bin' else 0o100644) << 16
        entry.compress_type = ZIP_DEFLATED
        archive.writestr(entry, path.read_bytes())
