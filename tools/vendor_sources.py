#!/usr/bin/env python3
"""Keep the native runtime's upstream source snapshots and exact build recipes."""
import hashlib
import io
import json
from pathlib import Path
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / '.delivery'
SOURCES = [
    ('proot-5.1.107.98.zip', 'https://codeload.github.com/termux/proot/zip/refs/tags/v5.1.107.98', '9de61bddd6f5016d065e2755b72ff4747290e5f992562f031785b107b51d02ba'),
    ('talloc-2.5.0.tar.gz', 'https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz', '912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007'),
    ('libandroid-shmem-0.7.tar.gz', 'https://codeload.github.com/termux/libandroid-shmem/tar.gz/refs/tags/v0.7', '1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867'),
]

def main():
    DEST.mkdir(exist_ok=True)
    manifest=[]
    with tarfile.open(DEST / 'Chocola-runtime-sources.tar.gz', 'w:gz') as tar:
        def add(name, data):
            info=tarfile.TarInfo(name);info.size=len(data);info.mode=0o644;tar.addfile(info,io.BytesIO(data))
        for name,url,sha in SOURCES:
            path=ROOT/'.runtime'/name
            if not path.exists():
                with urllib.request.urlopen(url,timeout=120) as r:path.write_bytes(r.read())
            data=path.read_bytes();assert hashlib.sha256(data).hexdigest()==sha,name
            add('sources/'+name,data);manifest.append({'name':name,'url':url,'sha256':sha})
            print('Source verified',name,flush=True)
        for package in ['proot','libtalloc','libandroid-shmem']:
            url='https://raw.githubusercontent.com/termux/termux-packages/master/packages/'+package+'/build.sh'
            data=urllib.request.urlopen(url,timeout=45).read()
            if package=='proot':
                text=data.decode().replace('5.1.107.99','5.1.107.98').replace('49951a8f35245eaf8e5b300b237ffb10a328f42641a9cbcd1e285d45e95d84e8','9de61bddd6f5016d065e2755b72ff4747290e5f992562f031785b107b51d02ba')
                data=text.encode()
            add('termux-recipes/'+package+'/build.sh',data)
        installed=ROOT/'.runtime/rootfs/lib/apk/db/installed'
        if not installed.exists():installed=ROOT/'.runtime/rootfs/var/lib/apk/db/installed'
        if installed.exists():add('alpine-installed-packages.txt',installed.read_bytes())
        add('source-manifest.json',json.dumps(manifest,indent=2).encode())
        add('prepare_runtime.py',(ROOT/'tools/prepare_runtime.py').read_bytes())
        add('README.txt',b'Native executables are separate subprocess components. PRoot is GPL-2.0-or-later; libtalloc is GPL-3.0-or-later; libandroid-shmem is BSD-3-Clause. Their source snapshots and build recipes are included. Alpine package source recipes are maintained in https://gitlab.alpinelinux.org/alpine/aports on the 3.23-stable branch; source archives are mirrored at https://distfiles.alpinelinux.org/distfiles/v3.23/. The installed package database identifies every packaged version and license. No runtime binary has its license removed. The only binary adjustment renames the talloc SONAME reference for Android APK library packaging, as reproduced in prepare_runtime.py.\n')

if __name__=='__main__':main()
