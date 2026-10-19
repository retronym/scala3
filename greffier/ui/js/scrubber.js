// Phase scrubber: renders a version's printed text with node spans from offsets.
// Long texts (> VIRT_LINES lines) are rendered as a window of lines (virtualised) so ~10^5-node trees stay interactive.
// The parser version is shown as the real source text (units[].text) with the nodes' source spans.
(function (R) {
  const $ = (id) => document.getElementById(id);
  const tip = () => $('tip');
  const VIRT_LINES = 500, LH = 18, PAD = 40;
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  // ---- type text: qualified names shortened (full name on hover), prototypes elided ----
  R.shortType = (t) => (t == null ? t : t.replace(/\b(?:[a-z_][\w$]*\.)+(?=[A-Za-z_])/g, ''));
  const PROTO = /^(<\?>|\?|<notype>|a type that can be selected or applied|\[applied to .*\]|\?\{.*\}|<error.*)$/;
  R.cleanType = (t) => (t == null || PROTO.test(t.trim()) ? null : t);
  const typeHtml = (t, max = 200) => { const c = R.cleanType(t); if (!c) return null; const s = R.shortType(c); return `<code title="${esc(c)}">${esc(s.length > max ? s.slice(0, max) + '…' : s)}</code>`; };
  R.typeHtml = typeHtml;

  // ---- the view of a version: text + sorted offsets (+ id index). Parser version: source text with node spans ----
  const isSorted = (a) => { for (let i = 1; i < a.length; i++) if (a[i][1] < a[i - 1][1]) return false; return true; };
  function viewOf(v) {
    const D = R.ST.D, off = v.offsets;
    if (v._view && v._viewFor === off && v._viewUnit === R.ST.srcOk) return v._view;
    const u = D.units.get(v.unit), src = u && u.text;
    let view;
    if (src && D.phaseRaw(v.phase) === 'parser') {
      const offs = []; v.ids.forEach((id) => { const nd = D.nodes.get(id); if (nd && nd.span[1] > nd.span[0] && nd.span[1] <= src.length) offs.push([id, nd.span[0], nd.span[1]]); });
      offs.sort((a, b) => a[1] - b[1] || b[2] - a[2] || b[0] - a[0]);
      const by = new Map(offs.map((o) => [o[0], o])); view = { text: src, offs, byId: by, ids: v.ids, source: true };
    } else {
      const a = off.filter((o) => o[2] > o[1]); if (!isSorted(a)) a.sort((x, y) => x[1] - y[1] || y[2] - x[2]);
      view = { text: v.text, offs: a, byId: v.byId, ids: v.ids, source: false };
    }
    v._view = view; v._viewFor = off; v._viewUnit = R.ST.srcOk; return view;
  }
  R.viewOf = viewOf;
  const snip = (v, n, max = 90) => { const w = viewOf(v), o = w.byId.get(n); if (!o) return null; const t = w.text.slice(o[1], o[2]).replace(/\s+/g, ' ').trim(); return t.length > max ? t.slice(0, max) + '…' : t; };
  // parent by offset nesting (robust: a node's `children` may skip nodes that print inside it)
  function parentMap(v) {
    const w = viewOf(v); if (w._par) return w._par;
    const par = new Map(), st = []; w.offs.forEach(([id, a, b]) => { while (st.length && st[st.length - 1][1] <= a) st.pop(); if (st.length) par.set(id, st[st.length - 1][0]); st.push([id, b]); });
    return (w._par = par);
  }
  R.parentOf = (v, id) => parentMap(v).get(id);

  // ---- light syntax highlighting (stateless per text chunk; chunks are the pieces between node spans) ----
  const KW = 'def|val|var|class|trait|object|module|extends|with|given|using|new|if|then|else|match|case|final|lazy|private|protected|override|implicit|type|import|package|this|super|return|while|do|try|catch|finally|throw|enum|extension|inline|opaque|sealed|abstract|transparent|erased|null|true|false|yield|for|forSome|export|as';
  const TOK = new RegExp('"(?:[^"\\\\\\n]|\\\\.)*"|//[^\\n]*|/\\*[\\s\\S]*?\\*/|\\b(?:' + KW + ')\\b|\\b\\d+(?:\\.\\d+)?[lLfFdD]?\\b', 'g');
  let curLang = 'scala';
  // bytecode (ASM Textifier): opcodes, labels, descriptors, directives
  const BC = /\/\/[^\n]*|"(?:[^"\\\n]|\\.)*"|\b(?:LINENUMBER|LOCALVARIABLE|MAXSTACK|MAXLOCALS|FRAME|TRYCATCHBLOCK)\b|\bL\d+\b|\b[A-Z][A-Z0-9_]{2,}\b|\(?\[*L[\w/$]+;\)?|\([\w/$;\[]*\)[\w/$;\[]+|\b-?\d+\b/g;
  function emitBc(el, str) {
    let last = 0, m; BC.lastIndex = 0;
    while ((m = BC.exec(str))) {
      if (m.index > last) el.append(str.slice(last, m.index));
      const t = m[0];
      if (t[0] === '"' || /^-?\d+$/.test(t)) el.append(t);   // strings and numeric operands stay plain
      else { const sp = document.createElement('span'); sp.textContent = t; sp.className = /^[A-Z][A-Z0-9_]+$/.test(t) && !/^(LINENUMBER|LOCALVARIABLE|MAXSTACK|MAXLOCALS|FRAME|TRYCATCHBLOCK)$/.test(t) && !/^L\d+$/.test(t) ? 't-op' : 't-dim'; el.append(sp); }
      last = m.index + t.length;
    }
    if (last < str.length) el.append(str.slice(last));
  }
  function emit(el, str) {
    if (!R.ST.syntax || str.length < 2) { el.append(str); return; }
    if (curLang === 'bc') return emitBc(el, str);
    let last = 0, m; TOK.lastIndex = 0;
    while ((m = TOK.exec(str))) {
      if (m.index > last) el.append(str.slice(last, m.index));
      const t = m[0], sp = document.createElement('span'); sp.textContent = t;
      sp.className = t[0] === '"' ? 't-str' : t[0] === '/' ? 't-com' : /^\d/.test(t) ? 't-num' : 't-kw'; el.append(sp); last = m.index + t.length;
    }
    if (last < str.length) el.append(str.slice(last));
  }

  // Build DOM for text[lo,hi) with nested spans from sorted offsets (clipped to the window).
  function build(text, offs, cls, lo, hi) {
    const frag = document.createDocumentFragment();
    const stack = [{ end: hi + 1, el: frag, pos: lo }];
    const flush = (f, to) => { if (to > f.pos) { emit(f.el, text.slice(f.pos, to)); f.pos = to; } };
    for (let i = 0; i < offs.length; i++) {
      const o = offs[i]; if (o[1] >= hi) break; if (o[2] <= lo) continue;
      const id = o[0], s = Math.max(o[1], lo), e0 = Math.min(o[2], hi);
      while (stack.length > 1 && stack[stack.length - 1].end <= s) { const f = stack.pop(); flush(f, f.end); }
      const top = stack[stack.length - 1], e = Math.min(e0, top.end);
      if (e <= s) continue;
      flush(top, s); top.pos = e;
      const el = document.createElement('span'); el.dataset.n = id; el.className = 'n ' + (cls(id) || '');
      top.el.append(el); stack.push({ end: e, el, pos: s });
    }
    while (stack.length) { const f = stack.pop(); flush(f, Math.min(f.end, hi)); }
    return frag;
  }

  // ---- classification of new nodes: structural / retype-only / parent-rebuilt / pure copy ----
  const CLS_LABEL = { struct: 'structural', retype: 'retyped', rebuilt: 'rebuilt', copy: 'copies' };
  function classOf(D, n, v) {
    if (D.isPureCopy(n)) return 'copy';
    let d = null; (D.deriveByNew.get(n) || []).forEach((x) => { if (x.phase <= v.phase && (x.unit == null || x.unit === v.unit) && (!d || x.phase >= d.phase)) d = x; });
    if (!d) return 'struct';
    if (d.how === 'retype') return 'retype';
    if (d.how === 'copy') return 'copy';
    if (d.how === 'rewrite' && d.old.length === 1 && d.new.length === 1) {
      const nd = D.nodes.get(n), od = D.nodes.get(d.old[0]);
      // only change is a changed child: same kind, name, symbol and span, same number of children, at least one differs
      if (nd && od && nd.kind === od.kind && nd.name === od.name && nd.sym === od.sym && nd.children.length === od.children.length && nd.span[0] === od.span[0] && nd.span[1] === od.span[1] && nd.children.some((c, i) => c !== od.children[i])) return 'rebuilt';
    }
    return 'struct';
  }
  // highlight data for the displayed version relative to the previously shown one
  R.hl = function (v) {
    const D = R.ST.D, prev = R.prevVer();
    if (!v._hl || v._hlPrev !== prev || v._hlCollapse !== R.ST.collapse || v._hlStruct !== R.ST.structOnly || v._hlOff !== v.offsets) {
      const cls = new Map(), counts = { struct: 0, retype: 0, rebuilt: 0, copy: 0 };
      if (prev) v.ids.forEach((n) => { if (!prev.ids.has(n)) { const c = classOf(D, n, v); cls.set(n, c); counts[c]++; } });
      const set = new Set(); cls.forEach((c, n) => { if (R.ST.structOnly ? c === 'struct' : !(R.ST.collapse && c === 'copy')) set.add(n); });
      v._hl = { cls, counts, set, prev }; v._hlPrev = prev; v._hlCollapse = R.ST.collapse; v._hlStruct = R.ST.structOnly; v._hlOff = v.offsets;
    }
    return v._hl;
  };
  // old nodes that left: present in the previous shown version, absent here; only top-most ones (parent not removed)
  function removedList(v, prev) {
    if (!prev) return [];
    const w = viewOf(prev), gone = new Set(); prev.ids.forEach((n) => { if (!v.ids.has(n)) gone.add(n); });
    const par = parentMap(prev), out = [];
    // a node whose derive successor is present was rewritten, not removed (the root PackageDef is rewritten by almost every phase)
    const D = R.ST.D, rewritten = (id) => (D.deriveByOld.get(id) || []).some((d) => d.how !== 'elide' && d.new.some((x) => v.ids.has(x)));
    w.offs.forEach(([id]) => { if (gone.has(id) && !gone.has(par.get(id)) && !rewritten(id)) out.push(id); });
    return out;
  }

  // ---- lineage ----
  // index of the latest printed version before position `from` (in the unit's full list) that contains node `o`
  function findHolder(D, vs, from, o) {
    let last = null, tried = 0;
    for (let i = from; i >= 0 && tried < 12; i--) { const x = vs[i]; if (x.ids === last) continue; last = x.ids; tried++; if (x.ids.has(o)) return i; }
    return -1;
  }
  function ancestry(D, n, v, depth = 0) {
    const ds = (D.deriveByNew.get(n) || []).filter((d) => d.phase <= v.phase && (d.unit == null || d.unit === v.unit)).sort((a, b) => b.phase - a.phase);
    if (!ds.length || depth > 5) return [];
    const d = ds[0], vs = D.versionsOf(v.unit);
    const di = vs.findIndex((x) => x.phase === d.phase);
    const olds = d.old.slice(0, 6).map((o) => {
      const hi = findHolder(D, vs, di - 1, o), hv = hi >= 0 ? vs[hi] : null;
      return { id: o, node: D.nodes.get(o), text: hv ? snip(hv, o) : null, phase: hv ? hv.phase : null };
    });
    const row = { how: d.how, conf: d.confidence, phase: d.phase, olds, more: Math.max(0, d.old.length - 6), nnew: d.new.length };
    const nextV = d.old.length === 1 && olds[0].phase != null ? vs.find((x) => x.phase === olds[0].phase) : null;
    return [row].concat(nextV ? ancestry(D, d.old[0], nextV, depth + 1) : []);
  }

  R.describeNode = function (n, v) {
    const D = R.ST.D, node = D.nodes.get(n); if (!node) return null;
    const si = node.sym != null ? D.infoAt(node.sym, v.phase) : null;
    return { node, tpe: D.tpeText(n, v), sym: node.sym != null ? D.symName(node.sym) : null, si, text: snip(v, n, 160), anc: ancestry(D, n, v), nev: (D.evByNode.get(n) || []).length };
  };

  function infoHtml(d, v, full) {
    const D = R.ST.D, n = d.node;
    let h = `<div class="k">${esc(n.kind)}${n.name ? ' <b>' + esc(n.name) + '</b>' : ''} <span class="dim">#${n.id}</span></div>`;
    const th = d.tpe && typeHtml(d.tpe); if (th) h += `<div><span class="dim">type</span> ${th}</div>`;
    if (d.sym) h += `<div><span class="dim">symbol</span> <b>${esc(d.sym)}</b> <span class="dim">#${n.sym}</span></div>`;
    if (d.si) h += `<div><span class="dim">at ${esc(D.phaseName(v.phase))}</span> ${typeHtml(d.si.info) || '<code>' + esc(d.si.info) + '</code>'} <span class="dim">[${esc(d.si.flags)}] owner ${esc(D.symName(d.si.owner))}${d.si.name && d.si.name !== d.sym ? ' · named ' + esc(d.si.name) : ''}</span></div>`;
    if (full) h += `<div><span class="dim">source span</span> ${n.span[0]}–${n.span[1]} · <span class="dim">children</span> ${n.children.length} · <span class="dim" title="events recorded on this very node; the Events tab counts events of its ancestors too">events on this node</span> ${d.nev}</div>`;
    if (d.anc.length) {
      h += `<div class="anc"><span class="dim">derived from${full ? ' (click a step to jump to that phase with the node selected)' : ''}</span>`;
      d.anc.forEach((a) => {
        h += `<div class="ar">⟵ <span class="how how-${a.how}">${esc(a.how)}</span>${a.conf ? ` <span class="dim">${esc(a.conf)}</span>` : ''} <span class="dim">@${esc(D.phaseName(a.phase))}</span>`;
        if (!a.olds.length) h += ' <i>(no ancestor)</i>';
        a.olds.forEach((o) => {
          const lk = full && o.phase != null ? ` class="old lk" data-ph="${o.phase}" data-n="${o.id}" title="jump to ${esc(D.phaseName(o.phase))} with this node selected"` : ' class="old"';
          h += `<pre${lk}>${o.text ? esc(o.text) : '<i>#' + o.id + ' (not printed in any earlier version; created inside a phase)</i>'}${o.phase != null ? ` <span class="dim">— ${esc(D.phaseShort(o.phase))}</span>` : ''}</pre>`;
        });
        if (a.more) h += `<div class="dim">… and ${a.more} more old nodes</div>`;
        h += '</div>';
      });
      h += '</div>';
    } else if (R.ST.D.versionsOf(v.unit)[0] !== v) h += '<div class="dim">no derive record — shared with the previous version (or untracked)</div>';
    return h;
  }

  // ---- state of the currently displayed version (for the virtual window) ----
  let cur = null;
  const lineStarts = (text) => { const a = [0]; for (let i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) a.push(i + 1); return a; };
  const lineOf = (ls, pos) => { let lo = 0, hi = ls.length - 1; while (lo < hi) { const m = (lo + hi + 1) >> 1; if (ls[m] <= pos) lo = m; else hi = m - 1; } return lo; };


  // ---- lines: the built spans are split into one block per text line (nested spans are re-opened on each line) so that
  // lines can be measured and animated (FLIP) as units ----
  function lineify(frag) {
    const lines = []; const mk = (anc) => { const div = document.createElement('div'); div.className = 'ln'; const clones = []; let tip = div; anc.forEach((a) => { const c = a.cloneNode(false); tip.append(c); tip = c; clones.push(c); }); return { div, clones, tip, has: false }; };
    const anc = []; let line = mk(anc);
    (function visit(node) {
      for (const c of [...node.childNodes]) {
        if (c.nodeType === 3) {
          const parts = c.data.split('\n');
          parts.forEach((p, i) => { if (i > 0) { lines.push(line.div); line = mk(anc); } if (p) { line.tip.append(p); line.has = true; } });
        } else {
          const shell = c.cloneNode(false); line.tip.append(shell); line.clones.push(shell); line.tip = shell; anc.push(c);
          visit(c);
          anc.pop(); line.clones.pop(); line.tip = line.clones.length ? line.clones[line.clones.length - 1] : line.div;
        }
      }
    })(frag);
    if (line.has || line.div.childNodes.length) lines.push(line.div);
    const out = document.createDocumentFragment(); lines.forEach((l) => out.append(l)); return out;
  }

  // ---- animated transitions between adjacent versions (FLIP on lines) ----
  const reduced = () => !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  const A = (R.anim = { running: [], ghosts: [], last: null, stats: null, lastSnap: null });
  const EASE = 'cubic-bezier(.2,.7,.2,1)', DUR = 220, MARGIN = 60, CAP = 300;
  function finishAnim() { A.running.forEach((a) => { try { a.cancel(); } catch (e) { /* done */ } }); A.running = []; A.ghosts.forEach((g) => g.remove()); A.ghosts = []; }
  R.animActive = () => R.ST.anim && !reduced();
  // positions are relative to the top of #text, so scrolling and banner height changes do not matter
  function visibleLines(pane) {
    const body = pane.parentNode, pt = pane.getBoundingClientRect().top, bt = body.getBoundingClientRect(), all = pane.querySelectorAll('.ln'), out = [];
    for (const el of all) { const r = el.getBoundingClientRect(); if (r.bottom >= bt.top - MARGIN * 18 && r.top <= bt.bottom + MARGIN * 18) out.push({ el, top: r.top - pt, h: r.height, text: el.textContent }); if (out.length > CAP) break; }
    return out;
  }
  function takeSnap(pane) { const t0 = performance.now(), ls = visibleLines(pane); return { lines: ls.map((l) => ({ top: l.top, h: l.h, text: l.text, clone: l.el.cloneNode(true) })), ms: performance.now() - t0 }; }
  // greedy monotone matching of equal lines; far-away matches are not slid (they fade instead)
  function runTransition(snap, pane) {
    finishAnim(); if (!snap || !R.animActive()) return;
    const t0 = performance.now(), speed = +($('animspeed').value) || 1, dur = DUR * speed, nw = visibleLines(pane);
    if (!snap.lines.length || !nw.length) return;
    const q = new Map(); snap.lines.forEach((l, i) => { if (l.text.trim()) { let a = q.get(l.text); if (!a) q.set(l.text, (a = [])); a.push(i); } });
    const matchOf = new Array(nw.length).fill(-1), used = new Set(); let lastO = -1;
    nw.forEach((l, j) => { const a = q.get(l.text); if (!a) return; const k = a.find((i) => i > lastO && !used.has(i)); if (k != null && Math.abs(snap.lines[k].top - l.top) < 600) { matchOf[j] = k; used.add(k); lastO = k; } });
    const opts = { duration: dur, easing: EASE, fill: 'none' }; let moved = 0, faded = 0, tinted = 0;
    nw.forEach((l, j) => {
      const k = matchOf[j];
      if (k >= 0) { const dy = snap.lines[k].top - l.top; if (Math.abs(dy) > 0.5) { A.running.push(l.el.animate([{ transform: `translateY(${dy}px)` }, { transform: 'none' }], opts)); moved++; } }
      else if (l.text.trim()) { A.running.push(l.el.animate([{ opacity: 0 }, { opacity: 1 }], opts)); faded++; }
      l.el.querySelectorAll('.nc-retype').forEach((sp) => { if (tinted < 80) { A.running.push(sp.animate([{ backgroundColor: 'rgba(28,126,214,.22)' }, { backgroundColor: 'rgba(28,126,214,0)' }], { duration: dur * 1.6, easing: 'linear' })); tinted++; } });
    });
    // elided lines: ghosts at their old position, struck through, fading out
    let ghosts = 0;
    snap.lines.forEach((l, i) => { if (!used.has(i) && l.text.trim() && ghosts < 60) { const g = l.clone.cloneNode(true); g.classList.add('ghost'); g.style.top = l.top + 'px'; pane.append(g); A.ghosts.push(g); A.running.push(g.animate([{ opacity: 0.85 }, { opacity: 0 }], { duration: dur, easing: EASE, fill: 'forwards' })); ghosts++; } });
    if (A.running.length) { const last = A.running[A.running.length - 1]; A.running.forEach((a) => (a.onfinish = () => { A.running = A.running.filter((x) => x !== a); })); setTimeout(() => { if (!A.running.length) { A.ghosts.forEach((g) => g.remove()); A.ghosts = []; } }, dur + 80); }
    A.stats = { ms: +(snap.ms + performance.now() - t0).toFixed(1), snapMs: +snap.ms.toFixed(1), lines: nw.length, moved, faded, ghosts, tinted, speed };
    R.animStats = A.stats;
  }
  R.replay = () => { if (A.lastSnap) { finishAnim(); runTransition(A.lastSnap, $('text')); } };

  function paintWindow() {
    if (!cur || !cur.virt) return;
    const body = $('text').parentNode, first = Math.max(0, Math.floor(body.scrollTop / LH) - PAD), last = Math.min(cur.ls.length - 1, Math.ceil((body.scrollTop + body.clientHeight) / LH) + PAD);
    if (cur.win && cur.win[0] <= first + PAD / 2 && cur.win[1] >= last - PAD / 2 && cur.win[2] === cur.stamp) return;
    cur.win = [first, last, cur.stamp];
    const lo = cur.ls[first], hi = last + 1 < cur.ls.length ? cur.ls[last + 1] : cur.text.length;
    const t0 = performance.now();
    curLang = cur.lang || 'scala'; cur.box.style.top = first * LH + 'px';
    cur.box.replaceChildren(lineify(build(cur.text, cur.offs, cur.cls, lo, hi)));
    R.lastWindowMs = Math.round(performance.now() - t0);
  }


  // paint a view {text, offs, byId} into #text; windowed above VIRT_LINES lines. targets: node ids to keep in view.
  function paint(W, clsF, targets, key, lang, animKey) {
    const pane = $('text'), body = pane.parentNode, text = W.text, offs = W.offs, st = R.ST;
    // animate only when stepping to an adjacent shown version of the same unit/class/mode; anything else renders instantly
    const P = R._painted, adjacent = !!(animKey && P && P.D === st.D && P.key === animKey && Math.abs(st.verIdx - P.verIdx) === 1 && P.W !== W);
    finishAnim(); const snap = adjacent && R.animActive() ? takeSnap(pane) : null;
    const ls = W._ls || (W._ls = lineStarts(text)), virt = ls.length > VIRT_LINES;
    curLang = lang || 'scala';
    if (virt) {
      pane.className = 'virt';
      const reuse = cur && cur.W === W && cur.spacer && pane.contains(cur.spacer);
      if (!reuse) { pane.replaceChildren(); const sp = document.createElement('div'); sp.style.height = ls.length * LH + 'px'; const box = document.createElement('div'); box.className = 'vbox'; sp.append(box); pane.append(sp); cur = { W, spacer: sp, box }; }
      Object.assign(cur, { virt: true, ls, text, offs, cls: clsF, lang: curLang, win: null, stamp: (cur.stamp || 0) + 1 });
      const target = [...targets].map((id) => W.byId.get(id)).filter(Boolean)[0];
      if (target && cur.lastKey !== key) { const line = lineOf(ls, target[1]); if (line * LH < body.scrollTop || line * LH > body.scrollTop + body.clientHeight - 2 * LH) body.scrollTop = Math.max(0, line * LH - body.clientHeight / 3); }
      cur.lastKey = key;
      paintWindow();
    } else {
      pane.className = ''; cur = { W, virt: false };
      pane.replaceChildren(lineify(build(text, offs, clsF, 0, text.length)));
      const el = pane.querySelector('.n.sel, .n.cur, .n.lnk');
      if (el) { const r = el.getBoundingClientRect(), br = body.getBoundingClientRect(); if (r.top < br.top || r.bottom > br.bottom) body.scrollTop += r.top - br.top - br.height / 3; }
    }
    pane.classList.toggle('bykind', $('bykind').checked); pane.classList.toggle('bc', curLang === 'bc');
    R._painted = { D: st.D, key: animKey, verIdx: st.verIdx, W };
    if (snap) { A.lastSnap = snap; runTransition(snap, pane); } else if (!adjacent) A.stats = null;
    return { ls, virt };
  }

  const chip = (cls, n, label, title) => (n ? `<span class="chip2 ${cls}" title="${esc(title)}">${n} ${label}</span>` : '');

  function render() {
    const { D } = R.ST, v = R.ver(), vs = R.vers(), pane = $('text'), body = pane.parentNode;
    if (!v) { pane.textContent = 'no versions'; return; }
    if (v.irPseudo) { if (R.renderIR) R.renderIR(v); return; }
    const hl = R.hl(v), news = hl.set, W = viewOf(v);
    const selSet = R.present(R.ST.selNode, v);
    const e = R.ev(), curSet = new Set();
    let ce = e; while (ce && !ce.nodes.some((n) => n != null) && ce.parent != null) ce = D.evBySeq.get(ce.parent);   // nearest enclosing event that names a node
    (ce ? ce.nodes : []).forEach((n) => { if (n != null) R.present(n, v).forEach((m) => curSet.add(m)); });
    const offs = W.offs, text = W.text;
    // 'top' = frontier: structural nodes with no structural child get the strong background
    const topNew = new Set();
    if (hl.counts.struct) {
      const par = parentMap(v), hasKid = new Set();
      news.forEach((n) => { if (hl.cls.get(n) === 'struct' && par.has(n)) hasKid.add(par.get(n)); });
      news.forEach((n) => { if (hl.cls.get(n) === 'struct' && !hasKid.has(n)) topNew.add(n); });
    }
    const NODES = D.nodes;
    const kcls = (id) => { const nd = NODES.get(id); return nd ? 'k-' + nd.kind.replace(/[^A-Za-z]/g, '') + (nd.span[1] <= nd.span[0] ? ' syn' : '') + ' ' : ''; };
    const cls0 = (id) => (news.has(id) ? 'new nc-' + hl.cls.get(id) + (topNew.has(id) ? ' top' : '') : '') + (selSet.has(id) ? ' sel' : '') + (curSet.has(id) ? ' cur' : '');
    const lk = R.irLinks ? R.irLinks().tree : null;
    const rg = new Set(); if (R.ST.range && !R.ST.range.ir && R.ST.range.unit === R.ST.unit) R.ST.range.nodes.forEach((n) => R.present(n, v).forEach((m) => rg.add(m)));
    const clsF = (id) => kcls(id) + cls0(id) + (lk && lk.has(id) ? ' lnk' : '') + (rg.has(id) ? ' rg' : '');
    const t0 = performance.now();
    const { ls, virt } = paint(W, clsF, [...selSet, ...curSet, ...rg], v.phase + ':' + R.ST.selNode + ':' + R.seq() + ':' + (R.ST.range ? R.ST.range.lo + '-' + R.ST.range.hi + ':' + R.ST.range.phase : ''), 'scala', 'tree:' + R.ST.unit);
    R.lastTextMs = Math.round(performance.now() - t0);
    // phase in progress: the cursor is inside phase g whose output (version) appears later (event scrubbing only)
    let banner = '';
    if (e && D.bands.length) {
      const all = D.versionsOf(R.ST.unit), gv = all.find((x) => x.phase === e.phase);
      if (gv && gv.seq != null && gv.seq > e.seq && v !== gv) banner = `<div class="banner"><b>${esc(D.phaseName(e.phase))}</b> in progress (seq ${e.seq}); showing <b>${esc(D.phaseName(v.phase))}</b> output. The ${esc(D.phaseName(e.phase))} tree appears at seq ${gv.seq}. <button id="jumpend" title="key: e">jump to end of phase (e)</button></div>`;
    }
    const prev = hl.prev, rem = removedList(v, prev), c = hl.counts;
    const chips = !prev ? '<span title="First version: nothing to compare with.">baseline</span>'
      : (c.struct + c.retype + c.rebuilt + c.copy + rem.length === 0 ? '<span title="Every node is the same object as in the previous version.">no changes</span>' : '') +
        chip('cs', c.struct, 'structural', 'Nodes that are really new or rewritten (rewrite, split, synthetic) in the tree shown. Green underline; the lowest ones are shaded. The phases table and the phase select count every node derived in the phase (including nodes of other units and pure copies), so their numbers are larger.') +
        chip('cr', c.retype, 'retyped', 'Same node re-typed by a later typer (erasure, inlining). Blue dotted underline.') +
        chip('cb', c.rebuilt, 'rebuilt', 'New node whose only change is a changed child (the spine above a change). Grey dotted underline.') +
        chip('cc', c.copy, 'pure copies' + (R.ST.collapse ? ' hidden' : ''), 'Re-created but identical to the source (same kind, name, symbol, span, type; children identical or pure copies).') +
        (rem.length ? `<a class="chip2 cm" href="#removed" id="remlink" title="Top-most nodes of the previous version that are not in this one. Click to list them.">${rem.length} removed</a>` : '');
    $('verinfo').innerHTML = banner + `<b>${esc(D.phaseName(v.phase))}</b> · ${v.ids.size} nodes${virt ? ` · ${ls.length} lines (windowed)` : ''}${W.source ? ' · <span title="The parser tree is shown as the source text with node spans (the printer would mangle interpolations).">source text</span>' : ''} · ${chips}` +
      (prev ? ` <span class="dim" title="Share of this version's nodes that are the very same objects as in the previous shown version (persistent tree sharing).">since ${esc(D.phaseShort(prev.phase))}, ${Math.round(100 * (1 - (c.struct + c.retype + c.rebuilt + c.copy) / Math.max(1, v.ids.size)))}% shared</span>` : '') +
      (e ? ` · cursor node${curSet.size === 1 ? '' : 's'}: ${curSet.size ? [...curSet].slice(0, 6).map((n) => '#' + n).join(', ') + (curSet.size > 6 ? ' …' : '') : '<span class="dim">none in this version</span>'}` : '');
    // removed list
    const rl = $('removed'); rl.hidden = !rem.length; if (rem.length) {
      const pw = viewOf(prev); $('remsum').textContent = `removed since ${D.phaseShort(prev.phase)} (${rem.length})`;
      if (rl.open) $('remlist').innerHTML = rem.slice(0, 300).map((id) => { const nd = D.nodes.get(id); return `<pre class="old lk" data-ph="${prev.phase}" data-n="${id}" title="jump to ${esc(D.phaseName(prev.phase))} with this node selected"><span class="dim">${esc(nd ? nd.kind : '#' + id)}</span> ${esc(snip(prev, id, 110) || '')}</pre>`; }).join('') + (rem.length > 300 ? `<div class="dim">… ${rem.length - 300} more</div>` : '');
    }
    renderSource(v, news, selSet, curSet);
    $('mpanel').hidden = true; if (R.renderSplitHook) R.renderSplitHook(v, selSet);
    const jb = $('jumpend'); if (jb) jb.onclick = () => R.phaseEnd();
    const rlink = $('remlink'); if (rlink) rlink.onclick = (ev) => { ev.preventDefault(); rl.open = true; rl.scrollIntoView({ block: 'nearest' }); };
    // side panel: breadcrumb + node info
    const nd = $('nodeinfo'), bc = $('crumbs');
    if (R.ST.selNode != null && !(D.nodes.get(R.ST.selNode) || {}).ir) {
      const shown = [...selSet];
      if (shown.length) {
        const chain = []; for (let x = shown[0], i = 0; x != null && i < 60; x = parentMap(v).get(x), i++) chain.unshift(x);
        const lab = (id) => { const n = D.nodes.get(id); return n ? n.kind + (n.name ? ' ' + String(n.name).slice(0, 18) : '') : '#' + id; };
        const tail = chain.length > 7 ? ['…'].concat(chain.slice(-6)) : chain;
        bc.innerHTML = tail.map((id) => (id === '…' ? '<span class="dim">…</span>' : `<a class="crumb${id === shown[0] ? ' on' : ''}" data-n="${id}" title="select this ancestor">${esc(lab(id))}</a>`)).join('<span class="dim"> › </span>');
      } else bc.innerHTML = '';
      nd.innerHTML = (!v.ids.has(R.ST.selNode) && !(R.ST.D.nodes.get(R.ST.selNode) || {}).ir ? `<div class="dim">#${R.ST.selNode} is not in this version; ${shown.length ? 'showing ' + (D.deriveByOld.has(R.ST.selNode) ? 'descendant' : 'ancestor') + (shown.length > 1 ? 's' : '') : 'no lineage into this version'}</div>` : '') +
        shown.slice(0, 4).map((n) => infoHtml(R.describeNode(n, v), v, true) + (R.irTreeLink ? R.irTreeLink(n) : '')).join('<hr>');
    } else if (R.ST.selNode != null && (D.nodes.get(R.ST.selNode) || {}).ir && R.irInfoHtml) { bc.innerHTML = ''; nd.innerHTML = '<div class="dim">A JVM node is selected (its tree origin is outlined).</div>' + R.irInfoHtml(R.ST.selNode); } else { bc.innerHTML = ''; nd.innerHTML = '<span class="dim">Click a span to select a node (Alt+click: its parent). Hover for lineage.</span>'; }
  }

  // ---- source pane: unit text with the selected node's origin and the new nodes' spans mapped back ----
  let srcKey = null;
  function renderSource(v, news, selSet, curSet) {
    const D = R.ST.D, u = D.units.get(R.ST.unit), pane = $('srcpane'), text = u && u.text;
    pane.hidden = !text; if (!text || !pane.open) { srcKey = null; return; }
    const key = [R.ST.unit, v.phase, R.ST.collapse, R.ST.structOnly, R.ST.selNode, [...curSet].join(','), news.size].join('|');
    if (key === srcKey) return; srcKey = key;
    const L = text.length, dn = new Int32Array(L + 2), ds = new Int32Array(L + 2), dc = new Int32Array(L + 2);
    const mark = (set, d) => set.forEach((n) => { const nd = D.nodes.get(n); if (nd && nd.span[1] > nd.span[0] && nd.span[0] < L) { d[nd.span[0]]++; d[Math.min(L, nd.span[1])]--; } });
    mark(news, dn); mark(selSet, ds); mark(curSet, dc);
    const box = $('srctext'), frag = document.createDocumentFragment(); let a = 0, cn = 0, cs = 0, cc = 0, first = -1;
    const seg = (from, to, n, s2, c2) => { if (to <= from) return; const sp = document.createElement('span'); sp.dataset.s = from; sp.textContent = text.slice(from, to); sp.className = (n ? 'sn ' : '') + (s2 ? 'ss ' : '') + (c2 ? 'sc' : ''); if (s2 && first < 0) first = from; frag.append(sp); };
    let st = [false, false, false];
    for (let i = 0; i <= L; i++) {
      cn += dn[i]; cs += ds[i]; cc += dc[i];
      const nx = [cn > 0, cs > 0, cc > 0];
      if (i === L || nx[0] !== st[0] || nx[1] !== st[1] || nx[2] !== st[2]) { seg(a, i, ...st); a = i; st = nx; }
    }
    box.replaceChildren(frag);
    $('srcinfo').textContent = `${(u.path || '').replace(/^.*\//, '')} · green = origin of highlighted new nodes, blue = selected node, orange underline = event cursor`;
    if (first >= 0) { const el = [...box.children].find((c) => c.classList.contains('ss')); if (el) box.scrollTop = Math.max(0, el.offsetTop - box.clientHeight / 3); }
  }
  function srcClick() {
    const D = R.ST.D, v = R.ver(), sel = window.getSelection(); if (!sel || !sel.anchorNode) return;
    const sp = sel.anchorNode.parentElement; if (!sp || sp.dataset.s == null) return;
    const pos = +sp.dataset.s + sel.anchorOffset; let best = null, bw = Infinity;
    v.offsets.forEach(([id]) => { const nd = D.nodes.get(id); if (!nd) return; const w = nd.span[1] - nd.span[0]; if (w > 0 && nd.span[0] <= pos && pos < nd.span[1] && w <= bw) { bw = w; best = id; } });
    if (best != null) R.set({ selNode: best, selSym: D.nodes.get(best).sym });
  }
  const KINDS = [['DefDef', '#1c7ed6'], ['ValDef', '#4263eb'], ['Class/Module/TypeDef', '#7048e8'], ['Apply', '#e8590c'], ['TypeApply', '#d6336c'], ['Select/Ident', '#2f9e44'], ['TypeTree', '#0c8599'], ['Literal', '#e67700'], ['Block/If/Match', '#868e96'], ['Package/Template', '#495057']];

  // the node under the pointer, innermost; Alt gives its parent. Hover and click use the same function.
  const nodeAt = (ev) => { const el = ev.target.closest && ev.target.closest('#text .n'); if (!el) return null; let id = +el.dataset.n; if (ev.altKey) { const p = R.parentOf(R.ver(), id); if (p != null) id = p; } return { el, id }; };
  function wire() {
    $('legend-kinds').innerHTML = '<span class="dim">node kinds (hover outline; "colour by kind" underlines):</span> ' + KINDS.map(([n, c]) => `<span><i style="background:${c}"></i>${n}</span>`).join('') + '<span title="Nodes with no source position (created by a phase)" style="font-style:italic"><i style="background:repeating-linear-gradient(135deg,#7a828e 0 2px,transparent 2px 4px)"></i>synthetic (no span)</span>';
    $('srcpane').addEventListener('toggle', () => { srcKey = null; if (R.ST.D) R.render(); });
    $('removed').addEventListener('toggle', () => { if (R.ST.D) R.render(); });
    $('srctext').addEventListener('click', srcClick);
    const pane = $('text');
    let lastId = null, lastEl = null, lastAll = [];
    const unhov = () => { lastAll.forEach((x) => x.classList.remove('hov')); lastAll = []; };
    pane.parentNode.addEventListener('scroll', () => { if (cur && cur.virt && !cur.raf) cur.raf = requestAnimationFrame(() => { cur.raf = 0; paintWindow(); }); }, { passive: true });
    // hover is driven by mousemove (not mouseover) so it stays correct when a click re-renders the text
    pane.addEventListener('mousemove', (ev) => {
      const t = tip(), h = nodeAt(ev);
      if (!h) { t.hidden = true; unhov(); lastEl = null; lastId = null; return; }
      if (h.id !== lastId || h.el !== lastEl) {
        unhov();
        lastEl = ev.altKey ? h.el.closest('.n') : h.el; lastId = h.id; lastAll = [...pane.querySelectorAll('.n[data-n="' + h.id + '"]')]; lastAll.forEach((x) => x.classList.add('hov'));
        const vv = R.ver(); if (vv.irPseudo) { t.innerHTML = R.irTip(h.id); t.hidden = false; return; }
        const d = R.describeNode(h.id, vv); if (!d) { t.hidden = true; return; }
        t.innerHTML = infoHtml(d, vv, false) + `<div class="dim">click selects this node · Alt+click selects its parent</div>` + (R.irTreeTip ? R.irTreeTip(h.id) : ''); t.hidden = false;
      }
      t.style.left = Math.min(ev.clientX + 14, innerWidth - t.offsetWidth - 8) + 'px'; t.style.top = Math.min(ev.clientY + 16, innerHeight - t.offsetHeight - 8) + 'px';
    });
    pane.addEventListener('mouseleave', () => { tip().hidden = true; unhov(); lastEl = null; lastId = null; });
    pane.addEventListener('click', (ev) => { const h = nodeAt(ev); if (h) { const nd = R.ST.D.nodes.get(h.id); lastEl = null; lastId = null; tip().hidden = true; R.set({ selNode: h.id, selSym: nd ? nd.sym : null }); } });
    // breadcrumb + lineage links
    document.addEventListener('click', (ev) => {
      const c = ev.target.closest && ev.target.closest('.crumb'); if (c) { const id = +c.dataset.n, nd = R.ST.D.nodes.get(id); R.set({ selNode: id, selSym: nd ? nd.sym : null }); return; }
      const l = ev.target.closest && ev.target.closest('.lk'); if (l) R.jumpTo(+l.dataset.ph, +l.dataset.n);
    });
  }
  document.addEventListener('DOMContentLoaded', wire);
  R.views.push(render);
  R._s = { build, viewOf, parentMap, paint, lineStarts, lineOf, esc, snip, nodeAt, emitBc, infoHtml, setLang: (l) => { curLang = l; }, lineify, classOf, LH };
})(window.R);
