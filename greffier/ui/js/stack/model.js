// Stack overlay, data side: one card per shown tree version of the current unit, built from the viewer's recording object (no second
// load). Line diffs come from the shared diff.js (cached per version pair, also used by the phases table), stars from R.interesting().
// Rows are computed lazily per card (the overlay asks for them as cards come near), node lineage tracing is ported from the PoC.
(function (R) {
  const M = (R.stk = R.stk || {});

  // cards of the viewer's current version list (skip no-ops applies): tree versions only
  M.buildCards = function () {
    const D = R.ST.D, vs = R.vers().filter((v) => !v.irPseudo), star = new Set(R.interesting ? R.interesting().filter((x) => !x.v.irPseudo).map((x) => x.v) : []);
    const cards = vs.map((v, i) => {
      const prev = i > 0 ? vs[i - 1] : null;
      // cheap no-op test from the header (the printed text is the same as an earlier version and nothing new); v0 data: compare text
      const noop = !!prev && (v.hdrNew != null && D.schema === 'v1' ? v.hdrNew === 0 && v.textSameAs != null : v.text === prev.text);
      return { i, v, phase: v.phase, name: D.phaseName(v.phase), short: D.phaseShort(v.phase), noop, star: star.has(v), prevV: prev, ready: false, add: 0, del: 0, rows: null };
    });
    return cards;
  };

  // lazily: lines, rows (diff against the previous card), row index per line; with "structural only" added lines that carry no structural
  // new node are weak (muted, not counted)
  M.ensureRows = function (c) {
    if (c.ready) return true;
    const D = R.ST.D, v = c.v, lines = R.linesOf(v), d = R.versionDiff(v, c.noop ? null : c.prevV);
    c.lines = lines; c.lineStart = [0]; for (let k = 0, p = 0; k < lines.length - 1; k++) { p += lines[k].length + 1; c.lineStart.push(p); }
    c.rows = d.rows; c.add = d.add; c.del = d.del;
    if (R.ST.structOnly && c.prevV && !c.noop && d.add) {
      const prev = c.prevV, off = v.byId, struct = new Set(), fake = v;
      v.ids.forEach((n) => { if (!prev.ids.has(n) && R._s.classOf(D, n, fake) === 'struct') { const o = off.get(n); if (o) struct.add(M.lineOf(c, o[1])); } });
      c.rows = d.rows.map((r) => (r.k === 'a' && !struct.has(r.nl) ? { k: 'a', t: r.t, nl: r.nl, w: true } : r)); c.add = c.rows.reduce((a, r) => a + (r.k === 'a' && !r.w ? 1 : 0), 0);
    }
    c.rowOfLine = new Int32Array(lines.length); c.rows.forEach((r, k) => { if (r.nl >= 0) c.rowOfLine[r.nl] = k; });
    c.score = c.add + c.del; c.ready = true; return true;
  };
  M.lineOf = (c, pos) => { let lo = 0, hi = c.lineStart.length - 1; while (lo < hi) { const m = (lo + hi + 1) >> 1; if (c.lineStart[m] <= pos) lo = m; else hi = m - 1; } return lo; };

  // node under a character offset (innermost span), else the outermost node starting on the line
  M.nodeAt = function (c, line, pos) {
    const off = c.v.offsets; let best = null;
    if (pos != null) for (const o of off) if (o[1] <= pos && pos < o[2] && (!best || o[2] - o[1] < best[2] - best[1])) best = o;
    if (best) return best[0];
    const s = c.lineStart[line], e = s + c.lines[line].length;
    for (const o of off) if (o[1] >= s && o[1] < e && (!best || o[2] - o[1] > best[2] - best[1])) best = o;
    if (best) return best[0];
    const ind = s + (c.lines[line].length - c.lines[line].trimStart().length);
    for (const o of off) if (o[1] <= ind && ind < o[2] && (!best || o[2] - o[1] < best[2] - best[1])) best = o;
    return best ? best[0] : null;
  };

  const RANK = { same: 0, copy: 1, retype: 2, rewrite: 3, split: 3, synth: 3, elide: 3 };
  function step(D, set, to, back) {
    const map = back ? D.deriveByNew : D.deriveByOld, side = back ? 'old' : 'new', out = new Set(); let how = 'same';
    for (const m of set) {
      if (to.v.ids.has(m)) { out.add(m); continue; }
      const q = [m], seen = new Set(q);
      for (let qi = 0; qi < q.length && qi < 300; qi++) {
        for (const d of map.get(q[qi]) || []) {
          const h = d.how === 'copy' ? 'copy' : d.how === 'retype' ? 'retype' : 'rewrite'; if (RANK[h] > RANK[how]) how = h;
          for (const o of d[side]) { if (seen.has(o)) continue; seen.add(o); if (to.v.ids.has(o)) out.add(o); else q.push(o); }
        }
      }
      if (out.size > 80) break;
    }
    return { out, how };
  }
  // trace node n of card j through all cards: per card the node set, primary node (earliest start), its line/row and how it got there
  M.trace = function (D, cards, j, n) {
    cards.forEach((c) => M.ensureRows(c));
    const N = cards.length, sets = new Array(N).fill(null), how = new Array(N).fill(null);
    sets[j] = new Set([n]);
    for (let i = j - 1; i >= 0; i--) { if (!sets[i + 1].size) { sets[i] = new Set(); continue; } const r = step(D, sets[i + 1], cards[i], true); sets[i] = r.out; how[i + 1] = r.how; }
    for (let i = j + 1; i < N; i++) { if (!sets[i - 1].size) { sets[i] = new Set(); continue; } const r = step(D, sets[i - 1], cards[i], false); sets[i] = r.out; how[i] = r.how; }
    const res = cards.map((c, i) => {
      const s = sets[i]; if (!s.size) return { set: s, primary: null, line: -1, row: -1, how: i > 0 && sets[i - 1].size ? 'gone' : null };
      let pr = null; for (const id of s) { const o = c.v.byId.get(id); if (o && (!pr || o[1] < pr[1])) pr = o; }
      const line = pr ? M.lineOf(c, pr[1]) : -1, rows = new Set(); for (const id of s) { const o = c.v.byId.get(id); if (o) rows.add(c.rowOfLine[M.lineOf(c, o[1])]); }
      return { set: s, primary: pr ? pr[0] : null, line, row: line >= 0 ? c.rowOfLine[line] : -1, rows, how: i === 0 || !sets[i - 1].size ? 'born' : how[i] };
    });
    for (let i = 1; i < N; i++) { const a = res[i - 1], b = res[i]; if (b.line >= 0 && a.line >= 0 && (b.how === 'same' || b.how === 'copy') && cards[i].lines[b.line].trim() !== cards[i - 1].lines[a.line].trim()) b.how = 'edit'; }
    const nd = D.nodes.get(n); return { node: n, kind: nd ? nd.kind : '?', from: j, per: res };
  };
})(window.R);
