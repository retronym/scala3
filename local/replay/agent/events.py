# LLM-assisted (Claude), see LLM_POLICY.md.
# Print implicit-search events (ImplicitSearch, Scope, Candidate, Rejected) and Tentative/Report events
# of a v1 stream as an indented tree. usage: events.py <file.jsonl> [kinds,comma,separated] [span-start-min span-start-max]
import json, sys
types, syms, evs = {}, {}, []
for l in open(sys.argv[1]):
    d = json.loads(l)
    if d['t'] == 'type': types[d['id']] = d.get('text')
    elif d['t'] == 'sym': syms[d['id']] = d.get('fullName') or d.get('name')
    elif d['t'] == 'ev': evs.append(d)
kinds = set((sys.argv[2] if len(sys.argv) > 2 else 'ImplicitSearch,Scope,Candidate,Rejected').split(','))
lo, hi = (int(sys.argv[3]), int(sys.argv[4])) if len(sys.argv) > 4 else (0, 1 << 30)
byseq = {e['seq']: e for e in evs}
def depth(e):
    n, p = 0, e['parent']
    while p is not None and p in byseq:
        if byseq[p]['kind'] in kinds: n += 1
        p = byseq[p]['parent']
    return n
def anc_span(e):
    p = e
    while p is not None:
        if p['span']: return p['span'][0]
        p = byseq.get(p['parent'])
    return -1
for e in evs:
    if e['kind'] not in kinds or not (lo <= anc_span(e) <= hi): continue
    p = dict(e['payload'])
    for k in ('pt', 'ref', 'than', 'tpe'):
        if isinstance(p.get(k), int): p[k] = types.get(p[k])
    for k in ('eligible', 'ambiguous'):
        if k in p: p[k] = [types.get(t) for t in p[k]]
    print('  ' * depth(e) + f"{e['seq']} {e['kind']} {e['span']} {p} {[syms.get(s) for s in e['syms']]}")
