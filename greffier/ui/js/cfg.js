// CFG lightbox: the basic blocks and control-flow edges of one method, derived in the viewer from the Textifier text of its MethodNode.
// Layered layout by dagre (cdnjs, pinned); blocks are cards with the same syntax tint and range/selection highlighting as the linear pane
// (they are built from the class version's text and offsets, so every instruction keeps the id of its InsnRange); the method's linear
// listing sits to the right; `[` / `]` step through the method's JVM phases and the graph re-layouts with animated block moves.
(function (R) {
  const S = () => R._s, $ = (id) => document.getElementById(id), esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const PSEUDO = new Set(['LINENUMBER', 'FRAME', 'LOCALVARIABLE', 'MAXSTACK', 'MAXLOCALS']);
  const CONDS = new Set(['IFEQ', 'IFNE', 'IFLT', 'IFGE', 'IFGT', 'IFLE', 'IF_ICMPEQ', 'IF_ICMPNE', 'IF_ICMPLT', 'IF_ICMPGE', 'IF_ICMPGT', 'IF_ICMPLE', 'IF_ACMPEQ', 'IF_ACMPNE', 'IFNULL', 'IFNONNULL']);
  const isRet = (op) => /^[ILFDA]?RETURN$/.test(op), isSwitch = (op) => op === 'TABLESWITCH' || op === 'LOOKUPSWITCH';

  // ---- parse: method text -> blocks, edges ------------------------------------------------------------------------------------
  // base = offset of the method text inside the class version's text (so lines map back to offsets / ranges)
  R.cfgParse = function (text, base) {
    const L = text.split('\n'), items = []; let off = 0, inSwitch = null;
    L.forEach((t, i) => {
      const it = { i, t, abs: base + off, end: base + off + t.length, kind: 'other' }; off += t.length + 1; let m;
      if ((m = /^\s*(L\d+)\s*$/.exec(t))) { it.kind = 'label'; it.label = m[1]; inSwitch = null; }
      else if ((m = /^\s*TRYCATCHBLOCK (L\d+) (L\d+) (L\d+) (\S+)/.exec(t))) { it.kind = 'tcb'; it.tcb = { s: m[1], e: m[2], h: m[3], type: m[4] }; }
      else if (inSwitch && (m = /^\s{6}(default|-?\d+): (L\d+)/.exec(t))) { it.kind = 'case'; inSwitch.cases.push({ key: m[1], to: m[2] }); }
      else if ((m = /^\s{4}([A-Z][A-Z0-9_]*)(?: (.*))?$/.exec(t))) {
        if (PSEUDO.has(m[1])) it.kind = 'pseudo'; else { it.kind = 'insn'; it.op = m[1]; it.arg = m[2] || ''; inSwitch = isSwitch(it.op) ? it : null; if (isSwitch(it.op)) it.cases = []; else inSwitch = null; }
      } else if (inSwitch && !/^\s{6}/.test(t)) inSwitch = null;
      items.push(it);
    });
    const jumpTarget = (it) => { const m = /(L\d+)/.exec(it.arg); return m ? m[1] : null; };
    // leaders: labels that are jump / switch / handler / try-range targets
    const leaders = new Set(), tcbs = items.filter((x) => x.kind === 'tcb').map((x) => x.tcb);
    items.forEach((it) => { if (it.kind === 'insn') { if (CONDS.has(it.op) || it.op === 'GOTO' || it.op === 'JSR') { const t = jumpTarget(it); if (t) leaders.add(t); } else if (isSwitch(it.op)) it.cases.forEach((c) => leaders.add(c.to)); } });
    tcbs.forEach((t) => { leaders.add(t.h); leaders.add(t.s); leaders.add(t.e); });
    // blocks over the body (from the first label / instruction)
    const first = items.findIndex((x) => x.kind === 'label' || x.kind === 'insn'); const blocks = []; let cur = null, brk = false;
    const open = (it) => { cur = { id: blocks.length, items: [], labels: [], insns: 0, lo: it.abs, hi: it.end, firstLine: it.i, lastLine: it.i }; blocks.push(cur); };
    for (let k = Math.max(0, first); k < items.length; k++) {
      const it = items[k]; if (it.kind === 'tcb') continue;
      if (!cur || (brk && it.kind !== 'case') || (it.kind === 'label' && leaders.has(it.label) && cur.insns > 0)) { open(it); brk = false; }
      cur.items.push(it); cur.hi = it.end; cur.lastLine = it.i; if (it.kind === 'label') cur.labels.push(it.label);
      if (it.kind === 'insn') { cur.insns++; cur.last = it; if (CONDS.has(it.op) || it.op === 'GOTO' || it.op === 'JSR' || isSwitch(it.op) || it.op === 'ATHROW' || isRet(it.op)) brk = true; }
      if (it.kind === 'case' && brk) { /* switch case lines belong to the switch block */ }
    }
    // blocks without an instruction (trailing labels / pseudo lines) fold into the previous block; their labels alias the next block with code
    const keep = []; blocks.forEach((b) => { if (b.insns > 0 || !keep.length) keep.push(b); else { const p = keep[keep.length - 1]; p.items.push(...b.items); p.hi = b.hi; p.lastLine = b.lastLine; p.trailing = (p.trailing || []).concat(b.labels); } });
    keep.forEach((b, i) => { b.id = i; }); const byLabel = new Map(); keep.forEach((b) => { b.labels.forEach((l) => byLabel.set(l, b.id)); });
    keep.forEach((b) => { (b.trailing || []).forEach((l) => { if (!byLabel.has(l)) byLabel.set(l, b.id); }); });
    keep.forEach((b) => { b.name = b.labels[0] || (b.id === 0 ? 'entry' : 'B' + b.id); });
    // edges
    const edges = [], add = (from, to, kind, label, extra) => { if (to == null || to === undefined) return; edges.push(Object.assign({ from, to, kind, label: label || '' }, extra)); };
    keep.forEach((b, i) => {
      const li = b.last, nxt = i + 1 < keep.length ? i + 1 : null;
      if (!li) { add(b.id, nxt, 'fall'); return; }
      if (CONDS.has(li.op)) { add(b.id, byLabel.get(jumpTarget(li)), 'true', li.op); add(b.id, nxt, 'false', 'else'); }
      else if (li.op === 'GOTO' || li.op === 'JSR') add(b.id, byLabel.get(jumpTarget(li)), 'goto', '');
      else if (isSwitch(li.op)) { const g = new Map(); li.cases.forEach((c) => { const t = byLabel.get(c.to); if (t != null) g.set(t, (g.get(t) || []).concat(c.key)); }); g.forEach((keys, t) => add(b.id, t, 'case', keys.join(', '))); }
      else if (li.op === 'ATHROW' || isRet(li.op)) { b.exit = li.op; }
      else add(b.id, nxt, 'fall');
    });
    // exception edges: from the first block of a covered range to the handler
    const pos = (l) => items.findIndex((x) => x.kind === 'label' && x.label === l);
    tcbs.forEach((t) => {
      const ps = pos(t.s), pe = pos(t.e), h = byLabel.get(t.h); if (ps < 0 || h == null) return;
      const cov = keep.filter((b) => b.items.some((x) => { const ix = items.indexOf(x); return ix >= ps && ix < (pe < 0 ? items.length : pe) && x.kind === 'insn'; }));
      if (cov.length) add(cov[0].id, h, 'exc', t.type === 'null' ? 'any' : t.type.replace(/^.*\//, ''), { range: `${t.s}–${t.e}`, covers: cov.length });
    });
    // back edges: DFS from the entry
    const adj = keep.map(() => []); edges.forEach((e) => adj[e.from].push(e)); const state = new Array(keep.length).fill(0);
    const dfs = (u) => { state[u] = 1; for (const e of adj[u]) { if (state[e.to] === 1) e.back = true; else if (state[e.to] === 0) dfs(e.to); } state[u] = 2; };
    keep.forEach((b, i) => { if (!state[i] && (i === 0 || !edges.some((e) => e.to === i && !e.back))) dfs(i); }); keep.forEach((b, i) => { if (!state[i]) dfs(i); });
    keep.forEach((b) => { b.entry = b.id === 0; });
    return { blocks: keep, edges, items, headEnd: first < 0 ? items.length : first };
  };

  // ---- lightbox ----------------------------------------------------------------------------------------------------------------
  const C = R._cfg = { open: false };
  const COL = { fall: '#8a94a6', true: '#3fb68b', false: '#ff5c7a', goto: '#5b9cff', case: '#b197fc', exc: '#f0a030' };
  function methodOf(cls, key, phase) {
    const D = R.ST.D, cv = D.irClassVersion(cls, phase); if (!cv) return null; const W = S().viewOf(cv);
    for (const id of cv.ids) { const n = D.nodes.get(id); if (n && n.kind === 'MethodNode' && n.name + n.desc === key) { const o = W.byId.get(id); if (o) return { cv, W, id, lo: o[1], hi: o[2] }; } }
    return null;
  }
  function historyOf(cls, key) {
    const D = R.ST.D, c = D.jvmClasses.get(cls), out = []; let last = null;
    c.versions.forEach((cv) => { const W = S().viewOf(cv); for (const id of cv.ids) { const n = D.nodes.get(id); if (n && n.kind === 'MethodNode' && n.name + n.desc === key) { const o = W.byId.get(id); if (o) { const t = W.text.slice(o[1], o[2]).replace(/^\s*\/\/ access flags .*$/m, ''); if (t !== last) { out.push(cv.phase); last = t; } } } } });
    return out;
  }
  R.cfgOpen = function (cls, key, phase) {
    const D = R.ST.D; if (!D || !D.hasIR) return; if (typeof dagre === 'undefined') { alert('dagre failed to load (needs cdnjs)'); return; }
    const hist = historyOf(cls, key); if (!hist.length) return;
    let hi = 0; hist.forEach((p, i) => { if (p <= (phase ?? D.irGenPhase)) hi = i; });
    C.cls = cls; C.key = key; C.hist = hist; R.ST._mSel = R.ST.selNode; C.hi = hi; C.fold = new Map(); C.seen = null; C.k = 1; C.x = 0; C.y = 0; C.focus = null;
    if (!C.built) { build(); C.built = true; } if (!C.open) { C.open = true; document.addEventListener('keydown', onKey, true); }
    $('cfgov').hidden = false; place(); render(false); { const v0 = R.ver(); C.seen = v0 && v0.irPseudo ? v0.phase : C.hist[C.hi]; }
    // the viewer follows the lightbox: put it on the JVM phase being shown (opening from a tree version moves it to bytecode)
    C.syncing = true; try { R.ST.irMethod = key; R.ST.cls = cls; const k = R.vers().findIndex((x) => x.irPseudo && x.phase === C.hist[C.hi]); const v = R.ver(); if (k >= 0 && !(v && v.irPseudo)) R.setPhase(k); else R.set({ irMethod: key, cls }); } finally { C.syncing = false; } R.ST._mExplicit = true; { const mo = R.methodOfNode(R.ST.selNode); if (!mo || mo.key !== key || mo.cls !== cls) { const id = R.methodNodeId(cls, key, C.hist[C.hi]); if (id != null) R.set({ selNode: id, selSym: null }); } } R.scheduleHash && R.scheduleHash();
  };
  // the lightbox covers the main panes only: header, timeline (axis, bands, phase select) and the explorer with the chapter pager stay operable
  function place() { const m = document.querySelector('main'), ov = $('cfgov'); if (!m) return; const r = m.getBoundingClientRect(); Object.assign(ov.style, { left: r.left + 'px', top: r.top + 'px', width: r.width + 'px', height: r.height + 'px' }); }
  R.cfgKey = () => (C.open ? C.key : null);
  R.cfgClose = function () { if (!C.open) return; C.open = false; R.scheduleHash && setTimeout(() => R.scheduleHash(), 0); $('cfgov').hidden = true; document.removeEventListener('keydown', onKey, true); };
  R.cfgOpenSelected = function () {
    const D = R.ST.D, st = R.ST; if (!D || !D.hasIR) return false; const v = R.ver(), phase = v && v.irPseudo ? v.phase : D.irGenPhase;
    const mo = R.methodOfNode(st.selNode); if (mo && mo.cls) { R.cfgOpen(mo.cls, mo.key, phase); return true; }
    if (st.irMethod) { R.cfgOpen(R.curClass(), st.irMethod, phase); return true; }
    return false;
  };
  function build() {
    const ov = document.createElement('div'); ov.id = 'cfgov'; ov.innerHTML = `<div id="cfgbd"></div><div id="cfgbox"><div id="cfghead"><b id="cfgtitle"></b><span id="cfgphase"></span><span id="cfgcnt"></span><span id="cfgclo"></span><label id="cfgcompactl" hidden title="fold large blocks (still never the ones changed in this step)"><input type="checkbox" id="cfgcompact"> compact</label><span id="cfgcue"></span><button id="cfgprev" title="previous JVM phase of this method ([)">◀ [</button><button id="cfgnext" title="next JVM phase of this method (])">] ▶</button><button id="cfgfit" title="fit to view (f)">⤢ fit</button><span id="cfglegend"></span><button id="cfgx" title="Esc">✕</button></div><div id="cfgbody"><div id="cfggraph"><div id="cfgcanvas"><svg id="cfgsvg"></svg></div><canvas id="cfgmini" width="160" height="110"></canvas></div><div id="cfglist" class="bc"></div></div></div>`;
    document.body.append(ov); window.addEventListener('resize', () => { if (C.open) { place(); fit(false); } }); $('cfgbd').onclick = R.cfgClose; $('cfgx').onclick = R.cfgClose; $('cfgfit').onclick = () => fit(true); $('cfgcompact').onchange = (e) => { C.compact = e.target.checked; render(true, true); }; $('cfgprev').onclick = () => step(-1); $('cfgnext').onclick = () => step(1);
    $('cfglegend').innerHTML = [['fall', 'fallthrough'], ['true', 'true'], ['false', 'false'], ['goto', 'goto'], ['case', 'switch'], ['exc', 'exception']].map(([k, t]) => `<i style="background:${COL[k]}"></i>${t}`).join(' ');
    const g = $('cfggraph'); let drag = null;
    g.addEventListener('wheel', (e) => { e.preventDefault(); const r = g.getBoundingClientRect(), mx = e.clientX - r.left, my = e.clientY - r.top, f = Math.exp(-e.deltaY * (e.ctrlKey ? 0.01 : 0.0016)), k2 = Math.max(0.08, Math.min(2.5, C.k * f)); C.x = mx - (mx - C.x) * (k2 / C.k); C.y = my - (my - C.y) * (k2 / C.k); C.k = k2; apply(); }, { passive: false });
    g.addEventListener('pointerdown', (e) => { if (e.target.closest('.cfgb') || e.target.closest('#cfgmini')) return; drag = { x: e.clientX, y: e.clientY, cx: C.x, cy: C.y }; g.setPointerCapture(e.pointerId); g.classList.add('pan'); });
    g.addEventListener('pointermove', (e) => { if (!drag) return; C.x = drag.cx + e.clientX - drag.x; C.y = drag.cy + e.clientY - drag.y; apply(); });
    g.addEventListener('pointerup', () => { drag = null; g.classList.remove('pan'); });
    $('cfgmini').addEventListener('pointerdown', (e) => { const mm = C.mini; if (!mm) return; const r = $('cfgmini').getBoundingClientRect(), gx = (e.clientX - r.left - mm.ox) / mm.s, gy = (e.clientY - r.top - mm.oy) / mm.s, vr = g.getBoundingClientRect(); C.x = vr.width / 2 - gx * C.k; C.y = vr.height / 2 - gy * C.k; apply(); });
    // hover / click inside blocks and the listing
    ov.addEventListener('mouseover', (e) => { const b = e.target.closest && e.target.closest('.cfgb'), ln = e.target.closest && e.target.closest('#cfglist .ln'), ed = e.target.closest && e.target.closest('#cfgsvg .ce'); hover(b ? +b.dataset.b : ln ? +ln.dataset.bl : null, ed, !!ln); });
    ov.addEventListener('mouseleave', () => hover(null));
    ov.addEventListener('click', (e) => {
      const h = e.target.closest && e.target.closest('.cfgb .bh'); if (h) { const b = h.parentNode, nm = b.dataset.name; C.fold.set(nm, b.dataset.folded !== '1'); render(true, true); return; }
      const n = e.target.closest && e.target.closest('.n'); if (n && n.dataset.n) R.set({ selNode: +n.dataset.n }); else { const b = e.target.closest && e.target.closest('.cfgb'); if (b) C.focus = +b.dataset.b, focusMark(); }
    });
    R.views.push(() => { if (C.open) { syncMethod(); highlights(); syncViewer(false); } });
  }
  // the viewer's phase drives the lightbox: a JVM phase that changed this method re-layouts the graph in place; others are stepped over with a cue;
  // moving back before genBCode closes the lightbox (the tree view behind it shows the method's DefDef)
  // the bytecode pane's current method (R.ST.irMethod) is the single source of truth: changing it (dropdown, selection) switches the graph
  function syncMethod() {
    const k = R.ST.irMethod, cls = R.ST.cls || C.cls; if (!k || (k === C.key && cls === C.cls)) return; if (!R.ST.D.jvmClasses.get(cls)) return;
    const hist = historyOf(cls, k); if (!hist.length) return; const v = R.ver(), p = v && v.irPseudo ? v.phase : hist[0]; let hi = 0; hist.forEach((q, i) => { if (q <= p) hi = i; });
    C.cls = cls; C.key = k; C.hist = hist; C.hi = hi; C.fold = new Map(); C.focus = null; C.seen = null; render(false); fit(false);
  }
  function syncViewer(initial) {
    const v = R.ver(); if (!v || C.syncing) return; if (!v.irPseudo) { if (!initial) R.cfgClose(); return; }
    const p = v.phase; if (C.seen === p && !initial) return; let hi = -1; C.hist.forEach((q, i) => { if (q <= p) hi = i; }); if (hi < 0) { if (!initial) R.cfgClose(); return; }
    const touched = C.hist.includes(p); C.seen = p;
    if (hi !== C.hi) { C.hi = hi; render(true, true); } else if (!touched && !initial) cue(`no change in ${C.key.replace(/\(.*$/, '')} at ${R.verName(v)}`);
  }
  function cue(t) { const el = $('cfgcue'); el.textContent = t; el.classList.add('on'); clearTimeout(C.cueT); C.cueT = setTimeout(() => el.classList.remove('on'), 1800); }
  function step(d) { const j = C.hi + d; if (j < 0 || j >= C.hist.length) return; C.hi = j; render(true, true); const k = R.vers().findIndex((x) => x.irPseudo && x.phase === C.hist[j]); if (k >= 0 && k !== R.ST.verIdx) { C.syncing = true; R.setPhase(k); C.syncing = false; } C.seen = C.hist[j]; }
  function onKey(e) {
    if (!C.open || /INPUT|SELECT|TEXTAREA/.test(e.target.tagName)) return; const k = e.key;
    if (k === 'Escape') R.cfgClose(); else if (k === '[') step(-1); else if (k === ']') step(1); else if (k === 'f') fit(true); else if (k === 'g') R.cfgClose();
    else if (k === 'Tab') { const n = C.model.blocks.length; C.focus = C.focus == null ? 0 : (C.focus + (e.shiftKey ? n - 1 : 1)) % n; focusMark(true); }
    else if ((k === 'ArrowLeft' || k === 'ArrowRight') && C.focus == null) { R.pageTurn(k === 'ArrowRight' ? 1 : -1); }   // ← → turn pages until a block has focus (Tab / click), then they move the focus
    else if (/^Arrow/.test(k)) { moveFocus(k); }
    else if (k === 'Enter' && C.focus != null) { const el = $('cfgcanvas').querySelector(`.cfgb[data-b="${C.focus}"] .n`); if (el) R.set({ selNode: +el.dataset.n }); }
    else return; e.preventDefault(); e.stopPropagation();
  }
  function moveFocus(k) {
    const P = C.pos; if (!P) return; if (C.focus == null) { C.focus = 0; focusMark(true); return; }
    const c = P[C.focus]; let best = null, bd = Infinity;
    P.forEach((p, i) => { if (i === C.focus) return; const dx = p.x - c.x, dy = p.y - c.y; const ok = k === 'ArrowDown' ? dy > 5 : k === 'ArrowUp' ? dy < -5 : k === 'ArrowRight' ? dx > 5 && Math.abs(dy) < 200 : dx < -5 && Math.abs(dy) < 200; if (!ok) return; const d = k === 'ArrowDown' || k === 'ArrowUp' ? Math.abs(dy) + Math.abs(dx) * 0.4 : Math.abs(dx) + Math.abs(dy) * 0.4; if (d < bd) { bd = d; best = i; } });
    if (best != null) { C.focus = best; focusMark(true); }
  }
  function focusMark(pan) {
    $('cfgcanvas').querySelectorAll('.cfgb.foc').forEach((e) => e.classList.remove('foc')); const el = $('cfgcanvas').querySelector(`.cfgb[data-b="${C.focus}"]`); if (!el) return; el.classList.add('foc');
    if (pan && C.pos) { const g = $('cfggraph').getBoundingClientRect(), p = C.pos[C.focus]; const sx = p.x * C.k + C.x, sy = p.y * C.k + C.y; if (sx < 60 || sx > g.width - 60 || sy < 40 || sy > g.height - 40) { C.x = g.width / 2 - p.x * C.k; C.y = g.height / 2 - p.y * C.k; apply(); } }
    hover(C.focus, null, false);
  }
  function apply() { $('cfgcanvas').style.transform = `translate(${C.x}px,${C.y}px) scale(${C.k})`; drawMini(); }
  function fit(anim) {
    const g = $('cfggraph').getBoundingClientRect(), b = C.bbox; if (!b || !g.width) return; const k = Math.min(1, (g.width - 40) / b.w, (g.height - 40) / b.h);
    const cv = $('cfgcanvas'); cv.style.transition = anim ? 'transform .3s ease' : 'none'; C.k = Math.max(0.3, k); /* big graphs start at a readable zoom and are panned / zoomed from the minimap */ C.x = (g.width - b.w * C.k) / 2 - b.x * C.k; C.y = Math.max(20, (g.height - b.h * C.k) / 2) - b.y * C.k + (g.height - b.h * C.k < 40 ? 0 : 0); apply(); setTimeout(() => { cv.style.transition = ''; }, 320);
  }
  function drawMini() {
    const cv = $('cfgmini'), b = C.bbox, g = $('cfggraph').getBoundingClientRect(); if (!b || !C.pos) return;
    const over = b.w * C.k > g.width * 1.05 || b.h * C.k > g.height * 1.05; cv.style.display = over ? '' : 'none'; if (!over) return;
    const w = cv.width, h = cv.height, s = Math.min((w - 8) / b.w, (h - 8) / b.h), ox = (w - b.w * s) / 2 - b.x * s, oy = (h - b.h * s) / 2 - b.y * s, ctx = cv.getContext('2d'); C.mini = { s, ox, oy };
    ctx.clearRect(0, 0, w, h); ctx.fillStyle = 'rgba(13,18,28,.85)'; ctx.fillRect(0, 0, w, h);
    C.pos.forEach((p, i) => { ctx.fillStyle = i === C.focus ? '#ffd666' : 'rgba(120,160,220,.7)'; ctx.fillRect(ox + (p.x - p.w / 2) * s, oy + (p.y - p.h / 2) * s, Math.max(2, p.w * s), Math.max(2, p.h * s)); });
    ctx.strokeStyle = '#ffd666'; ctx.lineWidth = 1; ctx.strokeRect(ox + (-C.x / C.k) * s, oy + (-C.y / C.k) * s, (g.width / C.k) * s, (g.height / C.k) * s);
  }
  function hover(bi, edgeEl, fromList) {
    if (bi != null) C.hoverB = bi;
    const cv = $('cfgcanvas'), svg = $('cfgsvg'); cv.classList.toggle('hv', bi != null || !!edgeEl); svg.classList.toggle('hv', bi != null || !!edgeEl);
    cv.querySelectorAll('.cfgb.hl, .cfgb.nb').forEach((e) => e.classList.remove('hl', 'nb')); svg.querySelectorAll('.ce.hl').forEach((e) => e.classList.remove('hl'));
    $('cfglist').querySelectorAll('.ln.hl').forEach((e) => e.classList.remove('hl'));
    let ids = null; if (edgeEl) ids = [+edgeEl.dataset.f, +edgeEl.dataset.t], edgeEl.classList.add('hl'); else if (bi != null) ids = [bi];
    if (!ids) return;
    ids.forEach((i) => { const el = cv.querySelector(`.cfgb[data-b="${i}"]`); if (el) el.classList.add('hl'); });
    if (bi != null && !edgeEl) svg.querySelectorAll('.ce').forEach((e) => { if (+e.dataset.f === bi || +e.dataset.t === bi) { e.classList.add('hl'); const o = +e.dataset.f === bi ? +e.dataset.t : +e.dataset.f; const el = cv.querySelector(`.cfgb[data-b="${o}"]`); if (el) el.classList.add('nb'); } });
    ids.forEach((i) => { $('cfglist').querySelectorAll(`.ln[data-bl="${i}"]`).forEach((e) => e.classList.add('hl')); });
    if (!fromList && bi != null) { const first = $('cfglist').querySelector(`.ln[data-bl="${bi}"]`); if (first) { const l = $('cfglist'), r = first.getBoundingClientRect(), lr = l.getBoundingClientRect(); if (r.top < lr.top + 20 || r.bottom > lr.bottom - 20) l.scrollTop += r.top - lr.top - lr.height / 3; } }
  }
  function sets() {
    const D = R.ST.D, st = R.ST, links = R.irLinks ? R.irLinks() : { ir: new Set() }, sel = new Set(); if (st.selNode != null && D.nodes.get(st.selNode) && D.nodes.get(st.selNode).ir) sel.add(st.selNode);
    return { sel, lnk: links.ir };
  }
  function highlights() {
    const { sel, lnk } = sets(); document.querySelectorAll('#cfgov [data-n]').forEach((el) => { const id = +el.dataset.n; el.classList.toggle('sel', sel.has(id)); el.classList.toggle('lnk', lnk.has(id) && !sel.has(id)); });
  }
  // ---- render -------------------------------------------------------------------------------------------------------------------
  function render(animate, keepView) {
    const D = R.ST.D, phase = C.hist[C.hi], m = methodOf(C.cls, C.key, phase);
    $('cfgtitle').textContent = C.key; $('cfgphase').innerHTML = `${esc(R.verName({ irPseudo: true, phase }))} <span class="dim">(${C.hi + 1}/${C.hist.length} changes of this method)</span>`;
    { const ops = (t) => t.split('\n').filter((l) => /^\s+[A-Z][A-Z0-9_]+\b/.test(l) && !/^\s+(LINENUMBER|FRAME|LOCALVARIABLE|MAXSTACK|MAXLOCALS|TRYCATCHBLOCK)\b/.test(l)), cur = ops(m ? m.W.text.slice(m.lo, m.hi) : '');
      const pm = C.hi > 0 ? methodOf(C.cls, C.key, C.hist[C.hi - 1]) : null, prev = pm ? ops(pm.W.text.slice(pm.lo, pm.hi)) : null; let a = 0, d = 0;
      if (prev) { const d2 = R.countDiff(prev.join('\n'), cur.join('\n')); a = d2.add; d = d2.del; } else a = cur.length;
      const elided = []; (D.irDerives || []).forEach((d) => { if (d.phase === phase && d.how === 'elide') d.old.forEach((o) => { if (D.irClassOf.get(o) === C.cls) elided.push(o); }); });
      const cl = R.closureCounts ? R.closureCounts(C.cls, phase, elided, C.key) : { rewritten: 0, dead: 0 };
      $('cfgclo').innerHTML = (cl.rewritten ? `<span class="cchip ci" title="indy closure call replaced by a direct call + inlined body">${cl.rewritten} closure invocation${cl.rewritten > 1 ? 's' : ''} rewritten</span>` : '') + (cl.dead ? `<span class="cchip cd" title="closure allocations whose result was only POPped, removed here">${cl.dead} dead allocation${cl.dead > 1 ? 's' : ''} removed</span>` : '');
      $('cfgcnt').innerHTML = prev ? `<b style="color:#4cc38a">+${a}</b> <b style="color:#ff5c7a">−${d}</b> <span class="dim">instr</span>` : `<b style="color:#4cc38a">+${a}</b> <span class="dim">instr emitted</span>`; }
    $('cfgprev').disabled = C.hi <= 0; $('cfgnext').disabled = C.hi >= C.hist.length - 1;
    if (!m) { $('cfgcanvas').querySelectorAll('.cfgb').forEach((e) => e.remove()); $('cfgsvg').innerHTML = ''; $('cfglist').innerHTML = '<div class="dim" style="padding:12px">the method does not exist in this phase (elided)</div>'; return; }
    const text = m.W.text.slice(m.lo, m.hi), model = R.cfgParse(text, m.lo), old = C.model, oldEls = new Map();
    const cv = $('cfgcanvas'); cv.querySelectorAll('.cfgb').forEach((e) => oldEls.set(+e.dataset.b, e));
    C.model = model; C.m = m;
    // block cards
    S().setLang('bc'); const { sel, lnk } = sets(), hows = new Map();
    D.irByNew && m.cv.ids.forEach((id) => { (D.irByNew.get(id) || []).forEach((d) => { if (d.phase === phase && d.how !== 'codegen') hows.set(id, d.how); }); });
    const clsF = (id) => { const nd = D.nodes.get(id), h = hows.get(id); return (nd ? 'k-' + nd.kind + ' ' : '') + (h === 'rewrite' ? 'new ir-rw ' : h === 'inlined-from' ? 'new ir-inl ' : h === 'copy' ? 'ir-cp ' : '') + (sel.has(id) ? 'sel ' : lnk.has(id) ? 'lnk ' : ''); };
    const ids = new Map(); // per block: range ids
    const els = model.blocks.map((b) => {
      const el = document.createElement('div'); el.className = 'cfgb' + (b.entry ? ' entry' : '') + (b.exit ? ' exit' : ''); el.dataset.b = b.id; el.tabIndex = -1;
      const rg = new Set(); b.items.forEach((it) => { if (it.kind === 'insn') { const o = innermost(m.W, it.abs); if (o) rg.add(o); } }); ids.set(b.id, rg);
      // folding is never automatic by size: an explicit per-label toggle (remembered across phase steps), or the compact mode of very large methods; blocks with a changed /
      // selected / linked instruction in this step are never folded
      const hot = [...rg].some((id) => hows.has(id) || sel.has(id) || lnk.has(id)), exp = C.fold.get(b.name), big = b.insns > 6;
      const collapsed = !hot && big && (exp === true || (exp === undefined && C.compact && b.insns > 8)); el.dataset.name = b.name; el.dataset.folded = collapsed ? '1' : '0';
      el.innerHTML = `<div class="bh"><b>${esc(b.name)}</b><span>${b.insns} insn${b.insns === 1 ? '' : 's'} · lines ${b.firstLine + 1}–${b.lastLine + 1}</span>${b.entry ? '<em class="tag">entry</em>' : ''}${b.exit ? `<em class="tag ex">${esc(b.exit.toLowerCase())}</em>` : ''}${hot ? '<em class="tag hotag" title="changed, selected or linked in this step: never folded">●</em>' : ''}${big ? `<em class="tag tg" title="fold / unfold this block">${collapsed ? '▸' : '▾'}</em>` : ''}</div><div class="bb bc"></div>`;
      const body = el.querySelector('.bb'); body.append(S().lineify(S().build(m.W.text, m.W.offs, clsF, b.lo, b.hi + 1)));
      const lns = [...body.children]; while (lns.length && !lns[lns.length - 1].textContent.trim()) lns.pop().remove();
      lns.forEach((ln) => { if (/^\s*(LINENUMBER|FRAME|LOCALVARIABLE|MAXSTACK|MAXLOCALS)\b/.test(ln.textContent)) ln.classList.add('minor'); });
      // dead pushes: a POP straight after an instruction that only pushes (load, constant, closure allocation, dup) is tagged
      { let prev = null; const PUSH = /^\s+([ILFDA]LOAD|[ILFDA]CONST_\w+|BIPUSH|SIPUSH|LDC|ACONST_NULL|DUP|DUP2|INVOKEDYNAMIC)\b/; lns.forEach((ln) => { const t = ln.textContent; if (!/^\s{4}[A-Z]/.test(t) || ln.classList.contains('minor')) return; if (/^\s+POP2?\b/.test(t) && prev && PUSH.test(prev.t)) { const c = document.createElement('span'); c.className = 'deadchip'; c.textContent = 'dead'; c.title = /INVOKEDYNAMIC/.test(prev.t) ? 'dead closure allocation: its result is only POPped (a later pushPop removes the pair)' : 'dead push: its result is only POPped'; ln.append(c); ln.classList.add('deadln'); } prev = { t }; }); }
      if (collapsed && big) { const vis = lns.filter((ln) => !ln.classList.contains('minor')); const keepSet = new Set([...vis.slice(0, 3), ...vis.slice(-2)]); let hidden = 0, ins = null; lns.forEach((ln) => { if (!keepSet.has(ln)) { ln.style.display = 'none'; if (!ins && lns.indexOf(ln) > 0) ins = ln; hidden++; } }); if (ins) { const d = document.createElement('div'); d.className = 'ln more'; d.textContent = `… ${b.insns - 5 > 0 ? b.insns - 5 : hidden} more instructions`; ins.before(d); } }
      return el;
    });
    { const tot = model.blocks.reduce((a, b) => a + b.insns, 0); $('cfgcompactl').hidden = tot <= 400; }
    // measure (unscaled), lay out with dagre
    const frag = document.createDocumentFragment(); els.forEach((el) => { el.style.visibility = 'hidden'; el.style.left = '0px'; el.style.top = '0px'; frag.append(el); }); const svg = $('cfgsvg');
    cv.append(frag); const sz = els.map((el) => [el.offsetWidth, el.offsetHeight]);
    const g = new dagre.graphlib.Graph({ multigraph: true }); g.setGraph({ rankdir: 'TB', ranksep: 46, nodesep: 34, edgesep: 14, marginx: 20, marginy: 20, acyclicer: 'greedy', ranker: 'network-simplex' }); g.setDefaultEdgeLabel(() => ({}));
    model.blocks.forEach((b) => g.setNode('b' + b.id, { width: sz[b.id][0], height: sz[b.id][1] }));
    model.edges.forEach((e, i) => g.setEdge('b' + e.from, 'b' + e.to, { width: Math.max(14, (e.label || '').length * 6 + 8), height: e.label ? 14 : 0, labelpos: 'c' }, 'e' + i));
    dagre.layout(g); const gr = g.graph();
    C.pos = model.blocks.map((b) => { const n = g.node('b' + b.id); return { x: n.x, y: n.y, w: n.width, h: n.height }; }); C.bbox = { x: 0, y: 0, w: gr.width, h: gr.height };
    // match old blocks to new ones (same / derived ranges) so they move instead of popping
    const match = new Map(); if (old && animate && C.lastIds) { const used = new Set(); model.blocks.forEach((b) => { const nid = ids.get(b.id); let best = -1, bs = 0; C.lastIds.forEach((oset, oi) => { if (used.has(oi)) return; let ov = 0; oset.forEach((o) => { if (nid.has(o)) ov++; else (D.irByOld.get(o) || []).forEach((d) => d.new.forEach((x) => { if (nid.has(x)) ov++; })); }); if (ov > bs) { bs = ov; best = oi; } }); if (best >= 0) { match.set(b.id, best); used.add(best); } }); }
    const dur = animate ? 420 : 0; const oldPos = C.lastPos;
    // the block the user was looking at stays where it is on screen: anchor on the hovered / selected instruction's block, else the entry block
    let anchorOld = null; if (oldPos && keepView) { anchorOld = C.hoverB != null && C.hoverB < oldPos.length ? C.hoverB : null; if (anchorOld == null && R.ST.selNode != null && C.lastIds) C.lastIds.forEach((set, bi) => { if (anchorOld == null && set.has(R.ST.selNode)) anchorOld = bi; }); if (anchorOld == null) anchorOld = 0; }
    if (anchorOld != null && match.size) { let j = -1; match.forEach((oi, nj) => { if (oi === anchorOld) j = nj; }); if (j < 0) j = match.has(0) ? 0 : [...match.keys()][0]; const op = oldPos[anchorOld], np = C.pos[j]; if (op && np) { C.x = op.x * C.k + C.x - np.x * C.k; C.y = op.y * C.k + C.y - np.y * C.k; C.animView = true; } }
    els.forEach((el, i) => {
      const p = C.pos[i], o = match.has(i) && oldPos ? oldPos[match.get(i)] : null;
      el.style.transition = 'none'; if (o) { el.style.left = o.x - o.w / 2 + 'px'; el.style.top = o.y - o.h / 2 + 'px'; el.style.opacity = '1'; } else { el.style.left = p.x - p.w / 2 + 'px'; el.style.top = p.y - p.h / 2 + 'px'; el.style.opacity = animate ? '0' : '1'; }
      el.style.visibility = ''; void el.offsetWidth; el.style.transition = dur ? `left ${dur}ms ease, top ${dur}ms ease, opacity ${dur}ms ease` : 'none'; el.style.left = p.x - p.w / 2 + 'px'; el.style.top = p.y - p.h / 2 + 'px'; el.style.opacity = '1';
    });
    // removed blocks fade out as ghosts at their old place
    oldEls.forEach((el, oi) => { const matched = [...match.values()].includes(oi); if (!animate || matched) { el.remove(); return; } el.classList.add('ghost'); el.style.transition = 'opacity .45s'; el.style.opacity = '0'; el.style.pointerEvents = 'none'; setTimeout(() => el.remove(), 500); });
    // the old ones that were matched are replaced by the new elements (already appended)
    oldEls.forEach((el, oi) => { if ([...match.values()].includes(oi)) el.remove(); });
    C.lastIds = ids; C.lastPos = C.pos;
    drawEdges(model, g, dur);
    // listing
    const lis = $('cfglist'); S().setLang('bc'); lis.replaceChildren(S().lineify(S().build(m.W.text, m.W.offs, clsF, m.lo, m.hi + 1)));
    const lines = [...lis.children], lineBlock = new Map(); model.blocks.forEach((b) => { for (let l = b.firstLine; l <= b.lastLine; l++) lineBlock.set(l, b.id); });
    lines.forEach((ln, i) => { ln.dataset.l = i; if (lineBlock.has(i)) ln.dataset.bl = lineBlock.get(i); });
    if (!keepView) fit(false); else drawMini(); { const cvs = $('cfgcanvas'); if (C.animView) { cvs.style.transition = 'transform .42s ease'; setTimeout(() => { cvs.style.transition = ''; }, 450); C.animView = false; } } apply(); if (C.focus != null) focusMark(false);
  }
  function innermost(W, abs) { let best = null; for (const o of W.offs) { if (o[1] > abs) break; if (o[2] > abs) { const n = R.ST.D.nodes.get(o[0]); if (n && n.kind === 'InsnRange' && (!best || o[2] - o[1] <= best[2] - best[1])) best = o; } } return best ? best[0] : null; }
  function drawEdges(model, g, dur) {
    const svg = $('cfgsvg'); svg.setAttribute('width', C.bbox.w); svg.setAttribute('height', C.bbox.h); svg.style.width = C.bbox.w + 'px'; svg.style.height = C.bbox.h + 'px';
    const line = typeof d3 !== 'undefined' ? d3.line().x((p) => p.x).y((p) => p.y).curve(d3.curveBasis) : null;
    let h = `<defs>${Object.entries(COL).map(([k, c]) => `<marker id="ar-${k}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="${c}"/></marker>`).join('')}</defs>`;
    model.edges.forEach((e, i) => {
      const d = g.edge({ v: 'b' + e.from, w: 'b' + e.to, name: 'e' + i }); if (!d || !d.points) return; const col = COL[e.kind], pts = d.points;
      const path = line ? line(pts) : 'M' + pts.map((p) => p.x + ',' + p.y).join(' L');
      h += `<g class="ce${e.back ? ' back' : ''}" data-f="${e.from}" data-t="${e.to}"><path class="hit" d="${path}"/><path class="vis" d="${path}" stroke="${col}" ${e.kind === 'exc' ? 'stroke-dasharray="6 4"' : ''} stroke-width="${e.back ? 3 : e.kind === 'fall' ? 1.4 : 1.8}" marker-end="url(#ar-${e.kind})" fill="none"/>`;
      if (e.label || e.back) { const mid = pts[Math.floor(pts.length / 2)], lx = d.x ?? mid.x, ly = d.y ?? mid.y; h += `<text x="${lx}" y="${ly + 3}" text-anchor="middle" fill="${col}" font-size="10" class="el">${e.back ? '↺ ' : ''}${esc(e.label)}</text>`; }
      h += `<title>${esc(e.kind)}${e.range ? ' ' + esc(e.range) : ''}${e.covers > 1 ? ` (covers ${e.covers} blocks)` : ''}</title></g>`;
    });
    svg.style.transition = 'none'; svg.style.opacity = dur ? '0' : '1'; svg.innerHTML = h; if (dur) { void svg.getBoundingClientRect(); svg.style.transition = `opacity ${dur}ms ease`; svg.style.opacity = '1'; }
  }
})(window.R = window.R || {});
