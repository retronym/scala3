"""Summarise bench-lab runs under out/bench-lab: JMH results of every profile, per-method counter attribution, and
async-profiler stacks (hot methods by self time, time and allocation by compiler phase).

  python3 -I bench-lab/scripts/analyze.py [out/bench-lab]
"""
import collections, glob, json, os, re, subprocess, sys

PHASES_ONLY = len(sys.argv) > 1 and sys.argv[1] == "--phases"   # --phases <jfr> <cpu|alloc|hw:event>: phase shares, tab-separated
root = "/nonexistent" if PHASES_ONLY else (sys.argv[1] if len(sys.argv) > 1 else "out/bench-lab")

# --- JMH results: one table per corpus, a row per metric, a column per profile
results = collections.defaultdict(dict)   # (corpus, metric) -> {profile: score}
for f in sorted(glob.glob(f"{root}/*/result.json")):
    prof = os.path.basename(os.path.dirname(f))
    for r in json.load(open(f)):
        corpus = r["params"]["corpus"]
        results[(corpus, "ms/op")][prof] = (r["primaryMetric"]["score"], r["primaryMetric"]["scoreError"])
        for k, v in r.get("secondaryMetrics", {}).items():
            results[(corpus, k)][prof] = (v["score"], v.get("scoreError", float("nan")))
profiles = sorted({p for d in results.values() for p in d})
for corpus in sorted({c for c, _ in results}):
    print(f"\n## {corpus}\n")
    print("| metric | " + " | ".join(profiles) + " |")
    print("|---" * (len(profiles) + 1) + "|")
    for (c, m), d in sorted(results.items()):
        if c != corpus or m.endswith(".other") or "cycles%" in m and False:
            continue
        def fmt(x):
            s, e = x
            if m == "ms/op":
                return f"{s:.1f} ± {e:.1f}" if isinstance(e, (int, float)) else f"{s:.1f}"
            return f"{s:.4g}" if isinstance(s, (int, float)) else str(s)
        print(f"| {m} | " + " | ".join(fmt(d[p]) if p in d else "" for p in profiles) + " |")

# --- xctrace per-method attribution (written to *.methods.txt by XctraceProfiler)
for f in sorted(glob.glob(f"{root}/*/*.methods.txt")):
    print(f"\n## attribution: {os.path.relpath(f, root)}\n")
    print(open(f).read().strip())

# --- async-profiler: self time and phase split, benchmark threads only
PHASES = [  # (label, regex on a frame); the first match from the root of the stack wins
    ("parser", r"dotty/tools/dotc/parsing/Parser\.|dotty/tools/dotc/parsing/Parsers"),
    ("typer", r"dotty/tools/dotc/typer/TyperPhase\."),
    ("posttyper", r"dotty/tools/dotc/transform/PostTyper\."),
    ("sbt-api/deps", r"dotty/tools/dotc/sbt/"),
    ("pickler", r"dotty/tools/dotc/transform/Pickler\."),
    ("inlining", r"dotty/tools/dotc/transform/(Inlining|PostInlining)\."),
    ("erasure", r"dotty/tools/dotc/transform/Erasure\."),
    ("megaphases", r"dotty/tools/dotc/transform/MegaPhase\."),
    ("genBCode", r"dotty/tools/backend/jvm/"),
    ("javac parse", r"com/sun/tools/javac/main/JavaCompiler\.parse"),
    ("javac enter", r"com/sun/tools/javac/main/JavaCompiler\.(enterTrees|enterDone)"),
    ("javac attribute", r"com/sun/tools/javac/main/JavaCompiler\.attribute"),
    ("javac flow", r"com/sun/tools/javac/main/JavaCompiler\.flow"),
    ("javac desugar", r"com/sun/tools/javac/main/JavaCompiler\.desugar"),
    ("javac generate", r"com/sun/tools/javac/main/JavaCompiler\.generate"),
    ("other phases", r"dotty/tools/dotc/core/Phases\$Phase\.runOn"),
]
PHASE_RES = [(l, re.compile(r)) for l, r in PHASES]

MEGA = re.compile(r"dotty/tools/dotc/transform/MegaPhase\.")
MINI = re.compile(r"dotty/tools/dotc/transform/([A-Za-z0-9]+)[.$]")

def phase(frames):
    # a named phase anywhere in the stack wins over the generic Phase.runOn, which sits nearer the root
    named = [(l, r) for l, r in PHASE_RES if l != "other phases"]
    generic = [(l, r) for l, r in PHASE_RES if l == "other phases"]
    for res in (named, generic):
        hit = phase_in(frames, res)
        if hit:
            return hit
    return "outside phases"

def phase_in(frames, res):
    for fr in frames:
        for label, rx in res:
            if rx.search(fr):
                if label == "megaphases":
                    # name the mini-phase: the transform frame right below the innermost MegaPhase frame
                    last = max(i for i, f in enumerate(frames) if MEGA.search(f))
                    for f in frames[last + 1:]:
                        m = MINI.match(f)
                        if m and m.group(1) != "MegaPhase":
                            return "mega:" + m.group(1)
                    return "mega:(MegaPhase itself)"
                return label
    return None

def collapsed(jfr, kind):
    # hw:<event>: one event of a multi-event kperf session; $JFRCONV: the jfrconv that understands it
    args = ["--hw", kind[3:]] if kind.startswith("hw:") else [f"--{kind}"]
    out = subprocess.run([os.environ.get("JFRCONV", "jfrconv"), *args, "--threads", "-I", ".*jmh-worker.*", "-o", "collapsed", jfr, "/dev/stdout"],
                         capture_output=True, text=True).stdout
    for line in out.splitlines():
        stack, _, n = line.rpartition(" ")
        if stack and n.isdigit():
            yield stack.split(";"), int(n)

def clean(fr):
    return re.sub(r"_\[[0-9a-z]\]$", "", fr)

if PHASES_ONLY:
    jfr, kind = sys.argv[2], sys.argv[3]
    by_phase, total = collections.Counter(), 0
    for frames, n in collapsed(jfr, kind):
        total += n
        by_phase[phase(frames)] += n
    for p, n in by_phase.most_common():
        print(f"{p}\t{n / total if total else 0:.4f}")
    sys.exit(0)

for jfr in sorted(glob.glob(f"{root}/async-*/**/*.jfr", recursive=True)):
    corpus = re.search(r"corpus-([^-/]+)", jfr).group(1)
    for kind in ("cpu", "alloc"):
        by_phase, by_self, total = collections.Counter(), collections.Counter(), 0
        for frames, n in collapsed(jfr, kind):
            total += n
            by_phase[phase(frames)] += n
            leaf = clean(frames[-1])
            if kind == "alloc":  # leaf is the allocated type; attribute to the allocating method too
                leaf = clean(frames[-2]) + " -> " + leaf if len(frames) > 1 else leaf
            by_self[leaf] += n
        if total == 0:
            continue
        print(f"\n## async {kind}: {corpus} ({total} {'samples' if kind == 'cpu' else 'bytes sampled'})\n")
        print("by phase: " + ", ".join(f"{p} {100 * n / total:.1f}%" for p, n in by_phase.most_common() if not p.startswith("mega:")))
        mega = sum(n for p, n in by_phase.items() if p.startswith("mega:"))
        print(f"megaphases {100 * mega / total:.1f}%: " + ", ".join(f"{p[5:]} {100 * n / total:.1f}%" for p, n in by_phase.most_common(40) if p.startswith("mega:")))
        print(f"\ntop {'self' if kind == 'cpu' else 'allocation sites'}:")
        for m, n in by_self.most_common(25):
            print(f"  {100 * n / total:5.1f}%  {m}")
