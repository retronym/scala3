"""Builds the raw-data part of the bench-lab site: one directory per run under <site>/data with its JMH results and log,
JFR recordings and gzipped collapsed stacks (benchmark threads; stacks through Positioned), xctrace attribution and flame
graphs, plus <site>/runs.html linking all of it.

  python3 -I bench-lab/scripts/site.py <site-dir> [jfrconv] <run-root>...

A run root holds profile directories (cpu-counters, ipc, async-cycles, ...), e.g. out/bench-lab or local/results/dotc.
"""
import glob, gzip, html, json, os, shutil, subprocess, sys

site, jfrconv, roots = sys.argv[1], sys.argv[2], sys.argv[3:]
data = os.path.join(site, "data")
os.makedirs(data, exist_ok=True)
FILTERS = {"bench": ["--threads", "-I", ".*jmh-worker.*"], "positioned": ["-I", ".*dotty/tools/dotc/ast/Positioned.*"]}

rows = []
for root in roots:
    for prof in sorted(glob.glob(os.path.join(root, "*"))):
        if not os.path.isdir(prof):
            continue
        name = (os.path.basename(root.rstrip("/")) + "-" if "results" in root else "") + os.path.basename(prof)
        dest = os.path.join(data, name)
        shutil.rmtree(dest, ignore_errors=True)
        os.makedirs(dest)
        files = []
        for pattern in ("result.json", "jmh.log", "**/*.jfr", "**/*.html", "*.methods.txt"):
            for f in glob.glob(os.path.join(prof, pattern), recursive=True):
                # flatten JMH's per-benchmark directory into the file name: <params>--<file>
                rel = os.path.relpath(f, prof)
                flat = rel.replace("dotty.tools.benchlab.", "").replace(os.sep, "--")
                shutil.copy(f, os.path.join(dest, flat))
                files.append(flat)
                if f.endswith(".jfr"):
                    for label, args in FILTERS.items():
                        out = os.path.join(dest, flat[:-4] + f"-{label}.collapsed")
                        kind = "--cpu"
                        subprocess.run([jfrconv, kind, *args, "-o", "collapsed", f, out], capture_output=True)
                        if os.path.exists(out) and os.path.getsize(out):
                            # deep stacks of long Scala names: hundreds of MB raw, a few percent of that gzipped
                            with open(out, "rb") as src, gzip.open(out + ".gz", "wb", compresslevel=6) as gz:
                                shutil.copyfileobj(src, gz)
                            files.append(os.path.basename(out) + ".gz")
                        if os.path.exists(out):
                            os.remove(out)
        summary = ""
        try:
            for r in json.load(open(os.path.join(prof, "result.json"))):
                p = ", ".join(f"{k}={v}" for k, v in r["params"].items() if v)
                summary += f"{r['benchmark'].rsplit('.', 2)[-2]} ({p}): {r['primaryMetric']['score']:.1f} ± {r['primaryMetric']['scoreError']:.1f} ms/op; "
        except (OSError, ValueError, KeyError, TypeError):
            pass
        rows.append((name, summary.rstrip("; "), sorted(files)))

def size(path):
    n = os.path.getsize(path)
    return f"{n / 1e6:.1f} MB" if n > 1e6 else f"{n / 1e3:.0f} KB"

with open(os.path.join(site, "runs.html"), "w") as out:
    out.write("""<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>bench-lab runs</title>
<style>
:root { --bg: #f6f7f9; --panel: #fff; --fg: #1d232b; --muted: #5d6875; --line: #dde2e8; --link: #2f6fb2; }
@media (prefers-color-scheme: dark) { :root { --bg: #14181d; --panel: #1b2027; --fg: #e3e8ee; --muted: #94a0ad; --line: #2c333c; --link: #6ea8e6; color-scheme: dark; } }
body { margin: 0; background: var(--bg); color: var(--fg); font: 14px/1.45 -apple-system, system-ui, sans-serif; }
.wrap { max-width: 1200px; margin: 0 auto; padding: 20px 16px 40px; display: grid; gap: 14px; }
h1 { font-size: 22px; margin: 0; } a { color: var(--link); }
.run { background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 14px 16px; }
.run h2 { font-size: 15px; margin: 0 0 4px; } .sum { color: var(--muted); font-size: 12px; margin: 0 0 8px; }
ul { margin: 0; padding-left: 18px; columns: 2 26rem; font-size: 12px; } li { break-inside: avoid; overflow-wrap: anywhere; }
.k { color: var(--muted); }
</style>
<div class="wrap"><h1>bench-lab runs</h1>
<p>Raw data per run: JMH results and log, JFR recordings (async-profiler; the kperf ones carry PMU events), collapsed
stacks (<span class="k">-bench</span>: benchmark threads; <span class="k">-positioned</span>: stacks through
Positioned), xctrace per-method attribution and flame graphs. <a href="live.html">Live dashboard</a>.</p>
""")
    for name, summary, files in rows:
        out.write(f'<section class="run" id="{html.escape(name)}"><h2>{html.escape(name)}</h2>')
        if summary:
            out.write(f'<p class="sum">{html.escape(summary)}</p>')
        out.write("<ul>")
        for f in files:
            out.write(f'<li><a href="data/{html.escape(name)}/{html.escape(f)}">{html.escape(f)}</a> '
                      f'<span class="k">{size(os.path.join(data, name, f))}</span></li>')
        out.write("</ul></section>\n")
    out.write("</div>\n")
print(f"{len(rows)} runs -> {site}/runs.html")
