"""dotc on re2s vs javac on re2j (the same regex library, in Scala and in Java): JMH results of every profile, normalized
by source size, plus phase split and hot methods from async-profiler.

  python3 -I bench-lab/scripts/compare.py <dotc-results>... -- <javac-results>... -- <re2s-src> <re2j-src>

Each results argument is a directory holding profile directories (cpu-counters, ipc, frontend, async-cpu-alloc); later
ones win for the same profile.
"""
import collections, glob, json, os, re, subprocess, sys

args = sys.argv[1:]
i, j = args.index("--"), len(args) - 1 - args[::-1].index("--")
dotc_dirs, javac_dirs, (re2s_src, re2j_src) = args[:i], args[i + 1:j], args[j + 1:]

def lines(src, ext):
    n = 0
    for p in glob.glob(f"{src}/**/*{ext}", recursive=True):
        n += sum(1 for l in open(p, errors="replace") if l.strip())
    return n

def results(dirs, corpus):
    m = {}
    for d in dirs:
        for f in sorted(glob.glob(f"{d}/*/result.json")):
            try:
                rs = json.load(open(f))
            except ValueError:
                continue
            for r in rs:
                if r["params"].get("corpus", r["params"].get("javaCorpus")) != corpus:
                    continue
                prof = os.path.basename(os.path.dirname(f))
                m[f"ms/op@{prof}"] = r["primaryMetric"]["score"]
                for k, v in r.get("secondaryMetrics", {}).items():
                    m[k] = v["score"]
    return m

S = results(dotc_dirs, "re2s")
J = results(javac_dirs, "re2j")
ls, lj = lines(re2s_src, ".scala"), lines(re2j_src, ".java")

def row(name, s, j, fmt="{:.3g}", ratio=True):
    sv = "" if s is None else fmt.format(s)
    jv = "" if j is None else fmt.format(j)
    r = f"{s / j:.2f}x" if ratio and s is not None and j not in (None, 0) else ""
    print(f"| {name} | {sv} | {jv} | {r} |")

def get(m, k):
    return m.get(k)

print(f"| | dotc on re2s | javac on re2j | dotc / javac |\n|---|---|---|---|")
row("non-blank source lines", ls, lj, "{:,}")
t_s, t_j = get(S, "ms/op@cpu-counters") or get(S, "ms/op@ipc"), get(J, "ms/op@cpu-counters") or get(J, "ms/op@ipc")
row("time per compile (ms)", t_s, t_j, "{:.1f}")
row("time per 1,000 lines (ms)", t_s and 1000 * t_s / ls, t_j and 1000 * t_j / lj, "{:.1f}")
a_s, a_j = get(S, "gc.alloc.rate.norm"), get(J, "gc.alloc.rate.norm")
row("allocation per compile (MB)", a_s and a_s / 1e6, a_j and a_j / 1e6, "{:.0f}")
row("allocation per line (KB)", a_s and a_s / ls / 1e3, a_j and a_j / lj / 1e3, "{:.1f}")
i_s, i_j = get(S, "xctrace.INST_ALL"), get(J, "xctrace.INST_ALL")
row("instructions per compile (millions)", i_s and i_s / 1e6, i_j and i_j / 1e6, "{:,.0f}")
row("instructions per line (thousands)", i_s and i_s / ls / 1e3, i_j and i_j / lj / 1e3, "{:.0f}")
row("IPC (compiler thread)", get(S, "xctrace.IPC.bench"), get(J, "xctrace.IPC.bench"), "{:.2f}")
for k, label in [("xctrace.BRANCH_MISPRED_NONSPEC/kinst", "branch mispredicts / 1k instr"),
                 ("xctrace.INST_BRANCH/kinst", "branches / 1k instr"),
                 ("xctrace.L1I_CACHE_MISS_DEMAND/kinst", "L1I misses / 1k instr"),
                 ("xctrace.L1I_TLB_MISS_DEMAND/kinst", "iTLB misses / 1k instr")]:
    row(label, get(S, k), get(J, k), "{:.2f}")
for cat in ("useful", "delivery", "processing", "discarded"):
    row(f"top-down: {cat} (%)", get(S, f"xctrace.{cat}.bench"), get(J, f"xctrace.{cat}.bench"), "{:.1f}", ratio=False)
gs, gj = get(S, "xctrace.oncpu.gc"), get(J, "xctrace.oncpu.gc")
bs, bj = get(S, "xctrace.oncpu.bench"), get(J, "xctrace.oncpu.bench")
row("GC CPU per compiler CPU", gs and bs and gs / bs, gj and bj and gj / bj, "{:.2f}")
row("JIT compilations, last warmup iteration", get(S, "jit.lastWarmup.compilations"), get(J, "jit.lastWarmup.compilations"), "{:.0f}", ratio=False)

# --- phases and hot methods
DOTC = [("parser", r"dotty/tools/dotc/parsing/"), ("typer", r"dotty/tools/dotc/typer/TyperPhase\."),
        ("posttyper", r"dotty/tools/dotc/transform/PostTyper\."), ("pickler", r"dotty/tools/dotc/transform/Pickler\."),
        ("erasure", r"dotty/tools/dotc/transform/Erasure\."), ("transforms (MegaPhase)", r"dotty/tools/dotc/transform/MegaPhase\."),
        ("backend", r"dotty/tools/backend/jvm/")]
JAVAC = [("parse", r"JavaCompiler\.parse"), ("enter", r"JavaCompiler\.(enterTrees|enterDone)"),
         ("attribute", r"JavaCompiler\.attribute"), ("flow", r"JavaCompiler\.flow"),
         ("desugar", r"JavaCompiler\.desugar"), ("generate", r"JavaCompiler\.generate")]

def profile(dirs, kind, phases):
    jfr = None
    for d in dirs:
        for f in glob.glob(f"{d}/async-cpu-alloc/**/*.jfr", recursive=True):
            if "re2s" in f or "JavacBenchmark" in f or "javaCorpus" in f:
                jfr = f
    if not jfr:
        return None, None, 0
    out = subprocess.run(["jfrconv", f"--{kind}", "-o", "collapsed", "-I", ".*jmh_generated.*", jfr, "/dev/stdout"],
                         capture_output=True, text=True).stdout
    ph, leaf, tot = collections.Counter(), collections.Counter(), 0
    rx = [(l, re.compile(r)) for l, r in phases]
    for line in out.splitlines():
        st, _, n = line.rpartition(" ")
        if not n.isdigit():
            continue
        fr, n = st.split(";"), int(n)
        tot += n
        ph[next((l for l, r in rx if any(r.search(f) for f in fr)), "other")] += n
        leaf[re.sub(r"_\[[0-9a-z]\]$", "", fr[-1])] += n
    return ph, leaf, tot

for kind in ("cpu", "alloc"):
    for name, dirs, phases in (("dotc on re2s", dotc_dirs, DOTC), ("javac on re2j", javac_dirs, JAVAC)):
        ph, leaf, tot = profile(dirs, kind, phases)
        if not tot:
            continue
        print(f"\n**{name}, {kind} by phase:** " + ", ".join(f"{p} {100 * n / tot:.0f}%" for p, n in ph.most_common()))
        if kind == "cpu":
            print("top self: " + "; ".join(f"{m.split('/')[-1]} {100 * n / tot:.1f}%" for m, n in leaf.most_common(8)))
