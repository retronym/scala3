"""Score variants of an iteration with one top-level block blanked out (blocks referenced from @main get the main body emptied too)."""
import re, subprocess, sys, json
from pathlib import Path
src = Path(sys.argv[1]).read_text()
lines = src.split("\n")
# top-level blocks: start at a non-indented, non-blank line
starts = [i for i, l in enumerate(lines) if l and not l[0].isspace() and not l.startswith(("import", "package", ")"))]
main_i = next(i for i in starts if lines[i].startswith("@main"))
def block(i):
    k = starts.index(i)
    end = starts[k + 1] if k + 1 < len(starts) else len(lines)
    return i, end
def variant(drop):
    out = list(lines)
    ms, me = block(main_i)
    out[ms:me] = ["@main def run(): Unit = ()"]
    if drop is not None:
        s, e = block(drop)
        out[s:e] = [""] * (e - s)
    return "\n".join(out)
base_score = None
res = []
for d in [None] + [i for i in starts if i != main_i]:
    p = Path(f"/tmp/ablate_{d}.scala") if False else Path(__file__).parent / "ablate" / f"a_{d}.scala"
    p.parent.mkdir(exist_ok=True)
    p.write_text(variant(d))
    r = subprocess.run([sys.executable, str(Path(__file__).parent / "golf.py"), str(p), f"ab{d}", "ablation"], capture_output=True, text=True)
    m = re.search(r"(OK|FAIL)\s+([\d.]+)%\s+\((\d+)/", r.stdout)
    status, pct, cov = (m.group(1), float(m.group(2)), int(m.group(3))) if m else ("ERR", 0, 0)
    label = "main emptied" if d is None else lines[d][:60]
    if d is None:
        base_score = cov
    s, e = block(d) if d is not None else (0, 0)
    res.append((label, status, cov, (base_score - cov) if d is not None else 0, e - s))
    print(f"{status:4s} {cov:6d} {('-' + str(base_score - cov)) if d is not None else '':>7s} lines {e - s:3d}  {label}", flush=True)
