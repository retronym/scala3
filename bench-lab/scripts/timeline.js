// A benchmark fork's timeline: its iterations (coloured by variant when variants alternate within the fork) and, on the
// same wall-clock axis, what the machine did meanwhile. Reads a cell's files as written by the fork and ab.py:
//   iterations.tsv  every iteration as it finishes: index, kind, variant, start_ms, end_ms, score, ops, gc_ms
//   timeline.tsv    per 50 ms: cycles and instructions of the benchmark thread, JIT, GC, other threads of the fork and
//                   other processes (from xctrace, so only once the fork has finished)
//   gc.log          -Xlog:gc with wall-clock `time` decorations (ab.py's "gcLog"): pauses
//   load.tsv        ab.py's 5 s samples of other processes' CPU, in cores
// Used by timeline.py (a page per cell) and abindex.py (a live panel in index.html).
const BenchTimeline = (() => {
  const NS = 'http://www.w3.org/2000/svg';
  const BIN_MS = 50;

  function tsv(text) {
    if (!text) return [];
    const lines = text.split('\n').filter(l => l.trim());
    if (!lines.length) return [];
    const head = lines[0].split('\t');
    return lines.slice(1).map(l => Object.fromEntries(l.split('\t').map((v, i) => [head[i], v])));
  }

  function parse(f) {
    const iters = tsv(f.iterations).map(r => ({i: +r.index, kind: r.kind, variant: r.variant, s: +r.start_ms, e: +r.end_ms,
      score: +r.score, gc: r.gc_ms === '' || r.gc_ms == null ? null : +r.gc_ms}));
    const series = tsv(f.timeline).map(b => ({t: +b.bin_ms, bench_c: +b.bench_cycles, bench_i: +b.bench_inst, jit_c: +b.jit_cycles,
      gc_c: +b.gc_cycles, other_c: +b.other_cycles, processes_c: +b.processes_cycles}));
    const pauses = [];
    for (const line of (f.gc || '').split('\n')) {
      const m = line.match(/^\[([^\]]+)\]\[[^\]]+\].*?(Pause[^(]*(?:\([^)]*\))*).*?([\d.]+)ms\s*$/);
      if (!m) continue;
      const end = Date.parse(m[1].replace(/([+-]\d\d)(\d\d)$/, '$1:$2'));
      if (!isNaN(end)) pauses.push({s: end - +m[3], e: end, what: m[2].trim()});
    }
    const load = (f.load || '').split('\n').map(l => l.split('\t')).filter(p => p.length >= 2 && p[0])
      .map(p => ({t: +p[0] * 1000, cores: +p[1], top: p[2] || ''}));
    return {iters, series, pauses, load};
  }

  async function fetchCell(base) {
    const get = async n => { try { const r = await fetch(base + n, {cache: 'no-store'}); return r.ok ? await r.text() : ''; } catch (e) { return ''; } };
    const [iterations, timeline, gc, load] = await Promise.all(['iterations.tsv', 'timeline.tsv', 'gc.log', 'load.tsv'].map(get));
    return parse({iterations, timeline, gc, load});
  }

  function mount(svg, legend, tip) {
    let D = {iters: [], series: [], pauses: [], load: []}, t0 = 0, t1 = 1, x0 = 0, x1 = 1, zoomed = false;
    const L = 150, R = 16;
    const rows = [
      {name: 'iterations', h: 46},
      {name: 'benchmark thread', h: 80, key: 'bench_c', unit: 'G cycles/s'},
      {name: 'IPC (benchmark)', h: 56, ipc: true},
      {name: 'GC threads', h: 70, key: 'gc_c', unit: 'G cycles/s', pauses: true},
      {name: 'JIT threads', h: 56, key: 'jit_c', unit: 'G cycles/s'},
      {name: 'other in fork', h: 46, key: 'other_c', unit: 'G cycles/s'},
      {name: 'other processes', h: 86, key: 'processes_c', unit: 'G cycles/s', load: true},
    ];
    const height = rows.reduce((a, r) => a + r.h + 8, 8) + 24;
    svg.setAttribute('height', height);
    let variants = [];
    const color = v => v ? `var(--v${variants.indexOf(v) % 6})` : 'var(--v0)';
    function el(tag, attrs, parent) { const e = document.createElementNS(NS, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); (parent || svg).appendChild(e); return e; }

    function draw() {
      svg.innerHTML = '';
      const W = svg.clientWidth || 900, X = t => L + (t - x0) / (x1 - x0) * (W - L - R);
      if (!D.iters.length && !D.series.length) {
        svg.setAttribute('height', 40);
        el('text', {x: 12, y: 24, class: 'lab'}).textContent = 'No iterations yet.';
        return;
      }
      svg.setAttribute('height', height);
      let y = 8;
      const span = x1 - x0, step = [100, 200, 500, 1000, 2000, 5000, 10000, 20000, 60000].find(s => span / s < 12) || 120000;
      for (let t = Math.ceil((x0 - t0) / step) * step; t0 + t <= x1; t += step) {
        el('line', {x1: X(t0 + t), x2: X(t0 + t), y1: 0, y2: height - 22, class: 'grid'});
        el('text', {x: X(t0 + t) + 2, y: height - 8, class: 'lab'}).textContent = (t / 1000).toFixed(step < 1000 ? 1 : 0) + ' s';
      }
      for (const r of rows) {
        el('text', {x: 6, y: y + 14}).textContent = r.name;
        if (r.unit) el('text', {x: 6, y: y + 28, class: 'lab'}).textContent = r.unit;
        el('line', {x1: L, x2: W - R, y1: y + r.h, y2: y + r.h, class: 'grid'});
        if (r.name === 'iterations') {
          for (const it of D.iters) {
            if (it.e < x0 || it.s > x1) continue;
            const a = Math.max(X(it.s), L), b = Math.min(X(it.e), W - R);
            const rect = el('rect', {x: a, y: y + 6, width: Math.max(1, b - a - 1), height: r.h - 12, rx: 2,
              fill: it.kind === 'warmup' ? 'var(--grey)' : color(it.variant)});
            rect.dataset.tip = `#${it.i} ${it.kind} ${it.variant || ''}: ${it.score.toFixed(1)} ms/op` + (it.gc != null ? `, GC pauses ${it.gc} ms` : '');
            if (b - a > 46) el('text', {x: a + 4, y: y + r.h / 2 + 4}).textContent = it.score.toFixed(1);
          }
        } else if (!D.series.length) {
          if (r.name === 'benchmark thread') el('text', {x: L + 6, y: y + 20, class: 'lab'}).textContent = 'counters appear when the fork has finished (xctrace export)';
        } else {
          const pts = r.ipc ? D.series.map(s => ({t: s.t, v: s.bench_c ? s.bench_i / s.bench_c : null}))
                            : D.series.map(s => ({t: s.t, v: s[r.key] / (BIN_MS / 1000) / 1e9}));
          const vis = pts.filter(p => p.t >= x0 - BIN_MS && p.t <= x1 && p.v != null);
          const max = r.ipc ? Math.max(4, ...vis.map(p => p.v)) : Math.max(0.5, ...vis.map(p => p.v));
          const Y = v => y + r.h - 4 - v / max * (r.h - 10);
          el('text', {x: L - 4, y: y + 12, 'text-anchor': 'end', class: 'lab'}).textContent = max.toFixed(1);
          let d = '';
          for (const p of vis) d += (d ? 'L' : 'M') + X(p.t + BIN_MS / 2).toFixed(1) + ',' + Y(p.v).toFixed(1);
          if (d) el('path', {d, fill: 'none', stroke: r.ipc ? 'var(--v2)' : 'var(--v0)', 'stroke-width': 1.2});
        }
        if (r.pauses) for (const p of D.pauses) {
          if (p.e < x0 || p.s > x1) continue;
          const m = el('rect', {x: X(p.s), y: y + 2, width: Math.max(2, X(p.e) - X(p.s)), height: r.h - 4, fill: 'var(--pause)', opacity: 0.55});
          m.dataset.tip = `${p.what}: ${(p.e - p.s).toFixed(1)} ms`;
        }
        if (r.load && D.load.length) {
          const lmax = Math.max(2, ...D.load.map(l => l.cores));
          el('text', {x: W - R - 2, y: y + 12, 'text-anchor': 'end', class: 'lab'}).textContent = `load.tsv: other processes, cores (max ${lmax.toFixed(1)})`;
          let ld = '';
          for (const l of D.load) {
            if (l.t < x0 - 5000 || l.t > x1 + 5000) continue;
            const yy = y + r.h - 4 - l.cores / lmax * (r.h - 10);
            const clamp = x => Math.min(Math.max(x, L), W - R);
            ld += (ld ? 'L' : 'M') + clamp(X(l.t - 5000)).toFixed(1) + ',' + yy.toFixed(1) + 'L' + clamp(X(l.t)).toFixed(1) + ',' + yy.toFixed(1);
          }
          if (ld) el('path', {d: ld, fill: 'none', stroke: 'var(--v1)', 'stroke-width': 1.2, 'stroke-dasharray': '4 3'});
        }
        y += r.h + 8;
      }
    }

    function set(data) {
      D = data;
      variants = [...new Set(D.iters.map(i => i.variant).filter(v => v))];
      const ts = [...D.iters.flatMap(i => [i.s, i.e]), ...D.series.map(s => s.t), ...D.series.map(s => s.t + BIN_MS)];
      const n0 = ts.length ? Math.min(...ts) : 0, n1 = ts.length ? Math.max(...ts) : 1;
      // keep a zoomed view where it is as data arrives; otherwise follow the whole range
      if (!zoomed) { x0 = n0; x1 = Math.max(n1, n0 + 1); }
      t0 = n0; t1 = Math.max(n1, n0 + 1);
      if (legend) legend.innerHTML = variants.map(v => `<span><i style="background:${color(v)}"></i>${v}</span>`).join('')
        + '<span><i style="background:var(--grey)"></i>warmup</span>' + (D.pauses.length ? '<span><i style="background:var(--pause)"></i>GC pause</span>' : '');
      draw();
    }

    svg.addEventListener('wheel', e => { e.preventDefault(); const W = svg.clientWidth, f = Math.exp(e.deltaY * 0.002);
      const at = x0 + (e.offsetX - L) / (W - L - R) * (x1 - x0); x0 = Math.max(t0, at - (at - x0) * f); x1 = Math.min(t1, at + (x1 - at) * f); zoomed = true; draw(); }, {passive: false});
    let drag = null;
    svg.addEventListener('pointerdown', e => { drag = {x: e.clientX, x0, x1}; svg.setPointerCapture(e.pointerId); });
    svg.addEventListener('pointermove', e => {
      const t = e.target.dataset && e.target.dataset.tip;
      if (tip && t && !drag) { tip.textContent = t; tip.style.display = 'block'; tip.style.left = (e.clientX + 12) + 'px'; tip.style.top = (e.clientY + 12) + 'px'; }
      else if (tip && !drag) tip.style.display = 'none';
      if (!drag) return;
      const W = svg.clientWidth, dt = (e.clientX - drag.x) / (W - L - R) * (drag.x1 - drag.x0), w = drag.x1 - drag.x0;
      x0 = Math.min(Math.max(t0, drag.x0 - dt), t1 - w); x1 = x0 + w; zoomed = true; draw();
    });
    svg.addEventListener('pointerup', () => drag = null);
    svg.addEventListener('dblclick', () => { zoomed = false; x0 = t0; x1 = t1; draw(); });
    window.addEventListener('resize', draw);
    return {set, reset() { zoomed = false; }};
  }

  return {parse, fetchCell, mount};
})();
