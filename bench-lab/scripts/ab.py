"""Interleaved A/B/... runs of a bench-lab benchmark over corpora and JVM-flag variants, with checks that each run
measured what it claims to, and a summary.

  python3 -I bench-lab/scripts/ab.py run    <spec.json> [--dry-run]   runs every missing cell; resumable
  python3 -I bench-lab/scripts/ab.py report <spec.json>               markdown summary, flame graph index for async

A spec (paths relative to the repository root):

  {
    "name": "exp12",                                   # results go to out/ab/<name>/<corpus>/<variant>/<round>
    "benchmark": "CompileBenchmark",
    "profile": {"xctrace": "ipc"},                     # or {"async": "cycles+instructions", "kperf": "/tmp/asprof-kperf.sock",
                                                       #     "lib": "<libasyncProfiler>", "jvm": "<java>"}
    "jvm": "<java>",                                   # optional: JMH -jvm for every run (checked against result.json)
    "jvmArgs": "-Xms4G -Xmx4G -Xss2M",                 # given to every variant, see below
    "rounds": 2,
    "corpora": {"scalaz": "-wi 10 -i 10 -w 10 -r 10", "tastyQuery": "-wi 15 -i 10 -w 5 -r 5"},
    "variants": {"base": "", "U": "-Ddotty.uncacheMethodParamRefs"},   # the first is the baseline of the report
    "loadgate": {"maxCores": 1.5, "quietSecs": 30}    # optional, see loadgate.py
  }

Cells run round by round, corpus by corpus, variants interleaved within each, so drift hits every variant alike.

Why `jvmArgs` goes to every variant: JMH's `-jvmArgsAppend` on the command line replaces the `@Fork(jvmArgsAppend)` of
the benchmark rather than adding to it, so a variant given only its flags silently loses the benchmark's heap
settings, while a baseline given nothing keeps them. Each run's `jvmArgs` in result.json is checked against the spec.
"""
import glob, json, os, re, shutil, subprocess, sys, time

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SCRIPTS = os.path.dirname(os.path.abspath(__file__))
JMH_LOCK = os.path.join(os.environ.get("TMPDIR", "/tmp"), "jmh.lock")


def log(msg):
    print(f"[ab {time.strftime('%H:%M:%S')}] {msg}", flush=True)


class Spec:
    def __init__(self, path):
        s = json.load(open(path))
        self.name = s["name"]
        self.benchmark = s.get("benchmark", "CompileBenchmark")
        self.profile = s.get("profile", {"xctrace": "ipc"})
        self.jvm_args = s.get("jvmArgs", "-Xms4G -Xmx4G -Xss2M")
        self.jvm = s.get("jvm") or self.profile.get("jvm")
        self.rounds = s.get("rounds", 2)
        self.corpora = s["corpora"]
        self.variants = s["variants"]
        self.loadgate = s.get("loadgate")
        self.out = os.path.join(REPO, "out", "ab", self.name)

    def cells(self):
        for r in range(1, self.rounds + 1):
            for corpus, iters in self.corpora.items():
                for variant, flags in self.variants.items():
                    yield r, corpus, iters, variant, flags

    def cell_dir(self, corpus, variant, r):
        return os.path.join(self.out, corpus, variant, str(r))

    def profile_dir(self, tag):
        """Where bench-lab's sbt command writes this profile (see project/BenchLab.scala)."""
        p = self.profile
        if "xctrace" in p:
            return os.path.join(REPO, "out", "bench-lab", p["xctrace"])
        return os.path.join(REPO, "out", "bench-lab", f"async-{p['async']}({tag})")

    def command(self, corpus, iters, variant, flags, tag):
        jvm_args = " ".join(x for x in [self.jvm_args, flags] if x)
        jvm = f"-jvm {self.jvm} " if self.jvm else ""
        jmh = f'{jvm}-p corpus={corpus} {iters} -jvmArgsAppend "{jvm_args}" {self.benchmark}'
        p = self.profile
        if "xctrace" in p:
            return f"benchLabXctrace {p['xctrace']} {jmh}"
        settings = [f"{k}={p[k]}" for k in ("lib", "kperf") if k in p] + [f"tag={tag}"]
        return f"benchLabAsync {p['async']} {' '.join(settings)} {jmh}"


def jmh_lock_held():
    return subprocess.run(["lsof", JMH_LOCK], capture_output=True).returncode == 0


def sbt_server_pid():
    """The sbt server of this checkout: the process holding the socket named in project/target/active.json."""
    try:
        uri = json.load(open(os.path.join(REPO, "project", "target", "active.json")))["uri"]
    except (OSError, ValueError, KeyError):
        return None
    out = subprocess.run(["lsof", "-t", uri.removeprefix("local://")], capture_output=True, text=True).stdout.split()
    return int(out[0]) if out else None


def wait_until_quiet(spec):
    while jmh_lock_held():
        log("JMH lock held by another run; waiting")
        time.sleep(30)
    if spec.loadgate:
        pid = sbt_server_pid()
        if pid is None:
            sys.exit("loadgate: no sbt server found for this checkout; start one with `sbt --client` first")
        g = spec.loadgate
        subprocess.run([sys.executable, "-I", os.path.join(SCRIPTS, "loadgate.py"), "wait", str(pid),
                        str(g.get("maxCores", 1.5)), str(g.get("quietSecs", 30))], check=True)


def check(spec, prof, started, flags):
    """The run produced fresh results, with the JVM arguments the spec asked for."""
    res = os.path.join(prof, "result.json")
    if not os.path.exists(res) or os.path.getmtime(res) < started:
        return "no fresh result.json (see sbt.log)"
    rs = json.load(open(res))
    if not rs:
        return "empty result.json"
    if spec.jvm and os.path.realpath(rs[0]["jvm"]) != os.path.realpath(spec.jvm):
        return f"ran on {rs[0]['jvm']}, not {spec.jvm}"
    args = rs[0]["jvmArgs"]
    for a in (spec.jvm_args + " " + flags).split():
        if a not in args:
            return f"JVM argument {a} missing from the run's jvmArgs: {args}"
    # a later -Xmx/-Xms/-Xss wins; make sure it is ours
    for opt in ("-Xmx", "-Xms", "-Xss"):
        ours = [a for a in spec.jvm_args.split() if a.startswith(opt)]
        last = [a for a in args if a.startswith(opt)][-1:]
        if ours and last != ours[-1:]:
            return f"{opt}: the run used {last}, not {ours[-1]}"
    return None


def run(spec, dry):
    cells = list(spec.cells())
    todo = [c for c in cells if not os.path.exists(os.path.join(spec.cell_dir(c[1], c[3], c[0]), "result.json"))]
    log(f"{spec.name}: {len(cells)} cells, {len(todo)} to run")
    for r, corpus, iters, variant, flags in todo:
        tag = f"{spec.name}-{corpus}-{variant}-{r}"
        cmd = spec.command(corpus, iters, variant, flags, tag)
        dest = spec.cell_dir(corpus, variant, r)
        if dry:
            print(f"{dest}:\n  sbt --client '{cmd}'")
            continue
        wait_until_quiet(spec)
        prof = spec.profile_dir(tag)
        if "xctrace" in spec.profile and os.path.isdir(prof):
            shutil.rmtree(prof)  # shared between runs: start clean so nothing stale is picked up
        os.makedirs(dest, exist_ok=True)
        log(f"round {r} {corpus} {variant}")
        started = time.time()
        with open(os.path.join(dest, "sbt.log"), "w") as out:
            # the sbt server reads SCALA3_BENCHMARKS when it starts, not from this client
            rc = subprocess.run(["sbt", "--client", cmd], cwd=REPO, stdout=out, stderr=subprocess.STDOUT).returncode
        problem = f"sbt exited with {rc} (see {dest}/sbt.log)" if rc else check(spec, prof, started, flags)
        if problem:
            sys.exit(f"{dest}: {problem}")
        # keep the small files with the cell; async profiles (JFR, flame graphs) stay where bench-lab's indexes expect them
        for f in glob.glob(os.path.join(prof, "*")):
            if f.endswith((".json", ".log", ".methods.txt")):
                shutil.copy(f, dest)
        if "async" in spec.profile:
            with open(os.path.join(dest, "profile-dir"), "w") as f:
                f.write(prof)
        s = summary(json.load(open(os.path.join(dest, "result.json")))[0])
        log(f"  {s['ms']:.0f} ± {s['err']:.0f} ms/op" + (f", {s['cyc'] / 1e9:.2f}G cycles/op" if s.get("cyc") else ""))


def summary(r):
    m, p = r.get("secondaryMetrics", {}), r["primaryMetric"]
    get = lambda k: m[k]["score"] if k in m else None
    return {"ms": p["score"], "err": p["scoreError"], "alloc": get("gc.alloc.rate.norm"), "gc": get("gc.time"),
            "cyc": get("xctrace.CORE_ACTIVE_CYCLE"), "ipc": get("xctrace.IPC.bench")}


def mean(xs):
    xs = [x for x in xs if x is not None]
    return sum(xs) / len(xs) if xs else None


def report(spec):
    print(f"### {spec.name}\n")
    print("| corpus | variant | ms/op (per round) | mean | Δ | cycles/op | Δ | IPC | alloc/op | GC time |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for corpus in spec.corpora:
        base = None
        for variant in spec.variants:
            rs = []
            for r in range(1, spec.rounds + 1):
                f = os.path.join(spec.cell_dir(corpus, variant, r), "result.json")
                if os.path.exists(f):
                    rs.append(summary(json.load(open(f))[0]))
            if not rs:
                continue
            row = {k: mean(s[k] for s in rs) for k in ("ms", "cyc", "ipc", "alloc", "gc")}
            base = base or row
            pct = lambda k: f"{100 * (row[k] / base[k] - 1):+.1f}%" if row[k] and base[k] and row is not base else ""
            fmt = lambda x, f: f.format(x) if x is not None else "–"
            per = " / ".join(f"{s['ms']:.0f} ± {s['err']:.0f}" for s in rs)
            print(f"| {corpus} | {variant} | {per} | {row['ms']:.0f} | {pct('ms')} | {fmt(row['cyc'] and row['cyc'] / 1e9, '{:.2f}G')} | "
                  f"{pct('cyc')} | {fmt(row['ipc'], '{:.2f}')} | {fmt(row['alloc'] and row['alloc'] / 1e6, '{:.0f} MB')} | "
                  f"{fmt(row['gc'] and row['gc'] / 1e3, '{:.1f} s')} |")
    if "async" in spec.profile:
        print("\n" + graphs(spec))


EVENT_GRAPHS = ["bench", "reverse", "bench-classify", "reverse-classify", "threads", "classify"]


def graphs(spec):
    """For async runs: every flame graph of every cell (re)rendered by regraph.py, with the jfrconv of the spec's
    profile ("jfrconv", else regraph's default), and out/ab/<name>/index.html linking them."""
    sys.path.insert(0, SCRIPTS)  # python3 -I does not put the script's directory on the path
    import regraph
    p = spec.profile
    events = [label for label, _ in regraph.events_of(f"async-{p['async']}")]
    cells = []
    for corpus in spec.corpora:
        for variant, flags in spec.variants.items():
            for r in range(1, spec.rounds + 1):
                cell = spec.cell_dir(corpus, variant, r)
                if os.path.exists(os.path.join(cell, "profile-dir")):
                    cells.append((corpus, variant, flags, r, cell, open(os.path.join(cell, "profile-dir")).read().strip()))
    regraph.regraph([c[5] for c in cells], p.get("jfrconv"))
    rows = []
    for corpus, variant, flags, r, cell, prof in cells:
        jfr = (glob.glob(os.path.join(prof, "**", "*.jfr"), recursive=True) or [None])[0]
        if jfr is None:
            continue
        tds = []
        for label in events:
            links = [f'<a href="{os.path.relpath(regraph.graph_path(jfr, label, k), spec.out)}">{k}</a>'
                     for k in regraph.KINDS if os.path.exists(regraph.graph_path(jfr, label, k))]
            tds.append("<td>" + " · ".join(links) + "</td>")
        s = summary(json.load(open(os.path.join(cell, "result.json")))[0])
        rows.append(f"<tr><th>{corpus}</th><th title=\"{flags}\">{variant} #{r}</th><td>{s['ms']:.0f} ± {s['err']:.0f}</td>{''.join(tds)}</tr>")
    events = [e.removeprefix("hw-") for e in events]
    page = f"""<!doctype html><meta charset="utf-8"><title>{spec.name} flame graphs</title>
<style>:root{{--bg:#f6f7f9;--fg:#1d232b;--muted:#5d6875;--line:#dde2e8;--link:#2f6fb2}}
@media (prefers-color-scheme: dark){{:root{{--bg:#14181d;--fg:#e3e8ee;--muted:#94a0ad;--line:#2c333c;--link:#6ea8e6;color-scheme:dark}}}}
body{{background:var(--bg);color:var(--fg);font:13px/1.5 -apple-system,system-ui,sans-serif;margin:20px}} a{{color:var(--link)}}
table{{border-collapse:collapse}} td,th{{border:1px solid var(--line);padding:4px 8px;text-align:left;vertical-align:top}} .k{{color:var(--muted)}}</style>
<h1>{spec.name}</h1>
<p class="k">{spec.benchmark}, profile {json.dumps(p)}, JVM arguments for every variant: {spec.jvm_args}.
Variants: {"; ".join(f"{v}: {f or '(none)'}" for v, f in spec.variants.items())}.<br>
bench: benchmark threads · reverse: by leaf · *-classify: benchmark threads, jfrconv --classify · threads: all threads · classify: all threads by category</p>
<table><tr><th>corpus</th><th>variant</th><th>ms/op</th>{''.join(f'<th>{e}</th>' for e in events)}</tr>{''.join(rows)}</table>"""
    index = os.path.join(spec.out, "index.html")
    os.makedirs(spec.out, exist_ok=True)
    open(index, "w").write(page)
    return f"Flame graphs: {index}"


if __name__ == "__main__":
    if len(sys.argv) < 3 or sys.argv[1] not in ("run", "report"):
        sys.exit(__doc__)
    spec = Spec(sys.argv[2])
    if sys.argv[1] == "run":
        run(spec, "--dry-run" in sys.argv)
    else:
        report(spec)
