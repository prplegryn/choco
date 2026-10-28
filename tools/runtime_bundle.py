#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    mode = sys.argv[1]
    if mode == 'create':
        dest = ROOT / '.delivery/runtime-arm64-v1.zip'
        dest.parent.mkdir(exist_ok=True)
        files = [ROOT / 'app/src/main/assets/runtime.zip', ROOT / 'app/src/main/assets/runtime-provenance.json'] + sorted((ROOT / 'app/src/main/jniLibs/arm64-v8a').glob('*.so'))
        with zipfile.ZipFile(dest, 'w', zipfile.ZIP_STORED) as z:
            for file in files: z.write(file, file.relative_to(ROOT).as_posix())
        lock = {'tag': 'runtime-v1', 'asset': dest.name, 'sha256': hashlib.sha256(dest.read_bytes()).hexdigest(),
                'files': {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in files},
                'provenance': json.loads((ROOT / 'app/src/main/assets/runtime-provenance.json').read_text())}
        (ROOT / 'runtime-lock.json').write_text(json.dumps(lock, indent=2) + '\n')
        print('Runtime bundle:', dest.stat().st_size, 'bytes', lock['sha256'])
    elif mode == 'verify':
        archive = Path(sys.argv[2]); lock = json.loads((ROOT / 'runtime-lock.json').read_text())
        assert hashlib.sha256(archive.read_bytes()).hexdigest() == lock['sha256'], 'Runtime bundle checksum mismatch'
        with zipfile.ZipFile(archive) as z:
            assert set(z.namelist()) == set(lock['files']), 'Unexpected runtime files'
            for name, sha in lock['files'].items():
                data = z.read(name); assert hashlib.sha256(data).hexdigest() == sha, name
                dest = ROOT / name; dest.parent.mkdir(parents=True, exist_ok=True); dest.write_bytes(data)
        print('Offline runtime verified and restored')
    else: raise SystemExit('Usage: runtime_bundle.py create|verify path')

if __name__ == '__main__': main()
