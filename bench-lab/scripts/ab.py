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
    "variantJmhArgs": {"U": "-p scalacOptions=-Yno-check-positions"},  # optional: extra JMH arguments per variant
    "loadgate": {"maxCores": 1.5, "quietSecs": 30},   # optional, see loadgate.py
    "historical": "<why>",                            # optional: the spec is kept for its results; `run` refuses it
    "gcLog": true,                                    # optional: gc.log per cell, shown in its timeline.html
    "paired": {"base": "-Yno-prime-spans", "prime": ""}  # optional, instead of variants: scalacOptions per variant,
                                                      # alternating by iteration within each fork (doc/paired-ab.md)
  }

Cells run round by round, corpus by corpus, variants interleaved within each, so drift hits every variant alike.
With a load gate, a cell starts once the machine is quiet, and competing load is sampled while it runs (`load.tsv` in
the cell); a cell whose mean competing load exceeds `maxCores` is run again.

Why `jvmArgs` goes to every variant: JMH's `-jvmArgsAppend` on the command line replaces the `@Fork(jvmArgsAppend)` of
the benchmark rather than adding to it, so a variant given only its flags silently loses the benchmark's heap
settings, while a baseline given nothing keeps them. Each run's `jvmArgs` in result.json is checked against the spec.
"""
import atexit, glob, json, os, re, shlex, shutil, subprocess, sys, time

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
        # "paired": {"<variant>": "<scalacOptions>", ...}: the variants alternate by iteration within each fork
        # (CompileBenchmark's `variants`), so each cell is one fork running all of them; see doc/paired-ab.md
        self.paired = s.get("paired")
        self.variants = {"paired": ""} if self.paired else s["variants"]
        self.variant_jmh_args = s.get("variantJmhArgs", {})
        if self.paired:
            spec = "|".join(f"{n}={o}" for n, o in self.paired.items())
            if "," in spec:
                sys.exit("paired: JMH splits parameter values at commas; options cannot contain one")
            self.variant_jmh_args = {"paired": f'-p "variants={spec}"'}
        self.loadgate = s.get("loadgate")
        self.gc_log = s.get("gcLog", False)  # -Xlog:gc to <cell>/gc.log, wall-clock stamped, for the cell's timeline
        self.historical = s.get("historical")  # why the spec no longer measures what its names say; report only
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

    def command(self, corpus, iters, variant, flags, tag, dest=None):
        gc = f"-Xlog:gc:file={dest}/gc.log:time,uptime" if self.gc_log and dest else ""
        # the fork appends its iterations to <dest>/iterations.tsv as they finish (PairedProfiler), for the live timeline
        cell = f"-Dbenchlab.cell={dest}" if dest else ""
        jvm_args = " ".join(x for x in [self.jvm_args, flags, gc, cell] if x)
        jvm = f"-jvm {self.jvm} " if self.jvm else ""
        extra = self.variant_jmh_args.get(variant, "")
        jmh = f'{jvm}-p corpus={corpus} {iters} {extra + " " if extra else ""}-jvmArgsAppend "{jvm_args}" {self.benchmark}'
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


RUNNING = os.path.join(REPO, "out", "ab", "running.json")


def set_running(spec, dest):
    """out/ab/running.json names the cell being run (index.html's timeline follows it); then index.html is rebuilt, so
    the page picks up new cells and results without anyone rerunning abindex.py."""
    if dest:
        os.makedirs(os.path.dirname(RUNNING), exist_ok=True)
        json.dump({"campaign": spec.name, "cell": os.path.relpath(dest, os.path.dirname(RUNNING)), "started": time.time()},
                  open(RUNNING, "w"))
        atexit.register(lambda: set_running(spec, None))
    elif os.path.exists(RUNNING):
        os.unlink(RUNNING)
    subprocess.run([sys.executable, "-I", os.path.join(SCRIPTS, "abindex.py")], capture_output=True)


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


MAX_ATTEMPTS = 10


def start_load_sampler(spec, path):
    """`loadgate.py sample` in the background: competing load every 5 s while a cell runs, into `path`."""
    if os.path.exists(path):
        os.unlink(path)
    pid = sbt_server_pid() if spec.loadgate else None
    if pid is None:
        return None
    return subprocess.Popen([sys.executable, "-I", os.path.join(SCRIPTS, "loadgate.py"), "sample", str(pid), path])


def mean_load(path):
    """Mean competing load in cores over a cell's samples (0 without samples)."""
    try:
        xs = [float(l.split("\t")[1]) for l in open(path) if l.strip()]
    except FileNotFoundError:
        return 0.0
    return sum(xs) / len(xs) if xs else 0.0


def check(spec, prof, started, flags, variant):
    """The run produced fresh results, with the JVM arguments and benchmark parameters the spec asked for."""
    res = os.path.join(prof, "result.json")
    if not os.path.exists(res) or os.path.getmtime(res) < started:
        return "no fresh result.json (see sbt.log)"
    rs = json.load(open(res))
    if not rs:
        return "empty result.json"
    if "xctrace" in spec.profile and not any(k.startswith("xctrace.") for k in rs[0]["secondaryMetrics"]):
        return "no xctrace counters in result.json (see jmh.log)"
    if spec.jvm and os.path.realpath(rs[0]["jvm"]) != os.path.realpath(spec.jvm):
        return f"ran on {rs[0]['jvm']}, not {spec.jvm}"
    args = rs[0]["jvmArgs"]
    for a in (spec.jvm_args + " " + flags).split():
        if a not in args:
            return f"JVM argument {a} missing from the run's jvmArgs: {args}"
    # a variant's `-p name=value`, which a quoting slip between here, sbt --client and JMH would silently drop
    words = shlex.split(spec.variant_jmh_args.get(variant, ""))
    for flag, kv in zip(words, words[1:]):
        if flag == "-p" and "=" in kv:
            name, value = kv.split("=", 1)
            got = rs[0].get("params", {}).get(name)
            if got != value:
                return f"parameter {name}: the run had {got!r}, not {value!r}"
    # a later -Xmx/-Xms/-Xss wins; make sure it is ours
    for opt in ("-Xmx", "-Xms", "-Xss"):
        ours = [a for a in spec.jvm_args.split() if a.startswith(opt)]
        last = [a for a in args if a.startswith(opt)][-1:]
        if ours and last != ours[-1:]:
            return f"{opt}: the run used {last}, not {ours[-1]}"
    return None


def check_samples(spec, prof, dest):
    """A kperf recording can come back nearly empty (samples lost in the helper): the benchmark threads must show at
    least 0.5 GHz worth of cycles per op."""
    sys.path.insert(0, SCRIPTS)
    import profdiff, regraph
    r = profdiff.load(prof, "cycles", spec.profile.get("jfrconv", regraph.DEFAULT_JFRCONV), dest, "check")
    os.unlink(r["collapsed"])
    cycles = r["total"] / r["ops"]
    if cycles < 0.5e6 * r["score"]:
        return f"only {cycles / 1e9:.2f}G cycles/op sampled on the benchmark threads at {r['score']:.0f} ms/op: lost samples?"
    return None


def run(spec, dry):
    if spec.historical:
        sys.exit(f"{spec.name} is historical, kept for its results; not running it: {spec.historical}")
    cells = list(spec.cells())
    todo = [c for c in cells if not os.path.exists(os.path.join(spec.cell_dir(c[1], c[3], c[0]), "result.json"))]
    log(f"{spec.name}: {len(cells)} cells, {len(todo)} to run")
    for r, corpus, iters, variant, flags in todo:
        tag = f"{spec.name}-{corpus}-{variant}-{r}"
        dest = spec.cell_dir(corpus, variant, r)
        cmd = spec.command(corpus, iters, variant, flags, tag, dest)
        if dry:
            print(f"{dest}:\n  sbt --client '{cmd}'")
            continue
        prof = spec.profile_dir(tag)
        for attempt in range(1, MAX_ATTEMPTS + 1):
            wait_until_quiet(spec)
            if "xctrace" in spec.profile and os.path.isdir(prof):
                shutil.rmtree(prof)  # shared between runs: start clean so nothing stale is picked up
            os.makedirs(dest, exist_ok=True)
            log(f"round {r} {corpus} {variant}" + (f" (attempt {attempt})" if attempt > 1 else ""))
            set_running(spec, dest)
            started = time.time()
            load_tsv = os.path.join(dest, "load.tsv")
            sampler = start_load_sampler(spec, load_tsv)
            try:
                with open(os.path.join(dest, "sbt.log"), "w") as out:
                    # the sbt server reads SCALA3_BENCHMARKS when it starts, not from this client
                    rc = subprocess.run(["sbt", "--client", cmd], cwd=REPO, stdout=out, stderr=subprocess.STDOUT).returncode
            finally:
                if sampler:
                    sampler.terminate()
                    sampler.wait()
            # another JMH run took the lock between wait_until_quiet and our JMH starting: JMH exits before logging
            jmh_log = os.path.join(prof, "jmh.log")
            if rc and jmh_lock_held() and (not os.path.exists(jmh_log) or os.path.getsize(jmh_log) == 0):
                log("  JMH did not start, another run holds the JMH lock; retrying")
                continue
            # the load gate only holds a cell until the machine is quiet; other work can start during it
            load = mean_load(load_tsv)
            if not rc and spec.loadgate and load > spec.loadgate.get("maxCores", 1.5):
                log(f"  other load averaged {load:.1f} cores during the run; rerunning")
                continue
            break
        else:
            sys.exit(f"{dest}: no clean run in {MAX_ATTEMPTS} attempts")
        problem = f"sbt exited with {rc} (see {dest}/sbt.log)" if rc else check(spec, prof, started, flags, variant)
        if not problem and "async" in spec.profile and spec.profile["async"].startswith("cycles"):
            problem = check_samples(spec, prof, dest)
        if problem:
            sys.exit(f"{dest}: {problem}")
        # keep the small files with the cell; async profiles (JFR, flame graphs) stay where bench-lab's indexes expect them
        for f in glob.glob(os.path.join(prof, "*")):
            if f.endswith((".json", ".log", ".methods.txt", ".threads.txt", ".tsv")):
                shutil.copy(f, dest)
        for f in glob.glob(os.path.join(dest, "*.timeline.tsv")):
            shutil.copy(f, os.path.join(dest, "timeline.tsv"))
        if not os.path.exists(os.path.join(dest, "iterations.tsv")):
            for f in glob.glob(os.path.join(dest, "*.iterations.tsv")):
                shutil.copy(f, os.path.join(dest, "iterations.tsv"))
        if os.path.exists(os.path.join(dest, "iterations.tsv")):
            subprocess.run([sys.executable, "-I", os.path.join(SCRIPTS, "timeline.py"), dest], capture_output=True)
        set_running(spec, None)
        if "async" in spec.profile:
            with open(os.path.join(dest, "profile-dir"), "w") as f:
                f.write(prof)
        s = summary(json.load(open(os.path.join(dest, "result.json")))[0])
        log(f"  {s['ms']:.0f} ± {s['err']:.0f} ms/op" + (f", {s['cyc'] / 1e9:.2f}G cycles/op" if s.get("cyc") else "")
            + (f", other load {load:.2f} cores" if spec.loadgate else ""))


def summary(r):
    m, p = r.get("secondaryMetrics", {}), r["primaryMetric"]
    get = lambda k: m[k]["score"] if k in m else None
    return {"ms": p["score"], "err": p["scoreError"], "alloc": get("gc.alloc.rate.norm"), "gc": get("gc.time"),
            "cyc": get("xctrace.CORE_ACTIVE_CYCLE"), "ipc": get("xctrace.IPC.bench"), "inst": get("xctrace.INST_ALL")}


def mean(xs):
    xs = [x for x in xs if x is not None]
    return sum(xs) / len(xs) if xs else None


def report_paired(spec):
    """One row per corpus and variant: its mean per fork, and the paired difference against the first variant (the
    mean within each fork, then the mean and spread of those over forks)."""
    names = list(spec.paired)
    print(f"### {spec.name} (paired within forks: {', '.join(names)})\n")
    metrics = [("time", "paired.{}", 1, "{:.1f}"), ("time excl. GC", "paired.gcFree.{}", 1, "{:.1f}"),
               ("instructions/op", "xctrace.INST_ALL.{}", 1e9, "{:.3f}G"),
               ("cycles/op", "xctrace.CORE_ACTIVE_CYCLE.{}", 1e9, "{:.3f}G")]
    print("| corpus | variant | forks | " + " | ".join(f"{m} | Δ (paired) | Δ per fork (median)" for m, *_ in metrics) + " |")
    print("|---|---|---|" + "---|---|---|" * len(metrics))
    for corpus in spec.corpora:
        cells = []
        for r in range(1, spec.rounds + 1):
            f = os.path.join(spec.cell_dir(corpus, "paired", r), "result.json")
            if os.path.exists(f):
                cells.append(json.load(open(f))[0].get("secondaryMetrics", {}))
        if not cells:
            continue
        for v in names:
            tds = []
            for _, key, scale, fmt in metrics:
                vals = [c[key.format(v)]["score"] for c in cells if key.format(v) in c]
                ds = [c[key.format(v) + ".delta%"]["score"] for c in cells if key.format(v) + ".delta%" in c]
                meds = [c[key.format(v) + ".delta%.median"]["score"] for c in cells if key.format(v) + ".delta%.median" in c]
                m = mean(vals)
                tds.append(fmt.format(m / scale) if m is not None else "–")
                if ds:
                    d = mean(ds)
                    sd = (sum((x - d) ** 2 for x in ds) / (len(ds) - 1)) ** 0.5 if len(ds) > 1 else 0.0
                    per = " ".join(f"{x:+.2f}" + (f" ({md:+.2f})" if len(meds) == len(ds) else "") for x, md in zip(ds, meds or ds))
                    tds += [f"{d:+.2f}% ± {sd:.2f}", per]
                else:
                    tds += ["", ""]
            print(f"| {corpus} | {v} | {len(cells)} | " + " | ".join(tds) + " |")


def report(spec):
    if spec.paired:
        return report_paired(spec)
    print(f"### {spec.name}\n")
    print("| corpus | variant | ms/op (per round) | mean | Δ | instructions/op (per round) | Δ | cycles/op | Δ | IPC | alloc/op | GC time |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
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
            row = {k: mean(s[k] for s in rs) for k in ("ms", "cyc", "ipc", "alloc", "gc", "inst")}
            base = base or row
            pct = lambda k: f"{100 * (row[k] / base[k] - 1):+.1f}%" if row[k] and base[k] and row is not base else ""
            fmt = lambda x, f: f.format(x) if x is not None else "–"
            per = " / ".join(f"{s['ms']:.0f} ± {s['err']:.0f}" for s in rs)
            insts = " / ".join(f"{s['inst'] / 1e9:.3f}G" for s in rs if s.get("inst"))
            print(f"| {corpus} | {variant} | {per} | {row['ms']:.0f} | {pct('ms')} | {insts} | {pct('inst')} | {fmt(row['cyc'] and row['cyc'] / 1e9, '{:.2f}G')} | "
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
