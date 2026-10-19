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
      if (d.how === 'rewrite' || d.how === 'inlined-from' || d.how === 'codegen') sc.struct += d.new.length; else if (d.how === 'elide') sc.removed += d.old.length; else if (d.how === 'copy') sc.copy += d.new.length; });
    const l = S.lines.get('ir:' + unit + ':' + phase); if (l) { sc.lines = l.add + l.del; sc.add = l.add; sc.del = l.del; }
    sc.ir = true; return sc;
  };
  R.scoreOf = (v) => (v.irPseudo ? R.irScore(v.unit, v.phase) : R.treeScore(v.unit, v.phase));
  const METRICS = { struct: (s) => s.struct, removed: (s) => s.removed, retype: (s) => s.retype, lines: (s) => s.lines || 0, total: (s) => s.struct + s.removed + s.retype + s.rebuilt + (s.lines || 0) / 4 };
  R.metricVal = (sc) => METRICS[R.ST.metric || 'struct'](sc);

  // ---- lines (lazy, chunked): the shared diff (diff.js) ----
  const isOp = /^\s+[A-Z][A-Z0-9_]+\b/, notOp = /^\s+(LINENUMBER|FRAME|LOCALVARIABLE|MAXSTACK|MAXLOCALS)\b/, opLine = (t) => isOp.test(t) && !notOp.test(t);
  R.computeLines = async (unit) => {
    ensure(); const D = S.D, cls0 = R.curClass && R.curClass(), tag = 'u' + unit; if (S.pending.has(tag) || S.done.has(tag)) return; S.pending.add(tag);
    const vs = D.versionsOf(unit); let n = 0, changed = 0;
    for (let i = 1; i < vs.length; i++) {
      const key = unit + ':' + vs[i].phase; if (S.lines.has(key)) continue;
      { const d = R.versionDiff(vs[i], vs[i - 1]); S.lines.set(key, { add: d.add, del: d.del }); } S.tree.delete(key); changed++;
      if (++n % 6 === 0) await new Promise((r) => setTimeout(r, 0)); if (S.D !== D) return;
    }
    // JVM: every class of the unit, phase by phase, summed per phase: instructions emitted at genBCode (+), instruction lines +/- after
    const acc = new Map(), bump = (ph, add, del) => { const k = 'ir:' + unit + ':' + ph; const o = acc.get(k) || { add: 0, del: 0 }; o.add += add; o.del += del; acc.set(k, o); };
    for (const c of D.jvmClasses.values()) {
      if (c.unit !== unit) continue; let prev = null;
      for (const cv of c.versions) {
        if (!prev) bump(cv.phase, cv.text.split('\n').filter((t) => isOp.test(t) && !notOp.test(t)).length, 0); else { const d = R.countDiff(prev.text, cv.text, opLine); bump(cv.phase, d.add, d.del); }
        prev = cv; if (++n % 4 === 0) await new Promise((r) => setTimeout(r, 0)); if (S.D !== D) return;
      }
    }
    acc.forEach((v, k) => { if (!S.lines.has(k)) { S.lines.set(k, v); changed++; } });
    S.pending.delete(tag); S.done.add(tag); if (R.ST.D === D && changed) R.render();
  };

  // ---- interesting phases ----
  const N_TOP = 5;
  R.versionScores = () => { const vs = R.vers(); return vs.map((v, i) => ({ v, i, sc: R.scoreOf(v) })); };
  R.interestingTop = () => {
    const D = R.ST.D, vs = R.versionScores().filter((x) => x.i > 0), top = vs.slice().sort((a, b) => R.metricVal(b.sc) - R.metricVal(a.sc)).filter((x) => R.metricVal(x.sc) > 0).slice(0, N_TOP);
    const stops = new Set(top.map((x) => x.i));
    // where bytecode first appears and the final class state are always stops, whatever the metric
    vs.forEach((x) => { if (x.v.irPseudo && (x.v.phase === D.irGenPhase || D.phaseRaw(x.v.phase) === 'jvm:classfile')) stops.add(x.i); });
    return vs.filter((x) => stops.has(x.i)).sort((a, b) => a.i - b.i);
  };
  // ---- chapters: consecutive versions coalesced until the accumulated change reaches max(MIN, PCT % of the unit's size) ----
  // metric 'lines' (default): changed printed lines (added + removed, from the line diff); bytecode phases: ranges rewritten + elided + inlined-from (range derives, not the text diff),
  // measured against the ranges emitted at genBCode. metric 'nodes': new / rewritten / removed nodes (rebuilt spine nodes and retypes excluded).
  // parser, typer, erasure, genBCode and jvm:classfile are chapters on their own (the coalescing restarts after them); a chapter that ends under the threshold,
  // or a forced phase with a score under 2 (except parser), joins the previous chapter. Named by the dominant phase.
  const scoreOf2 = (v, sc, metric) => (v.irPseudo ? sc.struct + sc.removed : metric === 'nodes' ? sc.struct + sc.removed : (sc.lines || 0));
  let chCache = null;
  const customChapters = () => {
    const D = R.ST.D; if (!D) return [];
    const vs = R.versionScores(), mn = R.ST.chThrNodes ?? 6, pct = R.ST.chThrPct ?? 4, metric = R.ST.chMetric || 'lines', key = [R.ST.unit, vs.length, mn, pct, metric, S.lines.size, R.curClass && R.curClass()].join('|');
    if (chCache && chCache.key === key) return chCache.val;
    const gen = vs.find((x) => x.v.irPseudo && x.v.phase === D.irGenPhase), irBase = gen ? Math.max(1, gen.sc.struct) : 1;
    const forced = new Set(); vs.forEach((x) => { const r = D.phaseRaw(x.v.phase); if (x.i === 0 || (!x.v.irPseudo && (r === 'typer' || r === 'erasure')) || (x.v.irPseudo && (x.v.phase === D.irGenPhase || r === 'jvm:classfile'))) forced.add(x.i); });
    const sizeAt = (x) => { if (x.v.irPseudo) return irBase; const tv = vs.slice(0, x.i + 1).filter((y) => !y.v.irPseudo).pop(); return metric === 'nodes' ? (tv && tv.v.ids ? tv.v.ids.size : 0) : (tv ? R.linesOf(tv.v).length : 0); };
    const out = []; let cur = null, acc = 0, lastThr = mn;
    const mk = (x) => ({ from: x.i, to: x.i, members: [], forced: forced.has(x.i), add: 0, del: 0, ir: !!x.v.irPseudo });
    const close = () => { if (!cur) return; cur.score = acc; if (!cur.forced && acc < lastThr && out.length) { const p = out[out.length - 1]; p.to = cur.to; p.members.push(...cur.members); p.score += acc; p.add += cur.add; p.del += cur.del; p.ir = cur.ir; } else out.push(cur); cur = null; acc = 0; };
    vs.forEach((x) => {
      const sc = scoreOf2(x.v, x.sc, metric), thr = Math.max(mn, Math.ceil(sizeAt(x) * pct / 100)); lastThr = thr;
      if (forced.has(x.i)) {
        close();
        if (x.i > 0 && sc < 2 && out.length) { const p = out[out.length - 1]; p.to = x.i; p.members.push({ i: x.i, sc }); p.score += sc; p.add += x.sc.add || 0; p.del += x.sc.del || 0; p.ir = !!x.v.irPseudo; return; }
        cur = mk(x); cur.members.push({ i: x.i, sc }); cur.dom = x.i; cur.domScore = sc; acc = sc; cur.add += x.sc.add || 0; cur.del += x.sc.del || 0; close(); return;
      }
      if (!cur) { cur = mk(x); cur.dom = x.i; cur.domScore = -1; }
      cur.to = x.i; cur.members.push({ i: x.i, sc }); acc += sc; cur.add += x.sc.add || 0; cur.del += x.sc.del || 0; if (sc > cur.domScore) { cur.domScore = sc; cur.dom = x.i; }
      if (acc >= thr) close();
    });
    close();
    // dominant phase of merged chapters: the member with the largest score
    out.forEach((c, k) => { c.k = k; let d = c.members[0]; c.members.forEach((m) => { if (m.sc > d.sc) d = m; }); c.dom = d.i; const v = vs[c.dom].v, minor = c.members.filter((m) => m.i !== c.dom && m.sc > 0).length; c.name = R.verName(v) + (minor ? ` (+ ${minor} minor phase${minor > 1 ? 's' : ''})` : ''); c.short = R.verName(v); c.v = vs[c.to].v; });
    chCache = { key, val: out }; return out;
  };
  // ---- chapters from the compiler's own phase groups (default): MegaPhase groups, pruned by the median ----
  // group = the MegaPhase a phase is fused into (phase.megaOf; unfused phases are groups of their own); bytecode: genBCode, inliner, each optimiser round, the post passes
  // (localVariables, lineNumbers), classfile. A group is a chapter iff its score (changed lines; bytecode: range-derive counts) >= half the median of the non-zero group scores of
  // its kind, else it joins the previous chapter. parser, typer, erasure, genBCode always are chapters; inliner and optimiser rounds collapse into one "optimiser" chapter unless
  // a single one reaches the median; classfile only is a chapter if it reaches the median.
  let gCache = null;
  const groupChapters = () => {
    const D = R.ST.D; if (!D) return [];
    const vs = R.versionScores(), key = [R.ST.unit, vs.length, S.lines.size, R.curClass && R.curClass()].join('|');
    if (gCache && gCache.key === key) return gCache.val;
    const kindOf = (x) => { const r = D.phaseRaw(x.v.phase); if (!x.v.irPseudo) return 'tree'; if (x.v.phase === D.irGenPhase) return 'gen'; if (r === 'jvm:classfile') return 'classfile'; if (r === 'jvm:localVariables' || r === 'jvm:lineNumbers') return 'post'; return 'opt'; };
    const keyOf = (x) => { const k = kindOf(x), ph = D.phases.get(x.v.phase) || {}; if (k === 'tree') return ph.megaOf ? 'm:' + ph.megaOf : 'p:' + x.v.phase; if (k !== 'opt') return k; const m = /^jvm:r(\d+):/.exec(D.phaseRaw(x.v.phase)); return m ? 'opt:r' + m[1] : 'opt:' + D.phaseRaw(x.v.phase); };
    const groups = []; vs.forEach((x) => { const k = keyOf(x), g = groups[groups.length - 1]; const sc = scoreOf2(x.v, x.sc, 'lines'); if (g && g.key === k) { g.idx.push(x.i); g.score += sc; g.add += x.sc.add || 0; g.del += x.sc.del || 0; g.members.push({ i: x.i, sc }); } else groups.push({ key: k, kind: kindOf(x), idx: [x.i], score: sc, add: x.sc.add || 0, del: x.sc.del || 0, members: [{ i: x.i, sc }], raw: D.phaseRaw(x.v.phase) }); });
    const med = (kinds) => { const a = groups.filter((g) => kinds.includes(g.kind) && g.score > 0).map((g) => g.score).sort((p, q) => p - q); return a.length ? a[a.length >> 1] : 0; };
    const mt = med(['tree']), mi = med(['opt', 'post']);
    const out = []; const push = (g, extra) => out.push(Object.assign({ gkeys: [g.key], from: g.idx[0], to: g.idx[g.idx.length - 1], members: g.members.slice(), score: g.score, add: g.add, del: g.del, ir: g.kind !== 'tree', forced: false }, extra));
    const merge = (g) => { const c = out[out.length - 1]; c.gkeys.push(g.key); c.to = g.idx[g.idx.length - 1]; c.members.push(...g.members); c.score += g.score; c.add += g.add; c.del += g.del; c.ir = g.kind !== 'tree'; };
    groups.forEach((g, gi) => {
      const first = gi === 0, raw = g.raw;
      if (g.kind === 'tree') { if (first || g.idx.length === 1 && (raw === 'typer' || raw === 'erasure') || g.score >= 0.5 * mt || !out.length) push(g, { forced: first || raw === 'typer' || raw === 'erasure' }); else merge(g); }
      else if (g.kind === 'gen') push(g, { forced: true });
      else if (g.kind === 'opt') { if (g.score >= mi) push(g, { opt: false }); else if (out.length && out[out.length - 1].opt) merge(g); else push(g, { opt: true }); }
      else if (g.kind === 'post') { if (g.score >= 0.5 * mi || !out.length) push(g); else merge(g); }
      else { if (g.score >= mi || !out.length) push(g); else merge(g); }
    });
    out.forEach((c, k) => {
      c.k = k; let d = c.members[0]; c.members.forEach((m) => { if (m.sc > d.sc) d = m; }); c.dom = d.i; const v = vs[c.dom].v, minor = c.members.filter((m) => m.i !== c.dom && m.sc > 0).length;
      c.name = c.opt && c.members.length > 1 ? `optimiser (${c.members.length} passes)` : c.gkeys && /^opt:r\d+$/.test(c.gkeys[0]) && c.gkeys.length === 1 ? `optimiser round ${c.gkeys[0].slice(5)}${minor ? ` (+ ${minor} minor pass${minor > 1 ? 'es' : ''})` : ''}` : R.verName(v) + (minor ? ` (+ ${minor} minor phase${minor > 1 ? 's' : ''})` : ''); c.short = c.opt ? 'optimiser' : R.verName(v); c.v = vs[c.to].v;
    });
    gCache = { key, val: out, medians: [mt, mi] }; return out;
  };
  R.chapters = () => ((R.ST.chMode || 'groups') === 'groups' ? groupChapters() : customChapters());
  // the page stops for the current granularity: chapter ends, ★ top-N, or every version with a change
  R.interesting = () => {
    const g = R.ST.gran || 'chapters';
    if (g === 'top') return R.interestingTop();
    const vs = R.versionScores();
    if (g === 'all') return vs.filter((x) => x.i > 0 && (structOf(x.v, x.sc) > 0 || x.sc.retype > 0 || (x.sc.lines || 0) > 0));
    return R.chapters().map((c) => Object.assign({ ch: c }, vs[c.dom]));   // a page lands on the chapter's dominant phase: that is where its change is visible
  };
  // page position: chapter (or stop) index for the current version, and the stops
  // ★ markers: the stops that actually changed something (the parser page is a stop but not a ★)
  R.stars = () => R.interesting().filter((x) => (x.ch ? x.ch.score > 0 : true));
  R.chapterRange = (ch) => { if (!ch || ch.from === ch.to) return ''; const vs = R.vers(); return `${R.verName(vs[ch.from])} … ${R.verName(vs[ch.to])}`; };
  R.pageInfo = () => {
    const stops = R.interesting(), cur = R.ST.verIdx; let k = stops.findIndex((x) => (x.ch ? x.ch.from <= cur && cur <= x.ch.to : x.i >= cur)); if (k < 0) k = stops.length - 1;
    const ch = (R.ST.gran || 'chapters') === 'chapters' ? R.chapters() : null;
    return { stops, k, range: stops[k] && stops[k].ch ? R.chapterRange(stops[k].ch) : '', label: ch ? 'chapter' : 'stop', name: stops[k] && (stops[k].ch ? stops[k].ch.name + ((stops[k].ch.add || stops[k].ch.del) ? ` · +${stops[k].ch.add} −${stops[k].ch.del} ${stops[k].ch.ir ? 'instr' : 'lines'}` : '') : R.verName(stops[k].v)), top: new Set(R.interestingTop().map((x) => x.i)) };
  };
  // ---- pager (◀ ▶ + counter + dots) shared by the explorer panel and the stack overlay ----
  const escH = (t) => String(t).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  R.pagerHtml = (info) => {
    const n = info.stops.length; if (!n) return '';
    const dots = info.stops.map((x, i) => `<button class="dot${info.top.has(x.i) ? ' star' : ''}${i === info.k ? ' cur' : ''}" data-k="${i}" title="${escH(x.ch ? x.ch.name : R.verName(x.v))}">${info.top.has(x.i) ? '★' : ''}</button>`).join('');
    return `<div class="prow"><button class="fab" data-act="-1" title="previous ${info.label} (←)">◀</button><div class="pcount"><b>${info.label} ${info.k + 1}/${n} · ${escH(info.name || '')}</b><span>${escH(info.range || (R.ST.gran === 'top' ? '★ top 5 + bytecode' : R.ST.gran === 'all' ? 'every change' : 'chapters'))}</span></div><button class="fab" data-act="1" title="next ${info.label} (→)">▶</button></div><div class="dots">${dots}</div>`;
  };
  R.wirePager = (el, turn, go) => { el.addEventListener('click', (e) => { const b = e.target.closest && e.target.closest('button'); if (!b) return; if (b.dataset.act) turn(+b.dataset.act); else if (b.dataset.k != null) go(+b.dataset.k); }); };
  R.pageTurn = (d) => R.jumpInteresting(d);
  R.pageGo = (k) => { const st = R.interesting()[k]; if (st) R.setPhase(st.i); };
  R.isBytecodeStart = (v) => !!(v && v.irPseudo && v.phase === R.ST.D.irGenPhase);
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
    const rows = R.versionScores(), top = new Set(R.stars().map((x) => x.i)), mv = R.metricVal;
    const val = { pos: (r) => r.i, name: (r) => R.verName(r.v), struct: (r) => r.sc.struct, removed: (r) => r.sc.removed, retype: (r) => r.sc.retype, rebuilt: (r) => r.sc.rebuilt, lines: (r) => (r.sc.ir ? 0 : r.sc.lines || 0), instr: (r) => (r.sc.ir ? r.sc.lines || 0 : 0), metric: (r) => mv(r.sc) };
    rows.sort((a, b) => { const x = val[sortKey](a), y = val[sortKey](b); return (x < y ? -1 : x > y ? 1 : 0) * sortDir; });
    const max = Math.max(1, ...rows.map((r) => mv(r.sc)));
    const th = (k, t, title) => `<th data-k="${k}" title="${esc(title || 'sort')}" class="${sortKey === k ? 'sorted' : ''}">${t}${sortKey === k ? (sortDir > 0 ? ' ▲' : ' ▼') : ''}</th>`;
    box.innerHTML = `<table class="phtab"><tr>${th('pos', '#')}${th('name', 'phase')}${th('struct', 'structural', 'new or rewritten nodes (IR: rewritten + inlined ranges)')}${th('removed', 'removed', 'nodes that left the tree (IR: elided ranges)')}${th('retype', 'retyped')}${th('rebuilt', 'rebuilt')}${th('lines', 'lines ±', 'added / removed lines of the printed text')}${th('instr', 'instructions ±', 'JVM phases: instructions emitted at genBCode (+), then instruction lines added / removed, summed over the unit\'s classes')}<th>score</th></tr>` +
      rows.map((r) => `<tr data-i="${r.i}" class="${top.has(r.i) ? 'star' : ''}${r.i === R.ST.verIdx ? ' cur' : ''}"><td>${r.i + 1}</td><td>${r.v.irPseudo ? '<span class="dim">JVM</span> ' : ''}${esc(R.verName(r.v))}${top.has(r.i) ? ' ★' : ''}${R.isBytecodeStart(r.v) ? ' <span class="bctag2">⟐ bytecode</span>' : ''}</td><td>${r.sc.struct || ''}</td><td>${r.sc.removed || ''}</td><td>${r.sc.retype || ''}</td><td>${r.sc.rebuilt || ''}</td><td>${r.sc.ir ? '' : r.sc.lines == null ? '…' : r.sc.lines ? '+' + r.sc.add + ' −' + r.sc.del : ''}</td><td>${r.sc.ir ? (r.sc.lines == null ? '…' : r.sc.lines ? '+' + r.sc.add + ' −' + r.sc.del : '') : ''}</td><td><span class="bar" style="width:${Math.round(60 * mv(r.sc) / max)}px"></span></td></tr>`).join('') + '</table>';
    box.querySelectorAll('th[data-k]').forEach((t) => (t.onclick = () => { const k = t.dataset.k; if (sortKey === k) sortDir = -sortDir; else { sortKey = k; sortDir = k === 'name' || k === 'pos' ? 1 : -1; } renderTable(); }));
    box.querySelectorAll('tr[data-i]').forEach((tr) => (tr.onclick = () => { R.setPhase(+tr.dataset.i); }));
    $('phtitle').textContent = `Phases of ${((D.units.get(R.ST.unit) || {}).path || '').replace(/^.*\//, '')}`;
  }
  R.renderPhaseTable = renderTable;

  // ---- heat strip under the bands ----
  function renderHeat() {
    const D = R.ST.D, box = $('heat'); if (!D || !D.bands.length) { box.hidden = true; return; }
    const zoomed = !!R.ST.zoomKey; box.hidden = zoomed; if (zoomed) return;
    const W = $('bands').clientWidth || 1000, ax = R.axis(W), top = new Set(R.stars().map((x) => x.v.phase)); R.computeLines(R.ST.unit);
    const vals = new Map(); R.versionScores().forEach((x) => { vals.set(x.v.irPseudo ? 'i' + x.v.phase : x.v.phase, R.metricVal(x.sc)); });
    const chs = R.chapters(), vsAll = R.vers(), curCh = chs.find((c) => c.from <= R.ST.verIdx && R.ST.verIdx <= c.to), phIdx = new Map(); vsAll.forEach((v, i) => phIdx.set(v.irPseudo ? 'i' + v.phase : v.phase, i));
    const max = Math.max(1, ...vals.values()), key = [curCh ? curCh.k : -1, R.ST.D.bands.length, R.ST.unit, R.ST.metric, ax.key, R.ST.collapse, R.ST.skip, S.lines.size, R.ST.gran, R.ST.chThrNodes, R.ST.chThrPct, R.ST.chMode, R.ST.chMetric].join('|'), chStart = new Set(R.chapters().filter((c) => c.from > 0).map((c) => { const v = R.vers()[c.from]; return v.irPseudo ? 'i' + v.phase : v.phase; }));
    if (box._key !== key) {
      box._key = key; box.innerHTML = '';
      ax.segs.forEach(({ b, a0, a1 }) => { if (b.phase === D.irGenPhase && D.hasIR && !b.ir) { const tg = document.createElement('div'); tg.className = 'bctag'; tg.textContent = '⟐ bytecode'; tg.style.left = Math.min(a0 * 100, 92) + '%'; box.append(tg); } const v = vals.get(b.ir ? 'i' + b.phase : b.phase) || 0; const el = document.createElement('div'); el.className = 'hc' + (top.has(b.phase) ? ' star' : '') + (chStart.has(b.ir ? 'i' + b.phase : b.phase) ? ' chb' : '') + (curCh && (() => { const i = phIdx.get(b.ir ? 'i' + b.phase : b.phase); return i != null && i >= curCh.from && i <= curCh.to; })() ? ' chcur' : '') + (b.phase === D.irGenPhase && D.hasIR && !b.ir ? ' bc' : ''); el.style.left = a0 * 100 + '%'; el.style.width = Math.max(2, (a1 - a0) * W) + 'px'; el.style.background = v ? `rgba(214,51,108,${(0.15 + 0.85 * Math.sqrt(v / max)).toFixed(2)})` : 'transparent'; el.title = `${D.phaseName(b.phase)}: ${v} (${R.ST.metric || 'struct'})`; box.append(el); });
    }
  }
  R.renderHeat = renderHeat;

  document.addEventListener('DOMContentLoaded', () => {
    $('btn-phtab').onclick = () => { $('phtab').hidden = !$('phtab').hidden; renderTable(); };
    const m1 = $('metric'), m2 = $('metric2'); m2.innerHTML = m1.innerHTML; const setM = (v) => { m1.value = m2.value = v; R.set({ metric: v }); }; m1.onchange = (ev) => setM(ev.target.value); m2.onchange = (ev) => setM(ev.target.value);
    document.querySelector('[data-close="phtab"]').onclick = () => { $('phtab').hidden = true; };
  });
  R.views.push(() => { renderHeat(); { const el = document.getElementById('expager'); if (el) { if (!el._w) { el._w = 1; R.wirePager(el, (d) => R.pageTurn(d), (k) => R.pageGo(k)); } if (R.ST.D) { const h = R.pagerHtml(R.pageInfo()); if (el._h !== h) { el._h = h; el.innerHTML = h; } } } } if (!$('phtab').hidden) renderTable(); });
})(window.R);
