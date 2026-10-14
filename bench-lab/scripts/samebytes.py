"""Compares the output of two builds, as written by OutputDigest (`<dir>/<corpus>/...`), modulo the build's git hash.

  python3 -I bench-lab/scripts/samebytes.py <dirA> <dirB> <corpus>...

A snapshot compiler's TASTy header names its version, `Scala 3.x.y-bin-SNAPSHOT-git-<hash>`, so two builds of different
commits never produce identical TASTy, nor identical class files for pickled quotes (TASTy, base64, in the constant
pool). Both are normalised to `git-0000000` before comparing; any other difference is reported. Exit status 1 if any.
"""
import base64, os, re, sys

GIT = re.compile(rb"(Scala 3\.[0-9A-Za-z.\-]*-git-)[0-9a-f]{7}")
# base64 of a TASTy header: the magic 5CA1AB1F encodes as XKGrH
B64 = re.compile(rb"XKGrH[A-Za-z0-9+/]{75}")


def normalise(data):
    data = GIT.sub(rb"\g<1>0000000", data)

    def b64(m):
        raw = base64.b64decode(m.group(0))
        return base64.b64encode(GIT.sub(rb"\g<1>0000000", raw))

    return B64.sub(b64, data)


def files(root):
    out = set()
    for d, _, fs in os.walk(root):
        for f in fs:
            out.add(os.path.relpath(os.path.join(d, f), root))
    return out


def compare(a, b):
    fa, fb = files(a), files(b)
    report = {"identical": 0, "git hash only": 0}
    problems = [f"only in A: {p}" for p in sorted(fa - fb)] + [f"only in B: {p}" for p in sorted(fb - fa)]
    for p in sorted(fa & fb):
        x, y = open(os.path.join(a, p), "rb").read(), open(os.path.join(b, p), "rb").read()
        if x == y:
            report["identical"] += 1
        elif normalise(x) == normalise(y):
            report["git hash only"] += 1
        else:
            problems.append(f"differs: {p}")
    return report, problems


def main():
    a, b, corpora = sys.argv[1], sys.argv[2], sys.argv[3:]
    bad = False
    for c in corpora:
        report, problems = compare(os.path.join(a, c), os.path.join(b, c))
        summary = ", ".join(f"{n} {k}" for k, n in report.items())
        print(f"{c}: {summary}, {len(problems)} different")
        for p in problems[:20]:
            print(f"  {p}")
        bad |= bool(problems)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
