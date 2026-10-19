// Change scores per (unit, phase): structural / retyped / rebuilt / copies / removed nodes (from the derive records, cheap),
// changed lines (line diff of consecutive printed versions, computed lazily in chunks), JVM phases: instructions rewritten/inlined/removed.
// Used by: heat strip under the bands, the phases table, "●●●" labels in the phase select, the interesting-phase keys (i / I),
// and the project explorer's per-member counts.
(function (R) {
  const $ = (id) => document.getElementById(id);
  const esc = (x) => String(x).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const S = { byUP: null, lines: new Map(), pending: new Set(), D: null, memberPhase: new Map(), memberTotal: null, unitPhase: new Map(), parents: null };
  R.scoreState = S;
  function ensure() {
    const D = R.ST.D; if (S.D === D) return; S.D = D; S.byUP = null; S.lines = new Map(); S.pending = new Set(); S.memberPhase = new Map(); S.memberTotal = null; S.unitPhase = new Map(); S.parents = null; S.tree = new Map(); S.done = new Set();
  }
  function byUP() {
    ensure(); const D = S.D; if (S.byUP) return S.byUP;
    const m = new Map(); D.derives.forEach((d) => { const k = d.unit + ':' + d.phase; let a = m.get(k); if (!a) m.set(k, (a = [])); a.push(d); }); return (S.byUP = m);
  }
  const blank = () => ({ struct: 0, retype: 0, rebuilt: 0, copy: 0, removed: 0, lines: null, add: 0, del: 0 });

  // ---- tree phases ----
  R.treeScore = (unit, phase) => {
    ensure(); const key = unit + ':' + phase; if (S.tree.has(key)) return S.tree.get(key);
    const D = S.D, sc = blank(), fake = { phase, unit };
    (byUP().get(key) || []).forEach((d) => {
      if (d.how === 'elide') { sc.removed += d.old.length; return; }
      d.new.forEach((n) => { const c = R._s.classOf(D, n, fake); sc[c]++; });
    });
    const l = S.lines.get(key); if (l) { sc.lines = l.add + l.del; sc.add = l.add; sc.del = l.del; }
    S.tree.set(key, sc); return sc;
  };
  // ---- IR phases (pseudo versions): ranges rewritten / inlined / elided at that JVM phase, for the classes of the unit ----
  R.irScore = (unit, phase) => {
    ensure(); const D = S.D, sc = blank(); if (!D.hasIR) return sc;
    D.irDerives.forEach((d) => { if (d.phase !== phase) return; const cls = d.new.length ? D.irClassOf.get(d.new[0]) : d.old.length ? D.irClassOf.get(d.old[0]) : null; const c = cls && D.jvmClasses.get(cls); if (!c || c.unit !== unit) return;
      if (d.how === 'rewrite' || d.how === 'inlined-from') sc.struct += d.new.length; else if (d.how === 'elide') sc.removed += d.old.length; else if (d.how === 'copy') sc.copy += d.new.length; });
    const l = S.lines.get('ir:' + unit + ':' + phase); if (l) { sc.lines = l.add + l.del; sc.add = l.add; sc.del = l.del; }
    return sc;
  };
  R.scoreOf = (v) => (v.irPseudo ? R.irScore(v.unit, v.phase) : R.treeScore(v.unit, v.phase));
  const METRICS = { struct: (s) => s.struct, removed: (s) => s.removed, retype: (s) => s.retype, lines: (s) => s.lines || 0, total: (s) => s.struct + s.removed + s.retype + s.rebuilt + (s.lines || 0) / 4 };
  R.metricVal = (sc) => METRICS[R.ST.metric || 'struct'](sc);

  // ---- lines (lazy, chunked) ----
  const isOp = /^\s+[A-Z][A-Z0-9_]+\b/, notOp = /^\s+(LINENUMBER|FRAME|LOCALVARIABLE|MAXSTACK|MAXLOCALS)\b/;
  function lineDiff(a, b, ir) {
    const A = a.split('\n'), B = b.split('\n'), q = new Map(); A.forEach((t, i) => { if (!t.trim()) return; let l = q.get(t); if (!l) q.set(t, (l = [])); l.push(i); });
    const used = new Set(); let last = -1, add = 0, del = 0;
    const keep = (t) => (ir ? isOp.test(t) && !notOp.test(t) : t.trim().length > 0);
    B.forEach((t) => { if (!keep(t)) return; const l = q.get(t); const k = l && l.find((i) => i > last && !used.has(i)); if (k != null) { used.add(k); last = k; } else add++; });
    A.forEach((t, i) => { if (keep(t) && !used.has(i)) del++; });
    return { add, del };
  }
  R.computeLines = async (unit) => {
    ensure(); const D = S.D, cls0 = R.curClass && R.curClass(), tag = 'u' + unit + '|' + cls0; if (S.pending.has(tag) || S.done.has(tag)) return; S.pending.add(tag);
    const vs = D.versionsOf(unit); let n = 0, changed = 0;
    for (let i = 1; i < vs.length; i++) {
      const key = unit + ':' + vs[i].phase; if (S.lines.has(key)) continue;
      S.lines.set(key, vs[i].text === vs[i - 1].text ? { add: 0, del: 0 } : lineDiff(vs[i - 1].text, vs[i].text, false)); S.tree.delete(key); changed++;
      if (++n % 6 === 0) await new Promise((r) => setTimeout(r, 0)); if (S.D !== D) return;
    }
    // JVM: current class of this unit, phase by phase
    const cls = R.curClass && R.curClass(); const c = cls && D.jvmClasses.get(cls);
    if (c && c.unit === unit) {
      let prev = null; for (const cv of c.versions) { if (prev && !S.lines.has('ir:' + unit + ':' + cv.phase)) { S.lines.set('ir:' + unit + ':' + cv.phase, lineDiff(prev.text, cv.text, true)); changed++; } prev = cv; if (++n % 4 === 0) await new Promise((r) => setTimeout(r, 0)); if (S.D !== D) return; }
    }
    S.pending.delete(tag); S.done.add(tag); if (R.ST.D === D && changed) R.render();
  };

  // ---- interesting phases ----
  const N_TOP = 5;
  R.versionScores = () => { const vs = R.vers(); return vs.map((v, i) => ({ v, i, sc: R.scoreOf(v) })); };
  R.interesting = () => {
    const vs = R.versionScores().filter((x) => x.i > 0), top = vs.slice().sort((a, b) => R.metricVal(b.sc) - R.metricVal(a.sc)).filter((x) => R.metricVal(x.sc) > 0).slice(0, N_TOP);
    return top.sort((a, b) => a.i - b.i);
  };
  R.dots = (v, all) => { const m = Math.max(1, ...all.map((x) => R.metricVal(x.sc))), r = R.metricVal(R.scoreOf(v)) / m; return r <= 0 ? '' : r < 0.08 ? '●' : r < 0.25 ? '●●' : r < 0.55 ? '●●●' : '●●●●'; };
  R.jumpInteresting = (d) => {
    const top = R.interesting(); if (!top.length) return; const cur = R.ST.verIdx;
    const t = d > 0 ? top.find((x) => x.i > cur) || top[0] : [...top].reverse().find((x) => x.i < cur) || top[top.length - 1]; R.setPhase(t.i);
  };

  // ---- phases table ----
  let sortKey = 'pos', sortDir = 1;
  function renderTable() {
    const D = R.ST.D, box = $('phbody'); if (!D || $('phtab').hidden) return;
    R.computeLines(R.ST.unit);
    const rows = R.versionScores(), top = new Set(R.interesting().map((x) => x.i)), mv = R.metricVal;
    const val = { pos: (r) => r.i, name: (r) => R.verName(r.v), struct: (r) => r.sc.struct, removed: (r) => r.sc.removed, retype: (r) => r.sc.retype, rebuilt: (r) => r.sc.rebuilt, lines: (r) => r.sc.lines || 0, metric: (r) => mv(r.sc) };
    rows.sort((a, b) => { const x = val[sortKey](a), y = val[sortKey](b); return (x < y ? -1 : x > y ? 1 : 0) * sortDir; });
    const max = Math.max(1, ...rows.map((r) => mv(r.sc)));
    const th = (k, t, title) => `<th data-k="${k}" title="${esc(title || 'sort')}" class="${sortKey === k ? 'sorted' : ''}">${t}${sortKey === k ? (sortDir > 0 ? ' ▲' : ' ▼') : ''}</th>`;
    box.innerHTML = `<table class="phtab"><tr>${th('pos', '#')}${th('name', 'phase')}${th('struct', 'structural', 'new or rewritten nodes (IR: rewritten + inlined ranges)')}${th('removed', 'removed', 'nodes that left the tree (IR: elided ranges)')}${th('retype', 'retyped')}${th('rebuilt', 'rebuilt')}${th('lines', 'lines ±', 'added / removed lines of the printed text (IR: instruction lines of the current class)')}<th>score</th></tr>` +
      rows.map((r) => `<tr data-i="${r.i}" class="${top.has(r.i) ? 'star' : ''}${r.i === R.ST.verIdx ? ' cur' : ''}"><td>${r.i + 1}</td><td>${r.v.irPseudo ? '<span class="dim">IR</span> ' : ''}${esc(R.verName(r.v))}${top.has(r.i) ? ' ★' : ''}</td><td>${r.sc.struct || ''}</td><td>${r.sc.removed || ''}</td><td>${r.sc.retype || ''}</td><td>${r.sc.rebuilt || ''}</td><td>${r.sc.lines == null ? '…' : r.sc.lines ? '+' + r.sc.add + ' −' + r.sc.del : ''}</td><td><span class="bar" style="width:${Math.round(60 * mv(r.sc) / max)}px"></span></td></tr>`).join('') + '</table>';
    box.querySelectorAll('th[data-k]').forEach((t) => (t.onclick = () => { const k = t.dataset.k; if (sortKey === k) sortDir = -sortDir; else { sortKey = k; sortDir = k === 'name' || k === 'pos' ? 1 : -1; } renderTable(); }));
    box.querySelectorAll('tr[data-i]').forEach((tr) => (tr.onclick = () => { R.setPhase(+tr.dataset.i); }));
    $('phtitle').textContent = `Phases of ${((D.units.get(R.ST.unit) || {}).path || '').replace(/^.*\//, '')}`;
  }
  R.renderPhaseTable = renderTable;

  // ---- heat strip under the bands ----
  function renderHeat() {
    const D = R.ST.D, box = $('heat'); if (!D || !D.bands.length) { box.hidden = true; return; }
    const zoomed = !!R.ST.zoomKey; box.hidden = zoomed; if (zoomed) return;
    const W = $('bands').clientWidth || 1000, ax = R.axis(W), top = new Set(R.interesting().map((x) => x.v.phase)); R.computeLines(R.ST.unit);
    const vals = new Map(); R.versionScores().forEach((x) => { if (!x.v.irPseudo) vals.set(x.v.phase, R.metricVal(x.sc)); });
    const max = Math.max(1, ...vals.values()), key = [R.ST.D.bands.length, R.ST.unit, R.ST.metric, ax.key, R.ST.collapse, R.ST.skip, S.lines.size].join('|');
    if (box._key !== key) {
      box._key = key; box.innerHTML = '';
      ax.segs.forEach(({ b, a0, a1 }) => { const v = vals.get(b.phase) || 0; const el = document.createElement('div'); el.className = 'hc' + (top.has(b.phase) ? ' star' : ''); el.style.left = a0 * 100 + '%'; el.style.width = Math.max(2, (a1 - a0) * W) + 'px'; el.style.background = v ? `rgba(214,51,108,${(0.15 + 0.85 * Math.sqrt(v / max)).toFixed(2)})` : 'transparent'; el.title = `${D.phaseName(b.phase)}: ${v} (${R.ST.metric || 'struct'})`; box.append(el); });
    }
  }
  R.renderHeat = renderHeat;

  document.addEventListener('DOMContentLoaded', () => {
    $('btn-phtab').onclick = () => { $('phtab').hidden = !$('phtab').hidden; renderTable(); };
    const m1 = $('metric'), m2 = $('metric2'); m2.innerHTML = m1.innerHTML; const setM = (v) => { m1.value = m2.value = v; R.set({ metric: v }); }; m1.onchange = (ev) => setM(ev.target.value); m2.onchange = (ev) => setM(ev.target.value);
    document.querySelector('[data-close="phtab"]').onclick = () => { $('phtab').hidden = true; };
  });
  R.views.push(() => { renderHeat(); if (!$('phtab').hidden) renderTable(); });
})(window.R);
