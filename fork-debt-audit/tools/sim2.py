import os, sys, re, collections
s3root, s2root, out = sys.argv[1], sys.argv[2], sys.argv[3]
K=6
ident = re.compile(r'[A-Za-z_][A-Za-z0-9_]*')
STOP = set('val def var if else match case class object trait extends with new return import package private protected override final implicit lazy type this super true false null yield for while do try catch finally throw then end given using inline transparent sealed abstract'.split())
def shingles(root, rel):
    try: t = open(os.path.join(root, rel), encoding='utf-8', errors='replace').read()
    except: return set()
    t = re.sub(r'/\*.*?\*/', ' ', t, flags=re.S); t = re.sub(r'//[^\n]*', ' ', t)
    toks = [w for w in ident.findall(t) if w not in STOP and len(w) > 1]
    return set(tuple(toks[i:i+K]) for i in range(len(toks)-K+1)), len(toks)
def walk(root, dirs):
    for d in dirs:
        for dp, _, fns in os.walk(os.path.join(root, d)):
            for fn in fns:
                if fn.endswith(('.scala','.java')): yield os.path.relpath(os.path.join(dp, fn), root)
s2 = {}
idx = collections.defaultdict(set)
for f in walk(s2root, ['src']):
    sh = shingles(s2root, f)
    if not sh: continue
    s2[f] = sh
    for s in sh[0]: idx[s].add(f)
rows = []
for f in walk(s3root, ['compiler/src','library/src','sbt-bridge/src','scaladoc/src','tasty/src','interfaces/src','library-js/src','compiler/src-bootstrapped','library/src-bootstrapped','library/src-non-bootstrapped']):
    r = shingles(s3root, f)
    if not r: continue
    sh, ntok = r
    if len(sh) < 30: continue
    cnt = collections.Counter()
    for s in sh:
        fs = idx.get(s)
        if fs and len(fs) <= 10:
            for g in fs: cnt[g] += 1
    if not cnt: rows.append((f, len(sh), '', 0, 0.0)); continue
    best, n = cnt.most_common(1)[0]
    rows.append((f, len(sh), best, n, n/len(sh)))
rows.sort(key=lambda r: -r[3])
with open(out,'w') as o:
    o.write('s3_file\tshingles\tbest_s2\tshared\tratio\n')
    for r in rows: o.write('%s\t%d\t%s\t%d\t%.2f\n' % r)
