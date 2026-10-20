#!/usr/bin/env python3
# LLM-assisted (Claude), see LLM_POLICY.md.
"""Compile files through the server and wait: client.py [--url http://127.0.0.1:8777] --scala 3.7.4 [--opt] [--label L]
       [--each] [--download DIR] [-- dotc options...] <files...>

--each compiles every file on its own (one recording each), otherwise all files form one compile. --opt adds
`-opt -opt-inline:<sources>` (Scala 2.13: `-opt:local -opt:inline:<sources>`). Prints one line per compile: status, time, diagnostics, recording URL."""
import argparse, json, os, sys, time, urllib.request


def call(url, body=None):
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json'}, method='GET' if body is None else 'POST')
    try:
        with urllib.request.urlopen(req) as r: return r.status, json.load(r)
    except urllib.error.HTTPError as e: return e.code, json.load(e)


def compile_files(base, scala, options, paths, label=None, download=None):
    files = [{'name': os.path.basename(p), 'text': open(p).read()} for p in paths]
    code, r = call(base + '/api/compile', {'scala': scala, 'options': options, 'files': files, 'label': label})
    if code != 202: return {'status': f'HTTP {code}', 'log': r.get('error')}
    t0 = time.time()
    while True:
        _, st = call(f"{base}/api/compile/{r['id']}")
        if st['status'] in ('done', 'error'): break
        time.sleep(0.5)
    st['wallMs'] = int((time.time() - t0) * 1000)
    if download and st.get('recording'):
        os.makedirs(download, exist_ok=True)
        out = os.path.join(download, (label or os.path.splitext(files[0]['name'])[0]) + '.jsonl')
        urllib.request.urlretrieve(base + st['recording'], out)
        st['file'] = out
    return st


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--url', default='http://127.0.0.1:8777')
    ap.add_argument('--scala', required=True)
    ap.add_argument('--opt', action='store_true')
    ap.add_argument('--each', action='store_true')
    ap.add_argument('--label')
    ap.add_argument('--download')
    ap.add_argument('rest', nargs='+')
    a = ap.parse_args()
    if '--' in a.rest:
        k = a.rest.index('--'); opts, paths = a.rest[k + 1:], a.rest[:k]
        # options first, then files: the files are the trailing .scala/.java arguments
        while opts and opts[-1].endswith(('.scala', '.java')): paths.insert(0, opts.pop())
    else: opts, paths = [], a.rest
    if a.opt: opts = opts + (['-opt:local', '-opt:inline:<sources>'] if a.scala.startswith('2.') else ['-opt', '-opt-inline:<sources>'])
    batches = [[p] for p in paths] if a.each else [paths]
    ok = True
    for b in batches:
        label = a.label or (os.path.splitext(os.path.basename(b[0]))[0] + ('-opt' if a.opt else '') if len(b) == 1 else None)
        st = compile_files(a.url, a.scala, opts, b, label, a.download)
        ds = st.get('diagnostics') or []
        errs = sum(d['severity'] == 'error' for d in ds)
        print(f"{a.scala:14} {label or ','.join(map(os.path.basename, b)):22} {st['status']:6} compile {st.get('elapsedMs', 0) / 1000:5.1f} s"
              f"  wall {st.get('wallMs', 0) / 1000:5.1f} s  exit {st.get('exitCode')}  {errs} errors {len(ds) - errs} other  {st.get('file') or st.get('recording') or ''}")
        if st['status'] != 'done':
            ok = False; print(st.get('log', '')[-2000:], file=sys.stderr)
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
