#!/usr/bin/env python3
"""Private repository, fixed signing, Release transport and exact-run delivery.

Reads GH_TOKEN and GITHUB_USERNAME from the environment; never writes credentials
into git remotes, source files, logs or command-line arguments.
"""
import base64
import ctypes
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
USER = os.environ['GITHUB_USERNAME']
REPO = USER + '/Chocola'
TOKEN = os.environ['GH_TOKEN']
SIGNING = Path.home() / '.local/share/chocola-signing'

class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected and urllib.parse.urlparse(newurl).netloc != 'api.github.com':
            redirected.remove_header('Authorization')
        return redirected

OPENER = urllib.request.build_opener(SafeRedirect())

def api(path, method='GET', data=None, binary=False, content_type=None, accept=None):
    url = path if path.startswith('https://') else 'https://api.github.com' + path
    body = data if isinstance(data, bytes) else json.dumps(data).encode() if data is not None else None
    headers = {'Authorization': 'Bearer ' + TOKEN, 'Accept': accept or ('application/octet-stream' if binary else 'application/vnd.github+json'),
               'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'Chocola-build'}
    if body is not None: headers['Content-Type'] = content_type or 'application/json'
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with OPENER.open(request, timeout=180) as response:
            raw = response.read()
            return raw if binary else json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        # GitHub messages contain no request Authorization headers.
        detail = error.read().decode(errors='replace')[:700]
        raise RuntimeError(f'GitHub {error.code}: {detail}') from None

def create_repo():
    try: repo = api('/repos/' + REPO)
    except RuntimeError as error:
        if 'GitHub 404:' not in str(error): raise
        repo = api('/user/repos', 'POST', {'name': 'Chocola', 'private': True, 'description': 'Native Android chat and agent with an offline Linux userspace', 'auto_init': False})
    print(json.dumps({'repository': repo['full_name'], 'private': repo['private']}))

def signing():
    SIGNING.mkdir(parents=True, exist_ok=True); SIGNING.chmod(0o700)
    config = SIGNING / 'signing.json'
    key = SIGNING / 'chocola.p12'
    if config.exists(): password = json.loads(config.read_text())['password']
    else:
        password = secrets.token_urlsafe(36)
        config.write_text(json.dumps({'password': password, 'alias': 'chocola'})); config.chmod(0o600)
    if not key.exists():
        env = os.environ.copy(); env['CHOCOLA_SIGNING_PASSWORD'] = password
        subprocess.run(['keytool', '-genkeypair', '-keystore', str(key), '-storetype', 'PKCS12', '-alias', 'chocola',
                        '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', 'CN=Chocola,O=Chocola',
                        '-storepass:env', 'CHOCOLA_SIGNING_PASSWORD', '-keypass:env', 'CHOCOLA_SIGNING_PASSWORD'], env=env, check=True, capture_output=True)
        key.chmod(0o600)
    public = api('/repos/' + REPO + '/actions/secrets/public-key')
    pk = base64.b64decode(public['key'])
    sodium = ctypes.CDLL('/data/data/com.termux/files/usr/lib/libsodium.so')
    sodium.sodium_init()
    sodium.crypto_box_seal.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_ulonglong, ctypes.c_void_p]
    sodium.crypto_box_seal.restype = ctypes.c_int
    for name, value in {'CHOCOLA_KEYSTORE_BASE64': base64.b64encode(key.read_bytes()).decode(), 'CHOCOLA_SIGNING_PASSWORD': password}.items():
        raw = value.encode(); cipher = ctypes.create_string_buffer(len(raw) + 48)
        assert sodium.crypto_box_seal(cipher, raw, len(raw), pk) == 0
        api('/repos/' + REPO + '/actions/secrets/' + name, 'PUT', {'encrypted_value': base64.b64encode(cipher.raw).decode(), 'key_id': public['key_id']})
    print('Fixed signing key stored locally and in encrypted repository secrets')

def push():
    subprocess.run(['git', 'config', 'user.name', USER], cwd=ROOT, check=True)
    subprocess.run(['git', 'config', 'user.email', USER + '@users.noreply.github.com'], cwd=ROOT, check=True)
    remote = 'https://github.com/' + REPO + '.git'
    present = subprocess.run(['git', 'remote', 'get-url', 'origin'], cwd=ROOT, capture_output=True).returncode == 0
    subprocess.run(['git', 'remote', 'set-url' if present else 'add', 'origin', remote], cwd=ROOT, check=True)
    with tempfile.TemporaryDirectory(prefix='chocola-git-') as tmp:
        helper = Path(tmp) / 'askpass.sh'
        helper.write_text('#!/bin/sh\ncase "$1" in *Username*) printf "%s" x-access-token ;; *) printf "%s" "$GH_TOKEN" ;; esac\n')
        helper.chmod(0o700)
        env = os.environ.copy(); env['GIT_ASKPASS'] = str(helper); env['GIT_TERMINAL_PROMPT'] = '0'
        subprocess.run(['git', 'push', '-u', 'origin', 'main'], cwd=ROOT, env=env, check=True)

def upload_runtime():
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    release = api('/repos/' + REPO + '/releases', 'POST', {'tag_name': 'runtime-v1', 'target_commitish': sha, 'name': 'Offline Linux runtime 1',
                  'body': 'Verified arm64 Alpine userspace and native PRoot entry points. Sources and build recipes are included. Rebuilt from tools/prepare_runtime.py; exact file digests are recorded in runtime-lock.json.', 'prerelease': True})
    upload = release['upload_url'].split('{')[0]
    for filename in ['runtime-arm64-v1.zip', 'Chocola-runtime-sources.tar.gz']:
        p = ROOT / '.delivery' / filename
        result = api(upload + '?name=' + urllib.parse.quote(p.name), 'POST', p.read_bytes(), content_type='application/octet-stream')
        print('Uploaded', result['name'], result['size'])

def dispatch():
    api('/repos/' + REPO + '/actions/workflows/android.yml/dispatches', 'POST', {'ref': 'main'})
    print('Dispatched Android build on main')

def status():
    runs = api('/repos/' + REPO + '/actions/runs?per_page=5')['workflow_runs']
    print(json.dumps([{'id': r['id'], 'number': r['run_number'], 'sha': r['head_sha'], 'status': r['status'], 'conclusion': r['conclusion'], 'url': r['html_url']} for r in runs], indent=2))

def download(tag):
    release = api('/repos/' + REPO + '/releases/tags/' + tag)
    dest = Path.home() / 'storage/downloads/apksync'
    dest.mkdir(parents=True, exist_ok=True)
    expected = ['Chocola.apk', 'Chocola.apk.sha256', 'Chocola-build.json', 'Chocola-signature.txt', 'Chocola-mapping.txt', 'Chocola-design.md', 'Chocola-research.md', 'Chocola-models.md', 'Chocola-runtime.json']
    local_names = {'Chocola-design.md':'Chocola-设计说明.md', 'Chocola-research.md':'Chocola-研究依据.md', 'Chocola-models.md':'Chocola-模型预设.md'}
    for name in expected:
        asset = next(a for a in release['assets'] if a['name'] == name)
        data = api('/repos/' + REPO + '/releases/assets/' + str(asset['id']), binary=True)
        local_name = local_names.get(name,name)
        temp = dest / (local_name + '.part'); temp.write_bytes(data); temp.replace(dest / local_name)
        print('Synced', name, len(data))
    meta = json.loads((dest / 'Chocola-build.json').read_text())
    actual = hashlib.sha256((dest / 'Chocola.apk').read_bytes()).hexdigest()
    assert actual == meta['sha256'], 'APK digest does not match release metadata'
    subprocess.run(['apksigner', 'verify', '--verbose', '--print-certs', str(dest / 'Chocola.apk')], check=True)
    print(json.dumps({'tag': tag, 'sha256': actual, 'path': str(dest / 'Chocola.apk'), 'release': release['html_url']}, ensure_ascii=False))

def main():
    action = sys.argv[1]
    if action == 'create': create_repo()
    elif action == 'signing': signing()
    elif action == 'push': push()
    elif action == 'runtime': upload_runtime()
    elif action == 'dispatch': dispatch()
    elif action == 'status': status()
    elif action == 'download': download(sys.argv[2])
    elif action == 'logs':
        run = sys.argv[2]
        data = api('/repos/' + REPO + '/actions/runs/' + run + '/logs', binary=True, accept='application/vnd.github+json')
        path = ROOT / '.delivery' / ('run-' + run + '.zip'); path.parent.mkdir(exist_ok=True); path.write_bytes(data); print(path)
    else: raise SystemExit('Unknown action')

if __name__ == '__main__': main()
