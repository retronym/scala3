"""Compile corpora with the working tree's non-bootstrapped compiler and -Ddotty.patmatStats (DO NOT MERGE
instrumentation in transform/patmat/PatmatStats.scala), writing one report per corpus.

  python3 -I bench-lab/scripts/patmat_stats.py [--jdk <java home>] [--refresh-cp] [--extra="-D..."] [--tag <name>] <corpus>...

Reports go to out/patmat-stats/<corpus>[-<tag>].txt, class files to out/patmat-stats/classes/<corpus>[-<tag>] and
the compiler's messages to out/patmat-stats/<corpus>[-<tag>].log, to check that a variant changes neither. The compiler's runtime classpath comes from sbt
(`export scala3-compiler-nonbootstrapped/Runtime/fullClasspath`), cached in out/patmat-stats/cp.txt; `--refresh-cp`
asks sbt again. Timings in the report are from one cold-JVM compile: trust the counters and the relative times, and the
JMH benchmark (PatmatBenchmark) for absolute ones.
"""
import argparse, os, re, shutil, subprocess, sys, time

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(REPO, "out", "patmat-stats")
DEFAULT_JDK = os.path.expanduser("~/.sdkman/candidates/java/17.0.20-tem")
CORPORA = os.environ.get("SCALA3_BENCHMARKS", os.path.expanduser("~/code/lampepfl/scala3-benchmarks"))
# Options from lampepfl/scala3-benchmarks' build.sbt (as in bench-lab's Corpus.scala)
OPTIONS = {
    "small": [], "dottyUtil": [], "re2s": [],
    "tastyQuery": ["-Yexplicit-nulls", "-Wconf:msg=Unnecessary .nn:s"],
    "scalaz": ["-nowarn", "-source", "3.0", "-Xkind-projector", "-language:implicitConversions"],
}


def log(msg):
    print(f"[patmat-stats {time.strftime('%H:%M:%S')}] {msg}", flush=True)


def classpath(jdk, refresh):
    cp_file = os.path.join(OUT, "cp.txt")
    if refresh or not os.path.exists(cp_file):
        env = dict(os.environ, JAVA_HOME=jdk, PATH=os.path.join(jdk, "bin") + os.pathsep + os.environ["PATH"])
        log("asking sbt for the compiler's classpath")
        res = subprocess.run(["sbt", "--client", "export scala3-compiler-nonbootstrapped/Runtime/fullClasspath"],
                             cwd=REPO, env=env, capture_output=True, text=True)
        lines = [l for l in res.stdout.splitlines() if re.match(r"^/\S+(:\S+)+$", l.strip())]
        if res.returncode != 0 or not lines:
            sys.exit(f"sbt export failed:\n{res.stdout[-3000:]}\n{res.stderr[-3000:]}")
        with open(cp_file, "w") as f:
            f.write(lines[-1].strip())
    return open(cp_file).read().strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jdk", default=DEFAULT_JDK)
    ap.add_argument("--refresh-cp", action="store_true")
    ap.add_argument("--extra", default="", help="extra JVM -D flags, space separated")
    ap.add_argument("--tag", default="", help="suffix of the report's name, for variants")
    ap.add_argument("corpora", nargs="+")
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)
    cp = classpath(a.jdk, a.refresh_cp)
    lib = ":".join(p for p in cp.split(":") if "library" in p)
    java = os.path.join(a.jdk, "bin", "java")
    for corpus in a.corpora:
        src = os.path.join(CORPORA, "bench-sources", corpus)
        files = sorted(os.path.join(d, f) for d, _, fs in os.walk(src) for f in fs if f.endswith(".scala"))
        if not files:
            sys.exit(f"no sources under {src}")
        name = f"{corpus}{'-' + a.tag if a.tag else ''}"
        report = os.path.join(OUT, f"{name}.txt")
        outdir = os.path.join(OUT, "classes", name)
        shutil.rmtree(outdir, ignore_errors=True)
        os.makedirs(outdir)
        args_file = os.path.join(OUT, f"{corpus}.args")
        with open(args_file, "w") as f:
            # one argument per line, quoted: dotc splits an @file at whitespace
            f.write("\n".join(f'"{x}"' for x in ["-feature", "-deprecation", *OPTIONS.get(corpus, []), "-classpath", lib, "-d", outdir, *files]))
        cmd = [java, "-Xss2M", "-Xmx4G", f"-Ddotty.patmatStats={report}", *a.extra.split(),
               "-cp", cp, "dotty.tools.dotc.Main", f"@{args_file}"]
        log(f"{corpus}: {len(files)} files")
        t0 = time.time()
        res = subprocess.run(cmd, capture_output=True, text=True)
        if res.returncode != 0:
            sys.exit(f"{corpus}: dotc failed ({res.returncode}):\n{res.stdout[-3000:]}\n{res.stderr[-3000:]}")
        with open(os.path.join(OUT, f"{name}.log"), "w") as f:
            f.write(res.stdout + res.stderr)
        log(f"{corpus}: {time.time() - t0:.1f} s, report {report}")


if __name__ == "__main__":
    main()
