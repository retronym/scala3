"""Weight of one frame's subtree in bench-lab async profiles: inclusive share and per op, which callers it comes
from, and where inside it the time goes (the deepest frame matching a list of categories, else the leaf).

  python3 -I bench-lab/scripts/subtree.py [--event cycles] -f <regex> [-c <name>=<regex>...] [--top 12] <run>...

The categories (`-c`, first match wins) default to the denotation transformer machinery: transform bodies, goBack and
goForward.
"""
import argparse, collections, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import profdiff, regraph

CATEGORIES = [("transform bodies (forcing)", r"\.transform_|transformInfo|InfoTransformer|DenotTransformer|Completer|\.complete_"),
              ("goBack", r"SingleDenotation\.goBack"), ("goForward (machinery)", r"SingleDenotation\.goForward")]


def short(f):
    return re.sub(r"^(dotty\.tools\.dotc\.|dotty/tools/dotc/)", "", f).replace("/", ".")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--event", default="cycles")
    ap.add_argument("--jfrconv", default=regraph.DEFAULT_JFRCONV)
    ap.add_argument("-f", "--frame", required=True)
    ap.add_argument("-c", "--category", action="append", help="<name>=<regex>, instead of the default categories")
    ap.add_argument("--top", type=int, default=12)
    ap.add_argument("runs", nargs="+")
    x = ap.parse_args()
    pat = re.compile(x.frame)
    categories = [tuple(c.split("=", 1)) for c in x.category] if x.category else CATEGORIES
    tmp = os.environ.get("TMPDIR", "/tmp")
    for run in x.runs:
        r = profdiff.load(run, x.event, x.jfrconv, tmp, "st")
        incl = 0
        callers, inside = collections.Counter(), collections.Counter()
        for line in open(r["collapsed"]):
            stack, _, n = line.rstrip().rpartition(" ")
            n = int(n)
            fr = stack.split(";")
            idx = next((i for i, f in enumerate(fr) if pat.search(f)), None)
            if idx is None:
                continue
            incl += n
            callers[" < ".join(short(f) for f in reversed(fr[max(0, idx - 2):idx]))] += n
            below = fr[idx:]
            cat = next((c for c, p in categories if any(re.search(p, f) for f in below)), None)
            inside[cat or ("self (with inlined frames)" if all("_[i]" in f or f == below[0] for f in below)
                           else "other callees: " + short(below[1]).split("_[")[0])] += n
        os.unlink(r["collapsed"])
        per = lambda v: f"{100 * v / r['total']:.2f}% ({v / r['ops'] / 1e6:.0f}M/op)"
        print(f"\n### {os.path.relpath(run)}  {x.event}: {r['total'] / r['ops'] / 1e9:.2f}G/op, `{x.frame}` inclusive {per(incl)}")
        print(f"  inside: {' / '.join(c for c, _ in categories)} anywhere below, else self (only inlined frames below), else the callee:")
        for k, v in inside.most_common(x.top):
            print(f"    {per(v):>20}  {k}")
        print("  callers (2 frames):")
        for k, v in callers.most_common(x.top):
            print(f"    {per(v):>20}  {k}")


if __name__ == "__main__":
    main()
