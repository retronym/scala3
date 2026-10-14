"""Inclusive share of frames in the benchmark threads of async-profiler JFR recordings: for each regex, the share of
samples whose stack contains a matching frame, overall and inside an optional root frame.

  python3 -I bench-lab/scripts/frameshare.py [--event cpu] [--root <regex>] -f <regex> [-f <regex>...] <jfr>...

`--root` (e.g. `SpaceEngine\\$\\.checkMatch`) also reports each share relative to the samples under the root, so a
microbenchmark and a full compilation can be compared frame by frame. Frames are matched against
`<class>.<method>` as jfrconv writes them.
"""
import argparse, os, re, subprocess, sys, tempfile

DEFAULT_JFRCONV = "jfrconv"


def collapsed(jfr, event, jfrconv):
    out = os.path.join(tempfile.mkdtemp(prefix="frameshare-"), "stacks.collapsed")
    kind = ["--hw", event] if event not in ("cpu", "wall", "alloc", "lock") else [f"--{event}"]
    subprocess.run([jfrconv, "-J-Xmx8g", *kind, "--threads", "-I", ".*jmh-worker.*", "-o", "collapsed", jfr, out],
                   check=True, capture_output=True)
    for line in open(out):
        stack, _, n = line.rstrip().rpartition(" ")
        yield stack.split(";"), int(n)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--event", default="cpu")
    ap.add_argument("--jfrconv", default=DEFAULT_JFRCONV)
    ap.add_argument("--root")
    ap.add_argument("-f", dest="frames", action="append", required=True)
    ap.add_argument("jfrs", nargs="+")
    a = ap.parse_args()
    pats = [re.compile(f) for f in a.frames]
    root = re.compile(a.root) if a.root else None
    for jfr in a.jfrs:
        total = under_root = 0
        hits = [0] * len(pats)
        hits_root = [0] * len(pats)
        for frames, n in collapsed(jfr, a.event, a.jfrconv):
            total += n
            in_root = root is None or any(root.search(f) for f in frames)
            if in_root:
                under_root += n
            for i, p in enumerate(pats):
                if any(p.search(f) for f in frames):
                    hits[i] += n
                    if in_root:
                        hits_root[i] += n
        print(f"\n{jfr}: {total} samples" + (f", {100 * under_root / total:.1f}% under {a.root}" if root else ""))
        for i, f in enumerate(a.frames):
            rel = f"  {100 * hits_root[i] / under_root:6.1f}% of root" if root and under_root else ""
            print(f"  {100 * hits[i] / total:6.1f}%{rel}  {f}")


if __name__ == "__main__":
    main()
