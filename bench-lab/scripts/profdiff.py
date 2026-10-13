"""Compares the async-profiler recordings of two bench-lab runs (e.g. two cells of an ab.py spec with an async profile):
a differential flame graph of the benchmark threads, and the methods whose self time per op changed most.

  python3 -I bench-lab/scripts/profdiff.py [--event cycles] [--jfrconv <jfrconv>] [--top 25] <run-a> <run-b> <out-dir>

A run is a profile dir written by benchLabAsync, or an ab.py cell (its `profile-dir` file points there). Samples are
normalized per op (ops per iteration = ceil(iteration time / score)), so runs of different lengths or speeds compare;
with hardware events the table is in events per op.
"""
import argparse, collections, glob, json, math, os, subprocess, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import regraph


def profile_dir(run):
    p = os.path.join(run, "profile-dir")
    return open(p).read().strip() if os.path.exists(p) else run


def load(run, event, jfrconv, out_dir, name):
    d = profile_dir(run)
    jfr = glob.glob(os.path.join(d, "**", "*.jfr"), recursive=True)[0]
    res = json.load(open(os.path.join(d, "result.json")))[0]
    # JMH runs whole ops until the iteration time is up, so an iteration has ceil(time / score) ops (at least one)
    iteration_ms = float(res["measurementTime"].split()[0]) * 1000
    ops = res["measurementIterations"] * math.ceil(iteration_ms / res["primaryMetric"]["score"])
    collapsed = os.path.join(out_dir, f"{name}-{event}.collapsed")
    select = [s for label, s in regraph.events_of(d) if label.removeprefix("hw-") == event][0]
    subprocess.run([jfrconv, "-J-Xmx8g", *select, "--threads", "-I", ".*jmh-worker.*", "-o", "collapsed", jfr, collapsed],
                   check=True, capture_output=True)
    self_, total = collections.Counter(), 0
    for line in open(collapsed):
        stack, _, n = line.rstrip().rpartition(" ")
        n = int(n)
        total += n
        self_[stack.rsplit(";", 1)[-1]] += n
    return {"collapsed": collapsed, "ops": ops, "self": self_, "total": total,
            "score": res["primaryMetric"]["score"], "dir": d}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--event", default="cycles")
    ap.add_argument("--jfrconv", default=regraph.DEFAULT_JFRCONV)
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("a"); ap.add_argument("b"); ap.add_argument("out")
    x = ap.parse_args()
    os.makedirs(x.out, exist_ok=True)
    A = load(x.a, x.event, x.jfrconv, x.out, "a")
    B = load(x.b, x.event, x.jfrconv, x.out, "b")
    # collapsed hardware-event stacks count events (samples × period), not samples
    per_op = lambda r, n: n / r["ops"]
    diff_html = os.path.join(x.out, f"diff-{x.event}.html")
    subprocess.run([x.jfrconv, "-J-Xmx8g", "--diff", "--minwidth", "0.2", "--title",
                    f"{x.event}: {os.path.basename(x.a.rstrip('/'))} → {os.path.basename(x.b.rstrip('/'))}",
                    A["collapsed"], B["collapsed"], diff_html], capture_output=True)
    ta, tb = per_op(A, A["total"]), per_op(B, B["total"])
    print(f"{x.event} per op (benchmark threads): a {ta / 1e9:.2f}G, b {tb / 1e9:.2f}G, {100 * (tb / ta - 1):+.1f}%  "
          f"(ms/op a {A['score']:.0f}, b {B['score']:.0f}; ops a {A['ops']}, b {B['ops']})")
    delta = {m: per_op(B, B["self"][m]) - per_op(A, A["self"][m]) for m in set(A["self"]) | set(B["self"])}
    rows = sorted(delta.items(), key=lambda kv: kv[1])
    fmt = lambda m, d: (f"| {d / 1e6:+7.0f}M | {per_op(A, A['self'][m]) / 1e6:6.0f}M | {per_op(B, B['self'][m]) / 1e6:6.0f}M | "
                       f"{100 * d / ta:+.2f}% | `{m}` |")
    print(f"\nself {x.event} per op, biggest decreases then increases (b − a):\n")
    print("| Δ | a | b | Δ of a's total | method |\n|---|---|---|---|---|")
    for m, d in rows[:x.top]:
        print(fmt(m, d))
    print("| … | | | | |")
    for m, d in rows[-x.top:][::-1]:
        print(fmt(m, d))
    print(f"\ndifferential flame graph: {diff_html}")
    for f in (A["collapsed"], B["collapsed"]):
        os.unlink(f)


main()
