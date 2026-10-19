#!/usr/bin/env python3
"""Scans directories of .jsonl recordings and writes recordings.json for the page's "recordings" panel.
usage: gen_index.py [dir ...]   (default: data/)   Output: recordings.json next to this script (urls are relative to index.html)."""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
dirs = sys.argv[1:] or [os.path.join(HERE, 'data')]
out = []
for di, d in enumerate(dirs):
    tag = os.path.basename(os.path.normpath(d)) if len(dirs) > 1 and di > 0 else ''
    for name in sorted(os.listdir(d)):
        if not name.endswith('.jsonl'): continue
        path = os.path.join(d, name); c = {'ev': 0, 'node': 0, 'version': 0, 'phase': 0, 'derive': 0, 'edge': 0, 'sym': 0}; meta = {}; jvm = 0; classes = set()
        with open(path, encoding='utf-8') as f:
            for line in f:
                if line.startswith('{"t":"meta"'):
                    try: meta = json.loads(line)
                    except Exception: pass
                else:
                    k = line[6:line.index('"', 6)] if line.startswith('{"t":"') else None
                    if k == 'version' and line.startswith('{"t":"version","ir":"jvm"'):
                        jvm += 1; i = line.find('"class":"'); classes.add(line[i + 9:line.index('"', i + 9)]) if i > 0 else None
                    elif k in c: c[k] += 1
        units = meta.get('units', [])
        out.append({'name': (tag + '/' if tag else '') + name[:-6], 'jvm': jvm, 'classes': len(classes), 'url': os.path.relpath(path, HERE), 'size': os.path.getsize(path), 'schema': meta.get('schema', 'v0'),
                    'units': [os.path.basename(u.get('path', '')) for u in units], 'phases': c['phase'], 'events': c['ev'], 'nodes': c['node'], 'versions': c['version'], 'edges': c['edge']})
with open(os.path.join(HERE, 'recordings.json'), 'w') as f: json.dump(out, f, indent=1)
print(len(out), 'recordings ->', os.path.join(HERE, 'recordings.json'))
