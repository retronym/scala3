"""(Re)renders the flame graphs of async-profiler runs with a given jfrconv, by default the integration build of
retronym/async-profiler (kperf multi-event JFR with `--hw`, and the flame.html with search/filter and bottom-up view).

  python3 -I bench-lab/scripts/regraph.py [--jfrconv <jfrconv>] [-j <jobs>] <profile-dir>...

A profile dir is one that bench-lab's benchLabAsync wrote, `out/bench-lab/async-<events>(<tag>)`. For each JFR under
it, each event and each graph kind below, writes `<jfr stem>-<label>[-<kind>].html` (the names bench-lab uses, so its
indexes keep working), plus the classified forward and reverse graphs of the benchmark threads.
"""
import argparse, concurrent.futures, glob, os, re, subprocess, sys

DEFAULT_JFRCONV = os.path.expanduser("~/code/.worktrees/async-profiler/integration-kperf-flamegraph/build/bin/jfrconv")

# per kind: jfrconv options (thread filter, classification), and whether to render bottom-up
BENCH = ["--threads", "-I", ".*jmh-worker.*"]
KINDS = {
    "bench": (BENCH, False),
    "reverse": (BENCH, True),
    "threads": (["--threads"], False),
    "classify": (["--classify"], False),
    "bench-classify": (["-I", ".*jmh-worker.*", "--classify"], False),
    "reverse-classify": (["-I", ".*jmh-worker.*", "--classify"], True),
}


def events_of(profile_dir):
    """`async-cycles+instructions(tag)` -> [("hw-cycles", ["--hw", "cycles"]), ...]; `async-cpu-alloc` -> cpu, alloc"""
    m = re.match(r"async-([^()]+)", os.path.basename(profile_dir.rstrip("/")))
    spec = m.group(1) if m else "cpu"
    if "+" in spec:
        return [(f"hw-{e.split('@')[0]}", ["--hw", e.split("@")[0]]) for e in spec.split("+")]
    return [(e, [f"--{e}"]) for e in spec.split("-") if e in ("cpu", "wall", "alloc", "lock")] or [("cpu", ["--cpu"])]


def graph_path(jfr, label, kind):
    return f"{jfr[:-4]}-{label}{'' if kind == 'bench' else '-' + kind}.html"


def render_one(jfrconv, jfr, label, select, kind, title):
    extra, reverse = KINDS[kind]
    out = graph_path(jfr, label, kind)
    cmd = [jfrconv, *select, *extra, *(["--reverse"] if reverse else []), "--minwidth", "0.2", "--title", title, jfr, out]
    r = subprocess.run(cmd, capture_output=True, text=True)
    return f"{out}: jfrconv failed: {r.stderr.strip()[:300]}" if r.returncode else None


def regraph(dirs, jfrconv=None, jobs=4, title_of=None):
    jfrconv = jfrconv or DEFAULT_JFRCONV
    tasks = []
    for d in dirs:
        for jfr in sorted(glob.glob(os.path.join(d, "**", "*.jfr"), recursive=True)):
            name = title_of(d) if title_of else os.path.basename(d.rstrip("/"))
            for label, select in events_of(d):
                for kind in KINDS:
                    tasks.append((jfrconv, jfr, label, select, kind, f"{name}: {label.removeprefix('hw-')}, {kind}"))
    with concurrent.futures.ThreadPoolExecutor(jobs) as pool:
        problems = [p for p in pool.map(lambda t: render_one(*t), tasks) if p]
    for p in problems:
        print(p, file=sys.stderr)
    return len(tasks) - len(problems), problems


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jfrconv", default=DEFAULT_JFRCONV)
    ap.add_argument("-j", "--jobs", type=int, default=4)
    ap.add_argument("dirs", nargs="+")
    a = ap.parse_args()
    ok, problems = regraph(a.dirs, a.jfrconv, a.jobs)
    print(f"{ok} graphs written, {len(problems)} problems")
    sys.exit(1 if problems else 0)
