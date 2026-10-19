// Data model for the stack view: one "card" per recorded tree version of a unit, with its printed text, a line diff against the
// previous version (rows: same / added / removed-ghost), node offsets, and node lineage traced through all cards.
// Parsing is done by the existing viewer's loader (../ui/js/loader.js, a classic script that defines window.R).
const R = window.R;

export async function loadUrl(url, onProgress) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
  const L = R.createLoader({ lazy: true });
  await R.streamInto(L, url.split('/').pop(), res.body, onProgress, +res.headers.get('content-length') || 0);
  return L.finish();
}

export async function loadFile(file, onProgress) {
  const L = R.createLoader({ lazy: true });
  await R.streamInto(L, file.name, file.stream(), onProgress, file.size);
  return L.finish();
}

export function unitLabel(D, u) {
  const m = D.units.get(u); const p = m && m.path ? m.path : 'unit ' + u;
  return p.split('/').pop();
}

// ---- line diff (LCS on the middle part after common prefix/suffix) --------------------------------------------------
function diffLines(A, B) {
  const rows = [];
  let p = 0; while (p < A.length && p < B.length && A[p] === B[p]) p++;
  let ea = A.length, eb = B.length; while (ea > p && eb > p && A[ea - 1] === B[eb - 1]) { ea--; eb--; }
  for (let i = 0; i < p; i++) rows.push({ k: 's', t: B[i], nl: i });
  const n = ea - p, m = eb - p;
  if (n * m > 4e6) {
    for (let i = 0; i < n; i++) rows.push({ k: 'd', t: A[p + i], nl: -1 });
    for (let j = 0; j < m; j++) rows.push({ k: 'a', t: B[p + j], nl: p + j });
  } else if (n || m) {
    const W = m + 1, T = new Uint16Array((n + 1) * W);
    for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--)
      T[i * W + j] = A[p + i] === B[p + j] ? T[(i + 1) * W + j + 1] + 1 : Math.max(T[(i + 1) * W + j], T[i * W + j + 1]);
    let i = 0, j = 0;
    while (i < n || j < m) {
      if (i < n && j < m && A[p + i] === B[p + j]) { rows.push({ k: 's', t: B[p + j], nl: p + j }); i++; j++; }
      else if (i < n && (j >= m || T[(i + 1) * W + j] >= T[i * W + j + 1])) { rows.push({ k: 'd', t: A[p + i], nl: -1 }); i++; }
      else { rows.push({ k: 'a', t: B[p + j], nl: p + j }); j++; }
    }
  }
  for (let i = eb; i < B.length; i++) rows.push({ k: 's', t: B[i], nl: i });
  return rows;
}

// ---- cards ------------------------------------------------------------------------------------------------------------
export function buildCards(D, unit) {
  const t0 = performance.now();
  const vs = D.versionsOf(unit).filter((v) => !v.ir);
  const cards = [];
  let prev = null;
  vs.forEach((v, i) => {
    const text = v.text || '';
    const lines = text.split('\n');
    const off = v.offsets.map((o) => [o[0], o[1], o[2]]);
    const c = { i, v, phase: v.phase, name: D.phaseName(v.phase), short: D.phaseShort(v.phase), text, lines, off,
      noop: !!prev && text === prev.text, star: false, add: 0, del: 0, score: 0 };
    c.lineStart = [0]; for (let k = 0, p = 0; k < lines.length - 1; k++) { p += lines[k].length + 1; c.lineStart.push(p); }
    c.rows = !prev || c.noop ? lines.map((t, k) => ({ k: 's', t, nl: k })) : diffLines(prev.lines, lines);
    c.rowOfLine = new Int32Array(lines.length); c.rows.forEach((r, k) => { if (r.nl >= 0) c.rowOfLine[r.nl] = k; else c.del++; if (r.k === 'a') c.add++; });
    c.score = c.add + c.del + 0.25 * (D.nonCopyNew.get(unit + ':' + v.phase) || 0);
    cards.push(c); prev = c;
  });
  // ★: the most changed versions (as in the flat viewer: top few by change score)
  cards.slice(1).filter((c) => c.score > 0).sort((a, b) => b.score - a.score).slice(0, 6).forEach((c) => { c.star = true; });
  return { cards, ms: performance.now() - t0 };
}

function ensureIds(c) {
  if (c.ids) return;
  c.ids = new Set(); c.byId = new Map();
  c.off.forEach((o) => { c.ids.add(o[0]); c.byId.set(o[0], o); });
}

export const lineOf = (c, pos) => { let lo = 0, hi = c.lineStart.length - 1; while (lo < hi) { const m = (lo + hi + 1) >> 1; if (c.lineStart[m] <= pos) lo = m; else hi = m - 1; } return lo; };

// Node under a character offset (innermost span), else the outermost node starting on the line.
export function nodeAt(c, line, pos) {
  let best = null;
  if (pos != null) for (const o of c.off) if (o[1] <= pos && pos < o[2] && (!best || o[2] - o[1] < best[2] - best[1])) best = o;
  if (best) return best[0];
  const s = c.lineStart[line], e = s + c.lines[line].length;
  for (const o of c.off) if (o[1] >= s && o[1] < e && (!best || o[2] - o[1] > best[2] - best[1])) best = o;
  if (best) return best[0];
  const ind = s + (c.lines[line].length - c.lines[line].trimStart().length);
  for (const o of c.off) if (o[1] <= ind && ind < o[2] && (!best || o[2] - o[1] < best[2] - best[1])) best = o;
  return best ? best[0] : null;
}

const RANK = { same: 0, copy: 1, retype: 2, rewrite: 3, split: 3, synth: 3, elide: 3 };
// One step of lineage from the node set of one card to the next/previous card: a node id present in both carries over,
// otherwise follow derive records (backward: new -> old, forward: old -> new) until reaching nodes of the target card.
function step(D, set, to, back) {
  const map = back ? D.deriveByNew : D.deriveByOld, side = back ? 'old' : 'new';
  const out = new Set(); let how = 'same';
  for (const m of set) {
    if (to.ids.has(m)) { out.add(m); continue; }
    const q = [m], seen = new Set(q);
    for (let qi = 0; qi < q.length && qi < 300; qi++) {
      for (const d of map.get(q[qi]) || []) {
        const h = d.how === 'copy' ? 'copy' : d.how === 'retype' ? 'retype' : 'rewrite';
        if (RANK[h] > RANK[how]) how = h;
        for (const o of d[side]) { if (seen.has(o)) continue; seen.add(o); if (to.ids.has(o)) out.add(o); else q.push(o); }
      }
    }
    if (out.size > 80) break;
  }
  return { out, how };
}

// Trace node n of card j through all cards. Per card: node set, primary node (earliest start), its line/row, and how it got
// there from the previous card: born / same / copy / retype / rewrite / edit (same node, printed line changed) / gone.
export function trace(D, cards, j, n) {
  cards.forEach(ensureIds);
  const N = cards.length, sets = new Array(N).fill(null), how = new Array(N).fill(null);
  sets[j] = new Set([n]);
  for (let i = j - 1; i >= 0; i--) {
    if (!sets[i + 1].size) { sets[i] = new Set(); continue; }
    const r = step(D, sets[i + 1], cards[i], true); sets[i] = r.out; how[i + 1] = r.how;
  }
  for (let i = j + 1; i < N; i++) {
    if (!sets[i - 1].size) { sets[i] = new Set(); continue; }
    const r = step(D, sets[i - 1], cards[i], false); sets[i] = r.out; how[i] = r.how;
  }
  const res = cards.map((c, i) => {
    const s = sets[i]; if (!s.size) return { set: s, primary: null, line: -1, row: -1, how: i > 0 && sets[i - 1].size ? 'gone' : null };
    let pr = null; for (const id of s) { const o = c.byId.get(id); if (o && (!pr || o[1] < pr[1])) pr = o; }
    const line = pr ? lineOf(c, pr[1]) : -1;
    const rows = new Set(); for (const id of s) { const o = c.byId.get(id); if (o) rows.add(c.rowOfLine[lineOf(c, o[1])]); }
    return { set: s, primary: pr ? pr[0] : null, line, row: line >= 0 ? c.rowOfLine[line] : -1, rows, how: i === 0 || !sets[i - 1].size ? 'born' : how[i] };
  });
  for (let i = 1; i < N; i++) {
    const a = res[i - 1], b = res[i];
    if (b.line >= 0 && a.line >= 0 && (b.how === 'same' || b.how === 'copy') && cards[i].lines[b.line].trim() !== cards[i - 1].lines[a.line].trim()) b.how = 'edit';
  }
  const nd = D.nodes.get(n);
  return { node: n, kind: nd ? nd.kind : '?', from: j, per: res };
}
