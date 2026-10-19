// Stack overlay, data side: one card per shown tree version of the current unit, built from the viewer's recording object (no second
// load). Line diffs come from the shared diff.js (cached per version pair, also used by the phases table), stars from R.interesting().
// Rows are computed lazily per card (the overlay asks for them as cards come near), node lineage tracing is ported from the PoC.
(function (R) {
  const M = (R.stk = R.stk || {});

  const isOp = (t) => /^\s+[A-Z][A-Z0-9_]+\b/.test(t) && !/^\s+(LINENUMBER|FRAME|LOCALVARIABLE|MAXSTACK|MAXLOCALS)\b/.test(t);
  // cards of the viewer's current version list (skip no-ops applies): the tree versions, then the JVM class versions of the chosen class
  // (genBCode, then every jvm:* phase that changed that class). A version with at most 2 changed lines counts as a no-op (the shared line
  // counts of the heat strip decide, once computed) unless ST.trivialNoop is off.
  M.buildCards = function () {
    const D = R.ST.D, vs = R.vers().filter((v) => !v.irPseudo), star = new Set(R.stars ? R.stars().filter((x) => !x.v.irPseudo).map((x) => x.v) : []);
    const lines = R.scoreState && R.scoreState.lines, trivial = R.ST.trivialNoop !== false;
    const cards = vs.map((v, i) => {
      const prev = i > 0 ? vs[i - 1] : null;
      // cheap no-op test from the header (the printed text is the same as an earlier version and nothing new); v0 data: compare text
      let noop = !!prev && (v.hdrNew != null && D.schema === 'v1' ? v.hdrNew === 0 && v.textSameAs != null : v.text === prev.text);
      if (!noop && trivial && prev && lines) { const l = lines.get(R.ST.unit + ':' + v.phase); if (l && l.add + l.del <= 2) noop = true; }
      return { i, v, kind: 'tree', phase: v.phase, name: D.phaseName(v.phase) + (i === 0 ? ' (printed tree)' : ''), short: D.phaseShort(v.phase) + (i === 0 ? ' (printed tree)' : ''), noop, star: star.has(v), first: i === 0, prevV: prev, ready: false, add: 0, del: 0, rows: null };
    });
    const cls = D.hasIR && R.curClass && R.curClass(), c = cls && D.jvmClasses.get(cls);
    if (c && c.unit === R.ST.unit) {
      const irStar = new Set(R.stars ? R.stars().filter((x) => x.v.irPseudo).map((x) => x.v.phase) : []);
      c.versions.forEach((v, k) => {
        const nm = v.phase === D.irGenPhase ? 'genBCode (bytecode)' : D.phaseRaw(v.phase);
        cards.push({ i: cards.length, v, kind: 'ir', cls, phase: v.phase, name: nm, short: nm, noop: false, star: irStar.has(v.phase) || k === 0, first: k === 0, prevV: k > 0 ? c.versions[k - 1] : null, ready: false, add: 0, del: 0, rows: null });
      });
    }
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
    if (c.kind === 'ir') {   // instructions added / removed; rows of methods this step did not touch are muted
      let add = 0, del = 0; c.rows.forEach((r) => { if (r.k !== 's' && isOp(r.t)) { if (r.k === 'a') add++; else del++; } }); c.add = add; c.del = del; c.ops = lines.filter(isOp).length;
      if (!c.first) {
        const mOf = new Int32Array(lines.length).fill(-1); let mi = 0;
        v.offsets.forEach(([id, st, en]) => { const nd = D.nodes.get(id); if (nd && nd.kind === 'MethodNode') { const a = M.lineOf(c, st), b = M.lineOf(c, Math.max(st, en - 1)); for (let l = a; l <= b && l < lines.length; l++) mOf[l] = mi; mi++; } });
        const changed = new Set(); let carry = 0; c.rows.forEach((r) => { if (r.nl >= 0) carry = r.nl; if (r.k !== 's' && mOf[carry] >= 0) changed.add(mOf[carry]); });
        carry = 0; c.rows = c.rows.map((r) => { if (r.nl >= 0) carry = r.nl; return r.k === 's' && mOf[carry] >= 0 && !changed.has(mOf[carry]) ? { k: 's', t: r.t, nl: r.nl, mu: true } : r; });
      }
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

  const RANK = { same: 0, copy: 1, retype: 2, rewrite: 3, split: 3, synth: 3, elide: 3, 'inlined-from': 3 };
  // one step of lineage between the node sets of two cards: ids present in both carry over, else follow derive records (tree: D.derive*, bytecode: D.ir*)
  function step(D, set, to, back, ir) {
    const map = ir ? (back ? D.irByNew : D.irByOld) : (back ? D.deriveByNew : D.deriveByOld), side = back ? 'old' : 'new', out = new Set(); let how = 'same';
    for (const m of set) {
      if (to.v.ids.has(m)) { out.add(m); continue; }
      const q = [m], seen = new Set(q);
      for (let qi = 0; qi < q.length && qi < 300; qi++) {
        for (const d of map.get(q[qi]) || []) {
          if (ir && d.how === 'codegen') continue;
          const h = d.how === 'copy' ? 'copy' : d.how === 'retype' ? 'retype' : 'rewrite'; if (RANK[h] > RANK[how]) how = h;
          for (const o of d[side]) { if (seen.has(o)) continue; seen.add(o); if (to.v.ids.has(o)) out.add(o); else q.push(o); }
        }
      }
      if (out.size > 80) break;
    }
    return { out, how };
  }
  // trace node n of card j through all cards. Tree cards follow derive records; the thread continues into the JVM cards through codegen
  // derives (tree node -> InsnRange) and back (instruction -> tree node). Per card: node set, primary node, its line/row, how it got there.
  M.trace = function (D, cards, j, n) { return traceSet(D, cards, j, n instanceof Set ? n : new Set([n]), n instanceof Set); };
  function traceSet(D, cards, j, start, multi) {
    cards.forEach((c) => M.ensureRows(c));
    const N = cards.length, Nt = cards.findIndex((c) => c.kind === 'ir') < 0 ? N : cards.findIndex((c) => c.kind === 'ir');
    const sets = new Array(N).fill(null), how = new Array(N).fill(null), E = () => new Set(); let via = null, switchClass = null;
    sets[j] = new Set(start); const n = multi ? null : [...start][0];
    const backTree = (from) => { for (let i = from; i >= 0; i--) { if (!sets[i + 1].size) { sets[i] = E(); continue; } const r = step(D, sets[i + 1], cards[i], true, false); sets[i] = r.out; how[i + 1] = r.how; } };
    const fwdIR = (from) => { for (let i = from; i < N; i++) { if (!sets[i - 1].size) { sets[i] = E(); continue; } const r = step(D, sets[i - 1], cards[i], false, true); sets[i] = r.out; how[i] = r.how; } };
    if (j < Nt) {
      backTree(j - 1);
      for (let i = j + 1; i < Nt; i++) { if (!sets[i - 1].size) { sets[i] = E(); continue; } const r = step(D, sets[i - 1], cards[i], false, false); sets[i] = r.out; how[i] = r.how; }
      if (Nt < N) {   // into bytecode: the codegen ranges of the final tree nodes in this class; a node without one climbs to the nearest ancestor that has one
        const gen = E(), lastV = Nt > 0 ? cards[Nt - 1].v : null, cv = cards[Nt].v;
        const rangesOf = (t, ok) => (D.irFromTree.get(t) || []).filter(ok);
        if (lastV) sets[Nt - 1].forEach((t) => {
          let x = t, hops = 0, r = rangesOf(x, (y) => cv.ids.has(y));
          while (!r.length && hops < 40) { x = R.parentOf(lastV, x); if (x == null) break; hops++; r = rangesOf(x, (y) => cv.ids.has(y)); }
          if (r.length) { r.forEach((y) => gen.add(y)); if (x !== t) { via = via || (D.nodes.get(x) || {}).kind; } return; }
          // not in this class: find where the range lives and ask the overlay to switch the JVM segment's class
          x = t; hops = 0; let any = rangesOf(x, () => true);
          while (!any.length && hops < 40) { x = R.parentOf(lastV, x); if (x == null) break; hops++; any = rangesOf(x, () => true); }
          if (any.length && !switchClass) { switchClass = D.irClassOf.get(any[0]) || null; if (x !== t) via = (D.nodes.get(x) || {}).kind; }
        });
        sets[Nt] = gen; how[Nt] = 'born'; fwdIR(Nt + 1);
      }
    } else {   // started on an instruction: back through the JVM cards, then through codegen to the tree
      for (let i = j - 1; i >= Nt; i--) { if (!sets[i + 1].size) { sets[i] = E(); continue; } const r = step(D, sets[i + 1], cards[i], true, true); sets[i] = r.out; how[i + 1] = r.how; }
      fwdIR(j + 1);
      if (Nt > 0) { const t = E(); (sets[Nt] || []).forEach((x) => (D.irToTree.get(x) || []).forEach((tn) => { if (cards[Nt - 1].v.ids.has(tn)) t.add(tn); })); sets[Nt - 1] = t; how[Nt] = how[Nt] || 'born'; backTree(Nt - 2); }
    }
    for (let i = 0; i < N; i++) if (!sets[i]) sets[i] = E();
    const res = cards.map((c, i) => {
      const s = sets[i]; if (!s.size) return { set: s, primary: null, line: -1, row: -1, how: i > 0 && sets[i - 1].size ? 'gone' : null };
      let pr = null; for (const id of s) { const o = c.v.byId.get(id); if (o && (!pr || o[1] < pr[1])) pr = o; }
      const line = pr ? M.lineOf(c, pr[1]) : -1, rows = new Set(); for (const id of s) { const o = c.v.byId.get(id); if (o) rows.add(c.rowOfLine[M.lineOf(c, o[1])]); }
      return { set: s, primary: pr ? pr[0] : null, line, row: line >= 0 ? c.rowOfLine[line] : -1, rows, how: i === 0 || !sets[i - 1].size ? 'born' : how[i] || 'same' };
    });
    for (let i = 1; i < N; i++) { const a = res[i - 1], b = res[i]; if (b.line >= 0 && a.line >= 0 && cards[i].kind === cards[i - 1].kind && (b.how === 'same' || b.how === 'copy') && cards[i].lines[b.line].trim() !== cards[i - 1].lines[a.line].trim()) b.how = 'edit'; }
    if (via && Nt < N && res[Nt].set.size) res[Nt].via = via;
    const nd = multi ? null : D.nodes.get(n); return { node: n, kind: nd ? nd.kind : '?', from: j, per: res, switchClass, sets };
  }

  // ---- line ranges ------------------------------------------------------------------------------------------------------
  // outermost nodes of version v whose span lies inside lines a..b (0-based, inclusive); a range inside one node gives that node's innermost cover
  M.nodesInLines = function (v, a, b) {
    const lines = R.linesOf(v), ls = [0]; for (let k = 0, p = 0; k < lines.length - 1; k++) { p += lines[k].length + 1; ls.push(p); }
    const S = ls[a], E = (ls[b] ?? 0) + (lines[b] || '').length, inside = new Set(), off = v.offsets;
    // the trimmed range: skip leading indentation / trailing blanks so a node starting at the first token is inside
    const S2 = S + ((lines[a] || '').length - (lines[a] || '').trimStart().length);
    for (const o of off) if (o[2] > o[1] && o[1] >= S2 && o[2] <= E) inside.add(o[0]);
    let out = new Set();
    inside.forEach((id) => { let x = R.parentOf(v, id), hit = false, h = 0; while (x != null && h++ < 200) { if (inside.has(x)) { hit = true; break; } x = R.parentOf(v, x); } if (!hit) out.add(id); });
    if (!out.size) { let best = null; for (const o of off) if (o[1] <= S2 && E <= o[2] && (!best || o[2] - o[1] < best[2] - best[1])) best = o; if (best) out.add(best[0]); }
    return out;
  };
  // rows (of card c, ready) covered by node set `set`: [first,last] row spans, merged; ghost rows between are included
  M.spansOf = function (c, set) {
    const sp = [], byId = c.v.byId; if (!c.rowOfLine) { c.rowOfLine = new Int32Array(c.lines.length); c.rows.forEach((r, k) => { if (r.nl >= 0) c.rowOfLine[r.nl] = k; }); }
    for (const id of set) { const o = byId.get(id); if (!o) continue; const l0 = M.lineOf(c, o[1]), l1 = M.lineOf(c, Math.max(o[1], o[2] - 1)); sp.push([c.rowOfLine[l0], c.rowOfLine[Math.max(l0, l1)]]); }
    sp.sort((x, y) => x[0] - y[0]); const out = [];
    for (const q of sp) { const l = out[out.length - 1]; if (l && q[0] <= l[1] + 1) l[1] = Math.max(l[1], q[1]); else out.push([q[0], q[1]]); }
    return out;
  };
})(window.R);
