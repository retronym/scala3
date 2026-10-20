#!/usr/bin/env python3
"""Mock of the compile server API (frozen contract) for developing the viewer's Compile drawer without the real server.
usage: mock-api.py [port]   (default 8790; run from anywhere)  Serves canned responses and the bundled recordings in ../data/.
A compile "succeeds" after ~2.5 s and returns data/Closures.jsonl (data/Closures-opt.jsonl when -opt is among the options);
a source containing the text FAIL ends with status error; a source containing `warn` yields two diagnostics."""
import json, os, sys, time, uuid, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, '..', 'data')
JOBS = {}      # id -> job dict
LOCK = threading.Lock()


def cors(h):
    h.send_header('Access-Control-Allow-Origin', '*')
    h.send_header('Access-Control-Allow-Methods', 'GET, POST, HEAD, OPTIONS')
    h.send_header('Access-Control-Allow-Headers', 'content-type')


def job_view(j):
    now = time.time(); el = now - j['t0']
    st = 'queued' if el < 0.6 else 'running' if el < 2.5 else j['final']
    out = {'status': st, 'log': j['log'][: int(len(j['log']) * min(1, el / 2.5))] if st != 'queued' else '', 'elapsedMs': int(min(el, 2.5) * 1000)}
    if st in ('done', 'error'):
        out['exitCode'] = 0 if st == 'done' else 1
        out['diagnostics'] = j['diags']
        if st == 'done': out['recording'] = '/api/recordings/%s.jsonl' % j['id']
    return out


def recordings():
    # grouped like the real server: same files, different options / versions -> one entry with variants
    groups = {}
    for j in JOBS.values():
        if time.time() - j['t0'] < 2.5 or j['final'] != 'done': continue
        groups.setdefault(j['key'], []).append(j)
    out = []
    for key, js in groups.items():
        js.sort(key=lambda j: (len(j['options']), j['t0']))
        def stats(j):
            return {'jvm': 5, 'classes': 3, 'schema': 'v1', 'phases': 97, 'events': 780, 'nodes': 1090, 'versions': 85, 'edges': 18, 'size': os.path.getsize(j['file'])}
        b = js[0]
        e = {'name': b['label'], 'url': '/api/recordings/%s.jsonl' % b['id'], 'size': os.path.getsize(b['file']), 'schema': 'v1', 'units': [f['name'] for f in b['files']], 'scala': b['scala'], 'when': time.strftime('%H:%M:%S', time.localtime(b['t0'])),
             'phases': 97, 'events': 780, 'nodes': 1090, 'versions': 85, 'edges': 18, 'jvm': 5, 'classes': 3}
        if len(js) > 1:
            e['variants'] = [dict(label='base' if j is b else (' '.join(o for o in j['options'] if o not in b['options']) or j['scala']), name=j['label'], url='/api/recordings/%s.jsonl' % j['id'], args=j['options'], scala=j['scala'], **stats(j)) for j in js]
        out.append(e)
    return out


class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def send(self, code, body=b'', ctype='application/json', head=False):
        if isinstance(body, (dict, list)): body = json.dumps(body).encode()
        self.send_response(code); cors(self); self.send_header('Content-Type', ctype); self.send_header('Content-Length', str(len(body))); self.end_headers()
        if not head: self.wfile.write(body)

    def do_OPTIONS(self): self.send(204)
    def do_HEAD(self): self.do_GET(head=True)

    def do_GET(self, head=False):
        p = self.path.split('?')[0]
        if p == '/api/versions': return self.send(200, {'versions': [{'scala': '3.7.4', 'ready': True, 'notes': ''}, {'scala': '3.6.4', 'ready': True, 'notes': ''}, {'scala': '3.8.0-RC1', 'ready': False, 'notes': 'building'}], 'default': '3.7.4'}, head=head)
        if p == '/api/recordings': return self.send(200, recordings(), head=head)
        if p.startswith('/api/recordings/') and p.endswith('.jsonl'):
            j = JOBS.get(p.split('/')[-1][:-6])
            if not j or time.time() - j['t0'] < 2.5: return self.send(404, {'error': 'no such recording'}, head=head)
            with open(j['file'], 'rb') as f: return self.send(200, f.read(), 'application/x-ndjson', head=head)
        if p.startswith('/api/compile/'):
            j = JOBS.get(p.split('/')[-1])
            return self.send(200, job_view(j), head=head) if j else self.send(404, {'error': 'no such compile'}, head=head)
        self.send(404, {'error': 'not found'}, head=head)

    def do_POST(self):
        if self.path.split('?')[0] != '/api/compile': return self.send(404, {'error': 'not found'})
        n = int(self.headers.get('content-length') or 0); req = json.loads(self.rfile.read(n) or b'{}')
        files = req.get('files') or []; opts = req.get('options') or []; text = '\n'.join(f.get('text', '') for f in files)
        fail = 'FAIL' in text
        file = os.path.join(DATA, 'Closures-opt.jsonl' if '-opt' in opts else 'Closures.jsonl')
        jid = uuid.uuid4().hex[:10]
        diags = [] if fail else ([{'severity': 'warning', 'message': 'unused local definition', 'file': files[0]['name'] if files else 'a.scala', 'line': 3}, {'severity': 'warning', 'message': 'pattern may not be exhaustive', 'file': files[0]['name'] if files else 'a.scala', 'line': 8}] if 'warn' in text else [])
        if fail: diags = [{'severity': 'error', 'message': 'FAIL marker found (mock)', 'file': files[0]['name'] if files else 'a.scala', 'line': 1}]
        log = ('[mock] scalac %s %s\n' % (req.get('scala'), ' '.join(opts))) + ''.join('[mock] compiling %s (%d chars)\n' % (f.get('name'), len(f.get('text', ''))) for f in files) + ('[mock] phase typer\n[mock] phase erasure\n[mock] phase genBCode\n' if not fail else '[mock] error: FAIL marker\n')
        key = json.dumps([[f['name'], f['text']] for f in files], sort_keys=True)
        with LOCK: JOBS[jid] = {'id': jid, 't0': time.time(), 'final': 'error' if fail else 'done', 'log': log, 'diags': diags, 'file': file, 'files': files, 'options': opts, 'scala': req.get('scala', '3.7.4'), 'label': req.get('label') or (files[0]['name'] if files else 'compile'), 'key': key}
        self.send(202, {'id': jid})


if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8790
    print('mock compile API on http://localhost:%d' % port, flush=True)
    ThreadingHTTPServer(('127.0.0.1', port), H).serve_forever()
