from pathlib import Path
from zipfile import ZipFile,ZipInfo,ZIP_DEFLATED
root=Path(__file__).resolve().parent
assets=root/'src/main/assets';assets.mkdir(parents=True,exist_ok=True)
with ZipFile(assets/'backend-test.zip','w',ZIP_DEFLATED) as z:
    for path in sorted((root/'root-module').rglob('*')):
        if not path.is_file(): continue
        info=ZipInfo(path.relative_to(root/'root-module').as_posix(),(2026,1,1,0,0,0))
        info.create_system=3
        info.external_attr=(0o100755 if path.suffix=='.sh' else 0o100644)<<16
        info.compress_type=ZIP_DEFLATED
        z.writestr(info,path.read_bytes())
