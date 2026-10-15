"""timeline.html for one benchmark fork (an ab.py cell): its iterations and, on the same wall-clock axis, what the
machine did meanwhile; see timeline.js for the files it reads. A standalone page, with the cell's files embedded;
index.html (abindex.py) shows the same view live.

  python3 -I bench-lab/scripts/timeline.py <cell-dir> [<out.html>]
"""
import html, json, os, sys

SCRIPTS = os.path.dirname(os.path.abspath(__file__))

# shared with abindex.py's live panel
CSS = """
:root { --v0:#2f6fb2; --v1:#c2571a; --v2:#2e8a57; --v3:#8a4fbf; --v4:#b8860b; --v5:#3a9aa6; --pause:#d33; --grey:#c3c9d1; }
@media (prefers-color-scheme: dark) { :root { --v0:#6ea8e6; --v1:#e58a4e; --v2:#5cc08a; --v3:#b58ae6; --v4:#e0b84a; --v5:#6cc6d1;
  --pause:#ff6b6b; --grey:#46505c; } }
svg.timeline { background:var(--panel); border:1px solid var(--line); border-radius:8px; width:100%; touch-action:none; cursor:grab; display:block; }
svg.timeline text { fill:var(--fg); font-size:11px; } svg.timeline .lab { fill:var(--muted); } svg.timeline .grid { stroke:var(--line); }
.tl-legend span { display:inline-block; margin-right:12px; font-size:12px; }
.tl-legend i { display:inline-block; width:10px; height:10px; border-radius:2px; margin-right:4px; vertical-align:-1px; }
.tl-tip { position:fixed; pointer-events:none; background:var(--panel); border:1px solid var(--line); border-radius:6px;
  padding:4px 8px; font-size:12px; display:none; max-width:360px; z-index:5; }
"""


def script():
    return open(os.path.join(SCRIPTS, "timeline.js")).read()


def main():
    d = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(d, "timeline.html")
    read = lambda n: open(os.path.join(d, n)).read() if os.path.exists(os.path.join(d, n)) else ""
    files = {"iterations": read("iterations.tsv"), "timeline": read("timeline.tsv"), "gc": read("gc.log"), "load": read("load.tsv")}
    page = f"""<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Fork timeline</title>
<style>
:root {{ --bg:#f6f7f9; --panel:#fff; --fg:#1d232b; --muted:#5d6875; --line:#dde2e8; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#14181d; --panel:#1b2027; --fg:#e3e8ee; --muted:#94a0ad; --line:#2c333c; color-scheme:dark; }} }}
body {{ margin:0; background:var(--bg); color:var(--fg); font:13px/1.45 -apple-system,system-ui,sans-serif; }}
main {{ padding:16px; }} h1 {{ font-size:16px; margin:0 0 4px; overflow-wrap:anywhere; }} .k {{ color:var(--muted); font-size:12px; }}
{CSS}
</style>
<main><h1>{html.escape(os.path.relpath(d))}</h1>
<p class="k">Wheel or pinch to zoom, drag to pan, double-click to reset. Counter lanes cover the measurement iterations
only (xctrace records those); cycles are per second, so one busy performance core is about 4–4.5 G.</p>
<div class="tl-legend" id="legend"></div><svg class="timeline" id="chart"></svg></main><div class="tl-tip" id="tip"></div>
<script>{script()}
BenchTimeline.mount(document.getElementById('chart'), document.getElementById('legend'), document.getElementById('tip'))
  .set(BenchTimeline.parse({json.dumps(files)}));
</script>
"""
    open(out, "w").write(page)
    print(out)


if __name__ == "__main__":
    main()
