#!/usr/bin/env python3
"""Scans directories of .jsonl recordings and writes recordings.json for the page's "recordings" panel.
Recordings whose meta.args differ only by compiler options are grouped into one entry with `variants` (base = the one with the fewest options); files without meta.args stay flat, with a name suffix fallback (<base>-<suffix>.jsonl is a variant of <base>.jsonl).
usage: gen_index.py [dir ...]   (default: data/)   Output: recordings.json next to this script (urls are relative to index.html)."""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
dirs = sys.argv[1:] or [os.path.join(HERE, 'data')]
out = []
def norm_args(args):
    """compiler options without the environment-specific ones (output dir, recorder plugin, colour)"""
    r = []
    for a in args or []:
        if a.startswith(('-d=', '-color=', '-Xplugin=', '-P=List(replay-record:')): continue
        r.append(a)
    return r
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
        margs = meta.get('args'); out.append({'dir': d, 'args': margs, 'nargs': norm_args(margs), 'compiler': meta.get('compiler'), 'name': (tag + '/' if tag else '') + name[:-6], 'jvm': jvm, 'classes': len(classes), 'url': os.path.relpath(path, HERE), 'size': os.path.getsize(path), 'schema': meta.get('schema', 'v0'),
                    'units': [os.path.basename(u.get('path', '')) for u in units], 'phases': c['phase'], 'events': c['ev'], 'nodes': c['node'], 'versions': c['version'], 'edges': c['edge']})

# ---- group option variants -------------------------------------------------------------------------------------------------------------
import re
def norm_args(args):
    """compiler options without the environment-specific ones (output dir, recorder plugin, colour)"""
    r = []
    for a in args or []:
        if a.startswith(('-d=', '-color=', '-Xplugin=', '-P=List(replay-record:')): continue
        r.append(a)
    return r
def pretty(a):
    m = re.match(r'^(-[^=]+)=(.*)$', a)
    if not m: return a
    k, v = m.group(1), m.group(2)
    if v == 'true': return k
    mm = re.match(r'^List\((.*)\)$', v)
    return k + ':' + (mm.group(1) if mm else v)
def stats(e): return {k: e[k] for k in ('jvm', 'classes', 'schema', 'phases', 'events', 'nodes', 'versions', 'edges', 'size')}
groups = {}
for e in out:
    if e.get('args') is None: continue
    groups.setdefault((e['dir'], tuple(sorted(e['units'])), e.get('compiler')), []).append(e)
merged, used = {}, set()
for key, es in groups.items():
    if len(es) < 2: continue
    es.sort(key=lambda e: (len(e['nargs']), len(e['name'])))
    base = es[0]; bset = set(base['nargs']); vs = []
    for e in es:
        extra = [a for a in e['nargs'] if a not in bset]
        label = 'base' if e is base else (' '.join(pretty(a) for a in extra) or e['name'].split('/')[-1][len(base['name'].split('/')[-1]):].lstrip('-_') or e['name'])
        vs.append(dict(label=label, name=e['name'], url=e['url'], args=e['nargs'], **stats(e)))
        used.add(id(e))
    entry = {k: v for k, v in base.items() if k not in ('nargs', 'dir', 'args')}
    entry['variants'] = vs; merged[id(base)] = entry
# name-suffix fallback for recordings without meta.args: foo-opt.jsonl is a variant of foo.jsonl
byname = {e['name']: e for e in out if e.get('args') is None}
for e in list(out):
    if e.get('args') is not None or id(e) in used: continue
    m = re.match(r'^(.*)-([A-Za-z0-9]+)$', e['name'])
    if m and m.group(1) in byname and byname[m.group(1)] is not e and 'variants' not in byname[m.group(1)]:
        b = byname[m.group(1)]; b['variants'] = [dict(label='base', name=b['name'], url=b['url'], args=[], **stats(b))]
    if m and m.group(1) in byname and 'variants' in byname[m.group(1)]:
        byname[m.group(1)]['variants'].append(dict(label='-' + m.group(2), name=e['name'], url=e['url'], args=[], **stats(e))); used.add(id(e))
res = []
for e in out:
    if id(e) in merged: res.append(merged[id(e)]); continue
    if id(e) in used: continue
    res.append({k: v for k, v in e.items() if k not in ('nargs', 'dir', 'args')})
out = res

with open(os.path.join(HERE, 'recordings.json'), 'w') as f: json.dump(out, f, indent=1)
print(len(out), 'recordings ->', os.path.join(HERE, 'recordings.json'))
