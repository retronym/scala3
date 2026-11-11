import os, sys, re, collections, json
s3root, s2root, out = sys.argv[1], sys.argv[2], sys.argv[3]
def norm(line):
    l = line.strip()
    l = re.sub(r'\s+', ' ', l)
    return l
def trivial(l):
    if len(l) < 25: return True
    if l.startswith(('import ', '//', '*', '/*', 'package ')): return True
    if re.fullmatch(r'[\{\}\(\)\[\]; ]*', l): return True
    return False
def load(root, dirs):
    files = {}
    for d in dirs:
        for dp, _, fns in os.walk(os.path.join(root, d)):
            for fn in fns:
                if fn.endswith(('.scala', '.java')):
                    p = os.path.join(dp, fn)
                    try: lines = open(p, encoding='utf-8', errors='replace').read().split('\n')
                    except: continue
                    files[os.path.relpath(p, root)] = [norm(x) for x in lines]
    return files
s3 = load(s3root, ['compiler/src', 'library/src', 'sbt-bridge/src', 'scaladoc/src', 'tasty/src', 'interfaces/src', 'library-js/src'])
s2 = load(s2root, ['src'])
idx = collections.defaultdict(set)
for f, ls in s2.items():
    for l in set(ls):
        if not trivial(l): idx[l].add(f)
# lines appearing in > 20 s2 files are boilerplate
rows = []
for f, ls in s3.items():
    nontriv = [l for l in ls if not trivial(l)]
    uniq = set(nontriv)
    if len(uniq) < 10: continue
    cnt = collections.Counter()
    for l in uniq:
        fs = idx.get(l)
        if fs and len(fs) <= 20:
            for g in fs: cnt[g] += 1
    if not cnt: 
        rows.append((f, len(uniq), None, 0, 0.0)); continue
    best, n = cnt.most_common(1)[0]
    rows.append((f, len(uniq), best, n, n/len(uniq)))
rows.sort(key=lambda r: (-r[3]))
with open(out, 'w') as o:
    o.write('s3_file\tnontrivial_uniq_lines\tbest_s2_match\tshared_lines\tratio\n')
    for r in rows:
        o.write('%s\t%d\t%s\t%d\t%.2f\n' % r)
