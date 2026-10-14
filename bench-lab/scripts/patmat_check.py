"""Check that space-engine variants change neither the output nor the diagnostics: compiles each corpus with the
working tree's non-bootstrapped compiler per variant (via patmat_stats.py) and compares with the first variant: the
compiler's messages exactly, TASTy files exactly, and class files exactly or else modulo fresh-name numbers (`A$69` vs
`A$35`: fewer inferences in the space engine consume fewer of a unit's fresh names).

  python3 -I bench-lab/scripts/patmat_check.py [--corpora tastyQuery,scalaz,dottyUtil,re2s] <name>=<jvm flags> ...
"""
import argparse, os, re, subprocess, sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(REPO, "out", "patmat-stats")
JAVAP = os.path.expanduser("~/.sdkman/candidates/java/17.0.20-tem/bin/javap")


def files(d):
    return {os.path.relpath(os.path.join(r, f), d) for r, _, fs in os.walk(d) for f in fs}


def javap(d, rel):
    out = subprocess.run([JAVAP, "-p", "-c", "-v", os.path.join(d, rel)], capture_output=True, text=True).stdout
    lines = [l for l in out.splitlines() if not re.search(r"Classfile|SHA-256|Last modified|Compiled from|^\s+Size", l)]
    return re.sub(r"\s+", " ", re.sub(r"\$\d+", "$N", "\n".join(lines)))


def compare(corpus, base, variant):
    a, b = (os.path.join(OUT, "classes", f"{corpus}-{t}") for t in (base, variant))
    problems, renamed = [], 0
    fa, fb = files(a), files(b)
    if fa != fb:
        problems.append(f"file sets differ: {sorted(fa ^ fb)[:5]}")
    for rel in sorted(fa & fb):
        if open(os.path.join(a, rel), "rb").read() == open(os.path.join(b, rel), "rb").read():
            continue
        if rel.endswith(".class") and javap(a, rel) == javap(b, rel):
            renamed += 1
        else:
            problems.append(f"differs: {rel}")
    la, lb = (open(os.path.join(OUT, f"{corpus}-{t}.log")).read() for t in (base, variant))
    if la != lb:
        problems.append(f"messages differ ({la.count(chr(10))} vs {lb.count(chr(10))} lines)")
    return problems, renamed, la.count("Warning")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--corpora", default="tastyQuery,scalaz,dottyUtil,re2s")
    ap.add_argument("variants", nargs="+")
    a = ap.parse_args()
    variants = dict(v.split("=", 1) for v in a.variants)
    corpora = a.corpora.split(",")
    for name, flags in variants.items():
        cmd = [sys.executable, "-I", os.path.join(REPO, "bench-lab/scripts/patmat_stats.py"), f"--tag={name}"]
        if flags:
            cmd.append(f"--extra={flags}")
        subprocess.run(cmd + corpora, check=True, stdout=subprocess.DEVNULL)
    base = list(variants)[0]
    ok = True
    for name in list(variants)[1:]:
        for c in corpora:
            problems, renamed, warnings = compare(c, base, name)
            ok &= not problems
            status = "OK" if not problems else "DIFFERS: " + "; ".join(problems[:6])
            print(f"{name:>12} {c:>11}: {status} ({renamed} class files differ only in fresh-name numbers; {warnings} warnings)")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
