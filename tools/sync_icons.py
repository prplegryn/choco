#!/usr/bin/env python3
"""Vendor a small, pinned Lucide subset as Android vectors; no runtime icon library."""
import concurrent.futures
import json
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MAPPING = {
    'menu': 'panel-left', 'plus': 'plus', 'new': 'plus', 'close': 'x',
    'left': 'chevron-left', 'right': 'chevron-right', 'down': 'chevron-down', 'up': 'chevron-up',
    'send': 'arrow-up', 'stop': 'square', 'more': 'ellipsis', 'copy': 'copy', 'check': 'check',
    'search': 'search', 'folder': 'folder', 'file': 'file-text', 'terminal': 'terminal',
    'memory': 'notebook-text', 'settings': 'settings', 'sliders': 'sliders-horizontal',
    'refresh': 'rotate-ccw', 'undo': 'undo-2', 'edit': 'square-pen', 'trash': 'trash',
    'lock': 'lock-keyhole', 'unlock': 'lock-keyhole-open', 'key': 'key-round', 'globe': 'globe',
    'attach': 'paperclip', 'pin': 'pin', 'share': 'share-2', 'external': 'external-link',
    'download': 'download', 'upload': 'upload', 'play': 'play', 'info': 'info',
    'eye': 'eye', 'eye-off': 'eye-off', 'background': 'activity', 'notification': 'bell', 'battery': 'battery', 'repeat': 'repeat-2', 'context': 'list-collapse',
}
MAPPING.update({'models': 'bot', 'tone': 'messages-square', 'display': 'palette', 'skills': 'puzzle', 'mcp': 'plug', 'backup': 'archive', 'font': 'type', 'wallpaper': 'image', 'glass': 'droplets', 'indicator': 'sparkles', 'server': 'server', 'wallet': 'wallet', 'shield': 'shield-check'})
CUSTOM = {'refresh': 'chocola-refresh.svg'}

def fetch(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'Chocola-icon-source'})
    with urllib.request.urlopen(request, timeout=40) as response:
        return response.read()

def n(value):
    return format(float(value), 'g')

def path_data(node):
    a = node.attrib
    tag = node.tag.rsplit('}', 1)[-1]
    if tag == 'path': return a['d']
    if tag == 'line': return f"M{a['x1']} {a['y1']} L{a['x2']} {a['y2']}"
    if tag in ('polyline', 'polygon'):
        points = a['points'].replace(',', ' ').split()
        return 'M' + ' L'.join(' '.join(points[i:i+2]) for i in range(0, len(points), 2)) + (' Z' if tag == 'polygon' else '')
    if tag in ('circle', 'ellipse'):
        x,y = float(a['cx']),float(a['cy'])
        rx,ry = float(a.get('r', a.get('rx'))),float(a.get('r', a.get('ry')))
        return f'M{n(x-rx)} {n(y)} a{n(rx)} {n(ry)} 0 1 0 {n(2*rx)} 0 a{n(rx)} {n(ry)} 0 1 0 {n(-2*rx)} 0 Z'
    if tag == 'rect':
        x,y,w,h = (float(a.get(k,0)) for k in ('x','y','width','height'))
        r = min(float(a.get('rx',0)),w/2,h/2)
        return f'M{n(x+r)} {n(y)} H{n(x+w-r)} Q{n(x+w)} {n(y)} {n(x+w)} {n(y+r)} V{n(y+h-r)} Q{n(x+w)} {n(y+h)} {n(x+w-r)} {n(y+h)} H{n(x+r)} Q{n(x)} {n(y+h)} {n(x)} {n(y+h-r)} V{n(y+r)} Q{n(x)} {n(y)} {n(x+r)} {n(y)} Z'
    raise ValueError(f'Unsupported SVG element: {tag}')

def main():
    source_dir = ROOT / 'design/icons'
    source_dir.mkdir(parents=True, exist_ok=True)
    lock = source_dir / 'lucide.json'
    revision = json.loads(lock.read_text())['revision'] if lock.exists() else '49161aba73f6dcba95907de16b07cb06b64088cc'
    base = f'https://raw.githubusercontent.com/lucide-icons/lucide/{revision}/'
    names = sorted(set(MAPPING.values()))
    def get(name):
        path = source_dir / (name + '.svg')
        if not path.exists(): path.write_bytes(fetch(base + 'icons/' + name + '.svg'))
        return name, path.read_text()
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        sources = dict(pool.map(get, names))
    license_text = fetch(base + 'LICENSE')
    (source_dir / 'LICENSE').write_bytes(license_text)
    (ROOT / 'app/src/main/assets/lucide-license.txt').write_bytes(license_text)
    drawable = ROOT / 'app/src/main/res/drawable'
    for alias, name in MAPPING.items():
        root = ET.fromstring((source_dir / CUSTOM[alias]).read_text() if alias in CUSTOM else sources[name])
        attribution = '<!-- Chocola custom 24-unit glyph. Source: design/icons/' + CUSTOM[alias] + '. -->' if alias in CUSTOM else '<!-- Lucide Icons. ISC / Feather MIT. See assets/lucide-license.txt. -->'
        lines = [attribution, '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">']
        for node in root:
            data = path_data(node).replace('"','&quot;')
            lines.append('    <path android:fillColor="@android:color/transparent" android:strokeColor="#222326" android:strokeWidth="1.75" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="' + data + '" />')
        lines.append('</vector>')
        (drawable / f'ic_{alias.replace("-","_")}.xml').write_text('\n'.join(lines)+'\n')
    lock.write_text(json.dumps({'repository':'https://github.com/lucide-icons/lucide','revision':revision,'strokeWidth':1.75,'icons':MAPPING,'customOverrides':CUSTOM},indent=2)+'\n')
    print(f'Vendored {len(sources)} Lucide glyphs at {revision}')

if __name__ == '__main__': main()
