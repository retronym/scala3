#!/usr/bin/env python3
"""Score one Scala file: JaCoCo line coverage of the compiler for a plain `scalac File.scala`.

Usage: golf.py [--track standard|experimental] <file.scala> <iteration-number|baseline> <note>
Writes golf/out/<track>-<n>.json, a record for the artifact's `iterations` collection.
Tracks: `experimental` allows experimental and preview language features (this is a SNAPSHOT build, where they need no
flag); `standard` forbids them.
"""
import json
import re
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import harness as h

MAX_LINES = 256


def check(src: str, track: str):
    lines = src.rstrip("\n").split("\n")
    problems = []
    if len(lines) > MAX_LINES:
        problems.append(f"{len(lines)} lines > {MAX_LINES}")
    if ";" in src:
        problems.append("contains ';'")
    if "//>" in src:
        problems.append("contains a //> directive")
    if any(len(l) > 120 for l in lines):
        problems.append("line longer than 120 chars")
    if any("\t" in l for l in lines):
        problems.append("tab indentation")
    if track == "standard" and re.search(r"experimental|scala\.language\.preview|@preview", src):
        problems.append("experimental or preview feature in the standard track")
    return len(lines), problems


def main():
    args = sys.argv[1:]
    track = "experimental"
    if args[0] == "--track":
        track, args = args[1], args[2:]
    path, n, note = Path(args[0]).resolve(), args[1], args[2]
    src = path.read_text()
    nlines, problems = check(src, track)
    if problems:
        sys.exit("rule violations: " + ", ".join(problems))
    tc = h.toolchain("local")
    reg = h.Registry(h.WORK / "campaigns" / tc.key / "registry.json", exclusive=True)
    case = h.Case(path.name, path, [[path]])
    out_root = h.WORK / "golf"
    job = h.make_job(tc, case, "base", out_root)
    job.steps = [(k, [a for a in args if not a.startswith("-Wconf")]) for k, args in job.steps]  # plain scalac
    exec_dir = out_root / "exec"
    warm_bits = [0]

    def on_warm(ex):
        if ex.exists():
            warm_bits[0] |= reg.bits(ex.read_bytes())
    results = h.run_jobs(tc, [job], exec_dir, workers=1, chunk=1, on_warmup=on_warm)
    r = results[0]
    ex = exec_dir / (job.id + ".exec")
    bits = reg.bits(ex.read_bytes()) if ex.exists() else 0
    reg.save()
    cov, tot = h.jacoco_line_coverage(tc, reg, bits | warm_bits[0], out_root / "jacoco")
    rec = dict(n=int(n) if n.isdigit() else 0, baseline=n == "baseline", track="baseline" if n == "baseline" else track, note=note, status=r.status,
               line_pct=round(100 * cov / tot, 3), lines_covered=cov, lines_total=tot, file_lines=nlines,
               probes=(bits | warm_bits[0]).bit_count(), compile_ms=r.ms, source=src)
    (Path(__file__).parent / "out").mkdir(exist_ok=True)
    (Path(__file__).parent / "out" / f"{track}-{n}.json").write_text(json.dumps(rec))
    (out_root / f"{track}-{n}.bits.z").write_bytes(zlib.compress((bits | warm_bits[0]).to_bytes(reg.size, "little")))
    print(f"{track} #{n} {r.status} {rec['line_pct']:.2f}% ({cov}/{tot} lines), {nlines} source lines, {r.ms} ms")


if __name__ == "__main__":
    main()
