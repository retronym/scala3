#!/usr/bin/env python3
"""Coverage-guided compilation corpus harness for the Scala 3 compiler.

See README.md for the workflow. Subcommands:

  measure      compile a pool of cases under some variants with JaCoCo attached, recording per-job probe coverage
  select       greedy set cover over measured jobs -> manifest of (case, variant)
  materialize  copy the cases of a selection into cases/ and write manifest.txt
  report       JaCoCo line/branch report for the union of some measured campaigns (or of a manifest run)
  gaps         list least-covered compiler source files / methods from a report
  compile      compile the corpus manifest to an output dir (for bytecode comparison)
  diff         compare two output dirs from `compile`
"""

import argparse
import concurrent.futures
import hashlib
import json
import os
import queue
import random
import re
import shlex
import shutil
import struct
import subprocess
import sys
import threading
import time
import zlib
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
WORK = Path(os.environ.get("CORPUS_WORK", HERE / ".work"))
CASES = HERE / "cases"
MANIFEST = HERE / "manifest.txt"
JACOCO_VERSION = "0.8.13"

# ---------------------------------------------------------------------------------------------------------------------
# Variants: option sets layered on top of a case's own `//> using options`.
# `{tmp}` is replaced by a per-job scratch dir. `post` lists extra steps run after the case's own stages.

VARIANTS = {
    "base": dict(opts=[]),
    "check": dict(opts=["-Ycheck:all", "-Xverify-signatures", "-Yno-double-bindings"]),
    "sbt": dict(opts=["-Yforce-sbt-phases", "-Xsemanticdb"]),
    "pickler": dict(opts=["-Ytest-pickler", "-Xprint-types", "-Yprint-pos", "-Yprint-pos-syms"]),
    "lint": dict(opts=["-Wall", "-Wsafe-init", "-Wvalue-discard", "-Wnonunit-statement", "-Wimplausible-patterns",
                       "-Wenum-comment-discard", "-Wtostring-interpolated", "-deprecation", "-feature", "-unchecked"]),
    "nulls": dict(opts=["-Yexplicit-nulls"]),
    "print": dict(opts=["-Vprint:all", "-Xprint-types", "-Yprint-syms", "-Yprint-debug", "-uniqid"]),
    "coverage": dict(opts=["-coverage-out", "{tmp}/scoverage"]),
    "migration": dict(opts=["-source", "3.0-migration"]),
    "future": dict(opts=["-source", "future"]),
    "from-tasty": dict(opts=[], post="from-tasty"),
    "java-tasty": dict(opts=["-Yjava-tasty", "-Ypickle-write", "{tmp}/early.jar"]),
    "rewrite-indent": dict(opts=["-rewrite", "-indent"], copy_sources=True),
    "rewrite-noindent": dict(opts=["-rewrite", "-no-indent"], copy_sources=True),
    "rewrite-new": dict(opts=["-rewrite", "-new-syntax"], copy_sources=True),
    "rewrite-old": dict(opts=["-rewrite", "-old-syntax"], copy_sources=True),
    "rewrite-migration": dict(opts=["-rewrite", "-source", "3.4-migration"], copy_sources=True),
    "debug": dict(opts=["-Ydebug", "-Ylog:all", "-Ydebug-flags", "-Ydebug-names", "-Ydebug-pos", "-Ycheck-mods",
                        "-Ycheck-reentrant", "-Ydebug-missing-refs"]),
    "release11": dict(opts=["-release", "11"]),
    "opt": dict(opts=["-opt", "-opt-inline:**", "-Wopt:all", "-Yopt-log-inline", "_"]),
    "opt-everything": dict(opts=["-opt", "-opt-inline:**", "-Yopt-inline-heuristics:everything"]),
    "profile": dict(opts=["-Yprofile-enabled", "-Yprofile-trace", "{tmp}/trace.json", "-Vprofile", "-Vprofile-details", "5",
                          "-Ydetailed-stats", "-Yinstrument", "-Yinstrument-defs", "-verbose"]),
    "docs": dict(opts=["-Xread-docs", "-Xcook-docs", "-Yretain-trees"]),
    "sbt-dump": dict(opts=["-Yforce-sbt-phases", "-Ydump-sbt-inc", "-Xsemanticdb", "-semanticdb-text"]),
    "jar": dict(opts=[], jar=True),
    "parallel-backend": dict(opts=["-Ybackend-parallelism", "4"]),
    "misc": dict(opts=["-Xno-forwarders", "-Yno-patmat-opt", "-Ycheck-all-patmat", "-Yno-suspended-units",
                       "-Yretain-trees", "-Xno-generic-signatures"]),
    "legacy-lazy": dict(opts=["-Ylegacy-lazy-vals", "-Ycheck-constraint-deps"]),
    "recheck": dict(opts=["-Yrecheck-test"]),
    "init-global": dict(opts=["-Ysafe-init-global"]),
    "print-tasty": dict(opts=["-Yprint-tasty"], post="print-tasty"),
    "print-plain": dict(opts=["-Vprint:all", "-Yplain-printer", "-Xprint-diff-del", "-Yprint-pos", "-Yshow-tree-ids",
                              "-Yprint-debug-owners", "-Xprint-inline"]),
}

SOURCE_EXTS = (".scala", ".java")
GROUP_ORDINAL = re.compile(r"^\d+$")
COMPILER_VERSION = re.compile(r"^c[\d.]+$")

# Options that need infrastructure we don't provide (or that make compilation of the case meaningless here).
UNSUPPORTED_OPTS = ("-scalajs", "-Xplugin", "-Yscala2-library-tasty", "-Ystop-before:", "-Ystop-after:", "-Yread-docs")


def log(*a):
    print(*a, file=sys.stderr, flush=True)


# ---------------------------------------------------------------------------------------------------------------------
# Toolchain: compiler classpath, library classpath, JaCoCo.

def java_bin():
    home = os.environ.get("JAVA_HOME")
    return str(Path(home) / "bin" / "java") if home else "java"


def coursier_fetch(*deps, extra=()):
    out = subprocess.run(["coursier", "fetch", "--classpath", *extra, *deps], check=True, capture_output=True, text=True)
    return out.stdout.strip()


@dataclass
class Toolchain:
    name: str
    compiler_cp: list
    library_cp: list
    compiler_classes: list  # dirs/jars containing compiler classes, for JaCoCo reports
    source_roots: list

    @property
    def key(self):
        return hashlib.sha1("\n".join(self.compiler_cp).encode()).hexdigest()[:12]


def toolchain(spec, refresh=False):
    """`local` = this checkout's nonbootstrapped compiler (built via sbt); otherwise a released version (via coursier)."""
    WORK.mkdir(parents=True, exist_ok=True)
    cache = WORK / f"toolchain-{spec}.json"
    if cache.exists() and not refresh:
        return Toolchain(**json.loads(cache.read_text()))
    if spec == "local":
        out = subprocess.run(["sbt", "--client", "export scala3-compiler-nonbootstrapped/Runtime/fullClasspath"],
                             cwd=REPO, check=True, capture_output=True, text=True).stdout
        line = [l for l in out.splitlines() if "scala3-compiler-nonbootstrapped" in l and ":" in l and not l.startswith("[")][-1]
        cp = line.strip().split(":")
        lib = [p for p in cp if re.search(r"(scala3?-library)", p)]
        classes = [p for p in cp if re.search(r"(scala3-compiler|tasty-core)", p)]
        roots = [str(REPO / "compiler" / "src"), str(REPO / "tasty" / "src"), str(REPO / "compiler" / "src-bootstrapped"),
                 str(REPO / "compiler" / "src-non-bootstrapped")]
    else:
        cp = coursier_fetch(f"org.scala-lang:scala3-compiler_3:{spec}").split(":")
        lib = [p for p in cp if re.search(r"/(scala3-library_3|scala-library)/", p)]
        classes = [p for p in cp if re.search(r"/(scala3-compiler_3|tasty-core_3)/", p)]
        roots = []
    tc = Toolchain(spec, cp, lib, classes, [r for r in roots if Path(r).exists()])
    cache.write_text(json.dumps(tc.__dict__, indent=1))
    return tc


def jacoco_jars():
    cache = WORK / "jacoco.json"
    if cache.exists():
        return json.loads(cache.read_text())
    agent = coursier_fetch(f"org.jacoco:org.jacoco.agent:{JACOCO_VERSION},classifier=runtime", extra=["--intransitive"])
    cli = coursier_fetch(f"org.jacoco:org.jacoco.cli:{JACOCO_VERSION},classifier=nodeps", extra=["--intransitive"])
    d = dict(agent=agent, cli=cli)
    cache.write_text(json.dumps(d))
    return d


def driver_classes():
    src = HERE / "driver" / "CoverageDriver.java"
    out = WORK / "driver"
    stamp = out / "CoverageDriver.class"
    if not stamp.exists() or stamp.stat().st_mtime < src.stat().st_mtime:
        out.mkdir(parents=True, exist_ok=True)
        javac = str(Path(java_bin()).parent / "javac")
        subprocess.run([javac, "--release", "17", "-d", str(out), str(src)], check=True)
    return str(out)


# ---------------------------------------------------------------------------------------------------------------------
# Cases (vulpix conventions: a file, or a dir whose `_N`-suffixed files form ordered separate-compilation groups).

@dataclass
class Case:
    id: str              # stable id: path relative to REPO (or to CASES for materialized cases)
    path: Path
    stages: list         # list[list[Path]]
    scalac_opts: list = field(default_factory=list)
    javac_opts: list = field(default_factory=list)
    skip: str = ""
    pool_opts: list = field(default_factory=list)  # options of the pool the case came from (cf. CompilationTests)
    deps: list = field(default_factory=list)       # `//> using dep` coordinates
    stage_opts: list = None                        # per-stage scalac options, for multi-stage cases


DIRECTIVE = re.compile(r"^\s*//>\s*using\s+(options|javacOpt|javacOptions|dep)\s+(.*)$")


def parse_directives(files):
    sopts, jopts, deps = [], [], []
    for f in files:
        try:
            text = f.read_text(errors="replace")
        except OSError:
            continue
        for line in text.splitlines()[:30]:
            m = DIRECTIVE.match(line)
            if m:
                try:
                    args = shlex.split(m.group(2))
                except ValueError:
                    args = m.group(2).split()
                {"options": sopts, "dep": deps}.get(m.group(1), jopts).extend(args)
    return sopts, jopts, deps


_dep_cache = {}


def resolve_deps(deps):
    """`//> using dep g:a:v` -> classpath entries (coursier), without the Scala library (we supply our own)."""
    key = tuple(sorted(deps))
    if key not in _dep_cache:
        cache = WORK / "deps" / (hashlib.sha1("\n".join(key).encode()).hexdigest()[:12] + ".txt")
        if not cache.exists():
            cache.parent.mkdir(parents=True, exist_ok=True)
            cp = coursier_fetch(*deps, extra=["--exclude", "org.scala-lang:scala-library", "--exclude", "org.scala-lang:scala3-library_3"])
            cache.write_text(cp)
        _dep_cache[key] = [p for p in cache.read_text().split(":") if p]
    return _dep_cache[key]


def group_key(f: Path):
    stem = f.name.rsplit(".", 1)[0]
    parts = stem.split("_")[1:] if "_" in stem else []
    ordinal = next((int(p) for p in parts if GROUP_ORDINAL.match(p)), -(1 << 31))
    compiler = next((p for p in parts if COMPILER_VERSION.match(p)), "")
    return ordinal, compiler


def make_case(path: Path, id: str, pool_opts=()):
    if path.is_file():
        files = [path]
        stages = [files]
    else:
        # files under a `_sourcepath` dir are not passed to the compiler (cases use them with `-sourcepath {case}/_sourcepath`)
        files = sorted(p for p in path.rglob("*") if p.is_file() and p.suffix in SOURCE_EXTS
                       and "_sourcepath" not in p.relative_to(path).parts)
        if not files:
            return None
        groups = {}
        # vulpix only groups the top-level files of the dir; nested dirs join the first group
        for f in files:
            key = group_key(f) if f.parent == path else (-(1 << 31), "")
            groups.setdefault(key, []).append(f)
        stages = [groups[k] for k in sorted(groups)]
        if any(k[1] for k in groups):
            return Case(id, path, stages, skip="needs other compiler version")
    sopts, jopts, deps = parse_directives(files)
    c = Case(id, path, stages, list(pool_opts) + sopts, jopts, pool_opts=list(pool_opts), deps=deps)
    if len(stages) > 1:
        # options apply to the stage whose files declare them
        c.stage_opts = [list(pool_opts) + parse_directives(s)[0] for s in stages]
    if any(o.startswith(u) for o in sopts for u in UNSUPPORTED_OPTS):
        c.skip = "unsupported option"
    return c


def discover(roots, base=REPO):
    """roots: `path` or `path=<scalac options for every case in it>`"""
    cases = []
    expanded = []
    for r in roots:
        if r.startswith("@"):
            expanded += [l.strip() for l in (HERE / r[1:]).read_text().splitlines() if l.strip() and not l.startswith("#")]
        else:
            expanded.append(r)
    for r in expanded:
        r, _, opts = r.partition("=")
        if not Path(r).is_absolute() and not Path(r).exists():
            r = REPO / r
        opts = shlex.split(opts)
        r = Path(r).resolve()
        children = [r] if r.is_file() else sorted(r.iterdir())
        for p in children:
            if p.name.startswith(".") or (p.is_file() and p.suffix not in SOURCE_EXTS):
                continue
            try:
                id = str(p.relative_to(base))
            except ValueError:
                id = str(p)
            c = make_case(p, id, opts)
            if c and not c.skip:
                cases.append(c)
    return cases


# ---------------------------------------------------------------------------------------------------------------------
# Jobs

@dataclass
class Job:
    id: str
    case: Case
    variant: str
    out: Path
    steps: list  # list[(kind, args)]


def subst(opts, tmp):
    return [o.replace("{tmp}", str(tmp)) for o in opts]


def make_job(tc, case, variant, out_root, extra_opts=(), order="sorted", joint=False):
    v = VARIANTS[variant]
    jid = re.sub(r"[^A-Za-z0-9_.-]", "_", case.id) + "@" + variant
    out = out_root / jid
    tmp = out_root / (jid + ".tmp")
    classes = out / "classes"
    stages = case.stages
    if joint:
        stages = [[f for s in stages for f in s]]
    if v.get("copy_sources"):
        src = tmp / "src"
        mapping = {}
        for s in stages:
            for f in s:
                rel = f.relative_to(case.path if case.path.is_dir() else case.path.parent)
                mapping[f] = src / rel
        stages = [[mapping[f] for f in s] for s in stages]
    # `//> using dep @compiler` puts the compiler itself on the classpath (e.g. to build a plugin in an earlier stage)
    deps = [d for d in case.deps if d != "@compiler"]
    libcp = tc.library_cp + (resolve_deps(deps) if deps else [])
    if "@compiler" in case.deps:
        libcp += [p for p in tc.compiler_cp if p not in libcp]
    cp = ":".join(libcp + [str(classes)])
    dest = str(classes)
    if v.get("jar"):
        # every stage writes its own jar; later stages see the earlier jars
        jars = [tmp / f"stage{i}.jar" for i in range(len(stages))]
    steps = []
    for si, s in enumerate(stages):
        s = list(s)
        sopts = case.stage_opts[si] if case.stage_opts and len(case.stage_opts) == len(stages) else case.scalac_opts
        if order == "reverse":
            s.reverse()
        elif order.startswith("shuffle"):
            random.Random(order).shuffle(s)
        scala = [str(f) for f in s if "JAVA_ONLY" not in f.name]
        java = [str(f) for f in s if f.suffix == ".java" and "SCALA_ONLY" not in f.name]
        if v.get("jar"):
            i = len([k for k, _ in steps if k == "scalac"])
            dest = str(jars[i])
            cp = ":".join(libcp + [str(classes)] + [str(j) for j in jars[:i]])
        args = ["-d", dest, "-classpath", cp, "-color:never", "-Wconf:any:s" if variant != "lint" else "-Wconf:any:w"]
        args += subst(v["opts"], tmp) + list(extra_opts) + [expand_case_opt(o, classes, tmp, case, libcp) for o in sopts] + scala
        steps.append(("scalac", args))
        if java:
            steps.append(("javac", ["-d", str(classes), "-cp", cp, "-nowarn", "-proc:none", "-encoding", "UTF-8"] +
                          case.javac_opts + java))
    if v.get("post") == "print-tasty":
        steps.append(("scalac", ["-print-tasty", "-classpath", cp, "-color:never", f"@tasty-in:{classes}"]))
    if v.get("post") == "from-tasty":
        steps.append(("scalac", ["-from-tasty", "-d", str(tmp / "from-tasty"), "-classpath", cp, "-color:never",
                                 "-Wconf:any:s", f"@tasty-in:{classes}"]))
    job = Job(jid, case, variant, out, steps)
    resources = [f for f in case.path.iterdir() if f.is_file() and f.suffix not in SOURCE_EXTS and not f.name.startswith(".")
                 and f.suffix not in (".check", ".flags")] if case.path.is_dir() else []
    job.prepare = lambda: prepare(job, tmp, classes, mapping if v.get("copy_sources") else None, resources)
    return job


def expand_case_opt(o, classes, tmp, case, libcp):
    return (o.replace("{classes}", str(classes)).replace("{tmp}", str(tmp)).replace("{libcp}", ":".join(libcp))
             .replace("{case}", str(case.path)))


def prepare(job, tmp, classes, mapping, resources=()):
    shutil.rmtree(job.out, ignore_errors=True)
    shutil.rmtree(tmp, ignore_errors=True)
    classes.mkdir(parents=True, exist_ok=True)
    tmp.mkdir(parents=True, exist_ok=True)
    for r in resources:  # e.g. plugin.properties
        shutil.copyfile(r, classes / r.name)
    if mapping:
        for a, b in mapping.items():
            b.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(a, b)
    for kind, args in job.steps:
        if "-from-tasty" in args:
            (tmp / "from-tasty").mkdir(parents=True, exist_ok=True)


def write_jobs(path, jobs, exec_dir):
    with open(path, "w") as fh:
        for j in jobs:
            fh.write(f"@job {j.id} {exec_dir / (j.id + '.exec') if exec_dir else '-'}\n")
            for kind, args in j.steps:
                fh.write(f"@{kind}\n")
                for a in args:
                    assert "\n" not in a
                    fh.write(a + "\n")
            fh.write("@end\n")


@dataclass
class Result:
    job: Job
    status: str
    ms: int
    detail: str


def warmup_job(tc, n):
    src = WORK / "warmup" / "Warmup.scala"
    if not src.exists():
        src.parent.mkdir(parents=True, exist_ok=True)
        src.write_text("object Warmup:\n  def main(args: Array[String]): Unit = println(List(1, 2).map(_ + 1))\n")
    c = Case("__warmup__", src, [[src]])
    j = make_job(tc, c, "base", WORK / "warmup" / f"out-{n}-{threading.get_ident()}")
    j.id = f"__warmup__{os.getpid()}_{n}_{threading.get_ident()}"
    return j


def run_jobs(tc, jobs, exec_dir=None, workers=8, chunk=1000, timeout=180, heap="4g", on_result=None, verbose=False,
             on_warmup=None):
    """Run jobs across `workers` JVMs, each processing chunks of jobs. A stalled JVM is killed and its remaining jobs
    resumed in a fresh one."""
    cp = ":".join(tc.compiler_cp + [driver_classes()])
    jvm = ["nice", "-n", "10", java_bin(), f"-Xmx{heap}", "-Xss16m", "-XX:+UseParallelGC", "-cp", cp]
    if exec_dir:
        exec_dir.mkdir(parents=True, exist_ok=True)
        agent = jacoco_jars()["agent"]
        jvm.insert(4, f"-javaagent:{agent}=output=none,includes=dotty.*:scala.quoted.runtime.impl.*,inclnolocationclasses=false")
    lock = threading.Lock()
    results = []
    chunks = [jobs[i:i + chunk] for i in range(0, len(jobs), chunk)]
    jobdir = WORK / "jobfiles"
    jobdir.mkdir(parents=True, exist_ok=True)

    def run_chunk(ci, cjobs):
        pending = list(cjobs)
        while pending:
            for j in pending:
                j.prepare()
            jf = jobdir / f"chunk-{os.getpid()}-{ci}-{len(pending)}.txt"
            # The first compilation in a JVM covers one-time initialisation; give that to an unrecorded warm-up job so
            # it is not credited to whichever real job happens to run first.
            warm = warmup_job(tc, ci)
            warm.prepare()
            write_jobs(jf, [warm] + pending, exec_dir)
            p = subprocess.Popen(jvm + ["CoverageDriver", str(jf)] + ([] if verbose else ["--quiet"]),
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, cwd=WORK)
            q = queue.Queue()
            threading.Thread(target=lambda: ([q.put(l) for l in p.stdout], q.put(None)), daemon=True).start()
            byid = {j.id: j for j in pending}
            done = set()
            tail = []
            while True:
                try:
                    line = q.get(timeout=timeout)
                except queue.Empty:
                    p.kill()
                    stuck = next(j for j in pending if j.id not in done)
                    record(Result(stuck, "TIMEOUT", timeout * 1000, ""))
                    done.add(stuck.id)
                    break
                if line is None:
                    p.wait()
                    if len(done) < len(pending):
                        stuck = next(j for j in pending if j.id not in done)
                        record(Result(stuck, "JVMDIED", 0, " | ".join(tail[-5:])))
                        done.add(stuck.id)
                    break
                if line.startswith("@@RESULT "):
                    _, jid, status, ms, *rest = line.rstrip("\n").split(" ", 4)
                    if jid.startswith("__warmup__"):
                        if on_warmup and exec_dir:
                            on_warmup(exec_dir / (jid + ".exec"))
                        continue
                    done.add(jid)
                    record(Result(byid[jid], status, int(ms), rest[0] if rest else ""))
                else:
                    tail.append(line.rstrip())
                    if verbose:
                        log(line.rstrip())
            jf.unlink(missing_ok=True)
            pending = [j for j in pending if j.id not in done]

    def record(r):
        with lock:
            results.append(r)
            if on_result:
                on_result(r)
            n = len(results)
            if n % 100 == 0 or n == len(jobs):
                fails = sum(1 for x in results if x.status != "OK")
                log(f"  {n}/{len(jobs)} jobs ({fails} not OK)")

    with concurrent.futures.ThreadPoolExecutor(workers) as ex:
        list(ex.map(lambda a: run_chunk(*a), enumerate(chunks)))
    return results


# ---------------------------------------------------------------------------------------------------------------------
# JaCoCo exec files <-> probe bitsets
#
# Each class gets a byte-aligned slot in a global bit vector (the "registry", per toolchain), so a job's coverage is a
# single Python int and set operations are cheap.

def read_exec(data):
    """Yield (class_id, name, nprobes, packed_probe_bytes) from a JaCoCo exec file."""
    i = 0
    n = len(data)

    def varint():
        nonlocal i
        v, shift = 0, 0
        while True:
            b = data[i]; i += 1
            v |= (b & 0x7F) << shift
            if not b & 0x80:
                return v
            shift += 7

    def utf():
        nonlocal i
        ln = struct.unpack_from(">H", data, i)[0]
        s = data[i + 2:i + 2 + ln].decode("utf-8", "replace")
        i += 2 + ln
        return s

    while i < n:
        block = data[i]; i += 1
        if block == 0x01:
            i += 4  # magic + version
        elif block == 0x10:
            utf(); i += 16
        elif block == 0x11:
            cid = struct.unpack_from(">q", data, i)[0]; i += 8
            name = utf()
            np_ = varint()
            nb = (np_ + 7) // 8
            yield cid, name, np_, data[i:i + nb]
            i += nb
        else:
            raise ValueError(f"bad exec block {block:#x} at {i - 1}")


def write_exec(path, entries):
    """entries: iterable of (class_id, name, nprobes, packed_bytes)"""
    def varint(v):
        out = bytearray()
        while True:
            if v & ~0x7F:
                out.append((v & 0x7F) | 0x80); v >>= 7
            else:
                out.append(v); return bytes(out)

    def utf(s):
        b = s.encode("utf-8")
        return struct.pack(">H", len(b)) + b

    with open(path, "wb") as fh:
        fh.write(b"\x01" + struct.pack(">HH", 0xC0C0, 0x1007))
        fh.write(b"\x10" + utf("corpus") + struct.pack(">qq", 0, 0))
        for cid, name, np_, packed in entries:
            fh.write(b"\x11" + struct.pack(">q", cid) + utf(name) + varint(np_) + bytes(packed))


class Registry:
    def __init__(self, path, exclusive=False):
        self.path = path
        if exclusive:
            # offsets are assigned in memory: two campaigns writing the same registry would corrupt each other
            import fcntl
            path.parent.mkdir(parents=True, exist_ok=True)
            self.lockfh = open(str(path) + ".lock", "w")
            try:
                fcntl.flock(self.lockfh, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                sys.exit(f"another campaign is running against {path}; use a different CORPUS_WORK")
        self.slots = {}  # (cid, name) -> (byte_offset, nprobes)
        self.size = 0
        if path.exists():
            for cid, name, off, np_ in json.loads(path.read_text()):
                self.slots[(cid, name)] = (off, np_)
                self.size = max(self.size, off + (np_ + 7) // 8)
        self.lock = threading.Lock()

    def save(self):
        self.path.write_text(json.dumps([[c, n, o, p] for (c, n), (o, p) in self.slots.items()]))

    def bits(self, exec_bytes):
        entries = list(read_exec(exec_bytes))
        with self.lock:
            for cid, name, np_, _ in entries:
                if (cid, name) not in self.slots:
                    self.slots[(cid, name)] = (self.size, np_)
                    self.size += (np_ + 7) // 8
            buf = bytearray(self.size)
            for cid, name, np_, packed in entries:
                if any(packed):
                    off, _ = self.slots[(cid, name)]
                    buf[off:off + len(packed)] = packed
        return int.from_bytes(buf, "little")

    def to_exec(self, path, bits):
        raw = bits.to_bytes(self.size, "little")
        write_exec(path, ((cid, name, np_, raw[off:off + (np_ + 7) // 8])
                          for (cid, name), (off, np_) in sorted(self.slots.items(), key=lambda kv: kv[1][0])))

    def total_probes(self):
        return sum(np_ for _, np_ in self.slots.values())


class WarmupSink:
    """Accumulates the coverage of warm-up jobs of a campaign into `warmup.z`."""
    def __init__(self, d, reg):
        self.path = d / "warmup.z"
        self.reg = reg
        self.lock = threading.Lock()
        self.bits = load_bits(self.path) if self.path.exists() else 0

    def __call__(self, ex):
        if ex.exists():
            b = self.reg.bits(ex.read_bytes())
            ex.unlink()
            with self.lock:
                self.bits |= b
                save_bits(self.path, self.bits)


def save_bits(path, bits):
    path.write_bytes(zlib.compress(bits.to_bytes((bits.bit_length() + 7) // 8, "little"), 1))


def load_bits(path):
    return int.from_bytes(zlib.decompress(path.read_bytes()), "little")


def load_warmup(tc, names):
    acc = 0
    for n in names:
        p = campaign_dir(tc, n) / "warmup.z"
        if p.exists():
            acc |= load_bits(p)
    return acc


def campaign_dir(tc, name):
    return WORK / "campaigns" / tc.key / name


def load_campaign(tc, name):
    d = campaign_dir(tc, name)
    out = []
    with open(d / "results.jsonl") as fh:
        for line in fh:
            r = json.loads(line)
            if r["status"] == "OK":
                bits = load_bits(d / "bits" / (r["job"] + ".z"))
                r["bits"] = bits
                r["campaign"] = name
                out.append(r)
    return out


def case_size(case_path):
    p = Path(case_path)
    if not p.is_absolute():
        p = REPO / p
    if p.is_file():
        return p.stat().st_size
    return sum(f.stat().st_size for f in p.rglob("*") if f.is_file() and f.suffix in SOURCE_EXTS)


# ---------------------------------------------------------------------------------------------------------------------
# Commands

def cmd_measure(a):
    tc = toolchain(a.compiler)
    cases = discover(a.pool)
    if a.cases_from:
        wanted = {l.split()[0] for l in Path(a.cases_from).read_text().splitlines() if l.strip()}
        cases = [c for c in cases if c.id in wanted]
    if a.sample:
        random.Random(0).shuffle(cases)
        cases = cases[:a.sample]
    d = campaign_dir(tc, a.name)
    if a.fresh:
        shutil.rmtree(d, ignore_errors=True)
    reg = Registry(WORK / "campaigns" / tc.key / "registry.json", exclusive=True)
    (d / "bits").mkdir(parents=True, exist_ok=True)
    done = set()
    resfile = d / "results.jsonl"
    if resfile.exists() and not a.fresh:
        done = {json.loads(l)["job"] for l in resfile.read_text().splitlines()}
    out_root = WORK / "out" / a.name
    jobs = [make_job(tc, c, v, out_root) for c in cases for v in a.variants]
    jobs = [j for j in jobs if j.id not in done]
    log(f"measure {a.name}: {len(cases)} cases x {len(a.variants)} variants -> {len(jobs)} jobs to run")
    exec_dir = WORK / "exec" / a.name
    fh = open(resfile, "a")

    def on_result(r):
        rec = dict(job=r.job.id, case=r.job.case.id, variant=r.job.variant, status=r.status, ms=r.ms, detail=r.detail,
                   size=case_size(r.job.case.path), opts=r.job.case.pool_opts)
        ex = exec_dir / (r.job.id + ".exec")
        if r.status == "OK" and ex.exists():
            bits = reg.bits(ex.read_bytes())
            save_bits(d / "bits" / (r.job.id + ".z"), bits)
            rec["probes"] = bits.bit_count()
        ex.unlink(missing_ok=True)
        if r.status == "OK" and rec.get("probes") and len(reg.slots) != getattr(reg, "saved", -1):
            reg.saved = len(reg.slots)
            with reg.lock:
                reg.save()
        shutil.rmtree(r.job.out, ignore_errors=True)
        shutil.rmtree(str(r.job.out) + ".tmp", ignore_errors=True)
        fh.write(json.dumps(rec) + "\n")
        fh.flush()

    try:
        run_jobs(tc, jobs, exec_dir, workers=a.workers, chunk=a.chunk, timeout=a.timeout, on_result=on_result,
                 on_warmup=WarmupSink(d, reg))
    finally:
        fh.close()
        reg.save()
    summarize(tc, [a.name], reg)


def summarize(tc, names, reg=None):
    reg = reg or Registry(WORK / "campaigns" / tc.key / "registry.json")
    union = load_warmup(tc, names)
    stats = {"(warmup)": union}
    for n in names:
        for r in load_campaign(tc, n):
            union |= r["bits"]
            stats[r["variant"]] = stats.get(r["variant"], 0) | r["bits"]
    total = reg.total_probes()
    log(f"union over {names}: {union.bit_count()} / {total} probes ({100 * union.bit_count() / max(total, 1):.1f}%)")
    for v, b in sorted(stats.items()):
        log(f"  {v:20s} {b.bit_count():8d}  unique-to-variant {(b & ~sum_others(stats, v)).bit_count()}")


def sum_others(stats, v):
    acc = 0
    for k, b in stats.items():
        if k != v:
            acc |= b
    return acc


def cmd_select(a):
    tc = toolchain(a.compiler)
    reg = Registry(WORK / "campaigns" / tc.key / "registry.json")
    jobs = [r for n in a.campaigns for r in load_campaign(tc, n)]
    # dedupe (case, variant) across campaigns, keeping the most recent
    byk = {}
    for r in jobs:
        byk[(r["case"], r["variant"])] = r
    jobs = list(byk.values())
    warm = load_warmup(tc, a.campaigns)
    union = warm
    for r in jobs:
        union |= r["bits"]
    total = union.bit_count()
    cover = warm
    chosen = []
    forced = set()
    if a.keep:
        forced = {l.split()[0] for l in Path(a.keep).read_text().splitlines() if l.strip() and not l.startswith("#")}
    # cost model: source bytes + per-job overhead (in bytes) + compile time weight, so ties go to small, fast cases
    def cost(r):
        return r["size"] + a.job_overhead + a.ms_weight * r["ms"]

    for r in jobs:
        if r["case"] in forced and r["variant"] == "base":
            chosen.append((r, (r["bits"] & ~cover).bit_count()))
            cover |= r["bits"]
    import heapq
    heap = [(-(r["bits"] & ~cover).bit_count() / cost(r), i) for i, r in enumerate(jobs)]
    heapq.heapify(heap)
    while heap:
        negscore, i = heapq.heappop(heap)
        r = jobs[i]
        gain = (r["bits"] & ~cover).bit_count()
        score = gain / cost(r)
        if heap and score < -heap[0][0] - 1e-12:
            heapq.heappush(heap, (-score, i))
            continue
        if gain < a.min_gain:
            continue
        chosen.append((r, gain))
        cover |= r["bits"]
    lines = []
    tot_size = 0
    for r, gain in chosen:
        tot_size += r["size"]
        opts = " -- " + " ".join(r.get("opts") or []) if r.get("opts") else ""
        lines.append(f"{r['case']} {r['variant']}{opts}  # +{gain} probes, {r['size']}B, {r['ms']}ms")
    Path(a.out).write_text("\n".join(lines) + "\n")
    log(f"selected {len(chosen)} jobs / {len(jobs)}; covers {cover.bit_count()} of {total} pool probes "
        f"({100 * cover.bit_count() / max(total, 1):.2f}%), {tot_size / 1024:.0f} KiB of source; "
        f"registry has {reg.total_probes()} probes")


def materialized_name(case_id):
    # tests/pos/i1234.scala -> pos/i1234.scala ; coverage-corpus/cases/x -> x
    p = Path(case_id)
    parts = p.parts
    if parts[:2] == ("coverage-corpus", "cases"):
        return Path(*parts[2:])
    if parts[0] == "tests":
        return Path(*parts[1:])
    return p


def cmd_materialize(a):
    entries = [l.split("#")[0].split() for l in Path(a.selection).read_text().splitlines() if l.strip() and not l.startswith("#")]
    out = []
    for case_id, variant, *opts in entries:
        dest = materialized_name(case_id)
        src = REPO / case_id
        tgt = CASES / dest
        if src.resolve() != tgt.resolve():
            if tgt.exists() and not a.overwrite:
                pass
            elif src.is_dir():
                shutil.copytree(src, tgt, dirs_exist_ok=True)
            else:
                tgt.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(src, tgt)
        out.append(" ".join([str(dest), variant, *opts]))
    out = sorted(set(out))
    MANIFEST.write_text("# <case under cases/> <variant>; generated by `harness.py materialize`, then hand-edited\n" + "\n".join(out) + "\n")
    log(f"wrote {MANIFEST} ({len(out)} entries)")


def manifest_jobs(tc, out_root, extra_opts=(), order="sorted", joint=False, variants=None):
    jobs = []
    for line in MANIFEST.read_text().splitlines():
        line = line.split("#")[0].strip()
        if not line:
            continue
        rel, variant, *opts = line.split()
        if variants and variant not in variants:
            continue
        c = make_case(CASES / rel, rel, opts[1:] if opts[:1] == ["--"] else opts)
        if c is None or c.skip:
            log(f"skip {rel}: {c and c.skip}")
            continue
        jobs.append(make_job(tc, c, variant, out_root, extra_opts, order, joint))
    return jobs


def cmd_corpus_measure(a):
    """Measure the materialized corpus (manifest) itself, as a campaign named `corpus`."""
    tc = toolchain(a.compiler)
    d = campaign_dir(tc, a.name)
    shutil.rmtree(d, ignore_errors=True)
    (d / "bits").mkdir(parents=True)
    reg = Registry(WORK / "campaigns" / tc.key / "registry.json", exclusive=True)
    out_root = WORK / "out" / a.name
    jobs = manifest_jobs(tc, out_root)
    exec_dir = WORK / "exec" / a.name
    fh = open(d / "results.jsonl", "w")

    def on_result(r):
        rec = dict(job=r.job.id, case=r.job.case.id, variant=r.job.variant, status=r.status, ms=r.ms, detail=r.detail,
                   size=case_size(CASES / r.job.case.id), opts=r.job.case.pool_opts)
        ex = exec_dir / (r.job.id + ".exec")
        if r.status == "OK" and ex.exists():
            bits = reg.bits(ex.read_bytes())
            save_bits(d / "bits" / (r.job.id + ".z"), bits)
        else:
            log(f"  {r.status} {r.job.id} {r.detail}")
        ex.unlink(missing_ok=True)
        fh.write(json.dumps(rec) + "\n")
        fh.flush()

    t0 = time.time()
    run_jobs(tc, jobs, exec_dir, workers=a.workers, chunk=a.chunk, on_result=on_result, on_warmup=WarmupSink(d, reg))
    fh.close()
    reg.save()
    log(f"corpus measured in {time.time() - t0:.0f}s")
    summarize(tc, [a.name], reg)


def jacoco_line_coverage(tc, reg, bits, workdir):
    """(covered lines, total lines) of the compiler for a probe bitset, via jacococli."""
    workdir.mkdir(parents=True, exist_ok=True)
    reg.to_exec(workdir / "x.exec", bits)
    cmd = [java_bin(), "-jar", jacoco_jars()["cli"], "report", str(workdir / "x.exec"), "--csv", str(workdir / "x.csv")]
    for c in tc.compiler_classes:
        cmd += ["--classfiles", c]
    subprocess.run(cmd, check=True, capture_output=True)
    import csv as csvm
    rows = list(csvm.DictReader(open(workdir / "x.csv")))
    cov = sum(int(r["LINE_COVERED"]) for r in rows)
    return cov, cov + sum(int(r["LINE_MISSED"]) for r in rows)


def source_lines(case_id):
    p = Path(case_id)
    if not p.is_absolute():
        p = (CASES / p) if (CASES / p).exists() else (REPO / p)
    files = [p] if p.is_file() else [f for f in p.rglob("*") if f.is_file() and f.suffix in SOURCE_EXTS]
    return sum(f.read_bytes().count(b"\n") for f in files)


def cmd_curve(a):
    """Coverage vs corpus size along the greedy order of a selection: TSV + SVG plot."""
    tc = toolchain(a.compiler)
    reg = Registry(WORK / "campaigns" / tc.key / "registry.json")
    byk = {}
    for n in a.campaigns:
        for r in load_campaign(tc, n):
            byk[(r["case"], r["variant"])] = r
    warm = load_warmup(tc, a.campaigns)
    entries = [l.split("#")[0].split()[:2] for l in Path(a.selection).read_text().splitlines()
               if l.strip() and not l.startswith("#")]
    pts, acc, loc = [], warm, 0
    n = len(entries)
    marks = sorted({int(round(n * f)) for f in [x / a.points for x in range(1, a.points + 1)]} |
                   {2 ** k for k in range(20) if 2 ** k < n} | {n})
    cum = []
    for i, (case, variant) in enumerate(entries, 1):
        acc |= byk[(case, variant)]["bits"]
        loc += source_lines(case)
        if i in marks:
            cum.append((i, loc, acc))
    pool = warm
    for r in byk.values():
        pool |= r["bits"]

    def one(t):
        i, loc, bits = t
        c, tot = jacoco_line_coverage(tc, reg, bits, WORK / "curve" / str(i))
        return (i, loc, c, tot)
    with concurrent.futures.ThreadPoolExecutor(6) as ex:
        pts = list(ex.map(one, cum + [(-1, 0, pool)]))
    pool_pt = pts.pop()
    out = Path(a.out)
    with open(out.with_suffix(".tsv"), "w") as fh:
        fh.write("jobs\tsource_lines\tlines_covered\tlines_total\tpct\n")
        for i, l, c, t in pts:
            fh.write(f"{i}\t{l}\t{c}\t{t}\t{100 * c / t:.2f}\n")
    pool_pct = 100 * pool_pt[2] / pool_pt[3]
    for i, l, c, t in pts:
        print(f"{i:5d} jobs {l / 1000:8.1f} kLOC  {100 * c / t:6.2f}% lines")
    print(f"pool union: {pool_pct:.2f}% lines")
    write_svg(out.with_suffix(".svg"), [(l / 1000, 100 * c / t) for _, l, c, t in pts], pool_pct, a.title)
    log(f"wrote {out.with_suffix('.svg')}")


def write_svg(path, pts, ref, title):
    import math
    W, H, L, R, T, B = 720, 420, 60, 20, 40, 50
    xs = [max(x, 0.01) for x, _ in pts]
    x0, x1 = math.log10(min(xs)), math.log10(max(xs))
    x1 = x1 if x1 > x0 else x0 + 1
    ymin = math.floor(min(y for _, y in pts) / 10) * 10
    ymax = math.ceil(max(ref, max(y for _, y in pts)) / 10) * 10
    sx = lambda x: L + (math.log10(max(x, 0.01)) - x0) / (x1 - x0) * (W - L - R)
    sy = lambda y: T + (ymax - y) / (ymax - ymin) * (H - T - B)
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" font-family="sans-serif" font-size="12">',
         f'<rect width="{W}" height="{H}" fill="white"/>',
         f'<text x="{W / 2}" y="22" text-anchor="middle" font-size="14">{title}</text>']
    for y in range(int(ymin), int(ymax) + 1, 5):
        o.append(f'<line x1="{L}" x2="{W - R}" y1="{sy(y)}" y2="{sy(y)}" stroke="#eee"/>')
        o.append(f'<text x="{L - 6}" y="{sy(y) + 4}" text-anchor="end">{y}%</text>')
    for k in range(math.floor(x0), math.ceil(x1) + 1):
        for m in (1, 2, 5):
            v = m * 10 ** k
            if x0 <= math.log10(v) <= x1:
                o.append(f'<line x1="{sx(v)}" x2="{sx(v)}" y1="{T}" y2="{H - B}" stroke="#eee"/>')
                o.append(f'<text x="{sx(v)}" y="{H - B + 16}" text-anchor="middle">{v:g}</text>')
    o.append(f'<text x="{(L + W - R) / 2}" y="{H - 12}" text-anchor="middle">corpus size (kLOC, log scale)</text>')
    o.append(f'<text x="16" y="{(T + H - B) / 2}" transform="rotate(-90 16 {(T + H - B) / 2})" text-anchor="middle">compiler line coverage</text>')
    o.append(f'<line x1="{L}" x2="{W - R}" y1="{sy(ref)}" y2="{sy(ref)}" stroke="#c33" stroke-dasharray="5,4"/>')
    o.append(f'<text x="{W - R - 4}" y="{sy(ref) - 6}" text-anchor="end" fill="#c33">whole pool: {ref:.1f}%</text>')
    o.append('<polyline fill="none" stroke="#2563eb" stroke-width="2" points="' +
             " ".join(f"{sx(x)},{sy(y)}" for x, y in pts) + '"/>')
    for x, y in pts:
        o.append(f'<circle cx="{sx(x)}" cy="{sy(y)}" r="3" fill="#2563eb"/>')
    x, y = pts[-1]
    o.append(f'<text x="{sx(x) - 6}" y="{sy(y) + 18}" text-anchor="end" fill="#2563eb">{x:.1f} kLOC: {y:.1f}%</text>')
    o.append("</svg>")
    path.write_text("\n".join(o))


def cmd_report(a):
    tc = toolchain(a.compiler)
    reg = Registry(WORK / "campaigns" / tc.key / "registry.json")
    union = load_warmup(tc, a.campaigns)
    for n in a.campaigns:
        for r in load_campaign(tc, n):
            union |= r["bits"]
    rd = WORK / "reports" / a.out
    rd.mkdir(parents=True, exist_ok=True)
    reg.to_exec(rd / "merged.exec", union)
    cli = jacoco_jars()["cli"]
    cmd = [java_bin(), "-jar", cli, "report", str(rd / "merged.exec"), "--csv", str(rd / "report.csv"), "--xml", str(rd / "report.xml")]
    for c in tc.compiler_classes:
        cmd += ["--classfiles", c]
    if a.html:
        cmd += ["--html", str(rd / "html")]
        for s in tc.source_roots:
            cmd += ["--sourcefiles", s]
    subprocess.run(cmd, check=True, capture_output=not a.verbose)
    print_csv_summary(rd / "report.csv", a.top)
    log(f"report: {rd}")


def print_csv_summary(csv, top):
    import csv as csvm
    rows = list(csvm.DictReader(open(csv)))
    tot = {k: sum(int(r[k]) for r in rows) for k in ("LINE_MISSED", "LINE_COVERED", "BRANCH_MISSED", "BRANCH_COVERED",
                                                       "INSTRUCTION_MISSED", "INSTRUCTION_COVERED")}

    def pct(m, c):
        return 100 * c / max(m + c, 1)
    print(f"lines {tot['LINE_COVERED']}/{tot['LINE_COVERED'] + tot['LINE_MISSED']} "
          f"({pct(tot['LINE_MISSED'], tot['LINE_COVERED']):.2f}%)  "
          f"branches {pct(tot['BRANCH_MISSED'], tot['BRANCH_COVERED']):.2f}%  "
          f"instructions {pct(tot['INSTRUCTION_MISSED'], tot['INSTRUCTION_COVERED']):.2f}%")
    pk = {}
    for r in rows:
        p = pk.setdefault(r["PACKAGE"], [0, 0])
        p[0] += int(r["LINE_MISSED"]); p[1] += int(r["LINE_COVERED"])
    print("\nper package (by missed lines):")
    for name, (m, c) in sorted(pk.items(), key=lambda kv: -kv[1][0])[:top]:
        print(f"  {m:7d} missed  {pct(m, c):5.1f}%  {name}")


def cmd_gaps(a):
    """Biggest uncovered methods from a report's XML, to guide writing synthetic cases."""
    import xml.etree.ElementTree as ET
    rd = WORK / "reports" / a.report
    tree = ET.parse(rd / "report.xml")
    rows = []
    for pkg in tree.getroot().iter("package"):
        for cls in pkg.iter("class"):
            src = cls.get("sourcefilename")
            for m in cls.iter("method"):
                ctr = {c.get("type"): (int(c.get("missed")), int(c.get("covered"))) for c in m.findall("counter")}
                lm, lc = ctr.get("LINE", (0, 0))
                if lm and (a.include_partial or lc == 0):
                    rows.append((lm, lc, f"{pkg.get('name')}/{src}:{m.get('line')}", cls.get("name").split("/")[-1] + "." + m.get("name")))
    if a.filter:
        rows = [r for r in rows if re.search(a.filter, r[2] + " " + r[3])]
    for lm, lc, loc, name in sorted(rows, reverse=True)[:a.top]:
        print(f"{lm:5d} missed {lc:5d} hit  {loc:70s} {name}")


def cmd_compile(a):
    tc = toolchain(a.compiler)
    out_root = Path(a.out).resolve()
    if out_root.exists():
        shutil.rmtree(out_root)
    out_root.mkdir(parents=True)
    jobs = manifest_jobs(tc, out_root, a.extra_opts, a.order, a.joint, set(a.variants) if a.variants else None)
    t0 = time.time()
    results = run_jobs(tc, jobs, None, workers=a.workers, chunk=a.chunk, verbose=a.verbose)
    bad = [r for r in results if r.status != "OK"]
    with open(out_root / "results.txt", "w") as fh:
        for r in sorted(results, key=lambda r: r.job.id):
            fh.write(f"{r.job.id} {r.status} {r.detail}\n")
    for r in bad:
        log(f"  {r.status} {r.job.id} {r.detail}")
    log(f"compiled {len(results) - len(bad)}/{len(results)} jobs OK in {time.time() - t0:.0f}s -> {out_root}")


SERVE = WORK / "serve"


def cmd_serve(a):
    """Start a warm compiler JVM (no coverage) that `try` sends jobs to."""
    tc = toolchain(a.compiler)
    SERVE.mkdir(parents=True, exist_ok=True)
    pidf = SERVE / "pid"
    if pidf.exists():
        try:
            os.kill(int(pidf.read_text()), 0)
            log(f"already serving (pid {pidf.read_text()})"); return
        except (ProcessLookupError, ValueError):
            pass
    cp = ":".join(tc.compiler_cp + [driver_classes()])
    p = subprocess.Popen(["nice", "-n", "10", java_bin(), "-Xmx4g", "-Xss16m", "-cp", cp, "CoverageDriver", "--serve", str(SERVE)],
                         stdout=open(SERVE / "server.log", "a"), stderr=subprocess.STDOUT, start_new_session=True)
    pidf.write_text(str(p.pid))
    log(f"serving from pid {p.pid}; `harness.py try <case>`; stop with `kill {p.pid}`")


def cmd_try(a):
    """Compile some cases (paths) under variants and print compiler output, via the warm `serve` JVM if running."""
    tc = toolchain(a.compiler)
    cases = [make_case(Path(p).resolve(), p, shlex.split(a.opts)) for p in a.cases]
    out_root = WORK / "try"
    jobs = [make_job(tc, c, v, out_root) for c in cases for v in a.variants]
    pidf = SERVE / "pid"
    alive = False
    if pidf.exists():
        try:
            os.kill(int(pidf.read_text()), 0); alive = True
        except (ProcessLookupError, ValueError):
            pass
    if not alive:
        log("no warm server (`harness.py serve`); using a fresh JVM")
        for r in run_jobs(tc, jobs, None, workers=1, chunk=len(jobs), verbose=True):
            print(f"{r.status} {r.job.id} {r.detail}")
        return
    for j in jobs:
        j.prepare()
    name = f"try-{os.getpid()}-{time.time_ns()}"
    tmp = SERVE / (name + ".tmp")
    write_jobs(tmp, jobs, None)
    tmp.rename(SERVE / (name + ".job"))
    done = SERVE / (name + ".done")
    while not done.exists():
        time.sleep(0.05)
    out = (SERVE / (name + ".out")).read_text()
    print(out, end="")
    done.unlink(); (SERVE / (name + ".out")).unlink()


def cmd_diff(a):
    A, B = Path(a.a), Path(a.b)
    ra = dict(l.split(" ", 2)[:2] for l in (A / "results.txt").read_text().splitlines())
    rb = dict(l.split(" ", 2)[:2] for l in (B / "results.txt").read_text().splitlines())
    for j in sorted(set(ra) | set(rb)):
        if ra.get(j) != rb.get(j):
            print(f"STATUS {j}: {ra.get(j)} vs {rb.get(j)}")

    def files(root):
        out = {}
        for p in root.rglob("*"):
            if p.is_file() and p.suffix in (".class", ".tasty", ".sjsir") and "/classes/" in str(p):
                out[str(p.relative_to(root))] = p
        return out
    fa, fb = files(A), files(B)
    only_a = sorted(set(fa) - set(fb))
    only_b = sorted(set(fb) - set(fa))
    differ = sorted(k for k in set(fa) & set(fb) if fa[k].read_bytes() != fb[k].read_bytes())
    for k in only_a:
        print(f"ONLY-A {k}")
    for k in only_b:
        print(f"ONLY-B {k}")
    for k in differ:
        print(f"DIFFER {k}")
        if a.show and k.endswith(".class"):
            javap = str(Path(java_bin()).parent / "javap")
            da = subprocess.run([javap, "-c", "-p", "-v", str(fa[k])], capture_output=True, text=True).stdout.splitlines()[3:]
            db = subprocess.run([javap, "-c", "-p", "-v", str(fb[k])], capture_output=True, text=True).stdout.splitlines()[3:]
            import difflib
            for l in list(difflib.unified_diff(da, db, lineterm="", n=2))[:a.show]:
                print("    " + l)
    print(f"{len(fa)} vs {len(fb)} files: {len(only_a)} only in A, {len(only_b)} only in B, {len(differ)} differ")
    sys.exit(1 if (only_a or only_b or differ) else 0)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--compiler", default="local", help="`local` (this checkout, via sbt) or a released version, e.g. 3.7.3")
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common_run(p):
        p.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 4) - 1), help="worker JVMs (niced); default leaves a core free")
        p.add_argument("--chunk", type=int, default=1000, help="jobs per JVM before restarting it")

    p = sub.add_parser("measure"); common_run(p)
    p.add_argument("name")
    p.add_argument("--pool", nargs="+", required=True, help="dirs of cases (vulpix layout) or single cases")
    p.add_argument("--variants", nargs="+", default=["base"], choices=sorted(VARIANTS))
    p.add_argument("--cases-from", help="only cases listed (first column) in this file")
    p.add_argument("--sample", type=int)
    p.add_argument("--timeout", type=int, default=180)
    p.add_argument("--fresh", action="store_true")
    p.set_defaults(fn=cmd_measure)

    p = sub.add_parser("select")
    p.add_argument("campaigns", nargs="+")
    p.add_argument("--out", required=True)
    p.add_argument("--min-gain", type=int, default=5, help="stop taking jobs that add fewer probes than this")
    p.add_argument("--job-overhead", type=int, default=1500, help="cost of a job, in source-byte equivalents")
    p.add_argument("--ms-weight", type=float, default=2.0, help="cost per compile millisecond, in source-byte equivalents")
    p.add_argument("--keep", help="file listing cases always selected (with base variant)")
    p.set_defaults(fn=cmd_select)

    p = sub.add_parser("materialize")
    p.add_argument("selection")
    p.add_argument("--overwrite", action="store_true")
    p.set_defaults(fn=cmd_materialize)

    p = sub.add_parser("corpus-measure"); common_run(p)
    p.add_argument("--name", default="corpus")
    p.set_defaults(fn=cmd_corpus_measure)

    p = sub.add_parser("report")
    p.add_argument("campaigns", nargs="+")
    p.add_argument("--out", default="latest")
    p.add_argument("--html", action="store_true")
    p.add_argument("--top", type=int, default=25)
    p.add_argument("--verbose", action="store_true")
    p.set_defaults(fn=cmd_report)

    p = sub.add_parser("gaps")
    p.add_argument("--report", default="latest")
    p.add_argument("--top", type=int, default=60)
    p.add_argument("--filter")
    p.add_argument("--include-partial", action="store_true")
    p.set_defaults(fn=cmd_gaps)

    p = sub.add_parser("compile"); common_run(p)
    p.add_argument("--out", required=True)
    p.add_argument("--extra-opts", default=[], type=shlex.split, help="extra scalac options for every job")
    p.add_argument("--order", default="sorted", help="sorted | reverse | shuffle:<seed>  (source order within a batch)")
    p.add_argument("--joint", action="store_true", help="compile all stages of a case in one batch")
    p.add_argument("--variants", nargs="*", help="only manifest entries with these variants")
    p.add_argument("--verbose", action="store_true")
    p.set_defaults(fn=cmd_compile)

    p = sub.add_parser("curve")
    p.add_argument("selection")
    p.add_argument("campaigns", nargs="+")
    p.add_argument("--out", required=True, help="path stem for .tsv/.svg")
    p.add_argument("--points", type=int, default=10)
    p.add_argument("--title", default="Compiler line coverage vs corpus size (greedy order)")
    p.set_defaults(fn=cmd_curve)

    p = sub.add_parser("serve")
    p.set_defaults(fn=cmd_serve)

    p = sub.add_parser("try")
    p.add_argument("cases", nargs="+")
    p.add_argument("--variants", nargs="+", default=["base"], choices=sorted(VARIANTS))
    p.add_argument("--opts", default="", help="extra scalac options (like a pool's)")
    p.set_defaults(fn=cmd_try)

    p = sub.add_parser("diff")
    p.add_argument("a"); p.add_argument("b")
    p.add_argument("--show", type=int, default=0, help="show up to N lines of javap diff per differing classfile")
    p.set_defaults(fn=cmd_diff)

    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
