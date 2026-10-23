#!/usr/bin/env python3
"""Hovers beside the x-ray's background pass: the lock-wait distribution.

For each file: reset the typer state (a fresh driver), start the background
pass with a viewport (the visible lines first, then the rest of the session),
and while it runs send hovers (`typeAt`) at random identifiers from another
thread of the server, one after another. Each hover's wall time and its time
blocked on the completion lock are recorded separately, with what the lock
holder was doing during the long waits. Repeated `--rounds` times per file.

  python3 lockwait.py [--server http://127.0.0.1:8799] [--rounds 3] [--out results.json] FILE...

FILE is relative to the x-ray's sources (e.g. core/Types.scala). Prints a
markdown summary; `--out` keeps every sample.
"""
import argparse, json, random, re, statistics, sys, time, urllib.parse, urllib.request


def call(base, path, data=None, timeout=600):
    body = urllib.parse.urlencode(data).encode() if data is not None else None
    with urllib.request.urlopen(base + path, body, timeout=timeout) as r:
        return json.load(r)


def pct(xs, p):
    if not xs:
        return float('nan')
    xs = sorted(xs)
    k = min(len(xs) - 1, max(0, int(round(p / 100 * (len(xs) - 1)))))
    return xs[k]


def kind(label):
    # the kind of the holder's root work: the label up to its subject
    for k in ('pass types statement at line', 'read action (serialised mode)', 'Namer signature', 'unpickle TASTy', 'load classpath', 'load source', 'lazy TASTy read',
              'bring forward', 'inherited cache', 'commit typer state', 'cleanup', 'wait for a completion'):
        if label.startswith(k):
            return k
    return label.split(' ')[0]


def one_round(base, file, rng, viewport_lines, max_hovers):
    src = call(base, '/source?file=' + urllib.parse.quote(file))['source']
    lines = src.split('\n')
    first = rng.randrange(0, max(1, len(lines) - viewport_lines))
    vf = sum(len(l) + 1 for l in lines[:first])
    vt = sum(len(l) + 1 for l in lines[:first + viewport_lines])
    idents = [m.end() for m in re.finditer(r'\b[A-Za-z_][A-Za-z0-9_]*\b', src)]
    call(base, '/reset', {})
    call(base, '/lockwaits?reset=1')
    st = call(base, '/background', {'code': src, 'file': file, 'visibleFrom': vf, 'visibleTo': vt})
    hovers = []
    t0 = time.time()
    while st.get('state') == 'running' and len(hovers) < max_hovers:
        o = rng.choice(idents)
        r = call(base, '/hover', {'code': src, 'offset': o, 'file': file})
        hovers.append({'offset': o, 'ms': r.get('ms'), 'lockWaitMs': r.get('lockWaitMs', 0.0),
                       'lockWaitCount': r.get('lockWaitCount', 0), 'error': r.get('error'),
                       'statementWaitMs': r.get('statementWaitMs', 0.0), 'backedOff': r.get('backedOff', 0),
                       'beside': r.get('concurrentWithBackground', False), 'lockWaits': r.get('lockWaits', [])})
        st = call(base, '/background')
    # the pass's own end, and the diagnostics check (computed after the pass)
    while st.get('state') == 'running' or (st.get('state') == 'done' and 'oracleDiagnosticCount' not in st):
        time.sleep(0.2)
        st = call(base, '/background')
    waits = call(base, '/lockwaits')['waits']
    return {'file': file, 'viewport': [first + 1, first + viewport_lines], 'hovers': hovers, 'waits': waits,
            'background': {k: st.get(k) for k in ('state', 'outcome', 'ms', 'visibleFirstMs', 'statements', 'diagnosticCount',
                                                   'oracleDiagnosticCount', 'diagnosticsMatch', 'lockWaitMs', 'lockWaitCount',
                                                   'foregroundDuring', 'error', 'missingDiagnostics', 'extraDiagnostics')},
            'wall': time.time() - t0}


def summarise(file, rounds):
    beside = [h for r in rounds for h in r['hovers'] if h['beside']]
    walls = [h['ms'] for h in beside]
    waits = [h['lockWaitMs'] for h in beside]
    waited = [w for w in waits if w > 0]
    long_waits = [w for r in rounds for w in r['waits']]
    by_kind = {}
    for w in long_waits:
        by_kind.setdefault(kind(w['root']), []).append(w['ms'])
    print(f"\n### {file}\n")
    print("| pass | statements | visible first | whole pass | diagnostics | whole-file run | hovers beside it | pass lock wait |")
    print("|---|---|---|---|---|---|---|---|")
    for i, r in enumerate(rounds, 1):
        b = r['background']
        print(f"| {i} | {b.get('statements')} | {b.get('visibleFirstMs')} ms | {b.get('ms')} ms | {b.get('diagnosticCount')} | "
              f"{b.get('oracleDiagnosticCount')} {'✓' if b.get('diagnosticsMatch') else '✗'} | {sum(1 for h in r['hovers'] if h['beside'])} | "
              f"{b.get('lockWaitMs')} ms ×{b.get('lockWaitCount')} |")
    for i, r in enumerate(rounds, 1):
        b = r['background']
        for m in (b.get('missingDiagnostics') or [])[:3]:
            print(f"\npass {i} misses line {m['line']}: {m['message'][:120]}")
        for m in (b.get('extraDiagnostics') or [])[:3]:
            print(f"\npass {i} adds line {m['line']}: {m['message'][:120]}")
    errors = [h['error'] for r in rounds for h in r['hovers'] if h.get('error')]
    if errors:
        print(f"\n{len(errors)} hovers failed, e.g. {errors[0][:160]}")
    if not beside:
        print("\nno hover ran beside the pass")
        return
    bo = [h.get('backedOff', 0) for h in beside]
    print(f"\nHovers beside the pass: {len(beside)}; {len(waited)} waited on the lock; {sum(bo)} cycle backoffs, at most {max(bo)} in one hover.\n")
    print("| per hover | median | p90 | max |")
    print("|---|---|---|---|")
    print(f"| wall (ms) | {statistics.median(walls):.1f} | {pct(walls, 90):.1f} | {max(walls):.1f} |")
    print(f"| lock wait (ms) | {statistics.median(waits):.1f} | {pct(waits, 90):.1f} | {max(waits):.1f} |")
    sw = [h.get('statementWaitMs', 0.0) for h in beside]
    print(f"| wait for a statement the pass is typing (ms) | {statistics.median(sw):.1f} | {pct(sw, 90):.1f} | {max(sw):.1f} |")
    rest = [h['ms'] - h['lockWaitMs'] - h.get('statementWaitMs', 0.0) for h in beside]
    print(f"| the rest: own typing, resume or fresh run (ms) | {statistics.median(rest):.1f} | {pct(rest, 90):.1f} | {max(rest):.1f} |")
    if waited:
        print(f"| lock wait, hovers that waited (ms) | {statistics.median(waited):.1f} | {pct(waited, 90):.1f} | {max(waited):.1f} |")
    if by_kind:
        print("\nWaits of 0.2 ms or more, by what the lock holder's root work was:\n")
        print("| holder's root | waits | total ms | max ms | longest example |")
        print("|---|---|---|---|---|")
        for k, ms in sorted(by_kind.items(), key=lambda kv: -sum(kv[1])):
            ex = max((w for w in long_waits if kind(w['root']) == k), key=lambda w: w['ms'])
            print(f"| {k} | {len(ms)} | {sum(ms):.1f} | {max(ms):.1f} | {ex['root']} |")


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument('--server', default='http://127.0.0.1:8799')
    ap.add_argument('--rounds', type=int, default=3)
    ap.add_argument('--viewport', type=int, default=60)
    ap.add_argument('--max-hovers', type=int, default=400)
    ap.add_argument('--seed', type=int, default=1)
    ap.add_argument('--out')
    ap.add_argument('files', nargs='+')
    a = ap.parse_args(argv)
    rng = random.Random(a.seed)
    out = {}
    for f in a.files:
        rounds = [one_round(a.server, f, rng, a.viewport, a.max_hovers) for _ in range(a.rounds)]
        out[f] = rounds
        summarise(f, rounds)
    if a.out:
        json.dump(out, open(a.out, 'w'), indent=1)


if __name__ == '__main__':
    main(sys.argv[1:])
