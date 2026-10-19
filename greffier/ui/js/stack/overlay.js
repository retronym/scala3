// Stack overlay glue: opens over the viewer, keeps the two in sync (the stack's front card sets the viewer's phase and cursor, the
// viewer's selected node is traced on open, a click on a line selects that node in the viewer, unit / skip / structural options
// rebuild the cards), plus the minimap and the lineage chronicle. The viewer's heavy views do not render while the overlay is open.
(function (R) {
  const M = (R.stk = R.stk || {}), $ = (id) => document.getElementById(id), esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  let stack = null; const S = { cards: [], list: [], sig: null, timer: null, last: null }; R._stk = S;
  R.stackOpen = false;

  function ensure() {
    if (stack) return stack;
    stack = R._stk.st = new M.Stack($('sstage'), {
      onList: (list) => { S.list = list; drawMini(); },
      onFront: (c) => { setTimeout(renderPager, 0); $('sfront').innerHTML = c ? `<b>${c.i + 1}/${S.cards.length}</b> ${esc(c.name)}${c.kind === 'ir' ? ' <span class=dimt>· ' + esc(c.cls.replace(/^.*\//, '')) + '</span>' : ''}${c.noop ? ' <span class=dimt>(no change)</span>' : ''}` : ''; document.querySelectorAll('#schron .ce').forEach((e) => e.classList.toggle('cur', c && +e.dataset.i === c.i)); syncViewer(c); },
      onTrace: (tr) => { renderChron(tr); drawMini(); },
      // the traced code lives in another class than the selected one: switch the JVM segment there and trace again
      onRange: (g) => onRange(g), onPage: (d) => pageTurn(d),
      onSwitchClass: (cls, c, n) => {
        const prev = R.curClass(); R.ST.cls = cls; S.keep = { kind: c.kind, phase: c.phase }; build(false); S.note = `bytecode is in class ${cls.replace(/^.*\//, '')} (was ${(prev || '').replace(/^.*\//, '')})`;
        const tc = S.cards.find((x) => x.kind === c.kind && x.phase === c.phase) || S.cards[0]; if (tc) stack.traceNode(tc, n);
      },
      onSelect: (n) => { const nd = R.ST.D.nodes.get(n); R.set({ selNode: n, selSym: nd ? nd.sym : null }); },
      onFrame: (u, sMax) => { const h = $('smini').clientHeight; $('smark').style.transform = `translateY(${(sMax ? u / sMax : 0) * (h - 16) + 8}px)`; },
    });
    R.stackObj = stack; return stack;
  }

  const viewerIndex = (c) => (c.kind === 'ir' ? R.vers().findIndex((v) => v.irPseudo && v.phase === c.phase) : R.vers().indexOf(c.v));
  // the front card moves the viewer's phase (and cursor to that version's seq): throttled, trailing
  function syncViewer(c) {
    if (!c || !R.stackOpen || S.opening) return; S.last = c;
    if (S.timer) return;
    S.timer = setTimeout(() => { S.timer = null; const c2 = S.last; if (!c2 || !R.stackOpen) return; const k = viewerIndex(c2); if (k >= 0 && k !== R.ST.verIdx) { R.ST.follow = true; if (c2.kind === 'ir') R.ST.cls = c2.cls; R.setPhase(k); } }, 90);
  }

  const status = () => { const D = R.ST.D;
  const nn = S.cards.filter((c) => c.noop).length, ns = S.cards.filter((c) => c.star).length;
  $('sstatus').textContent = `${((D.units.get(R.ST.unit) || {}).path || '').replace(/^.*\//, '')} · ${S.cards.length} versions (${nn} no change, ${ns} ★)${R.ST.skip ? ' · skipping no-ops' : ''}${R.ST.structOnly ? ' · structural lines only' : ''}`;
  };
  function build(keepPhase) {
    const D = R.ST.D, st = ensure(); S.cards = M.buildCards(); S.sig = sig();
    const units = D.unitsWithVersions.filter((u) => D.versionsOf(u).some((v) => !v.ir));
    const us = $('sunit'); us.innerHTML = units.map((u) => `<option value="${u}">${esc(((D.units.get(u) || {}).path || 'unit ' + u).replace(/^.*\//, ''))}</option>`).join(''); us.value = R.ST.unit; us.disabled = units.length < 2;
    st.setData(D, S.cards); renderChron(null);
    // class selector for the JVM segment
    const cl = D.hasIR ? [...D.jvmClasses.values()].filter((c) => c.unit === R.ST.unit) : [], cs = $('sclass'); $('sclslab').hidden = !cl.length;
    cs.innerHTML = cl.map((c) => `<option${c.name === R.curClass() ? ' selected' : ''}>${esc(c.name)}</option>`).join('');
    const firstIR = S.cards.find((c) => c.kind === 'ir'); st.marks = firstIR ? [{ card: firstIR, text: `JVM bytecode · class ${esc(firstIR.cls.replace(/^.*\//, ''))}` }] : [];
    // front: the viewer's current version (bytecode shown: the card of that JVM phase), or the card that was in front before a rebuild
    const cur = R.ver(); let k = -1;
    if (keepPhase === false && S.keep) { S.cards.forEach((c, i) => { if (c.kind === S.keep.kind && c.phase <= S.keep.phase) k = i; }); if (k < 0) k = S.cards.findIndex((c) => c.kind === S.keep.kind); }
    else if (cur && cur.irPseudo) S.cards.forEach((c, i) => { if (c.kind === 'ir' && c.phase <= cur.phase) k = i; });
    else if (cur) { k = S.cards.findIndex((c) => c.v === cur); if (k < 0) S.cards.forEach((c, i) => { if (c.kind === 'tree' && c.phase <= cur.phase) k = i; }); }
    if (k < 0) k = S.cards.length - 1; if (k < 0) return;
    st.ensure && S.cards[k] && st.ensure(S.cards[k]); st.jumpTo(S.cards[k].s, true); st.u = st.target = S.cards[k].s; S.fsig = fsig();
    if (R.computeLines) R.computeLines(R.ST.unit);
    status();
    { const r = R.ST.range; if (r && r.unit === R.ST.unit) setTimeout(() => { if (!R.stackOpen || stack.rg) return; const c = S.cards.find((x) => x.kind === (r.ir ? 'ir' : 'tree') && x.phase === r.phase && (!r.ir || x.cls === r.cls)); if (c) { const keep = S.cards[k]; stack.setRange(c, r.lo, r.hi, false); if (keep) stack.jumpTo(keep.s, true); } }, 50); }
    // the selected node is traced right after opening (its thread appears without a click)
    if (R.ST.selNode != null && keepPhase !== false) setTimeout(() => { if (!R.stackOpen) return; const sel = R.ST.selNode, isIR = !!(R.ST.D.nodes.get(sel) || {}).ir; let c = S.cards[k], n = null;
      if (isIR) { c = S.cards.find((x) => x.kind === 'ir' && x.v.ids.has(sel)) || c; if (c.v.ids.has(sel)) n = sel; } else if (c.kind === 'tree') n = [...R.present(sel, c.v)][0] ?? (c.v.ids.has(sel) ? sel : null); else { const t = S.cards.filter((x) => x.kind === 'tree').pop(); if (t) { c = t; n = [...R.present(sel, t.v)][0] ?? null; } }
      if (n != null && c) st.traceNode(c, n); }, 0);
  }
  const sig = () => [R.ST.D && R.ST.D.nodes.size, R.ST.unit, R.ST.skip, R.ST.collapse, R.ST.structOnly, R.vers().filter((v) => !v.irPseudo).length, R.curClass && R.curClass(), R.ST.gran, R.ST.chThrNodes, R.ST.chThrPct, R.ST.chMetric, R.ST.chMode].join('|');
  const fsig = () => [R.ST.trivialNoop !== false, R.scoreState && R.scoreState.lines.size].join('|');

  // the overlay owns wheel/touch input while open: the page behind must not scroll, even when the stack cannot move further
  const lockPage = (on) => {
    const b = document.body, de = document.documentElement;
    if (on) { S.scroll = [window.scrollX, window.scrollY]; S.ovf = [b.style.overflow, de.style.overflow]; b.style.overflow = de.style.overflow = 'hidden'; }
    else if (S.ovf) { b.style.overflow = S.ovf[0]; de.style.overflow = S.ovf[1]; S.ovf = null; if (S.scroll) window.scrollTo(S.scroll[0], S.scroll[1]); }
  };
  { const ov = $('stackov'); const stop = (e) => { if (R.stackOpen && !(e.target.closest && e.target.closest('#schron'))) e.preventDefault(); };
    ov.addEventListener('wheel', stop, { passive: false }); ov.addEventListener('touchmove', stop, { passive: false }); }
  R.openStack = function () {
    const D = R.ST.D; if (!D || R.stackOpen) return;
    const t0 = performance.now(); S.opening = true;
    R.stackOpen = true; $('stackov').hidden = false; lockPage(true); document.querySelectorAll('#viewtabs button').forEach((b) => b.classList.toggle('on', b.dataset.v === 'stack'));
    const st = ensure(); build(); st.start();
    S.opening = false; R.stackStats = { openMs: +(performance.now() - t0).toFixed(1) };
  };
  R.closeStack = function () {
    if (!R.stackOpen) return; R.stackOpen = false; $('stackov').hidden = true; lockPage(false); if (stack) stack.stop();
    if (S.timer) { clearTimeout(S.timer); S.timer = null; const c = S.last, k = c ? viewerIndex(c) : -1; if (k >= 0 && k !== R.ST.verIdx) { R.ST.follow = true; if (c.kind === 'ir') R.ST.cls = c.cls; R.setPhase(k); } }
    document.querySelectorAll('#viewtabs button').forEach((b) => b.classList.remove('on')); if (R.setView) R.setView(document.querySelector('main').classList.contains('max-scrub') ? 'scrub' : document.querySelector('main').classList.contains('max-insp') ? 'insp' : document.querySelector('main').classList.contains('max-proj') ? 'proj' : 'all'); R.render();
  };
  // Esc: first drop an active trace, then close
  R.stackEsc = () => { if (stack && stack.rg) { stack.clearRange(); return; } if (stack && stack.tr) { stack.clearTrace(); return; } R.closeStack(); };

  // ---- minimap ----
  function drawMini() {
    const cv = $('sminic'), box = $('smini'), dpr = devicePixelRatio || 1, w = box.clientWidth, h = box.clientHeight; if (!w || !h) return;
    cv.width = w * dpr; cv.height = h * dpr; cv.style.width = w + 'px'; cv.style.height = h + 'px'; const g = cv.getContext('2d'); g.scale(dpr, dpr); g.clearRect(0, 0, w, h);
    const list = S.list; if (!list.length) return; const sMax = (stack && stack.sMax) || 1, mx = Math.max(1, ...list.map((c) => (c.ready ? c.add + c.del : 0))), y = (c) => (c.s / sMax) * (h - 16) + 8, tr = stack && stack.tr;
    g.font = '10px ui-monospace, Menlo, monospace'; g.textBaseline = 'middle';
    for (const c of list) {
      const yy = y(c), amt = c.ready ? (c.add + c.del) / mx : 0; g.fillStyle = c.star ? '#ffd666' : c.noop ? 'rgba(140,160,190,0.25)' : 'rgba(170,190,220,0.6)';
      const len = c.noop ? 6 : 10 + 30 * Math.sqrt(amt); g.fillRect(w - 8 - len, yy - (c.star ? 1 : 0.5), len, c.star ? 2 : 1);
      if (c.star) { g.fillStyle = '#ffd666'; g.textAlign = 'left'; g.fillText('★', 2, yy); }
      const t = tr && tr.per[c.i];
      if (t && t.how && t.how !== 'same' && t.how !== 'copy') { g.fillStyle = M.HOW_COL[t.how]; g.beginPath(); g.arc(w - 4, yy, 2.5, 0, 7); g.fill(); } else if (t && t.how) { g.fillStyle = 'rgba(111,129,150,0.8)'; g.fillRect(w - 5, yy - 0.5, 2, 1); }
    }
    if (R.chapters) { g.fillStyle = 'rgba(110,170,255,0.7)'; R.chapters().forEach((ch) => { if (ch.from <= 0) return; const v = R.vers()[ch.from], c = S.cards.find((x) => (v.irPseudo ? x.kind === 'ir' && x.phase === v.phase : x.v === v)); if (c) g.fillRect(w - 52, y(c) - 6, 46, 1); }); }
  }
  // ---- pages: chapter stops (R.interesting) mapped onto the cards; ◀ ▶ fly to the previous / next one ----
  const stopCard = (x) => S.cards.find((c) => (x.v.irPseudo ? c.kind === 'ir' && c.phase === x.v.phase : c.v === x.v));
  function pages() {
    const stops = (R.interesting ? R.interesting() : []).map((x) => ({ x, c: stopCard(x) })).filter((p) => p.c).sort((a, b) => a.c.i - b.c.i), f = stack && stack.front, cur = f ? f.i : 0;
    const vi = f ? viewerIndex(f) : 0; let k = stops.findIndex((p) => (p.x.ch ? p.x.ch.from <= vi && vi <= p.x.ch.to : p.c.i >= cur)); if (k < 0) k = stops.length - 1; return { stops, k };
  }
  function pageTurn(d) {
    const { stops } = pages(); if (!stops.length) return; const f = stack.front, cur = f ? f.i : 0;
    const t = d > 0 ? stops.find((p) => p.c.i > cur) || stops[0] : [...stops].reverse().find((p) => p.c.i < cur) || stops[stops.length - 1]; if (t) stack.flyTo(t.c);
  }
  function renderPager() {
    const el = $('spager'); if (!el || !R.stackOpen) return; const { stops, k } = pages(); if (!el._w) { el._w = 1; R.wirePager(el, pageTurn, (i) => { const p = pages().stops[i]; if (p) stack.flyTo(p.c); }); }
    const top = new Set(R.interestingTop().map((x) => x.i)), info = { range: stops[k] && stops[k].x.ch ? R.chapterRange(stops[k].x.ch) : '', stops: stops.map((p) => p.x), k, label: (R.ST.gran || 'chapters') === 'chapters' ? 'chapter' : 'stop', name: stops[k] && (stops[k].x.ch ? stops[k].x.ch.name + ((stops[k].x.ch.add || stops[k].x.ch.del) ? ` · +${stops[k].x.ch.add} −${stops[k].x.ch.del} ${stops[k].x.ch.ir ? 'instr' : 'lines'}` : '') : R.verName(stops[k].x.v)), top };
    const h = R.pagerHtml(info); if (el._h !== h) { el._h = h; el.innerHTML = h; }
  }
  // ---- line range: viewer state (R.ST.range) + side panel with the per-phase rows ----
  const lineSpan = (c, sp) => { let a = sp[0][0], b = sp[sp.length - 1][1]; while (a <= b && c.rows[a] && c.rows[a].nl < 0) a++; while (b >= a && c.rows[b] && c.rows[b].nl < 0) b--; return [c.rows[a] ? c.rows[a].nl + 1 : 0, c.rows[b] ? c.rows[b].nl + 1 : 0]; };
  function onRange(g) {
    const box = $('srange');
    if (!g) { if (R.ST.range) R.set({ range: null }); box.hidden = true; return; }
    if (!g.dragging) { const c = g.card, isIR = c.kind === 'ir'; R.set({ range: { unit: R.ST.unit, phase: c.phase, lo: g.lo, hi: g.hi, nodes: g.nodes, ir: isIR, cls: isIR ? c.cls : null } }); }
    renderRange(g); if (!g.dragging) [300, 900, 2000].forEach((t) => setTimeout(() => { if (stack && stack.rg === g) renderRange(g); }, t));
  }
  function renderRange(g) {
    const box = $('srange'); box.hidden = false; const c0 = g.card, n = g.hi - g.lo + 1;
    let h = `<div class=ch><b>lines ${g.lo + 1}–${g.hi + 1}</b> <span class=dimt>${n} line${n > 1 ? 's' : ''}${g.nodes ? ', ' + g.nodes.size + ' node' + (g.nodes.size > 1 ? 's' : '') : ''} · ${esc(c0.short)}</span><button id="srx" title="Esc">✕</button></div>`;
    if (g.fallback) h += `<div class=dimt style="padding:2px 8px;color:#e8b04a">over 500 nodes: matched by line text only</div>`;
    if (g.nodes && !g.dragging) {
      let prev = null, rowsH = '';
      S.cards.forEach((c, i) => {
        if (!c.ready || c.noop && c !== c0) return; const sp = stack.rangeSpans(c); if (!sp || !sp.length) return;
        let cov = 0, add = 0, del = 0; sp.forEach(([x, y]) => { cov += y - x + 1; for (let k = x; k <= y; k++) { const r = c.rows[k]; if (r && r.k === 'a' && !r.w) add++; else if (r && r.k === 'd') del++; } });
        const ls = lineSpan(c, sp), moved = prev && (prev[0] !== ls[0] || prev[1] !== ls[1]) ? ` · moved from ${prev[0]}–${prev[1]}` : ''; prev = ls;
        rowsH += `<div class="ce" data-i="${i}"><span class=cp>${esc(c.short)}</span><code>${ls[0]}–${ls[1]} · ${cov} row${cov > 1 ? 's' : ''}${add || del ? ` · <span style="color:#4cc38a">+${add}</span> <span style="color:#ff5c7a">−${del}</span>` : ''}${moved}</code></div>`;
      });
      h += rowsH || '<div class=dimt style="padding:4px 8px">no other phase contains these nodes</div>';
    }
    box.innerHTML = h; box.querySelector('#srx').onclick = () => stack.clearRange(); box.querySelectorAll('.ce').forEach((e) => { e.onclick = () => stack.flyTo(S.cards[+e.dataset.i]); });
  }
  // ---- chronicle ----
  function renderChron(tr) {
    const box = $('schron'); if (!tr) { box.hidden = true; S.note = null; return; }
    const ev = []; tr.per.forEach((t, i) => { if (t.how && t.how !== 'same' && t.how !== 'copy') ev.push({ i, t }); }); const life = tr.per.filter((t) => t.set.size).length; box.hidden = false;
    box.innerHTML = `<div class=ch><b>${esc(tr.kind)}</b> #${tr.node} <span class=dimt>in ${life}/${tr.per.length} versions · trace ${tr.ms.toFixed(1)} ms</span><button id="schx" title="Esc">✕</button></div>${S.note ? `<div class=dimt style="padding:2px 8px;color:#b197fc">${esc(S.note)}</div>` : ''}`
      + ev.map(({ i, t }) => { const c = S.cards[i], l = t.line >= 0 && c.lines ? c.lines[t.line].trim() : ''; return `<div class=ce data-i="${i}"><span class=hw style="color:${M.HOW_COL[t.how]}">${M.HOW_TXT[t.how]}${t.via ? ' <span class=dimt>via ' + esc(t.via) + '</span>' : ''}</span><span class=cp>${esc(c.short)}</span><code>${esc(l.slice(0, 90))}</code></div>`; }).join('');
    box.querySelector('#schx').onclick = () => stack.clearTrace(); box.querySelectorAll('.ce').forEach((e) => { e.onclick = () => stack.flyTo(S.cards[+e.dataset.i]); });
  }

  document.addEventListener('DOMContentLoaded', () => {
    $('sclose').onclick = () => R.closeStack(); $('sbd').onclick = () => R.closeStack();
    $('sunit').onchange = (ev) => { R.selectUnit(+ev.target.value); };
    $('sclass').onchange = (ev) => { R.set({ cls: ev.target.value }); }; $('strivial').onchange = (ev) => { R.set({ trivialNoop: ev.target.checked }); };
    const box = $('smini'); let drag = false; const at = (e) => { const r = box.getBoundingClientRect(); stack.jumpTo(Math.max(0, Math.min(1, (e.clientY - r.top - 8) / (r.height - 16))) * stack.sMax, false); };
    box.addEventListener('pointerdown', (e) => { drag = true; box.setPointerCapture(e.pointerId); at(e); }); box.addEventListener('pointerup', () => { drag = false; });
    box.addEventListener('pointermove', (e) => { if (drag) at(e); const r = box.getBoundingClientRect(), c = stack && stack.nearest(((e.clientY - r.top - 8) / (r.height - 16)) * stack.sMax); box.title = c ? `${c.i + 1}. ${c.name}${c.noop ? ' (no change)' : c.ready ? ` +${c.add} −${c.del}` : ''}` : ''; });
    addEventListener('resize', () => { if (R.stackOpen) drawMini(); });
    setInterval(() => { if (!R.stackOpen || !stack) return; const f = stack.frames.slice(-60); if (!f.length) return; const avg = f.reduce((a, b) => a + b, 0) / f.length; $('sfps').textContent = `${(1000 / avg).toFixed(0)} fps · work ${(stack.lastFrameWork || 0).toFixed(1)} ms`; }, 500);
  });
  // a view of the viewer (light: runs while the overlay is open): rebuild the cards when unit or options changed underneath
  const view = () => {
    if (!R.stackOpen || !R.ST.D) return;
    if (sig() !== S.sig) { const f = stack && stack.front; S.keep = f ? { kind: f.kind, phase: f.phase } : null; build(false); }
    else if (fsig() !== S.fsig) { S.fsig = fsig(); const fresh = M.buildCards(); if (fresh.length === S.cards.length) { stack.relayout(fresh.map((c) => c.noop)); fresh.forEach((c, i) => { S.cards[i].noop = c.noop; }); status(); } }
  };
  { const v0 = view; var view2 = () => { v0(); renderPager(); }; }
  const viewP = () => { view(); renderPager(); }; viewP.light = true; R.views.push(viewP);
})(window.R);
