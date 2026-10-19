// Dependencies panel: answers "what does this unit depend on, and when?". Primary UI is a table of neighbours (kind, weight, first seq,
// a sparkline of when the edges were established) with kind tabs and a "what did this unit pull in" summary; the force graph
// below it is secondary (collapsed by default) and draws only the ego network (hops) of the selection, grouped by package.
(function (R) {
  const $ = (id) => document.getElementById(id);
  const esc = (x) => String(x).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const KINDS = ['resolves', 'forces', 'implicit', 'extends', 'inlined'];
  const KC = { resolves: '#8a94a6', forces: '#e8590c', extends: '#2f9e44', implicit: '#7048e8', inlined: '#d6336c' };
  const C = { D: null, aggs: new Map() };
  const keyFn = (level) => { const D = R.ST.D; return (s) => { if (level !== 'file') return s; const sy = D.syms.get(s); if (!sy) return s; if (sy.unit != null) return 'u' + sy.unit; const pk = D.pkgOf(s); return pk ? 'p:' + pk : s; }; };
  const nameOf = (k) => { const D = R.ST.D; return typeof k === 'string' ? (k[0] === 'p' ? k.slice(2) + '.*' : ((D.units.get(+k.slice(1)) || {}).path || k).replace(/^.*\//, '')) : D.symName(k); };
  // aggregated edges (kind, a, b) with the individual records for sparklines; cached per level
  function agg(level) {
    const D = R.ST.D; if (C.D !== D) { C.D = D; C.aggs = new Map(); } if (C.aggs.has(level)) return C.aggs.get(level);
    const keyOf = keyFn(level), m = new Map();
    D.edges.forEach((e) => { if (e.from == null || e.to == null) return; const a = keyOf(e.from), b = keyOf(e.to); if (a === b && level === 'file') return; const k = e.kind + '|' + a + '|' + b; let r = m.get(k); if (!r) m.set(k, (r = { kind: e.kind, a, b, seq: e.seq, w: 0, recs: [] })); const w = e.weight || 1; r.w += w; r.recs.push([e.seq, w]); if (e.seq < r.seq) r.seq = e.seq; });
    const flat = [...m.values()], adj = new Map();
    flat.forEach((r) => { [[r.a, r.b, 'out'], [r.b, r.a, 'in']].forEach(([k, o, dir]) => { let l = adj.get(k); if (!l) adj.set(k, (l = [])); l.push({ other: o, dir, r }); }); });
    const res = { flat, adj, keyOf }; C.aggs.set(level, res); return res;
  }
  const units = () => (R.selectedUnits ? [...R.selectedUnits()] : [R.ST.unit]);
  // centre keys of the panel: the graph node chosen by the user, else the selected unit(s)
  R.graphCentres = (level) => {
    const st = R.ST, D = st.D; level = level || st.level;
    if (st.gsel != null && st.gselFor === st.selSym) return [st.gsel];
    const us = new Set(units());
    if (level === 'file') return [...us].map((u) => 'u' + u);
    const out = []; D.syms.forEach((s, id) => { if (s.unit != null && us.has(s.unit)) out.push(id); }); return out;
  };
  // restrict the graph's edge list to the ego network of the centres and to the chosen kind
  R.depsFilter = (flat, keyOf) => {
    const st = R.ST; let f = st.gkind && st.gkind !== 'all' ? flat.filter((r) => r.kind === st.gkind) : flat;
    if (st.gscope === 'all') return f;
    const centres = new Set(R.graphCentres()), hops = +(st.ghops || 1); let E = new Set(centres);
    for (let h = 0; h < hops; h++) { const nx = new Set(E); f.forEach((r) => { if (E.has(r.a)) nx.add(r.b); if (E.has(r.b)) nx.add(r.a); }); E = nx; }
    return f.filter((r) => E.has(r.a) && E.has(r.b));
  };

  // ---- sparkline: when the edges were established, over the whole seq axis ----
  function spark(recs, maxSeq, curSeq) {
    const B = 24, W = 84, H = 16, b = new Array(B).fill(0); recs.forEach(([s, w]) => { b[Math.min(B - 1, Math.floor(s / Math.max(1, maxSeq) * B))] += w; });
    const m = Math.max(...b, 1), bw = W / B; let bars = ''; b.forEach((v, i) => { if (v) bars += `<rect x="${(i * bw).toFixed(1)}" y="${(H - 1 - (H - 2) * v / m).toFixed(1)}" width="${(bw - 0.6).toFixed(1)}" height="${((H - 2) * v / m + 1).toFixed(1)}" fill="#7a828e"/>`; });
    return `<svg width="${W}" height="${H}" class="spark"><rect width="${W}" height="${H}" fill="none" stroke="var(--line)"/>${bars}<line x1="${(curSeq / Math.max(1, maxSeq) * W).toFixed(1)}" x2="${(curSeq / Math.max(1, maxSeq) * W).toFixed(1)}" y1="0" y2="${H}" stroke="#f08c00"/></svg>`;
  }

  // ---- summary: what did this unit pull in ----
  function summary(us) {
    const D = R.ST.D, lib = new Map(), files = new Map(), by = {}, syms = new Set(); let used = 0, refs = 0;
    D.edges.forEach((e) => {
      if (e.from == null || e.to == null) return; const f = D.syms.get(e.from), t = D.syms.get(e.to), w = e.weight || 1; if (!f || !t) return;
      if (f.unit != null && us.has(f.unit) && !(t.unit != null && us.has(t.unit))) {
        refs += w; by[e.kind] = (by[e.kind] || 0) + w; if (syms.has(e.to)) { /* counted */ } else syms.add(e.to);
        if (t.unit == null) { const p = D.pkgOf(e.to) || '(none)'; let o = lib.get(p); if (!o) lib.set(p, (o = { n: new Set(), w: 0 })); o.n.add(e.to); o.w += w; } else { files.set(t.unit, (files.get(t.unit) || 0) + w); }
      } else if (t.unit != null && us.has(t.unit) && f.unit != null && !us.has(f.unit)) used += w;
    });
    const nLib = [...lib.values()].reduce((a, o) => a + o.n.size, 0), top = (m, k = 4) => [...m.entries()].sort((a, b) => b[1].w - a[1].w).slice(0, k);
    const lp = top(lib).map(([p, o]) => `${esc(p)} ${o.n.size}`).join(', '), fp = [...files.entries()].sort((a, b) => b[1] - a[1]).slice(0, 4).map(([u, w]) => `${esc(((D.units.get(u) || {}).path || '').replace(/^.*\//, ''))} ${w}`).join(', ');
    const kinds = Object.entries(by).sort((a, b) => b[1] - a[1]).map(([k, w]) => `<span class="kchip" style="border-color:${KC[k] || '#999'}">${k} ${w}</span>`).join(' ');
    return `<b>Pulls in</b> <b>${nLib}</b> library/classpath classes in ${lib.size} packages${lp ? ' <span class="dim">(' + lp + (lib.size > 4 ? ', …' : '') + ')</span>' : ''} and <b>${files.size}</b> other compiled files${fp ? ' <span class="dim">(' + fp + ')</span>' : ''} · ${refs} references ${kinds} · used by other files ${used}×`;
  }

  // ---- render ----
  function render() {
    const D = R.ST.D, st = R.ST; if (!D) return;
    const sec = $('proj'); if (sec.hidden) return;
    const level = st.level, A = agg(level), cen = R.graphCentres(level), cs = new Set(cen), maxSeq = D.evs.length ? D.evs[D.evs.length - 1].seq : 1, curSeq = R.seq();
    const centreNames = st.gsel != null && st.gselFor === st.selSym ? nameOf(st.gsel) : units().map((u) => nameOf('u' + u)).join(', ');
    $('gtitle').textContent = centreNames;
    // rows: edges incident to the centre(s), merged per (other node, kind, direction)
    const rows = new Map();
    cen.forEach((k) => (A.adj.get(k) || []).forEach(({ other, dir, r }) => { if (cs.has(other)) return; const id = other + '|' + r.kind + '|' + dir; let x = rows.get(id); if (!x) rows.set(id, (x = { other, dir, kind: r.kind, w: 0, seq: r.seq, recs: [] })); x.w += r.w; x.recs.push(...r.recs); if (r.seq < x.seq) x.seq = r.seq; }));
    let list = [...rows.values()]; const counts = { all: list.length }; list.forEach((x) => { counts[x.kind] = (counts[x.kind] || 0) + 1; });
    if (st.gkind && st.gkind !== 'all') list = list.filter((x) => x.kind === st.gkind);
    list.sort((a, b) => b.w - a.w || a.seq - b.seq);
    $('gtabs').innerHTML = ['all', ...KINDS.filter((k) => counts[k])].map((k) => `<button data-k="${k}" class="${(st.gkind || 'all') === k ? 'on' : ''}">${k} (${counts[k] || 0})</button>`).join('');
    $('gtabs').querySelectorAll('button').forEach((b) => (b.onclick = () => R.set({ gkind: b.dataset.k })));
    $('gsum').innerHTML = st.gsel != null && st.gselFor === st.selSym ? `<a id="gback" class="lnk">← back to the selected unit</a> · showing the neighbours of <b>${esc(centreNames)}</b>` : summary(new Set(units()));
    const gb = $('gback'); if (gb) gb.onclick = () => R.set({ gsel: null, gselFor: null });
    const maxw = Math.max(1, ...list.map((x) => x.w));
    $('neigh').innerHTML = list.length ? `<table class="ntab"><tr><th></th><th>depends on / used by</th><th>kind</th><th>weight</th><th>first seq</th><th title="when the edges were established, over the whole recording; orange = cursor">when</th></tr>` + list.slice(0, 250).map((x, i) => `<tr data-i="${i}" title="click: cursor to the first seq · double-click: make this the centre"><td>${x.dir === 'out' ? '→' : '←'}</td><td>${esc(nameOf(x.other))}${typeof x.other === 'string' && x.other[0] === 'u' ? ` <a class="lnk open" data-u="${x.other.slice(1)}">open</a>` : ''}</td><td><span class="kchip" style="border-color:${KC[x.kind] || '#999'}">${esc(x.kind)}</span></td><td>${x.w}<span class="wbar" style="width:${Math.round(40 * x.w / maxw)}px"></span></td><td>${x.seq}</td><td>${spark(x.recs, maxSeq, curSeq)}</td></tr>`).join('') + '</table>' + (list.length > 250 ? `<div class="dim">… ${list.length - 250} more</div>` : '') : '<span class="dim">No dependencies recorded for this selection' + (st.gkind && st.gkind !== 'all' ? ' of this kind' : '') + '.</span>';
    $('neigh').querySelectorAll('tr[data-i]').forEach((tr) => { const x = list[+tr.dataset.i]; tr.onclick = (ev) => { if (ev.target.closest('.open')) return; R.setEvBySeq(x.seq, true); }; tr.ondblclick = () => { const num = typeof x.other === 'number'; R.set({ gsel: x.other, gselFor: num ? x.other : null, selSym: num ? x.other : null, selNode: null }); }; });
    $('neigh').querySelectorAll('.open').forEach((a) => (a.onclick = (ev) => { ev.stopPropagation(); R.selectUnit(+a.dataset.u); }));
    // graph (secondary): rebuild when the centre, filters or level change
    const sig = [D.nodes.size, level, cen.join(',').slice(0, 200), st.gkind, st.gscope, st.ghops, $('minw').value, $('gdet').open].join('|');
    if (sig !== C.sig) { C.sig = sig; if (R.rebuildGraph) R.rebuildGraph(); }
    $('graphinfo').textContent = `${list.length} ${list.length === 1 ? 'dependency' : 'dependencies'} · ${counts.all || 0} total`;
    { const one = D.unitsWithVersions.length < 2; $('gscope').disabled = one; $('ghops').disabled = one; $('gscope').title = $('ghops').title = one ? 'only one unit in this recording' : ''; }
  }

  document.addEventListener('DOMContentLoaded', () => {
    $('gscope').onchange = (ev) => R.set({ gscope: ev.target.value }); $('ghops').onchange = (ev) => R.set({ ghops: +ev.target.value });
    $('gdet').addEventListener('toggle', () => { C.sig = null; R.render(); if ($('gdet').open) setTimeout(() => $('gdet').scrollIntoView({ block: 'nearest', behavior: 'smooth' }), 50); }); $('level').value = R.ST.level;
  });
  R.views.push(render);
})(window.R);
