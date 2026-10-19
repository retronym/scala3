// Project graph: force layout computed once on the (aggregated) edge set and frozen; time only controls visibility/heat.
// Levels: `class` (edge endpoints as recorded) or `file` (syms grouped by sym.unit). Updates are incremental: only edges/nodes
// between the previous and the new cursor are touched, so 10^3-10^4 edges stay interactive.
(function (R) {
  const $ = (id) => document.getElementById(id);
  const KIND_COL = { resolves: '#8a94a6', forces: '#e8590c', extends: '#2f9e44', implicit: '#7048e8', inlined: '#d6336c' };
  const W = 1000, H = 540;
  let G = null, G_keyOf = (x) => x;

  // Label propagation, deterministic: fixed visit order, ties to the smallest label, weights = summed edge weight.
  function communities(nodes, links) {
    const nb = new Map(nodes.map((n) => [n.id, new Map()]));
    links.forEach((l) => { const a = l.source.id ?? l.source, b = l.target.id ?? l.target; nb.get(a).set(b, (nb.get(a).get(b) || 0) + l.w); nb.get(b).set(a, (nb.get(b).get(a) || 0) + l.w); });
    const lab = new Map(nodes.map((n) => [n.id, n.id])), order = nodes.map((n) => n.id);
    for (let it = 0; it < 30; it++) {
      let ch = 0;
      for (const id of order) {
        const sc = new Map(); nb.get(id).forEach((w, o) => sc.set(lab.get(o), (sc.get(lab.get(o)) || 0) + w));
        let best = lab.get(id), bw = -1;
        [...sc].sort((x, y) => (x[0] < y[0] ? -1 : 1)).forEach(([l, w]) => { if (w > bw) { bw = w; best = l; } });
        if (best !== lab.get(id)) { lab.set(id, best); ch++; }
      }
      if (!ch) break;
    }
    const ids = [...new Set(lab.values())], idx = new Map(ids.map((l, i) => [l, i]));
    nodes.forEach((n) => (n.comm = idx.get(lab.get(n.id))));
    return ids.length;
  }
  const lowerBound = (a, f, x) => { let lo = 0, hi = a.length; while (lo < hi) { const m = (lo + hi) >> 1; if (f(a[m]) < x) lo = m + 1; else hi = m; } return lo; };

  function build() {
    const D = R.ST.D, host = $('graph'), level = R.ST.level, minw = Math.max(1, +$('minw').value || 1), t0 = performance.now();
    host.innerHTML = ''; G = null;
    if (!$('gdet').open) { $('legend').innerHTML = ''; return; }
    // file level: compiled syms by unit; classpath syms (no unit) grouped by package
    const keyOf = (s) => { if (level !== 'file') return s; const sy = D.syms.get(s); if (!sy) return s; if (sy.unit != null) return 'u' + sy.unit; const pk = D.pkgOf(s); return pk ? 'p:' + pk : s; };
    G_keyOf = keyOf;
    const agg = new Map();
    D.edges.forEach((e) => {
      if (e.from == null || e.to == null) return;
      const a = keyOf(e.from), b = keyOf(e.to); if (a === b && level === 'file') return;
      const k = e.kind + '|' + a + '|' + b; let r = agg.get(k);
      if (!r) agg.set(k, (r = { kind: e.kind, a, b, seq: e.seq, w: 0 }));
      r.w += e.weight || 1; if (e.seq < r.seq) r.seq = e.seq;
    });
    let flat = [...agg.values()].filter((r) => r.w >= minw);
    flat = R.depsFilter ? R.depsFilter(flat, keyOf) : flat;
    if (!flat.length) { host.innerHTML = '<div class="dim pad">no edge records' + (minw > 1 ? ' at this min weight' : '') + '</div>'; $('graphinfo').textContent = ''; return; }
    const nm = (k) => (typeof k === 'string' ? (k[0] === 'p' ? k.slice(2) + '.*' : ((D.units.get(+k.slice(1)) || {}).path || k).replace(/^.*\//, '')) : D.symName(k));
    const nodes = new Map();
    const node = (k) => { let n = nodes.get(k); if (!n) nodes.set(k, (n = { mem: new Set(), id: k, name: nm(k), deg: 0, first: Infinity, owner: typeof k === 'string' ? null : (D.syms.get(k) || {}).owner, ev: typeof k === 'string' ? 0 : (D.evBySym.get(k) || []).length })); return n; };
    flat.forEach((r) => { const s = node(r.a), t = node(r.b); s.deg += r.w; t.deg += r.w; s.first = Math.min(s.first, r.seq); t.first = Math.min(t.first, r.seq); r.s = s; r.t = t; });
    D.edges.forEach((e) => { if (e.from == null || e.to == null) return; const ka = keyOf(e.from), kb = keyOf(e.to); [[ka, e.from], [kb, e.to]].forEach(([k, sy]) => { const n = nodes.get(k); if (n && typeof k === 'string' && n.mem.size < 60) n.mem.add(D.symName(sy)); }); });
    const ns = [...nodes.values()].sort((a, b) => (String(a.id) < String(b.id) ? -1 : 1)), weighted = D.schema === 'v1';
    ns.forEach((n) => { n.count = weighted ? n.deg : n.ev; n.r = 3 + (weighted ? (ns.length > 150 ? 0.6 : 1.4) : 2.4) * Math.sqrt(n.count); if (n.r > 16) n.r = 16; });
    const pairs = new Map(); flat.forEach((r) => { const k = r.a + '|' + r.b; let p = pairs.get(k); if (!p) pairs.set(k, (p = { source: r.s, target: r.t, w: 0 })); p.w += r.w; });
    const links = [...pairs.values()];
    const nc = communities(ns, links);
    const N = ns.length, ticks = N < 150 ? 500 : N < 1000 ? 260 : 100;
    const sim = d3.forceSimulation(ns).randomSource(d3.randomLcg(42))
      .force('link', d3.forceLink(links).distance(N > 150 ? 26 : 34).strength(0.4)).force('charge', d3.forceManyBody().strength(N > 150 ? -70 : -95).distanceMax(300))
      .force('x', d3.forceX(W / 2).strength(0.06)).force('y', d3.forceY(H / 2).strength(0.09)).force('collide', d3.forceCollide((n) => n.r + 2)).stop();
    for (let i = 0; i < ticks; i++) sim.tick();       // computed once, then frozen
    const layoutMs = Math.round(performance.now() - t0);
    const xs = d3.extent(ns, (n) => n.x), ys = d3.extent(ns, (n) => n.y);
    const sc = Math.min((W - 60) / (xs[1] - xs[0] || 1), (H - 60) / (ys[1] - ys[0] || 1), N < 40 ? 3.2 : 1.6);
    ns.forEach((n) => { n.x = W / 2 + (n.x - (xs[0] + xs[1]) / 2) * sc; n.y = H / 2 + (n.y - (ys[0] + ys[1]) / 2) * sc; });

    const svg = d3.select(host).append('svg').attr('viewBox', `0 0 ${W} ${H}`).attr('preserveAspectRatio', 'xMidYMid meet');
    const defs = svg.append('defs'), many = flat.length > 1500;
    Object.entries(KIND_COL).forEach(([k, c]) => defs.append('marker').attr('id', 'ar-' + k).attr('viewBox', '0 -4 8 8').attr('refX', 8).attr('markerWidth', 5).attr('markerHeight', 5).attr('orient', 'auto').append('path').attr('d', 'M0,-4L8,0L0,4Z').attr('fill', c));
    const root = svg.append('g');
    svg.call(d3.zoom().scaleExtent([0.4, 12]).on('zoom', (e) => { root.attr('transform', e.transform); root.classed('zoomed', e.transform.k > 2.5); }));
    const col = (i) => d3.schemeTableau10[i % 10];
    const ownerCol = d3.scaleOrdinal(d3.schemeSet2);
    flat.sort((x, y) => x.seq - y.seq);
    // parallel edges of different kind between the same pair get different curvature
    const seenPair = new Map(); flat.forEach((r) => { const k = r.a + '|' + r.b; r.dup = seenPair.get(k) || 0; seenPair.set(k, r.dup + 1); });
    const path = (d) => {
      const dx = d.t.x - d.s.x, dy = d.t.y - d.s.y, len = Math.hypot(dx, dy) || 1, ux = dx / len, uy = dy / len;
      const x1 = d.s.x + ux * d.s.r, y1 = d.s.y + uy * d.s.r, x2 = d.t.x - ux * (d.t.r + 3), y2 = d.t.y - uy * (d.t.r + 3);
      if (many) return `M${x1},${y1}L${x2},${y2}`;
      const off = d.dup * 6, mx = (x1 + x2) / 2 - uy * (10 + off), my = (y1 + y2) / 2 + ux * (10 + off);
      return `M${x1},${y1}Q${mx},${my} ${x2},${y2}`;
    };
    const gE = root.append('g').attr('fill', 'none').selectAll('path').data(flat).join('path').attr('d', path).attr('stroke', (d) => KIND_COL[d.kind] || '#999').attr('stroke-width', 1.2).attr('stroke-opacity', 0);
    if (!many) gE.attr('marker-end', (d) => (KIND_COL[d.kind] ? `url(#ar-${d.kind})` : null)).append('title').text((d) => `${d.kind}: ${d.s.name} → ${d.t.name} · weight ${d.w} · first @seq ${d.seq}`);
    const gN = root.append('g').selectAll('g').data(ns).join('g').attr('transform', (d) => `translate(${d.x},${d.y})`).style('cursor', 'pointer').on('click', (ev, d) => select(d)).on('dblclick', (ev, d) => jump(d));
    gN.append('circle').attr('class', 'ring').attr('r', (d) => d.r + 5).attr('fill', 'none').attr('stroke', '#f08c00').attr('stroke-width', 2.5).attr('opacity', 0);
    gN.append('circle').attr('class', 'dot').attr('r', (d) => d.r).attr('fill', (d) => col(d.comm)).attr('stroke', (d) => (d.owner != null ? ownerCol(d.owner) : '#666')).attr('stroke-width', 2.2).attr('opacity', 0.22);
    gN.append('title').text((d) => `${d.name}\n${weighted ? 'incident weight ' + d.deg : d.ev + ' events'} · community ${d.comm}${d.owner != null ? ' · declared in ' + D.symName(d.owner) : ''}`);
    const labelled = new Set(ns.slice().sort((a, b) => b.count - a.count).slice(0, N > 120 ? 40 : N).map((n) => n.id));
    const gL = gN.append('text').attr('y', (d) => -d.r - 3).attr('text-anchor', 'middle').attr('font-size', 9).text((d) => d.name.replace(/\.\*$/, '').replace(/^.*\./, '').replace(/\$\d+$/, '') || d.name).attr('opacity', (d) => (labelled.has(d.id) ? 0.7 : 0));
    const order = ns.slice().sort((a, b) => a.first - b.first);
    G = { nodes: ns, flat, nc, gE, gN, gL, order, labelled, level, layoutMs, minw, weighted, eIdx: 0, nIdx: 0, recent: [], active: [], dotSel: gN.select('.dot'), ringSel: gN.select('.ring'), nodeEls: gN.nodes(), edgeEls: gE.nodes(), byId: new Map(ns.map((n) => [n.id, n])), pathMs: Math.round(performance.now() - t0) };
    G.adj = new Map(); flat.forEach((r) => { [[r.a, r.b, 'out', r.b], [r.b, r.a, 'in', r.a]].forEach(([k, , dir, other]) => { let l = G.adj.get(k); if (!l) G.adj.set(k, (l = [])); l.push({ other, dir, kind: r.kind, w: r.w, seq: r.seq }); }); });
    const lg = $('legend'); lg.innerHTML = '';
    Object.entries(KIND_COL).forEach(([k, c]) => lg.insertAdjacentHTML('beforeend', `<span class="lg"><i style="background:${c}"></i>${k}</span>`));
    lg.insertAdjacentHTML('beforeend', `<span class="dim"> · fill = community (${nc}), ring = declared owner, size = √${weighted ? 'incident weight' : 'events'} · layout ${layoutMs} ms for ${N} nodes / ${flat.length} edges</span>`);
  }

  // single click selects (no cursor move); double click jumps the cursor to the node's first event
  function select(d) {
    const num = typeof d.id === 'number';
    R.set({ gsel: d.id, gselFor: num ? d.id : null, selSym: num ? d.id : null, selNode: null });
  }
  function jump(d) {
    if (typeof d.id === 'string') { if (d.id[0] === 'u') { const u = +d.id.slice(1), l = R.evl(), i = l.findIndex((e) => e.unit === u); if (i >= 0) R.setEv(i, true); } else { const fe = G.adj.get(d.id); if (fe && fe.length) R.setEvBySeq(Math.min(...fe.map((x) => x.seq)), false); } return; }
    R.jumpToSym(d.id);
  }
  const selKey = () => { const st = R.ST; if (st.gsel != null && st.gselFor === st.selSym) return st.gsel; return st.selSym != null && G ? G_keyOf(st.selSym) : null; };
  // neighbours of the selected node as a table; ego focus; search
  function neighbours(key) {
    const box = $('neigh'); if (!G || key == null || !G.adj.has(key)) { box.innerHTML = '<span class="dim">Click a node to see its neighbours (double-click: jump the cursor there).</span>'; return; }
    const rows = G.adj.get(key).slice().sort((a, b) => b.w - a.w || a.seq - b.seq);
    const name = (k) => (G.byId.get(k) || {}).name || k;
    box.innerHTML = `<div class="dim">${esc(name(key))}: ${rows.length} edges</div><table class="ntab"><tr><th></th><th>node</th><th>kind</th><th>weight</th><th>first seq</th></tr>` +
      rows.slice(0, 300).map((r, i) => `<tr data-i="${i}" title="click: cursor to first seq; double-click: select that node"><td>${r.dir === 'out' ? '→' : '←'}</td><td>${esc(name(r.other))}</td><td>${esc(r.kind)}</td><td>${r.w}</td><td>${r.seq}</td></tr>`).join('') + '</table>';
    box.querySelectorAll('tr[data-i]').forEach((tr) => { const r = rows[+tr.dataset.i]; tr.onclick = () => R.setEvBySeq(r.seq, false); tr.ondblclick = () => { const d = G.byId.get(r.other); if (d) select(d); }; });
  }
  const esc = (x) => String(x).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
  function ego(key, hops) { const seen = new Set([key]); let fr = [key]; for (let h = 0; h < hops; h++) { const nx = []; fr.forEach((k) => (G.adj.get(k) || []).forEach((r) => { if (!seen.has(r.other)) { seen.add(r.other); nx.push(r.other); } })); fr = nx; } return seen; }
  function applyFocus(key) {
    const on = $('gfocus').checked && key != null && G.adj.has(key), sig = on ? key + '|' + $('ghops').value : '';
    if (G.focusSig === sig) return; G.focusSig = sig;
    const keep = on ? ego(key, +$('ghops').value) : null;
    G.nodeEls.forEach((el, i) => { el.style.display = !keep || keep.has(G.nodes[i].id) ? '' : 'none'; });
    G.edgeEls.forEach((el, i) => { const r = G.flat[i]; el.style.display = !keep || (keep.has(r.a) && keep.has(r.b)) ? '' : 'none'; });
  }
  function search() {
    const q = $('gsearch').value.trim().toLowerCase(), box = $('gmatches'); box.innerHTML = '';
    if (!G || !q) { if (G) G.gL.attr('font-weight', null); return; }
    const hit = (n) => n.name.toLowerCase().includes(q) || [...n.mem].some((x) => x.toLowerCase().includes(q));
    const m = G.nodes.filter(hit).sort((a, b) => b.count - a.count).slice(0, 8);
    m.forEach((n) => { const b = document.createElement('button'); const mm = n.mem.size && !n.name.toLowerCase().includes(q) ? [...n.mem].find((x) => x.toLowerCase().includes(q)) : null; b.textContent = n.name + (mm ? ' (' + mm + ')' : ''); b.onclick = () => select(n); box.append(b); });
    if (!m.length) box.innerHTML = '<span class="dim">no match</span>';
    const ids = new Set(G.nodes.filter(hit).map((n) => n.id));
    G.gL.attr('opacity', (d) => (ids.has(d.id) ? 1 : 0)).attr('font-weight', (d) => (ids.has(d.id) ? 700 : null));
  }

  // incremental update ----------------------------------------------------------------------------------------------------
  function render() {
    if (!G) return;
    const D = R.ST.D, seq = R.seq(), e = R.ev(), t0 = performance.now();
    const { flat, edgeEls, nodeEls, order } = G;
    const idx = lowerBound(flat, (r) => r.seq, seq + 1);                  // edges with seq <= cursor
    const setE = (i) => { const r = flat[i], el = edgeEls[i], vis = r.seq <= seq; el.setAttribute('stroke-opacity', vis ? (seq - r.seq < 4 ? 0.95 : 0.5) : 0); el.setAttribute('stroke-width', vis && seq - r.seq < 4 ? 2.4 : 1.2); };
    if (G.fresh !== false && !G.inited) { for (let i = 0; i < flat.length; i++) setE(i); G.inited = true; }
    else { const lo = Math.min(G.eIdx, idx), hi = Math.max(G.eIdx, idx); for (let i = lo; i < hi; i++) setE(i); }
    G.recent.forEach((i) => { if (i < flat.length) setE(i); });
    const rlo = lowerBound(flat, (r) => r.seq, seq - 3); G.recent = []; for (let i = rlo; i < idx; i++) { G.recent.push(i); setE(i); }
    G.eIdx = idx;
    // nodes: undim the ones first touched at or before the cursor
    const nidx = lowerBound(order, (n) => n.first, seq + 1), dots = G.dotSel.nodes();
    const idxOf = (n) => G.nodes.indexOf(n);
    if (!G.pos) G.pos = new Map(G.nodes.map((n, i) => [n.id, i]));
    if (!G.ninited) { dots.forEach((el) => el.setAttribute('opacity', 0.22)); G.ninited = true; G.nIdx = 0; }
    const lo = Math.min(G.nIdx, nidx), hi = Math.max(G.nIdx, nidx);
    for (let i = lo; i < hi; i++) dots[G.pos.get(order[i].id)].setAttribute('opacity', i < nidx ? 1 : 0.22);
    G.nIdx = nidx;
    // rings: cursor event syms (or their unit at file level) and the selected sym
    const rings = G.ringSel.nodes();
    G.active.forEach((i) => rings[i].setAttribute('opacity', 0));
    const keys = [];
    (e ? e.syms : []).forEach((s) => { const sy = D.syms.get(s); keys.push(G.level === 'file' && sy && sy.unit != null ? 'u' + sy.unit : s); });
    const sk = selKey(); if (sk != null) keys.push(sk);
    G.active = [];
    keys.forEach((k, j) => { const i = G.pos.get(k); if (i != null) { G.active.push(i); rings[i].setAttribute('opacity', j < (e ? e.syms.length : 0) ? 1 : 0.6); rings[i].setAttribute('stroke', j < (e ? e.syms.length : 0) ? '#f08c00' : '#1c7ed6'); } });
    const nk = sk != null ? sk + '|' + G.level : ''; G.neighKey = nk;
    applyFocus(sk);
    $('legendinfo') && 0; G.info = `${G.nodes.length} nodes · ${idx}/${flat.length} edges revealed at seq ${seq}`;
    R.lastGraphMs = Math.round(performance.now() - t0);
  }

  R.initViews = () => { /* the dependencies panel rebuilds the graph when its centre changes */ }; R.rebuildGraph = () => { build(); render(); };
  document.addEventListener('DOMContentLoaded', () => {
    $('level').onchange = (ev) => { R.set({ level: ev.target.value }); };
    $('minw').onchange = () => { R.render(); };
    $('gsearch').oninput = search; $('gsearch').onkeydown = (ev) => { if (ev.key === 'Enter') { const b = $('gmatches').querySelector('button'); if (b) b.click(); } };

  });
  R.views.push(render);
})(window.R);
