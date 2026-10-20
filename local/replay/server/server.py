#!/usr/bin/env python3
# LLM-assisted (Claude), see LLM_POLICY.md.
"""Greffier compile server: compile Scala sources with the recorder attached and serve the recordings.

  server.py [--port 8777] [--host 127.0.0.1] [--ui <viewer dir>] [--default 3.7.4]

API (README.md): GET /api/versions, POST /api/compile, GET /api/compile/<id>, GET /api/recordings,
GET /api/recordings/<id>.jsonl. Everything carries Access-Control-Allow-Origin: *.
One compile at a time, each in a fresh JVM (versions.dotc_cmd). Python 3 standard library only.
"""
import argparse, hashlib, json, mimetypes, os, queue, re, shutil, subprocess, sys, threading, time, traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, unquote

import versions as V

HERE = os.path.dirname(os.path.abspath(__file__))
RECORDINGS = os.path.join(HERE, 'recordings')
TIMEOUT_S = int(os.environ.get('GREFFIER_TIMEOUT_S', 120))
MAX_SOURCE_BYTES = 256 * 1024
MAX_FILES = 32
MAX_OPTIONS = 64
LOG_TAIL = 8000
FILE_NAME = re.compile(r'^[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}\.(scala|java)$')
# options the server sets itself, or that would read or write outside the job directory
FORBIDDEN_OPTION = re.compile(r'^(@|-d$|-d:|-Xplugin|-P:|-classpath|-cp$|-cp:|-sourcepath|-bootclasspath|-javabootclasspath|'
                              r'-extdirs|-javaextdirs|-semanticdb-target|-Xmain-class|-Ysave|-Yreplay|-Ydump|-Xpickle-write|-o$|-out)')


# ---- version catalogue -----------------------------------------------------------------------------------------------------------------

def known_released():
    """The latest 3.3.x (LTS) and 3.7.x, and the latest 3.x, from Maven Central via coursier (cached for a day)."""
    cache = os.path.join(V.VERSIONS, 'known.json')
    try:
        if time.time() - os.path.getmtime(cache) < 86400:
            with open(cache) as f: return json.load(f)
    except OSError: pass
    fallback = {'lts': '3.3.8', '3.7': '3.7.4', 'latest': None}
    try:
        out = subprocess.run(['coursier', 'complete-dep', 'org.scala-lang:scala3-compiler_3:'], capture_output=True, text=True, timeout=60).stdout
        vs = [tuple(map(int, v.split('.'))) for v in out.split() if re.fullmatch(r'3\.\d+\.\d+', v)]
        def latest(pred):
            c = sorted(v for v in vs if pred(v))
            return '.'.join(map(str, c[-1])) if c else None
        res = {'lts': latest(lambda v: v[1] == 3) or fallback['lts'], '3.7': latest(lambda v: v[1] == 7) or fallback['3.7'],
               'latest': latest(lambda v: True)}
    except Exception:
        return fallback
    os.makedirs(V.VERSIONS, exist_ok=True)
    with open(cache, 'w') as f: json.dump(res, f)
    return res


def version_list(default):
    labels = V.all_labels()
    k = known_released()
    wanted = [l for l in labels if l.startswith('main-')] + [k['lts'], k['3.7']] + ([k['latest']] if k.get('latest') else [])
    seen, res = set(), []
    for l in wanted + labels:
        if not l or l in seen: continue
        seen.add(l)
        i = V.load(l)
        if i is None:
            res.append({'scala': l, 'ready': False, 'notes': 'not built yet: the first compile fetches and builds it (about a minute)'})
            continue
        e = {'scala': l, 'ready': i.get('state') == 'ready', 'notes': i.get('notes') or i.get('error') or i.get('state', ''),
             'kind': i.get('kind'), 'state': i.get('state'), 'joinPoints': i.get('joinPoints'), 'warmupMs': i.get('warmupMs')}
        res.append(e)
    return {'versions': res, 'default': default}


def valid_version(v):
    return V.load(v) is not None or re.fullmatch(r'3\.\d+\.\d+(-RC\d+)?', v or '') is not None


# ---- jobs ------------------------------------------------------------------------------------------------------------------------------

jobs = {}             # id -> status dict (in memory; done jobs are also on disk)
jobs_lock = threading.Lock()
work = queue.Queue()


def path(i, ext): return os.path.join(RECORDINGS, i + ext)


def job_id(req):
    key = json.dumps({'scala': req['scala'], 'options': req['options'], 'files': [[f['name'], f['text']] for f in req['files']]},
                     sort_keys=True, separators=(',', ':'))
    return hashlib.sha256(key.encode()).hexdigest()[:20]


def status_of(i):
    with jobs_lock:
        if i in jobs: return dict(jobs[i])
    try:
        with open(path(i, '.result.json')) as f: return json.load(f)
    except OSError: return None


def set_status(i, **kw):
    with jobs_lock:
        jobs.setdefault(i, {}).update(kw)
        return dict(jobs[i])


def validate(body):
    if not isinstance(body, dict): raise ValueError('expected a JSON object')
    scala = body.get('scala')
    if not isinstance(scala, str) or not valid_version(scala): raise ValueError(f'unknown Scala version: {scala!r}')
    options = body.get('options', [])
    if not isinstance(options, list) or not all(isinstance(o, str) for o in options): raise ValueError('options: expected a list of strings')
    if len(options) > MAX_OPTIONS: raise ValueError(f'at most {MAX_OPTIONS} options')
    for o in options:
        if FORBIDDEN_OPTION.match(o): raise ValueError(f'option not allowed (the server sets it, or it touches files outside the job): {o}')
    files = body.get('files')
    if not isinstance(files, list) or not files: raise ValueError('files: expected a non-empty list')
    if len(files) > MAX_FILES: raise ValueError(f'at most {MAX_FILES} files')
    names, total = set(), 0
    for f in files:
        if not isinstance(f, dict) or not isinstance(f.get('name'), str) or not isinstance(f.get('text'), str):
            raise ValueError('files: expected [{"name": ..., "text": ...}]')
        if not FILE_NAME.match(f['name']): raise ValueError(f'bad file name (plain *.scala or *.java): {f["name"]!r}')
        if f['name'] in names: raise ValueError(f'duplicate file name {f["name"]}')
        names.add(f['name']); total += len(f['text'].encode())
    if total > MAX_SOURCE_BYTES: raise ValueError(f'sources are {total} bytes, the limit is {MAX_SOURCE_BYTES}')
    label = body.get('label')
    if label is not None and not isinstance(label, str): raise ValueError('label: expected a string')
    return {'scala': scala, 'options': options, 'files': [{'name': f['name'], 'text': f['text']} for f in files],
            'label': (label or '')[:200] or None}


def submit(req):
    i = job_id(req)
    st = status_of(i)
    if st and st.get('status') in ('queued', 'running', 'done'): return i
    os.makedirs(RECORDINGS, exist_ok=True)
    with open(path(i, '.request.json'), 'w') as f: json.dump(req, f, indent=1)
    set_status(i, id=i, status='queued', log='', elapsedMs=0, recording=None, diagnostics=[], exitCode=None, scala=req['scala'])
    work.put(i)
    return i


# dotc's message header: `-- [E007] Type Mismatch Error: /path/A.scala:3:15 -----`, `-- Warning: /path/A.scala:2:4 ---`,
# or with no position `-- Error: ----`
HEADER = re.compile(r'^-- (?:\[E\d+\] )?(?P<kind>[^:]*?)(?:: (?P<file>.+?):(?P<line>\d+):(?P<col>\d+))?\s*-*\s*$')
SUMMARY = re.compile(r'^\d+ (errors?|warnings?) found$')


def parse_diagnostics(stderr, srcdir):
    """dotc's -color:never messages as {severity, message, file, line, column}. Lines in a message block are
    the source echo (`3 |  code`), the caret line and the message proper (`  |  text`); keep the message."""
    diags, cur = [], None
    def done():
        if cur is not None:
            msg = '\n'.join(cur['_lines']).strip('\n')
            cur['message'] = re.sub(r'\n{3,}', '\n\n', msg).strip()
            del cur['_lines']; diags.append(cur)
    for line in stderr.splitlines():
        m = HEADER.match(line) if line.startswith('-- ') else None
        if m and ('Error' in m['kind'] or 'Warning' in m['kind'] or 'Info' in m['kind']):
            done()
            k = m['kind']
            sev = 'error' if 'Error' in k else 'warning' if 'Warning' in k else 'info'
            f = m['file']
            if f and os.path.isabs(f) and os.path.dirname(f) == srcdir: f = os.path.basename(f)
            cur = {'severity': sev, 'kind': k, 'file': f, 'line': int(m['line']) if m['line'] else None,
                   'column': int(m['col']) if m['col'] else None, '_lines': []}
            continue
        if cur is None: continue
        if SUMMARY.match(line.strip()) or line.startswith('Exception in thread') or line.startswith('[replay'):
            done(); cur = None; continue
        g = re.match(r'^\s*(\d+)?\s*\|(.*)$', line)
        if g:
            if g.group(1): continue                          # source echo
            text = g.group(2)
            if re.fullmatch(r'\s*\^+\s*', text) or text.strip().startswith('longer explanation'): continue    # caret, -explain hint
            cur['_lines'].append(text[1:] if text.startswith(' ') else text)
        elif line.strip() and not line.startswith('longer explanation'):
            cur['_lines'].append(line)
    done()
    # Messages without a position are printed without dotc's box (`bad option '-opt' was ignored`, the recorder's
    # plan warnings). They have no severity marker: the summary line's counts minus the boxed ones say which.
    counts = {'error': 0, 'warning': 0}
    for line in stderr.splitlines():
        m = SUMMARY.match(line.strip())
        if m: counts['error' if m[1].startswith('error') else 'warning'] += int(line.split()[0])
    left_w = counts['warning'] - sum(d['severity'] == 'warning' for d in diags)
    left_e = counts['error'] - sum(d['severity'] == 'error' for d in diags)
    in_box = False
    for line in stderr.splitlines():
        if line.startswith('-- '): in_box = True; continue
        if in_box:
            if not line.strip() or SUMMARY.match(line.strip()): in_box = False
            continue
        t = line.strip()
        if not t or SUMMARY.match(t) or t.startswith(('[replay', 'Exception in thread', 'at ', 'Caused by', '...', 'longer explanation')) \
           or 'child setpgid' in t or line.startswith(('\t', ' ')):
            continue
        if left_w > 0: sev = 'warning'; left_w -= 1
        elif left_e > 0: sev = 'error'; left_e -= 1
        else: continue
        diags.append({'severity': sev, 'kind': 'nopos', 'message': t, 'file': None, 'line': None, 'column': None})
    return diags


def stream_stats(p):
    """gen_index.py's per-recording counts."""
    c = {'ev': 0, 'node': 0, 'version': 0, 'phase': 0, 'edge': 0}; meta = {}; jvm = 0; classes = set()
    with open(p, encoding='utf-8') as f:
        for line in f:
            if line.startswith('{"t":"meta"'):
                try: meta = json.loads(line)
                except Exception: pass
            elif line.startswith('{"t":"'):
                k = line[6:line.index('"', 6)]
                if k == 'version' and line.startswith('{"t":"version","ir":"jvm"'):
                    jvm += 1; j = line.find('"class":"')
                    if j > 0: classes.add(line[j + 9:line.index('"', j + 9)])
                elif k in c: c[k] += 1
    return {'compiler': meta.get('compiler'), 'schema': meta.get('schema', 'v0'), 'units': [os.path.basename(u.get('path', '')) for u in meta.get('units', [])],
            'jvm': jvm, 'classes': len(classes), 'phases': c['phase'], 'events': c['ev'], 'nodes': c['node'], 'versions': c['version'],
            'edges': c['edge'], 'size': os.path.getsize(p)}


def ensure_version(i, scala):
    info = V.load(scala)
    if info and info.get('state') == 'ready': return info
    set_status(i, status='running', log=f'preparing the workspace for Scala {scala} (coursier fetch, build agent and plugin, warm-up compile)\n')
    t0 = time.time()
    with open(os.path.join(RECORDINGS, f'setup-{scala}.log'), 'w') as log:
        info = V.finish(scala, log) if info else V.setup(scala, log)
    set_status(i, log=f'prepared Scala {scala} in {time.time() - t0:.0f} s: {info.get("notes") or info.get("error")}\n')
    if info.get('state') != 'ready': raise RuntimeError(f'Scala {scala}: workspace not ready: {info.get("error")}')
    return info


def run_job(i):
    with open(path(i, '.request.json')) as f: req = json.load(f)
    st = set_status(i, status='running')
    ensure_version(i, req['scala'])
    wd = os.path.join(RECORDINGS, 'work', i); shutil.rmtree(wd, ignore_errors=True)
    srcdir = os.path.join(wd, 'src'); classes = os.path.join(wd, 'classes')
    os.makedirs(srcdir); os.makedirs(classes)
    files = []
    for f in req['files']:
        p = os.path.join(srcdir, f['name'])
        with open(p, 'w') as fh: fh.write(f['text'])
        files.append(p)
    out = path(i, '.jsonl'); tmp = out + '.part'
    if os.path.exists(tmp): os.remove(tmp)
    cmd = V.dotc_cmd(req['scala'], tmp, classes, req['options'], files, table=os.path.join(wd, 'jointable.txt'))
    t0 = time.time()
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, cwd=wd)
    timed_out = False
    try: output, _ = proc.communicate(timeout=TIMEOUT_S)
    except subprocess.TimeoutExpired:
        proc.kill(); output, _ = proc.communicate(); timed_out = True
    ms = int((time.time() - t0) * 1000)
    with open(path(i, '.log'), 'w') as f: f.write(' '.join(cmd) + '\n\n' + output)
    diags = parse_diagnostics(output, srcdir)
    rec = None
    if os.path.exists(tmp) and os.path.getsize(tmp) > 0:
        os.replace(tmp, out); rec = f'/api/recordings/{i}.jsonl'
    res = {'id': i, 'scala': req['scala'], 'label': req.get('label'), 'options': req['options'],
           'units': [f['name'] for f in req['files']], 'when': time.strftime('%Y-%m-%dT%H:%M:%S%z'),
           'elapsedMs': ms, 'exitCode': proc.returncode, 'diagnostics': diags, 'recording': rec,
           'log': output[-LOG_TAIL:]}
    if timed_out:
        res.update(status='error', log=f'timed out after {TIMEOUT_S} s\n' + output[-LOG_TAIL:])
    elif rec is None:
        res.update(status='error', log='no recording was written\n' + output[-LOG_TAIL:])
    else:
        res['status'] = 'done'
        try: res['stats'] = stream_stats(out)
        except Exception as e: res['stats'] = None; res['log'] += f'\n(stream stats failed: {e})'
    try:
        with open(os.path.join(wd, 'jointable.txt')) as f: res['joinTable'] = f.read()
    except OSError: pass
    shutil.rmtree(wd, ignore_errors=True)
    if res['status'] == 'done':
        with open(path(i, '.result.json'), 'w') as f: json.dump(res, f, indent=1)
    set_status(i, **res)


def worker():
    while True:
        i = work.get()
        try: run_job(i)
        except Exception as e:
            set_status(i, status='error', log=f'{e}\n{traceback.format_exc()[-3000:]}')
        finally: work.task_done()


# ---- index (the viewer's recordings.json) ----------------------------------------------------------------------------------------------

def pretty(opts): return ' '.join(opts) or 'base'


def recordings_index():
    """Every done compile, in the viewer's recordings.json shape. Compiles of the same sources with the same Scala
    version are one entry with `variants` (base = fewest options), as gen_index.py groups option variants."""
    if not os.path.isdir(RECORDINGS): return []
    done = []
    for n in os.listdir(RECORDINGS):
        if not n.endswith('.result.json'): continue
        try:
            with open(os.path.join(RECORDINGS, n)) as f: r = json.load(f)
            with open(path(r['id'], '.request.json')) as f: req = json.load(f)
        except Exception: continue
        if r.get('status') != 'done' or not r.get('stats'): continue
        r['_src'] = hashlib.sha256(json.dumps([[f['name'], f['text']] for f in req['files']]).encode()).hexdigest()
        done.append(r)
    groups = {}
    for r in done: groups.setdefault((r['scala'], r['_src']), []).append(r)
    out = []
    for (scala, _), rs in groups.items():
        rs.sort(key=lambda r: (len(r['options']), r['when']))
        base = rs[0]
        def entry(r):
            s = r['stats']
            name = r.get('label') or ', '.join(u.rsplit('.', 1)[0] for u in r['units'])
            return {'id': r['id'], 'name': f'{name} [{scala}]', 'url': r['recording'], 'scala': scala, 'when': r['when'],
                    'options': r['options'], 'label': r.get('label'), 'elapsedMs': r['elapsedMs'],
                    'errors': sum(d['severity'] == 'error' for d in r['diagnostics']), **s}
        e = entry(base)
        if len(rs) > 1:
            bset = set(base['options'])
            e['variants'] = [dict(entry(r), label='base' if r is base else pretty([o for o in r['options'] if o not in bset]),
                                  args=r['options']) for r in rs]
        e['when'] = max(r['when'] for r in rs)
        out.append(e)
    out.sort(key=lambda e: e['when'], reverse=True)
    return out


# ---- HTTP ------------------------------------------------------------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = 'greffier/0'
    ui = None
    default = None

    def log_message(self, fmt, *args):
        sys.stderr.write('%s %s\n' % (self.log_date_time_string(), fmt % args))

    def cors(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.send_header('Access-Control-Max-Age', '86400')

    def send_json(self, code, obj):
        b = json.dumps(obj).encode()
        self.send_response(code); self.cors()
        self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(b)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers(); self.wfile.write(b)

    def send_file(self, p, ctype):
        size = os.path.getsize(p)
        self.send_response(200); self.cors()
        self.send_header('Content-Type', ctype); self.send_header('Content-Length', str(size))
        self.end_headers()
        if self.command == 'HEAD': return
        with open(p, 'rb') as f: shutil.copyfileobj(f, self.wfile, 1 << 20)

    def do_OPTIONS(self):
        self.send_response(204); self.cors(); self.send_header('Content-Length', '0'); self.end_headers()

    def do_HEAD(self): self.do_GET()

    def do_GET(self):
        u = urlparse(self.path); p = unquote(u.path)
        try:
            if p == '/api/versions': return self.send_json(200, version_list(self.default))
            if p == '/api/recordings': return self.send_json(200, recordings_index())
            m = re.fullmatch(r'/api/recordings/([0-9a-f]{20})\.jsonl', p)
            if m:
                f = path(m[1], '.jsonl')
                return self.send_file(f, 'application/x-ndjson') if os.path.exists(f) else self.send_json(404, {'error': 'no such recording'})
            m = re.fullmatch(r'/api/compile/([0-9a-f]{20})', p)
            if m:
                st = status_of(m[1])
                if st is None: return self.send_json(404, {'error': 'no such compile'})
                keys = ('id', 'status', 'log', 'elapsedMs', 'recording', 'diagnostics', 'exitCode', 'scala')
                return self.send_json(200, {k: st.get(k) for k in keys})
            if p.startswith('/api/'): return self.send_json(404, {'error': 'not found'})
            if self.ui: return self.static(p)
            return self.send_json(404, {'error': 'no viewer: start the server with --ui <dir>'})
        except (BrokenPipeError, ConnectionResetError): pass
        except Exception as e:
            self.send_json(500, {'error': str(e)})

    def static(self, p):
        if p == '/recordings.json':
            # the viewer's own index plus everything compiled here (one origin, see README)
            entries = []
            f = os.path.join(self.ui, 'recordings.json')
            if os.path.exists(f):
                with open(f) as fh: entries = json.load(fh)
            return self.send_json(200, recordings_index() + entries)
        rel = os.path.normpath(p.lstrip('/')) if p != '/' else 'index.html'
        full = os.path.realpath(os.path.join(self.ui, rel))
        if not (full == self.ui or full.startswith(self.ui + os.sep)): return self.send_json(403, {'error': 'outside the viewer directory'})
        if os.path.isdir(full): full = os.path.join(full, 'index.html')
        if not os.path.isfile(full): return self.send_json(404, {'error': 'not found'})
        ctype = 'application/x-ndjson' if full.endswith('.jsonl') else (mimetypes.guess_type(full)[0] or 'application/octet-stream')
        if ctype.startswith('text/') or ctype in ('application/javascript', 'application/json'): ctype += '; charset=utf-8'
        return self.send_file(full, ctype)

    def do_POST(self):
        p = urlparse(self.path).path
        if p != '/api/compile': return self.send_json(404, {'error': 'not found'})
        try:
            n = int(self.headers.get('Content-Length') or 0)
            if n > 4 * MAX_SOURCE_BYTES: return self.send_json(413, {'error': f'request too large ({n} bytes)'})
            body = json.loads(self.rfile.read(n) or b'null')
            req = validate(body)
        except (ValueError, json.JSONDecodeError) as e:
            return self.send_json(400, {'error': str(e)})
        i = submit(req)
        return self.send_json(202, {'id': i})


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--port', type=int, default=8777)
    ap.add_argument('--host', default='127.0.0.1')
    ap.add_argument('--ui', help='serve this viewer directory at /')
    ap.add_argument('--default', default=None, help='default Scala version (default: the latest 3.7.x)')
    a = ap.parse_args()
    os.makedirs(RECORDINGS, exist_ok=True)
    Handler.ui = os.path.realpath(a.ui) if a.ui else None
    Handler.default = a.default or known_released()['3.7']
    threading.Thread(target=worker, daemon=True).start()
    srv = ThreadingHTTPServer((a.host, a.port), Handler)
    print(f'greffier server on http://{a.host}:{a.port}/  (ui: {Handler.ui or "none"}, default Scala {Handler.default})', file=sys.stderr)
    try: srv.serve_forever()
    except KeyboardInterrupt: pass


if __name__ == '__main__':
    main()
