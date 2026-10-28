#!/usr/bin/env python3
"""Prepare verified native entry points and a real offline Alpine userspace.

On Linux use --qemu with proot + qemu-user-static. On arm64 Android use
the installed proot. No root or Docker is required on the phone.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
CACHE = PROJECT / '.runtime'
NATIVE = PROJECT / 'app/src/main/jniLibs/arm64-v8a'
ASSETS = PROJECT / 'app/src/main/assets'
PACKAGES = {
    'proot': ('pool/main/p/proot/proot_5.1.107.98_aarch64.deb', '29de40efea4daa2a6168b617915206511c63e8d46bf5f4b07d48723441ef7f3b'),
    'libtalloc': ('pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb', '556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7'),
    'libandroid-shmem': ('pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb', '0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6'),
}
BASE = 'https://dl-cdn.alpinelinux.org/alpine/v3.23/releases/aarch64/alpine-minirootfs-3.23.7-aarch64.tar.gz'
BASE_SHA = '7dcc7420bcc426a615c0512cc85b4a52e9f89df9c600b93ce78d22e0e9234891'
TOOLS = ['bash', 'ca-certificates', 'curl', 'git', 'python3', 'py3-pip', 'ripgrep', 'openssh-client-default', 'sshpass', 'coreutils']


def download(url, path, expected):
    if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == expected:
        return
    print('Download', path.name, flush=True)
    for host in [url, url.replace('packages.termux.dev/apt', 'mirrors.krnk.org/apt/termux')]:
        try:
            with urllib.request.urlopen(host, timeout=90) as r:
                data = r.read()
            if hashlib.sha256(data).hexdigest() != expected:
                raise RuntimeError('Checksum mismatch: ' + path.name)
            path.write_bytes(data)
            return
        except Exception:
            if host == url and 'packages.termux.dev' in url:
                continue
            raise


def deb_tar(data):
    assert data[:8] == b'!<arch>\n'
    i = 8
    while i < len(data):
        name = data[i:i+16].decode().strip().rstrip('/')
        size = int(data[i+48:i+58].decode().strip())
        block = data[i+60:i+60+size]
        if name.startswith('data.tar'):
            return tarfile.open(fileobj=io.BytesIO(block), mode='r:*')
        i += 60 + size + size % 2
    raise RuntimeError('No data archive')


def native():
    NATIVE.mkdir(parents=True, exist_ok=True)
    provenance = {}
    for package, (name, sha) in PACKAGES.items():
        path = CACHE / Path(name).name
        url = 'https://packages.termux.dev/apt/termux-main/' + name
        download(url, path, sha)
        with deb_tar(path.read_bytes()) as tar:
            for m in tar:
                if not m.isfile():
                    continue
                dest = None
                if m.name.endswith('/bin/proot'): dest = 'libproot.so'
                elif m.name.endswith('/libexec/proot/loader'): dest = 'libproot-loader.so'
                elif '/lib/libtalloc.so.' in m.name: dest = 'libtalloc.so'
                elif m.name.endswith('/lib/libandroid-shmem.so'): dest = 'libandroid-shmem.so'
                if dest:
                    data = tar.extractfile(m).read()
                    # Keep ELF offsets stable while using Android's lib*.so packaging.
                    data = data.replace(b'libtalloc.so.2\x00', b'libtalloc.so\x00\x00\x00')
                    (NATIVE / dest).write_bytes(data)
                    (NATIVE / dest).chmod(0o755)
        provenance[package] = {'url': url, 'sha256': sha}
    expected = ['libproot.so', 'libproot-loader.so', 'libtalloc.so', 'libandroid-shmem.so']
    assert all((NATIVE / f).exists() for f in expected)
    return provenance


def command(root, qemu, args):
    proot = shutil.which('proot')
    if not proot:
        raise RuntimeError('Install proot before preparing the runtime')
    cmd = [proot, '--link2symlink', '-0', '-r', str(root), '-b', '/dev', '-b', '/proc', '-w', '/']
    if qemu:
        cmd += ['-q', shutil.which('qemu-aarch64-static') or '/usr/bin/qemu-aarch64-static']
    cmd += ['/usr/bin/env', '-i', 'HOME=/root', 'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin', 'LANG=C.UTF-8'] + args
    env = os.environ.copy()
    env.pop('LD_PRELOAD', None)
    env['PROOT_NO_SECCOMP'] = '1'
    if not qemu:
        env['PROOT_LOADER'] = str(NATIVE / 'libproot-loader.so')
    subprocess.run(cmd, env=env, check=True, timeout=600)


def rootfs(qemu):
    root = CACHE / 'rootfs'
    image = ASSETS / 'runtime.zip'
    ready = CACHE / 'runtime-ready'
    if image.exists() and ready.exists():
        try:
            if json.loads(ready.read_text()).get('tools') == TOOLS:
                return
        except (ValueError, TypeError):
            pass
    base = CACHE / 'alpine-minirootfs.tar.gz'
    download(BASE, base, BASE_SHA)
    if root.exists(): shutil.rmtree(root)
    root.mkdir()
    with tarfile.open(base) as tar:
        # Verified vendor archive; special devices are supplied by bind mounts.
        members = [m for m in tar if not (m.ischr() or m.isblk() or m.isfifo())]
        tar.extractall(root, members=members, filter='fully_trusted')
    (root / 'etc/resolv.conf').write_text('nameserver 1.1.1.1\nnameserver 8.8.8.8\n')
    # Use system CA during the package bootstrap; the guest then has its own.
    for ca in ['/etc/ssl/certs/ca-certificates.crt', '/data/data/com.termux/files/usr/etc/tls/cert.pem']:
        if Path(ca).exists():
            (root / 'etc/ssl/cert.pem').parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ca, root / 'etc/ssl/cert.pem')
            break
    command(root, qemu, ['/sbin/apk', 'add', '--no-cache'] + TOOLS)
    (root / 'workspace').mkdir(exist_ok=True)
    (root / 'shared').mkdir(exist_ok=True)
    (root / 'root/.profile').write_text('export LANG=C.UTF-8\nexport PAGER=cat\n')
    command(root, qemu, ['/bin/sh', '-c', 'python3 --version && git --version && curl --version && ssh -V && rg --version'])
    links, executable = {}, []
    ASSETS.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(image, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for path in sorted(root.rglob('*')):
            name = path.relative_to(root).as_posix()
            if path.is_symlink(): links[name] = os.readlink(path)
            elif path.is_file():
                z.write(path, name)
                if path.stat().st_mode & 0o111: executable.append(name)
            elif path.is_dir(): z.writestr(name + '/', b'')
        z.writestr('_chocola_links.json', json.dumps(links))
        z.writestr('_chocola_exec.json', json.dumps(executable))
    ready.write_text(json.dumps({'sha256': hashlib.sha256(image.read_bytes()).hexdigest(), 'tools': TOOLS}))
    print('Runtime image:', image.stat().st_size, 'bytes', flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--qemu', action='store_true')
    parser.add_argument('--native-only', action='store_true')
    args = parser.parse_args()
    CACHE.mkdir(exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    provenance = native()
    if not args.native_only: rootfs(args.qemu)
    provenance['alpine'] = {'url': BASE, 'sha256': BASE_SHA, 'packages': TOOLS}
    if (ASSETS / 'runtime.zip').exists():
        provenance['image_sha256'] = hashlib.sha256((ASSETS / 'runtime.zip').read_bytes()).hexdigest()
    (ASSETS / 'runtime-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')


if __name__ == '__main__': main()
