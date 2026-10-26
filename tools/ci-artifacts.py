"""Export only public build artifacts; signing stays outside the cloud builder."""
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
dist = root / 'dist'
version = re.search(r'versionName = "([^"]+)"', (root / 'build.gradle.kts').read_text())[1]
commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
apk = dist / f'MI-Screen-HDR-Enhanced-{version}-unsigned.apk'
(dist / 'unsigned.apk').rename(apk)
source = dist / f'MI-Screen-HDR-Enhanced-{version}-source.zip'
subprocess.run(['git', 'archive', '--format=zip', f'--prefix=MI-Screen-HDR-Enhanced-{version}/', '-o', str(source), commit], cwd=root, check=True)
run = f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
info = {
    'version': version, 'commit': commit, 'run_url': run,
    'run_attempt': os.environ['GITHUB_RUN_ATTEMPT'], 'runner': os.environ['RUNNER_OS'],
    'ndk': os.environ['HDR_NDK_VERSION'], 'compile_sdk': 37, 'build_tools': '37.0.0',
    'signing': 'unsigned cloud output; release signing is performed separately',
    'unsigned_apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
}
(dist / 'build-info.json').write_text(json.dumps(info, indent=2) + '\n')
with (dist / 'SHA256SUMS.txt').open('w') as stream:
    for path in (apk, source, dist / 'build-info.json'):
        stream.write(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name + '\n')
with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
    stream.write(f'Built **{version}** from `{commit}`.\n\n')
    stream.write('Host tests and API 102 / native packaging checks passed. The APK artifact is unsigned and requires signing before installation. This workflow does not publish a release.\n\n')
    stream.write(f'Unsigned APK SHA-256: `{info["unsigned_apk_sha256"]}`\n')
