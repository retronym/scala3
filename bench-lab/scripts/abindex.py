"""out/ab/index.html: every ab.py campaign under out/ab with its cells (score, cycles, JVM flags), links to every
flame graph of each cell (made by regraph.py; ones not rendered yet are greyed out) and the differential flame graphs
(profdiff.py) in the campaign directory. Rerun it to pick up graphs rendered since.

  python3 -I bench-lab/scripts/abindex.py [<out/ab>]
"""
import glob, html, json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import regraph

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, "out", "ab")
esc = html.escape
rel = lambda f: esc(os.path.relpath(f, root))


def link(path, label):
    return f'<a href="{rel(path)}">{esc(label)}</a>' if os.path.exists(path) else f'<span class="pending">{esc(label)}</span>'


def cell_row(spec, corpus, variant, cell):
    res_f = os.path.join(cell, "result.json")
    if not os.path.exists(res_f):
        return None
    r = json.load(open(res_f))[0]
    p = r["primaryMetric"]
    m = r.get("secondaryMetrics", {})
    cyc = m.get("xctrace.CORE_ACTIVE_CYCLE", {}).get("score")
    flags = " ".join(a for a in r["jvmArgs"] if a.startswith("-Ddotty."))
    graphs = ""
    pd = os.path.join(cell, "profile-dir")
    if os.path.exists(pd):
        prof = open(pd).read().strip()
        jfr = (glob.glob(os.path.join(prof, "**", "*.jfr"), recursive=True) or [None])[0]
        if jfr:
            parts = []
            for label, _ in regraph.events_of(prof):
                ks = " · ".join(link(regraph.graph_path(jfr, label, k), k) for k in regraph.KINDS)
                parts.append(f"<b>{esc(label.removeprefix('hw-'))}</b>: {ks}")
            graphs = "<br>".join(parts)
    return (f"<tr><td>{esc(corpus)}</td><td>{esc(variant)} #{esc(os.path.basename(cell))}</td>"
            f"<td>{p['score']:.0f} ± {p['scoreError']:.0f}</td><td>{f'{cyc / 1e9:.2f}G' if cyc else ''}</td>"
            f"<td><code>{esc(flags) or '(none)'}</code></td><td>{graphs}</td></tr>")


def md(text):
    """Just enough Markdown for notes: paragraphs, `- ` bullets, **bold**, `code` and [text](url) links."""
    import re as _re
    def inline(t):
        t = esc(t)
        t = _re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r'<a href="\2">\1</a>', t)
        t = _re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", t)
        return _re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    out, items = [], []
    for block in text.strip().split("\n\n"):
        lines = block.splitlines()
        if lines[0].startswith("### "):
            out.append(f"<h3>{inline(lines[0][4:])}</h3>")
            lines = lines[1:]
        if lines and all(l.lstrip().startswith("- ") for l in lines):
            out.append("<ul>" + "".join(f"<li>{inline(l.lstrip()[2:])}</li>" for l in lines) + "</ul>")
        elif lines:
            out.append(f"<p>{inline(' '.join(lines))}</p>")
    return "".join(out)


def notes(path):
    return f'<div class="notes">{md(open(path).read())}</div>' if os.path.exists(path) else ""


sections = []
for spec_dir in sorted(glob.glob(os.path.join(root, "*/")), key=os.path.getmtime, reverse=True):
    name = os.path.basename(spec_dir.rstrip("/"))
    rows = []
    for corpus_dir in sorted(glob.glob(os.path.join(spec_dir, "*/"))):
        corpus = os.path.basename(corpus_dir.rstrip("/"))
        if corpus.startswith("diff-"):
            continue
        for variant_dir in sorted(glob.glob(os.path.join(corpus_dir, "*/"))):
            variant = os.path.basename(variant_dir.rstrip("/"))
            for cell in sorted(glob.glob(os.path.join(variant_dir, "*"))):
                if os.path.isdir(cell) and os.path.basename(cell).isdigit():
                    row = cell_row(name, corpus, variant, cell)
                    if row:
                        rows.append(row)
    diffs = sorted(glob.glob(os.path.join(spec_dir, "diff-*", "*.html")))
    if not rows and not diffs:
        continue
    diff_html = ("<p>Differential flame graphs (a → b): " + " · ".join(link(d, os.path.basename(os.path.dirname(d)).removeprefix("diff-"))
                                                               for d in diffs) + "</p>") if diffs else ""
    sections.append(f"""<section><h2>{esc(name)}</h2>{notes(os.path.join(spec_dir, "NOTES.md"))}{diff_html}
<table><tr><th>corpus</th><th>variant</th><th>ms/op</th><th>cycles/op (xctrace)</th><th>flags</th><th>flame graphs</th></tr>{''.join(rows)}</table></section>""")

extra = [p for p in (os.path.join(REPO, "out", "bench-lab", "uniques-corpora.html"), os.path.join(REPO, "out", "bench-lab", "index.html")) if os.path.exists(p)]
page = f"""<!doctype html><meta charset="utf-8"><title>bench-lab campaigns</title>
<style>:root{{--bg:#f6f7f9;--fg:#1d232b;--muted:#5d6875;--line:#dde2e8;--link:#2f6fb2;--panel:#fff}}
@media (prefers-color-scheme: dark){{:root{{--bg:#14181d;--fg:#e3e8ee;--muted:#94a0ad;--line:#2c333c;--link:#6ea8e6;--panel:#1b2027;color-scheme:dark}}}}
body{{background:var(--bg);color:var(--fg);font:13px/1.5 -apple-system,system-ui,sans-serif;margin:20px}} a{{color:var(--link)}}
section{{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:12px 16px;margin:0 0 16px}}
table{{border-collapse:collapse}} td,th{{border:1px solid var(--line);padding:4px 8px;text-align:left;vertical-align:top}}
.pending{{color:var(--muted)}} code{{font-size:11px}}
.notes{{border-left:3px solid var(--link);padding:2px 12px;margin:8px 0 12px}} .notes h3{{margin:6px 0}}</style>
<h1>bench-lab campaigns</h1>
<p>Newest first. bench: benchmark threads · reverse: by leaf · threads: all threads · classify: all threads by category ·
bench-/reverse-classify: benchmark threads with --classify. Grey links are not rendered yet; reload after
<code>regraph.py</code> finishes. Also: {" · ".join(link(p, os.path.relpath(p, os.path.join(REPO, "out"))) for p in extra)}</p>
{notes(os.path.join(root, "FINDINGS.md"))}
{''.join(sections)}"""
out = os.path.join(root, "index.html")
open(out, "w").write(page)
print(out)
