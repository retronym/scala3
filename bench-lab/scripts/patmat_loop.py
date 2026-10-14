"""Quick interleaved A/B of space-engine variants with PatmatLoop (outside JMH, so no JMH lock): for each round, corpus
and variant, one JVM records the corpus's matches and checks them `--passes` times, holding JMH's lock so that
no JMH run measures meanwhile (and waiting for it); the score is the median pass time
of the second half. For iterating on fixes; confirm winners with JMH (bench-lab/ab/micro-patmat.json).

  python3 -I bench-lab/scripts/patmat_loop.py [--passes 40] [--rounds 2] [--corpora tastyQuery,scalaz]
      [--profile <variant>] <name>=<jvm flags> ...

The classpath is `export scala3-bench-lab/Jmh/fullClasspath`, cached in out/bench-lab-cp.txt (`--refresh-cp` to ask sbt
again; recompile with `sbt scala3-bench-lab/Jmh/compile` first). `--profile <variant>` also runs that variant once
under async-profiler (cpu) and writes a collapsed stack file and a flame graph to out/patmat-loop/.
"""
import argparse, contextlib, fcntl, glob, os, re, statistics, subprocess, sys, time

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(REPO, "out", "patmat-loop")
JVM = "/Users/jzaugg-virtus/code/.worktrees/jdk/jdk26-integration-branch-e9e67e/build/release/images/jdk/bin/java"
CORPORA = os.environ.get("SCALA3_BENCHMARKS", os.path.expanduser("~/code/lampepfl/scala3-benchmarks"))
ASYNC = "/opt/homebrew/lib/libasyncProfiler.dylib"
JMH_LOCK = os.path.join(os.environ.get("TMPDIR", "/tmp"), "jmh.lock")


@contextlib.contextmanager
def jmh_lock():
    """JMH's own lock (a POSIX lock on $TMPDIR/jmh.lock): runs here and JMH runs, ours or other sessions', exclude
    each other, so neither measures while the other loads the machine."""
    with open(JMH_LOCK, "a") as f:
        waited = False
        while True:
            try:
                fcntl.lockf(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except OSError:
                if not waited:
                    log("JMH lock held by another run; waiting")
                    waited = True
                time.sleep(5)
        try:
            yield
        finally:
            fcntl.lockf(f, fcntl.LOCK_UN)


def log(msg):
    print(f"[patmat-loop {time.strftime('%H:%M:%S')}] {msg}", flush=True)


def classpath(refresh):
    f = os.path.join(REPO, "out", "bench-lab-cp.txt")
    if refresh or not os.path.exists(f):
        res = subprocess.run(["sbt", "--client", "export scala3-bench-lab/Jmh/fullClasspath"], cwd=REPO,
                             capture_output=True, text=True)
        lines = [l.strip() for l in res.stdout.splitlines() if re.match(r"^/\S+(:\S+)+$", l.strip())]
        if not lines:
            sys.exit(f"sbt export failed:\n{res.stdout[-2000:]}")
        open(f, "w").write(lines[-1])
    return open(f).read().strip()


def run(cp, corpus, passes, flags, agent=None):
    lib = glob.glob(os.path.join(REPO, "library/target/scala-library-bootstrapped/scala-library-*.jar"))[0]
    cmd = [JVM, "-Xms4G", "-Xmx4G", "-Xss2M", f"-Dbenchlab.scalaLibrary={lib}",
           f"-Dbenchlab.corpora={CORPORA}/bench-sources", *flags.split()]
    if agent:
        cmd.append(agent)
    cmd += ["-cp", cp, "dotty.tools.benchlab.PatmatLoop", corpus, str(passes)]
    with jmh_lock():
        res = subprocess.run(cmd, capture_output=True, text=True)
    times = [float(m.group(1)) for m in re.finditer(r"^pass +\d+: +([\d.]+) ms", res.stdout, re.M)]
    if res.returncode != 0 or len(times) != passes:
        sys.exit(f"{corpus} {flags}: failed ({res.returncode}):\n{res.stdout[-2000:]}\n{res.stderr[-2000:]}")
    return statistics.median(times[passes // 2:])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--passes", type=int, default=40)
    ap.add_argument("--rounds", type=int, default=2)
    ap.add_argument("--corpora", default="tastyQuery,scalaz")
    ap.add_argument("--profile")
    ap.add_argument("--refresh-cp", action="store_true")
    ap.add_argument("variants", nargs="+", help="<name>=<jvm flags>")
    a = ap.parse_args()
    variants = dict(v.split("=", 1) for v in a.variants)
    cp = classpath(a.refresh_cp)
    os.makedirs(OUT, exist_ok=True)
    corpora = a.corpora.split(",")
    scores = {(c, v): [] for c in corpora for v in variants}
    for r in range(a.rounds):
        for c in corpora:
            for v, flags in variants.items():
                ms = run(cp, c, a.passes, flags)
                scores[(c, v)].append(ms)
                log(f"round {r + 1} {c} {v}: {ms:.1f} ms/pass")
    base = list(variants)[0]
    print(f"\n| corpus | variant | ms/pass (rounds) | mean | vs {base} |\n|---|---|---|---|---|")
    for c in corpora:
        b = statistics.mean(scores[(c, base)])
        for v in variants:
            m = statistics.mean(scores[(c, v)])
            rounds = " ".join(f"{x:.1f}" for x in scores[(c, v)])
            print(f"| {c} | {v} | {rounds} | {m:.1f} | {100 * (m / b - 1):+.1f}% |")
    if a.profile:
        for c in corpora:
            stem = os.path.join(OUT, f"{c}-{a.profile}")
            for fmt in ("collapsed", "html"):
                agent = f"-agentpath:{ASYNC}=start,event=cpu,interval=1ms,file={stem}.{fmt},{fmt}"
                run(cp, c, a.passes, variants[a.profile], agent)
            log(f"profile: {stem}.html")


if __name__ == "__main__":
    main()
