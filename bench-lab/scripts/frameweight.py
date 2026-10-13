"""How much of the benchmark threads' time some frames take, across bench-lab async profiles: self (the frame is the
leaf) and inclusive (the frame is anywhere on the stack, counted once per sample), as a share and per op.

  python3 -I bench-lab/scripts/frameweight.py [--event cycles] -f <regex> [-f <regex>...] <run>...

A run is a profile dir written by benchLabAsync or an ab.py cell. Frames are matched with re.search against the
frame names in collapsed stacks, e.g. `SingleDenotation\\.goBack`. self+stubs also counts itable/vtable stubs at the
leaf whose caller matches: the virtual dispatch the frame pays for.
"""
import argparse, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import profdiff, regraph


STUBS = ("itable stub", "vtable stub")


def weigh(run, event, patterns, jfrconv, tmp):
    r = profdiff.load(run, event, jfrconv, tmp, "fw")
    self_ = [0] * len(patterns)
    stubbed = [0] * len(patterns)  # self, plus itable/vtable stubs called from the frame (dispatch it pays for)
    incl = [0] * len(patterns)
    for line in open(r["collapsed"]):
        stack, _, n = line.rstrip().rpartition(" ")
        n = int(n)
        frames = stack.split(";")
        k = len(frames) - 1
        while k > 0 and frames[k] in STUBS:
            k -= 1
        for i, p in enumerate(patterns):
            if p.search(frames[-1]):
                self_[i] += n
            if p.search(frames[k]):
                stubbed[i] += n
            if any(p.search(f) for f in frames):
                incl[i] += n
    os.unlink(r["collapsed"])
    return r, self_, stubbed, incl


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--event", default="cycles")
    ap.add_argument("--jfrconv", default=regraph.DEFAULT_JFRCONV)
    ap.add_argument("-f", "--frame", action="append", required=True)
    ap.add_argument("runs", nargs="+")
    x = ap.parse_args()
    patterns = [re.compile(f) for f in x.frame]
    tmp = os.environ.get("TMPDIR", "/tmp")
    print(f"| run | ms/op | {x.event}/op | " + " | ".join(f"`{f}` self | self+stubs | incl" for f in x.frame) + " |")
    print("|---|---|---|" + "---|---|---|" * len(patterns))
    for run in x.runs:
        r, s, st, i = weigh(run, x.event, patterns, x.jfrconv, tmp)
        name = os.path.relpath(run, os.getcwd())
        pct = lambda a: f"{100 * a / r['total']:.2f}% ({a / r['ops'] / 1e6:.0f}M)"
        cells = " | ".join(f"{pct(a)} | {pct(b)} | {pct(c)}" for a, b, c in zip(s, st, i))
        print(f"| {name} | {r['score']:.0f} | {r['total'] / r['ops'] / 1e9:.2f}G | {cells} |")


if __name__ == "__main__":
    main()
