"""Annotated-source coverage report with per-line attribution to corpus jobs.

For each executable compiler source line: which jobs of a campaign cover it (exactly, via a probe -> lines map built
once per toolchain with JaCoCo's own analysis, see driver/ProbeLines.java), and whether any job of a wider set of
campaigns does. Output is static HTML under .work/reports/<out>/.
"""

import base64
import collections
import html
import json
import os
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

import harness as h


def probe_lines(tc):
    """{(vmName, probe): (sourcePath, [lines])}, building the map if needed."""
    d = h.WORK / "probelines" / tc.key
    out = d / "probelines.tsv"
    reg_path = h.WORK / "campaigns" / tc.key / "registry.json"
    if not out.exists() or out.stat().st_mtime < reg_path.stat().st_mtime:
        d.mkdir(parents=True, exist_ok=True)
        cp = h.coursier_fetch("org.jacoco:org.jacoco.core:" + h.JACOCO_VERSION)
        javac = str(Path(h.java_bin()).parent / "javac")
        subprocess.run([javac, "--release", "17", "-cp", cp, "-d", str(d), str(h.HERE / "driver" / "ProbeLines.java")], check=True)
        reg = json.loads(reg_path.read_text())
        with open(d / "classes.tsv", "w") as fh:
            for cid, name, off, n in reg:
                fh.write(f"{cid}\t{name}\t{n}\n")
        subprocess.run([h.java_bin(), "-Xmx8g", "-cp", cp + ":" + str(d), "ProbeLines", str(d / "classes.tsv"), str(out),
                        *tc.compiler_classes], check=True)
    m = {}
    for line in open(out):
        name, src, p, lines = line.rstrip("\n").split("\t")
        pkg = name.rsplit("/", 1)[0] if "/" in name else ""
        m[(name, int(p))] = (f"{pkg}/{src}" if pkg else src, [int(x) for x in lines.split(",")])
    return m


def bits_index(reg):
    """(vmName, probe) -> global bit index, matching Registry.bits."""
    return {(name, p): off * 8 + p for (cid, name), (off, n) in reg.slots.items() for p in range(n)}


def run(a):
    tc = h.toolchain(a.compiler)
    reg = h.Registry(h.WORK / "campaigns" / tc.key / "registry.json")
    pl = probe_lines(tc)
    gidx = bits_index(reg)

    jobs = h.load_campaign(tc, a.campaign)
    jobs.sort(key=lambda r: r["job"])
    warm = h.load_warmup(tc, [a.campaign])
    pool = warm
    for n in a.pool:
        pool |= h.load_warmup(tc, [n])
        for r in h.load_campaign(tc, n):
            pool |= r["bits"]
    for r in jobs:
        pool |= r["bits"]

    # line -> int bitmask over jobs (bit i = jobs[i]); warm-up coverage tracked separately
    line_jobs = collections.defaultdict(int)
    line_warm = set()
    line_pool = set()
    probe_jobs = collections.defaultdict(int)
    for i, r in enumerate(jobs):
        b = r["bits"]
        while b:
            low = b & -b
            probe_jobs[low.bit_length() - 1] |= 1 << i
            b ^= low
    for key, (src, lines) in pl.items():
        g = gidx.get(key)
        if g is None:
            continue
        jm = probe_jobs.get(g, 0)
        w = (warm >> g) & 1
        pb = (pool >> g) & 1
        for l in lines:
            if jm:
                line_jobs[(src, l)] |= jm
            if w:
                line_warm.add((src, l))
            if pb:
                line_pool.add((src, l))

    # executable lines and JaCoCo's own verdict for the same campaign, for validation
    rep = h.WORK / "reports" / a.campaign / "report.xml"
    if not rep.exists() or rep.stat().st_mtime < (h.campaign_dir(tc, a.campaign) / "results.jsonl").stat().st_mtime:
        raise SystemExit(f"run `harness.py report {a.campaign} --out {a.campaign}` first")
    files = {}
    jacoco_cov = 0
    for pkg in ET.parse(rep).getroot().iter("package"):
        for sf in pkg.findall("sourcefile"):
            src = f"{pkg.get('name')}/{sf.get('name')}"
            lines = {}
            for ln in sf.findall("line"):
                nr = int(ln.get("nr"))
                ci = int(ln.get("ci"))
                lines[nr] = ci > 0
                jacoco_cov += ci > 0
            files[src] = lines
    ours = {(s, l) for (s, l) in set(line_jobs) | line_warm if l in files.get(s, {})}
    mismatch = sum(1 for s, ls in files.items() for l, c in ls.items() if c != ((s, l) in ours))
    h.log(f"lines covered: JaCoCo {jacoco_cov}, attribution {len(ours)}, disagreements {mismatch}")

    # source text
    index = {}
    for root in tc.source_roots:
        for p in Path(root).rglob("*"):
            if p.suffix in (".scala", ".java"):
                index.setdefault(p.name, []).append(p)

    def find_source(src):
        for root in tc.source_roots:
            p = Path(root) / src
            if p.exists():
                return p
        cands = index.get(src.rsplit("/", 1)[-1], [])
        return cands[0] if len(cands) == 1 else None

    out = h.WORK / "reports" / a.out
    (out / "src").mkdir(parents=True, exist_ok=True)
    (out / "job").mkdir(parents=True, exist_ok=True)
    nbytes = (len(jobs) + 7) // 8
    job_names = [f"{r['case']} @{r['variant']}" for r in jobs]
    (out / "jobs.js").write_text("window.JOBS = " + json.dumps(job_names) + ";\n")
    (out / "style.css").write_text(CSS)

    unique = collections.defaultdict(list)  # job index -> [(src, line)]
    covered_by = collections.Counter()
    file_rows = []
    for src in sorted(files):
        lines = files[src]
        exe = len(lines)
        cov = sum(1 for l in lines if (src, l) in ours)
        poolonly = sum(1 for l in lines if (src, l) not in ours and (src, l) in line_pool)
        file_rows.append((src, exe, cov, poolonly))
        for l in lines:
            jm = line_jobs.get((src, l), 0)
            if jm:
                c = jm.bit_count()
                if c == 1 and (src, l) not in line_warm:
                    unique[jm.bit_length() - 1].append((src, l))
                for i in iter_bits(jm):
                    covered_by[i] += 1
        path = find_source(src)
        text = path.read_text(errors="replace").splitlines() if path else []
        data = {}
        for l in lines:
            jm = line_jobs.get((src, l), 0)
            data[l] = [base64.b64encode(jm.to_bytes(nbytes, "little")).decode() if jm else "", (src, l) in line_warm]
        rows = []
        for n, t in enumerate(text, 1):
            if n in lines:
                if (src, n) in ours:
                    jm = line_jobs.get((src, n), 0)
                    c = jm.bit_count()
                    cls = "u" if c == 1 and (src, n) not in line_warm else "c"
                    badge = str(c) if c else "w"
                elif (src, n) in line_pool:
                    cls, badge = "p", ""
                else:
                    cls, badge = "m", ""
            else:
                cls, badge = "", ""
            rows.append(f'<tr class="{cls}" data-l="{n}"><td class="n">{n}</td><td class="b">{badge}</td><td class="s">{html.escape(t) or " "}</td></tr>')
        depth = src.count("/") + 1
        up = "../" * depth
        page = PAGE.format(title=html.escape(src), up=up, css=up + "style.css", jobs=up + "jobs.js",
                           head=f'<a href="{up}index.html">index</a> / {html.escape(src)} &nbsp; {cov}/{exe} lines'
                                f' ({pct(cov, exe)}) covered by the corpus, {poolonly} more by the wider pool'
                                + ("" if path else " &nbsp; <b>source not found</b>"),
                           body="<table class=\"code\">" + "\n".join(rows) + "</table>",
                           data=json.dumps(data), nbytes=nbytes)
        p = out / "src" / (src + ".html")
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(page)

    # job pages: lines only this job covers
    job_rows = []
    for i, r in enumerate(jobs):
        u = unique.get(i, [])
        job_rows.append((i, job_names[i], covered_by[i], len(u)))
        items = []
        by_file = collections.defaultdict(list)
        for s, l in u:
            by_file[s].append(l)
        for s in sorted(by_file):
            ls = sorted(by_file[s])
            links = ", ".join(f'<a href="../src/{html.escape(s)}.html#L{l}">{l}</a>' for l in ls)
            items.append(f"<tr><td>{html.escape(s)}</td><td class=\"n\">{len(ls)}</td><td>{links}</td></tr>")
        case_dir = h.CASES / r["case"]
        srcs = [case_dir] if case_dir.is_file() else sorted(p for p in case_dir.rglob("*") if p.suffix in h.SOURCE_EXTS) if case_dir.exists() else []
        code = "".join(f"<h3>{html.escape(str(p.relative_to(h.CASES)))}</h3><pre>{html.escape(p.read_text(errors='replace'))}</pre>" for p in srcs)
        (out / "job" / f"{i}.html").write_text(PAGE.format(
            title=html.escape(job_names[i]), up="../", css="../style.css", jobs="../jobs.js",
            head=f'<a href="../index.html">index</a> / {html.escape(job_names[i])} &nbsp; covers {covered_by[i]} lines, {len(u)} of them covered by no other job',
            body=(f"<h2>Lines only this job covers</h2><table class=\"list\"><tr><th>File</th><th>Lines</th><th></th></tr>{''.join(items)}</table>" if items else "<p>No line is covered by this job alone.</p>")
                 + "<h2>Case source</h2>" + code,
            data="{}", nbytes=nbytes))

    tot_exe = sum(r[1] for r in file_rows)
    tot_cov = sum(r[2] for r in file_rows)
    tot_pool = sum(r[3] for r in file_rows)
    pk = collections.defaultdict(lambda: [0, 0, 0])
    for s, e, c, p in file_rows:
        k = s.rsplit("/", 1)[0]
        pk[k][0] += e; pk[k][1] += c; pk[k][2] += p
    frows = "".join(
        f'<tr><td><a href="src/{html.escape(s)}.html">{html.escape(s)}</a></td><td class="n">{e}</td><td class="n">{c}</td>'
        f'<td class="n">{pct(c, e)}</td><td class="n">{p}</td><td>{bar(c, p, e)}</td></tr>'
        for s, e, c, p in sorted(file_rows, key=lambda r: -(r[1] - r[2])))
    prows = "".join(
        f'<tr><td>{html.escape(k)}</td><td class="n">{e}</td><td class="n">{c}</td><td class="n">{pct(c, e)}</td><td class="n">{p}</td><td>{bar(c, p, e)}</td></tr>'
        for k, (e, c, p) in sorted(pk.items(), key=lambda kv: -(kv[1][0] - kv[1][1])))
    jrows = "".join(
        f'<tr><td><a href="job/{i}.html">{html.escape(n)}</a></td><td class="n">{c}</td><td class="n">{u}</td></tr>'
        for i, n, c, u in sorted(job_rows, key=lambda r: -r[3]))
    body = f"""
<p>{tot_cov} of {tot_exe} executable compiler lines ({pct(tot_cov, tot_exe)}) are covered by the {len(jobs)} jobs of campaign
<code>{html.escape(a.campaign)}</code>; {tot_pool} more are covered by the wider pool ({html.escape(', '.join(a.pool) or 'none')}).
JaCoCo's own report agrees on every line but {mismatch}.</p>
<p class="legend"><span class="k c">covered</span> <span class="k u">covered by one job only</span>
<span class="k p">pool only</span> <span class="k m">not covered</span>. In a source view, the number is how many jobs cover the line
(<i>w</i>: only the JVM warm-up compile); click a line to list them.</p>
<h2>Packages</h2><table class="list"><tr><th>Package</th><th>Lines</th><th>Corpus</th><th></th><th>Pool only</th><th></th></tr>{prows}</table>
<h2>Corpus jobs, by lines no other job covers</h2><table class="list"><tr><th>Job</th><th>Lines covered</th><th>Only this job</th></tr>{jrows}</table>
<h2>Source files, by uncovered lines</h2><table class="list"><tr><th>File</th><th>Lines</th><th>Corpus</th><th></th><th>Pool only</th><th></th></tr>{frows}</table>
"""
    (out / "index.html").write_text(PAGE.format(title="Compiler coverage attribution", up="", css="style.css", jobs="jobs.js",
                                                head="Compiler coverage attribution", body=body, data="{}", nbytes=nbytes))
    h.log(f"report: {out / 'index.html'}")


def iter_bits(x):
    while x:
        low = x & -x
        yield low.bit_length() - 1
        x ^= low


def pct(c, e):
    return f"{100 * c / e:.1f}%" if e else "–"


def bar(c, p, e):
    if not e:
        return ""
    return (f'<span class="bar"><span class="bc" style="width:{100 * c / e:.1f}%"></span>'
            f'<span class="bp" style="width:{100 * p / e:.1f}%"></span></span>')


CSS = """
:root { --bg:#fbfbfa; --fg:#1d2124; --muted:#6b7177; --line:#e3e5e7; --cov:#dff3e4; --uniq:#b9e6c6; --pool:#fdf1cf; --miss:#fbe0e0;
  --bar-c:#3f9d5a; --bar-p:#d7a521; --bar-bg:#eceeef; --link:#1f5fbf; --panel:#ffffff; }
@media (prefers-color-scheme: dark) { :root { --bg:#151719; --fg:#e4e6e8; --muted:#979ca1; --line:#2b2f33; --cov:#16351f; --uniq:#1f5530;
  --pool:#3d3214; --miss:#46201f; --bar-c:#4fbf72; --bar-p:#e0b43c; --bar-bg:#2b2f33; --link:#7fb0ff; --panel:#1c1f22; color-scheme: dark; } }
body { background:var(--bg); color:var(--fg); font:14px/1.45 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; margin:0; padding:16px 20px 80px; }
a { color:var(--link); text-decoration:none } a:hover { text-decoration:underline }
header { position:sticky; top:0; background:var(--bg); padding:8px 0; border-bottom:1px solid var(--line); z-index:2; }
h2 { font-size:15px; margin:24px 0 8px; }
code, pre, table.code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size:12px; }
pre { background:var(--panel); border:1px solid var(--line); padding:8px; overflow:auto; }
table.list { border-collapse:collapse; font-variant-numeric:tabular-nums; }
table.list td, table.list th { padding:3px 10px; border-bottom:1px solid var(--line); text-align:left; vertical-align:top; }
td.n, th.n { text-align:right; }
table.code { border-collapse:collapse; width:100%; }
table.code td { padding:0 6px; white-space:pre; }
table.code td.n { color:var(--muted); user-select:none; text-align:right; width:1%; }
table.code td.b { color:var(--muted); text-align:right; width:1%; font-size:11px; }
tr.c td.s { background:var(--cov) } tr.u td.s { background:var(--uniq) } tr.p td.s { background:var(--pool) } tr.m td.s { background:var(--miss) }
tr.c, tr.u { cursor:pointer } tr.sel td { outline:1px solid var(--link); }
.legend .k { padding:1px 6px; margin-right:6px; } .k.c { background:var(--cov) } .k.u { background:var(--uniq) } .k.p { background:var(--pool) } .k.m { background:var(--miss) }
.bar { display:inline-flex; width:160px; height:8px; background:var(--bar-bg); border-radius:4px; overflow:hidden; vertical-align:middle; }
.bc { background:var(--bar-c) } .bp { background:var(--bar-p) }
#panel { position:fixed; right:12px; bottom:12px; width:min(560px, calc(100vw - 24px)); max-height:45vh; overflow:auto; background:var(--panel);
  border:1px solid var(--line); border-radius:6px; padding:8px 12px; box-shadow:0 4px 16px rgba(0,0,0,.15); font-size:12px; }
#panel[hidden] { display:none }
#panel ul { margin:4px 0; padding-left:18px; }
"""

PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title><link rel="stylesheet" href="{css}"><script src="{jobs}"></script></head><body>
<header>{head}</header>
{body}
<div id="panel" hidden></div>
<script>
const DATA = {data}, NB = {nbytes}, UP = "{up}";
function jobsOf(b64) {{
  const s = atob(b64), out = [];
  for (let i = 0; i < s.length; i++) {{ const c = s.charCodeAt(i); for (let k = 0; k < 8; k++) if (c & (1 << k)) out.push(i * 8 + k); }}
  return out;
}}
const panel = document.getElementById('panel');
function show(tr) {{
  const l = tr.dataset.l, d = DATA[l];
  if (!d || (!d[0] && !d[1])) return;
  document.querySelectorAll('tr.sel').forEach(e => e.classList.remove('sel'));
  tr.classList.add('sel');
  history.replaceState(null, '', '#L' + l);
  const js = d[0] ? jobsOf(d[0]) : [];
  panel.hidden = false;
  panel.innerHTML = '';
  const hd = document.createElement('div');
  hd.textContent = 'Line ' + l + ': ' + js.length + ' job' + (js.length === 1 ? '' : 's') + (d[1] ? ' (also the JVM warm-up compile)' : '');
  panel.appendChild(hd);
  const ul = document.createElement('ul');
  js.forEach(i => {{ const li = document.createElement('li'), a = document.createElement('a'); a.href = UP + 'job/' + i + '.html'; a.textContent = JOBS[i]; li.appendChild(a); ul.appendChild(li); }});
  panel.appendChild(ul);
}}
document.querySelectorAll('table.code tr.c, table.code tr.u').forEach(tr => tr.addEventListener('click', () => show(tr)));
document.addEventListener('keydown', e => {{ if (e.key === 'Escape') panel.hidden = true; }});
if (location.hash.startsWith('#L')) {{
  const tr = document.querySelector('tr[data-l="' + location.hash.slice(2) + '"]');
  if (tr) {{ tr.scrollIntoView({{block: 'center'}}); tr.classList.add('sel'); show(tr); }}
}}
</script></body></html>
"""
