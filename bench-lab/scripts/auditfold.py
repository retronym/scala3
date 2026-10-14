"""Merges and folds caller-audit reports (written by the audit agent, see doc/caller-audit.md).

  python3 -I bench-lab/scripts/auditfold.py [--by site|method|outside=<package>] [--probe <name>] [--top 30] <report.json>...

Reports from several runs or corpora are summed (a column per report, and a total). Sites are folded:

- `site` (default): as recorded, the chain of frames.
- `method`: each frame's line number dropped, so a site is a chain of methods.
- `outside=<package>`: only the first frame not in `<package>` (e.g. `outside=dotty.tools.dotc.ast` for the first
  caller outside the tree code).

The creation site in a `[from ...]` qualifier (a tag) is folded the same way.

Counters are printed first, with each `.<label>` split as a share of its name.
"""
import argparse, collections, json, os, re

ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
ap.add_argument("--by", default="site")
ap.add_argument("--probe", action="append", help="only these probes (default: all)")
ap.add_argument("--top", type=int, default=30)
ap.add_argument("reports", nargs="+")
x = ap.parse_args()
reports = [(os.path.basename(os.path.dirname(os.path.abspath(r))) + "/" + os.path.basename(r), json.load(open(r))) for r in x.reports]
names = [n for n, _ in reports]


def fold_chain(chain):
    frames = [f for f in chain.split(" <- ") if f]
    if x.by == "method":
        frames = [re.sub(r":-?\d+$", "", f) for f in frames]
    elif x.by.startswith("outside="):
        pkg = x.by.split("=", 1)[1] + "."
        frames = [next((re.sub(r":-?\d+$", "", f) for f in frames if not f.startswith(pkg)), "(all inside)")] if frames else []
    return " <- ".join(frames)


def fold(site):
    """A site, and the creation site in its `[from ...]` qualifier, folded alike."""
    m = re.match(r"^(.*?)(  \[(.*)\])?$", site)
    chain, qual = m.group(1), m.group(3)
    if qual:
        qual = re.sub(r"from (.*?)(, [^,<]*)?$", lambda q: "from " + fold_chain(q.group(1)) + (q.group(2) or ""), qual)
    return fold_chain(chain) + (f"  [{qual}]" if qual else "")


def table(rows, total_of=None):
    w = len(names)
    hdr = "| " + " | ".join(names) + (" | total" if w > 1 else "") + " | share | |"
    print(hdr + "\n|" + "---|" * (w + (1 if w > 1 else 0) + 2))
    grand = total_of or sum(sum(v) for v in rows.values()) or 1
    for k, v in sorted(rows.items(), key=lambda kv: -sum(kv[1]))[:x.top]:
        cells = [str(n) for n in v] + ([str(sum(v))] if w > 1 else [])
        print("| " + " | ".join(cells) + f" | {100 * sum(v) / grand:.1f}% | `{k}` |")
    if len(rows) > x.top:
        print(f"| … {len(rows) - x.top} more | " + " |" * (w + (1 if w > 1 else 0) + 1))


counters = collections.defaultdict(lambda: [0] * len(reports))
for i, (_, r) in enumerate(reports):
    for k, v in r.get("counters", {}).items():
        counters[k][i] += v
if counters:
    print("### counters\n")
    groups = collections.defaultdict(int)
    for k, v in counters.items():
        groups[k.rsplit(".", 1)[0]] += sum(v)
    print("| " + " | ".join(names) + " | of its name | counter |\n|" + "---|" * (len(names) + 2))
    for k, v in sorted(counters.items()):
        print("| " + " | ".join(map(str, v)) + f" | {100 * sum(v) / (groups[k.rsplit('.', 1)[0]] or 1):.1f}% | `{k}` |")

probes = [p for p in dict.fromkeys(p for _, r in reports for p in r.get("probes", {})) if not x.probe or p in x.probe]
for p in probes:
    rows = collections.defaultdict(lambda: [0] * len(reports))
    hits, samples = 0, set()
    for i, (_, r) in enumerate(reports):
        pr = r["probes"].get(p)
        if not pr:
            continue
        hits += pr["hits"]
        samples.add(pr.get("sample", 1))
        for site, n in pr["sites"].items():
            rows[fold(site)][i] += n
    if not rows:
        continue
    sample = f", sites sampled 1 in {max(samples)}" if max(samples) > 1 else ""
    print(f"\n### {p}: {hits} hits{sample}, {len(rows)} distinct by {x.by}\n")
    table(rows)
