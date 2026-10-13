"""Writes the live dashboard's state.json (rows tagged by kind) from saved result sets.

  python3 -I bench-lab/scripts/dashdata.py <findings.json> <dest-dir> <set-dir>...

A set dir holds profile directories (cpu-counters, ipc, frontend, async-cpu-alloc, ...). Later sets win for the same
(corpus, metric). A set whose name ends in -pargc is labelled as a Parallel GC variant of its corpora.
"""
import glob, json, os, re, subprocess, sys, time

findings, dest, sets = sys.argv[1], sys.argv[2], sys.argv[3:]
here = os.path.dirname(__file__)
KEEP = re.compile(r"^(ms/op|gc\.alloc\.rate\.norm|gc\.time|gc\.count|jit\.(compilations|compileTime|deopts|lastWarmup\.compilations)|"
                  r"xctrace\.(useful|processing|delivery|discarded)\.bench|xctrace\.oncpu\.(bench|gc|jit)|xctrace\.cycles%\.gc|"
                  r"xctrace\.IPC\.bench|xctrace\.(INST_ALL|CORE_ACTIVE_CYCLE)|xctrace\.[A-Z0-9_]+/(kinst|cycles))$")

def corpus_of(params, set_name):
    c = params.get("corpus") or params.get("javaCorpus") or "?"
    return c + (" (Parallel GC)" if set_name.endswith("-pargc") else "")

state, metrics, runs = [], {}, []
for d in sets:
    set_name = os.path.basename(d.rstrip("/"))
    for prof in sorted(glob.glob(os.path.join(d, "*"))):
        f = os.path.join(prof, "result.json")
        try:
            rs = json.load(open(f))
        except (OSError, ValueError):
            continue
        if not rs:
            continue
        runs.append({"profile": f"{set_name}/{os.path.basename(prof)}", "status": "done",
                     "started": "", "finished": time.strftime("%H:%M", time.localtime(os.path.getmtime(f))), "load": None})
        for r in rs:
            c = corpus_of(r["params"], set_name)
            items = [("ms/op", r["primaryMetric"]["score"], r["primaryMetric"]["scoreError"], "ms/op")]
            items += [(k, v["score"], v.get("scoreError"), v["scoreUnit"]) for k, v in r.get("secondaryMetrics", {}).items()]
            for k, s, e, unit in items:
                if KEEP.match(k) and isinstance(s, (int, float)):
                    metrics[(c, k)] = {"corpus": c, "metric": k, "value": s, "unit": unit, "profile": f"{set_name}/{os.path.basename(prof)}",
                                       "error": e if isinstance(e, (int, float)) and e == e else None}

phases = {}
for d in sets:
    set_name = os.path.basename(d.rstrip("/"))
    for jfr in glob.glob(os.path.join(d, "async-cpu-alloc", "**", "*.jfr"), recursive=True):
        m = re.search(r"(?:corpus|javaCorpus)-([^-/]+)", jfr)
        c = (m.group(1) if m else "?") + (" (Parallel GC)" if set_name.endswith("-pargc") else "")
        for sample in ("cpu", "alloc"):
            out = subprocess.run(["python3", "-I", os.path.join(here, "analyze.py"), "--phases", jfr, sample], capture_output=True, text=True).stdout
            rows = []
            for line in out.splitlines():
                ph, share = line.rsplit("\t", 1)
                rows.append({"corpus": c, "sample": sample, "phase": ph, "share": float(share)})
            if rows:
                phases[(c, sample)] = rows

state += [{"kind": "runs", **r} for r in runs]
state += [{"kind": "metrics", **m} for m in sorted(metrics.values(), key=lambda r: (r["corpus"], r["metric"]))]
state += [{"kind": "phases", **r} for rows in phases.values() for r in rows]
if os.path.exists(findings):
    state += [{"kind": "findings", **f} for f in json.load(open(findings))]
state.append({"kind": "meta", "updated": time.strftime("%H:%M:%S")})
with open(os.path.join(dest, "state.json"), "w") as f:
    json.dump(state, f)
print(f"{len(runs)} runs, {len(metrics)} metrics, {len(phases)} phase profiles")
