"""Rank single-file pool cases (base variant, no pool options) by probes they add over a golf iteration, per source line."""
import sys, zlib
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import harness as h
tc = h.toolchain("local")
reg = h.Registry(h.WORK / "campaigns" / tc.key / "registry.json")
# argv[1]: a scored iteration, e.g. standard-2 (see golf.py)
g = int.from_bytes(zlib.decompress((h.WORK / "golf" / f"{sys.argv[1]}.bits.z").read_bytes()), "little")
rows = []
for camp in ["vulpix", "syn2"]:
    for r in h.load_campaign(tc, camp):
        allowed = set() if "--standard" in sys.argv else {"-language:experimental.captureChecking", "-language:experimental.separationChecking"}
        if r["variant"] != "base" or not set(r.get("opts") or []) <= allowed:
            continue
        p = h.REPO / r["case"]
        if not p.is_file():
            continue
        txt = p.read_text(errors="replace")
        if "//> using" in txt or ("--standard" in sys.argv and "experimental" in txt):
            continue
        lines = max(1, txt.count("\n"))
        gain = (r["bits"] & ~g).bit_count()
        rows.append((gain / lines, gain, lines, r["case"]))
rows.sort(reverse=True)
for d, gain, lines, c in rows[:int(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2].isdigit() else 40]:
    print(f"{d:7.1f}/line {gain:6d} probes {lines:4d} lines  {c}")
