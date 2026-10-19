// The 3D stack: cards on the z axis (newest in front), a depth cursor moved by wheel/keys/minimap with a damped follow, rows computed
// lazily, text DOM only for rows near the window of the nearest cards (canvas placeholders elsewhere), a lineage thread projected over
// the stack. Ported from the stack PoC; changes: runs inside the overlay (listeners only while active), speed / reduced motion come from
// the viewer's options, rows come lazily from the shared diff, text is windowed so 5,000-line versions stay cheap.
(function (R) {
  const M = (R.stk = R.stk || {}), { nodeAt, lineOf, trace } = M;
  const LH = 18, NEAR = 7, KMAX = 30, GAP = 95, RISE = 13, P = 1000, W_NOOP = 0.16, W_STAR = 1.4, HDR = 28, BAR = 9, CW = 7.4, PADT = 16, PADB = 16;
  const WIN = 24;               // rows built above and below the visible window
  const ROW_BUDGET = 5;         // ms per frame spent on computing rows of cards that come near
  const HOW_COL = (M.HOW_COL = { born: '#5fd38d', same: '#6f8196', copy: '#6f8196', edit: '#82aaff', retype: '#c792ea', rewrite: '#ffb454', gone: '#ff5370' });
  M.HOW_TXT = { born: 'born', same: 'kept', copy: 'copied', edit: 'reprinted', retype: 'retyped', rewrite: 'rewritten', gone: 'removed' };
  const esc = (s) => s.replace(/[&<>]/g, (c) => (c === '&' ? '&amp;' : c === '<' ? '&lt;' : '&gt;'));
  const KW = new Set('package class trait object module def val var lazy final private protected override given using extends with new this super if then else match case return while do try catch finally throw import type implicit sealed abstract inline synthetic true false null enum export opaque transparent erased'.split(' '));
  const TOK = /("(?:[^"\\]|\\.)*"?)|(\/\/.*$|\/\*.*?\*\/)|(\b\d[\d_.]*[LlFfDd]?\b)|([A-Za-z_$][A-Za-z0-9_$]*)|(<[a-z]+>)/g;
  function tint(line) {
    let out = '', last = 0, m; TOK.lastIndex = 0;
    while ((m = TOK.exec(line))) {
      out += esc(line.slice(last, m.index)); last = TOK.lastIndex; const t = m[0], e = esc(t);
      if (m[1]) out += `<i class=s>${e}</i>`; else if (m[2]) out += `<i class=c>${e}</i>`; else if (m[3]) out += `<i class=n>${e}</i>`; else if (m[5]) out += `<i class=k>${e}</i>`;
      else if (KW.has(t)) out += `<i class=k>${e}</i>`; else if (/^[A-Z]/.test(t)) out += `<i class=t>${e}</i>`; else out += e;
    }
    return out + esc(line.slice(last));
  }

  // bytecode: opcodes coloured, labels / descriptors / directives / comments dim, operands plain
  const BCT = /(\/\/.*$)|\b(LINENUMBER|LOCALVARIABLE|MAXSTACK|MAXLOCALS|FRAME|TRYCATCHBLOCK)\b|(\bL\d+\b)|(\b[A-Z][A-Z0-9_]{2,}\b)|(\(?\[*L[\w/$]+;\)?|\([\w/$;\[]*\)[\w/$;\[]+)/g;
  function tintBc(line) {
    let out = '', last = 0, m; BCT.lastIndex = 0;
    while ((m = BCT.exec(line))) { out += esc(line.slice(last, m.index)); last = BCT.lastIndex; const e = esc(m[0]); out += m[4] ? `<i class=op>${e}</i>` : `<i class=dm>${e}</i>`; }
    return out + esc(line.slice(last));
  }

  class Stack {
    constructor(el, opts) {
      this.el = el; this.opts = opts; this.svg = el.querySelector('#thread'); this.active = false; this.marks = []; this.groups = [];
      this.lbl = document.createElementNS('http://www.w3.org/2000/svg', 'g'); this.thr = document.createElementNS('http://www.w3.org/2000/svg', 'g'); this.svg.append(this.lbl, this.thr);
      this.u = 0; this.target = 0; this.lastWheel = 0; this.hover = null; this.tr = null;
      this.frames = []; this.works = []; this.textBuilt = 0; this.all = []; this.list = [];
      this.onFront = opts.onFront || (() => {}); this.onTrace = opts.onTrace || (() => {}); this.onSelect = opts.onSelect || (() => {}); this.onSwitchClass = opts.onSwitchClass || null; this.onRange = opts.onRange || (() => {}); this.onPage = opts.onPage || (() => {}); this.rg = null;
      el.addEventListener('wheel', (e) => this.active && this.wheel(e), { passive: false });
      el.addEventListener('touchstart', (e) => { const t = e.touches[0]; this._ts = t ? [t.clientX, t.clientY, performance.now()] : null; }, { passive: true });
      el.addEventListener('touchend', (e) => { const t = e.changedTouches[0], a = this._ts; this._ts = null; if (!t || !a) return; const dx = t.clientX - a[0], dy = t.clientY - a[1]; if (Math.abs(dx) > 60 && Math.abs(dy) < 40 && performance.now() - a[2] < 700) this.onPage(dx < 0 ? 1 : -1); }, { passive: true });
      el.addEventListener('pointermove', (e) => this.pmove(e));
      el.addEventListener('pointerleave', () => { this.hover = null; });
      el.addEventListener('click', (e) => this.click(e));
      el.addEventListener('pointerdown', (e) => {   // gutter: click selects a line, drag / shift-click extends to a range
        const ln = e.target.closest && e.target.closest('.ln'), row = ln && ln.closest('.r'), card = row && row.closest('.card'), c = card && card._card;
        if (!c || c !== this.front || !c.rows || e.button) return; const r0 = c.rows[+row.dataset.r]; if (!r0 || r0.nl < 0) return;
        e.preventDefault(); const anchor = e.shiftKey && this.rg && this.rg.card === c ? this.rg.a : r0.nl; let moved = false; this._rgDrag = false;
        const lineAt = (ev) => { const t = document.elementFromPoint(ev.clientX, ev.clientY), rr = t && t.closest && t.closest('.r'); if (rr && rr.closest('.card') === card) { const q = c.rows[+rr.dataset.r]; if (q && q.nl >= 0) return q.nl; } return null; };
        this.setRange(c, anchor, r0.nl, true);
        const mv = (ev) => { const l = lineAt(ev); if (l != null && l !== this.rg.b) { moved = true; this._rgDrag = true; this.setRange(c, anchor, l, true); } };
        const up = () => { removeEventListener('pointermove', mv); removeEventListener('pointerup', up); this.setRange(c, this.rg.a, this.rg.b, false); setTimeout(() => { this._rgDrag = false; }, 0); };
        addEventListener('pointermove', mv); addEventListener('pointerup', up);
      });
      el.addEventListener('pointerdown', (e) => {   // drag the scrollbar thumb of the front card
        const sb = e.target.closest && e.target.closest('.sb'), card = sb && sb.closest('.card'), c = card && card._card; if (!c || c !== this.front || !c.rows) return;
        e.preventDefault(); e.stopPropagation(); el.setPointerCapture(e.pointerId); const tr = this.bodyH - 8, n = Math.max(1, c.rows.length);
        const sy = e.clientY, top0 = c.top; const mv = (m) => { c.top = Math.max(0, Math.min(this.maxTop(c), top0 + ((m.clientY - sy) / tr) * n * (card.getBoundingClientRect().height ? this.H / card.getBoundingClientRect().height : 1))); c.phDirty = true; this.placeText(c); this.syncTops(); this.kick(); };
        const up = () => { el.removeEventListener('pointermove', mv); el.removeEventListener('pointerup', up); }; el.addEventListener('pointermove', mv); el.addEventListener('pointerup', up);
      });
      addEventListener('keydown', (e) => this.active && this.key(e));
      addEventListener('resize', () => { if (this.active) { this.layout(); this.kick(); } });
      this.raf = null; this.tPrev = performance.now();
    }
    get reduced() { return !R.animActive || !R.animActive(); }
    get speed() { return +(document.getElementById('animspeed').value) || 1; }
    start() { this.active = true; this.tPrev = performance.now(); this.layout(); this.kick(); }
    stop() { this.active = false; if (this.raf) { cancelAnimationFrame(this.raf); this.raf = null; } clearTimeout(this.fb); }

    // ---- data ----
    setData(D, cards) {
      this.D = D; this.all = cards; this.tr = null; this.rg = null; this.front = null;
      this.el.querySelectorAll('.card').forEach((c) => c.remove());
      cards.forEach((c) => { c.el = null; c.txt = null; c.top = 0; c.hv = 0; c.phDirty = true; c.win = null; });
      this.rebuild(); this.layout(); this.u = this.target = 0; this.kick();
    }
    rebuild() {
      this.all.forEach((c) => { if (c.el) c.el.style.display = 'none'; c.s = null; });
      this.list = this.all.slice(); let s = 0;
      for (let k = this.list.length - 1; k >= 0; k--) { const c = this.list[k]; c.s = s; s += c.noop ? W_NOOP : c.star ? W_STAR : 1; }
      this.sMax = this.list.length ? this.list[0].s : 0; this.opts.onList && this.opts.onList(this.list);
      this.groups = []; for (let k = 0; k < this.list.length; k++) { if (!this.list[k].noop) continue; let e = k; while (e + 1 < this.list.length && this.list[e + 1].noop) e++; this.groups.push({ anchor: this.list[k], n: e - k + 1 }); k = e; }
    }
    layout() {
      const r = this.el.getBoundingClientRect(); this.Sw = r.width; this.Sh = r.height;
      const left = this.Sw >= 1200 ? 300 : 0, side = 96;
      this.W = Math.max(300, Math.min(1040, this.Sw - left - side - 48)); this.L = Math.round(left + (this.Sw - left - side - this.W) / 2);
      this.T = Math.round(this.Sh * 0.34); this.H = this.Sh - this.T - 14; this.bodyH = this.H - HDR - BAR; this.VR = Math.max(4, Math.floor((this.bodyH - PADT - PADB) / LH));
      this.ox = this.L + this.W / 2; this.oy = Math.round(this.Sh * 0.015);
      this.el.style.perspective = P + 'px'; this.el.style.perspectiveOrigin = `${this.ox}px ${this.oy}px`; this.svg.setAttribute('viewBox', `0 0 ${this.Sw} ${this.Sh}`);
      this.all.forEach((c) => { if (c.el) this.size(c); c.phDirty = true; });
    }
    size(c) {
      const st = c.el.style; st.left = this.L + 'px'; st.top = this.T + 'px'; st.width = this.W + 'px'; st.height = this.H + 'px';  // slivers get their height from CSS (.card.noop:not(.front))
      if (c.ph) { c.ph.width = Math.round(this.W / 2); c.ph.height = Math.round(this.bodyH / 2); }
      if (c.bar) { c.bar.width = this.W; c.bar.height = BAR; if (c.ready) this.drawBar(c); }
    }

    // ---- card DOM ----
    hdrCount(c) { return !c.ready ? '…' : c.noop ? 'no change' : c.first ? (c.kind === 'ir' ? c.ops + ' instructions' : c.lines.length + ' lines') : `<b class=a>+${c.add}</b> <b class=d>−${c.del}</b>${c.kind === 'ir' ? ' <span class=u>instr</span>' : ''}`; }
    make(c) {
      const el = document.createElement('div');
      el.className = 'card' + (c.noop ? ' noop' : '') + (c.star ? ' star' : '') + (c.kind === 'ir' ? ' ir' : '');
      el.innerHTML = `<div class=hdr><span class=ix>${c.i + 1}</span><span class=ph title="${esc(c.name)}">${c.star ? '★ ' : ''}${esc(c.short)}</span><span class=tr></span><span class=ct>${c.noop ? 'no change' : '…'}</span></div>`
        + `<canvas class=bar></canvas><div class=body><canvas class=phc></canvas><div class=txt></div><div class=sb><i></i></div></div>` + `<div class=dim></div>`;
      c.el = el; c.dim = el.querySelector('.dim'); c.trEl = el.querySelector('.tr'); c.ctEl = el.querySelector('.ct'); c.bar = el.querySelector('.bar'); c.ph = el.querySelector('.phc'); c.txtEl = el.querySelector('.txt'); c.sbEl = el.querySelector('.sb i'); c.hx = c.hx || 0; el._card = c;
      this.el.insertBefore(el, this.svg); this.size(c); if (c.ready) this.rowsReady(c);
    }
    rowsReady(c) { if (c.ctEl) c.ctEl.innerHTML = this.hdrCount(c); if (c.bar) this.drawBar(c); this.paintTrace(c); this.paintRange(c); c.phDirty = true; }
    ensure(c) { if (c.ready) return true; const t0 = performance.now(); M.ensureRows(c); this.rowMs = performance.now() - t0; if (c.el) this.rowsReady(c); return true; }
    buildText(c) {
      if (!c.ready) return; const t0 = performance.now();
      const n = c.rows.length, a = Math.max(0, Math.floor(c.top) - WIN), b = Math.min(n, Math.floor(c.top) + this.VR + WIN);
      let h = ''; for (let k = a; k < b; k++) { const r = c.rows[k]; h += `<div class="r ${r.k}${r.w ? ' w' : ''}${r.mu ? ' mu' : ''}" data-r="${k}"><span class=ln>${r.nl >= 0 ? r.nl + 1 : ''}</span>${(c.kind === 'ir' ? tintBc : tint)(r.t) || ' '}</div>`; }
      c.txtEl.innerHTML = h; c.txt = true; c.win = [a, b]; c.txtEl.style.paddingTop = a * LH + 'px'; this.textBuilt++;
      this.paintTrace(c); this.paintRange(c); this.placeText(c); this.lastBuildMs = performance.now() - t0;
    }
    dropText(c) { if (!c.txt) return; c.txtEl.innerHTML = ''; c.txt = false; c.win = null; }
    placeText(c) {
      if (!c.txtEl) return; c.txtEl.style.transform = `translate3d(${-(c.hx || 0)}px,${PADT - c.top * LH}px,0)`; this.thumb(c);
      if (c.txt && c.win && (c.top < c.win[0] + 4 || c.top + this.VR > c.win[1] - 4) && !(c.win[0] === 0 && c.top < 4) && !(c.win[1] >= c.rows.length)) { c.winDirty = true; }
    }
    // scrollbar thumb: position of the visible rows within the card; thumbs of all cards move together (syncTops links the scroll positions)
    thumb(c) {
      if (!c.sbEl || !c.rows) return; const n = Math.max(1, c.rows.length), h = Math.min(1, this.VR / n), tr = this.bodyH - 8;
      c.sbEl.style.height = Math.max(18, h * tr) + 'px'; c.sbEl.style.transform = `translateY(${Math.min(1 - h, c.top / n) * tr}px)`; c.sbEl.parentNode.style.display = h >= 1 ? 'none' : '';
    }
    drawBar(c) {
      const g = c.bar.getContext('2d'), n = c.rows.length, w = c.bar.width;
      g.clearRect(0, 0, w, BAR); g.fillStyle = 'rgba(140,160,190,0.16)'; g.fillRect(0, BAR / 2 - 0.5, w, 1);
      c.rows.forEach((r, k) => { if (r.k === 's' || r.w) return; g.fillStyle = r.k === 'a' ? '#4cc38a' : '#ff5c7a'; g.fillRect((k / n) * w, 0, Math.max(1.5, w / n), BAR); });
      const sp = this.rg && this.rangeSpans(c); if (sp) { g.fillStyle = 'rgba(110,170,255,0.95)'; for (const [x, y] of sp) g.fillRect((x / n) * w, BAR - 3, Math.max(3, ((y - x + 1) / n) * w), 3); }
    }
    drawPh(c) {
      c.phDirty = false; if (!c.ph || !c.ready) return;
      const g = c.ph.getContext('2d'), sx = c.ph.width / this.W, sy = c.ph.height / this.bodyH, t = this.tr && this.tr.per[c.i];
      const rsp = this.rg && this.rangeSpans(c); g.clearRect(0, 0, c.ph.width, c.ph.height); const r0 = Math.floor(c.top), off = (c.top - r0) * LH;
      for (let k = 0; k <= this.VR + 1; k++) {
        const r = c.rows[r0 + k]; if (!r) break; const y = (k * LH - off + PADT) * sy;
        if (r.k !== 's' && !r.w) { g.fillStyle = r.k === 'a' ? 'rgba(76,195,138,0.22)' : 'rgba(255,92,122,0.18)'; g.fillRect(0, y, c.ph.width, LH * sy); }
        if (t && t.rows && t.rows.has(r0 + k)) { g.fillStyle = 'rgba(255,214,102,0.30)'; g.fillRect(0, y, c.ph.width, LH * sy); }
        if (rsp && rsp.some((q) => q[0] <= r0 + k && r0 + k <= q[1])) { g.fillStyle = 'rgba(110,170,255,0.28)'; g.fillRect(0, y, c.ph.width, LH * sy); }
        const ind = r.t.length - r.t.trimStart().length, len = r.t.trim().length; if (!len) continue;
        g.fillStyle = r.k === 'a' && !r.w ? '#4cc38a' : r.k === 'd' ? '#ff5c7a' : 'rgba(170,186,210,0.45)'; g.fillRect((44 + ind * CW) * sx, y + 4 * sy, Math.min(len * CW, this.W) * sx, 8 * sy);
      }
    }

    // ---- trace ----
    paintTrace(c) {
      const t = this.tr && this.tr.per[c.i];
      if (c.trEl) {
        if (!t || !t.how || !c.ready) { c.trEl.textContent = ''; c.trEl.style.color = ''; }
        else if (t.how === 'gone') { c.trEl.textContent = '✕ removed'; c.trEl.style.color = HOW_COL.gone; }
        else { c.trEl.textContent = `${M.HOW_TXT[t.how]} · ${(c.lines[t.line] || '').trim()}`; c.trEl.style.color = HOW_COL[t.how]; }
      }
      if (c.txt) {
        c.txtEl.querySelectorAll('.r.tr, .r.trp').forEach((e) => e.classList.remove('tr', 'trp'));
        if (t && t.rows) t.rows.forEach((k) => { if (!c.win || k < c.win[0] || k >= c.win[1]) return; const e = c.txtEl.children[k - c.win[0]]; if (e) e.classList.add(k === t.row ? 'trp' : 'tr'); });
      }
      c.phDirty = true;
    }
    setTrace(tr) { this.tr = tr; this.all.forEach((c) => { if (c.el) this.paintTrace(c); }); this.syncTops(); this.onTrace(tr); this.kick(); }
    // ---- line-range selection: outermost tree nodes covered by the lines of the front card, followed through every other card ----
    setRange(c, a, b, dragging) {
      const lo = Math.min(a, b), hi = Math.max(a, b); this.rg = { card: c, a, b, lo, hi, per: null, nodes: null, dragging: !!dragging };
      if (!dragging) {
        const t0 = performance.now(), nodes = M.nodesInLines(c.v, lo, hi); this.rg.nodes = nodes;
        if (nodes.size > 500) { this.rg.fallback = new Set(); for (let l = lo; l <= hi; l++) { const t = (c.lines[l] || '').trim(); if (t) this.rg.fallback.add(t); } }
        else { const tr = M.trace(this.D, this.all, c.i, nodes); this.rg.sets = tr.sets; this.rg.per = new Array(this.all.length).fill(null); }
        this.rg.ms = performance.now() - t0;
      }
      this.paintAllRange(); this.onRange(this.rg); this.kick();
    }
    clearRange() { if (!this.rg) return; this.rg = null; this.paintAllRange(); this.onRange(null); this.kick(); }
    // spans of rows of card c for the current range (lazy: computed when the card has rows)
    rangeSpans(c) {
      const g = this.rg; if (!g || !c.ready) return null;
      if (c === g.card) return [[c.rowOfLine[g.lo], c.rowOfLine[g.hi]]];
      if (g.dragging) return null;
      if (g.per && g.per[c.i]) return g.per[c.i];
      let sp;
      if (g.fallback) { sp = []; c.rows.forEach((r, k) => { if (r.nl >= 0 && g.fallback.has(r.t.trim())) { const l = sp[sp.length - 1]; if (l && k <= l[1] + 1) l[1] = k; else sp.push([k, k]); } }); }
      else sp = M.spansOf(c, g.sets[c.i] || new Set());
      if (g.per) g.per[c.i] = sp; return sp;
    }
    paintRange(c) {
      if (!c.txt && !c.bar) return; const sp = this.rg ? this.rangeSpans(c) : null;
      if (c.txt) {
        c.txtEl.querySelectorAll('.r.rg').forEach((e) => e.classList.remove('rg'));
        if (sp) for (const [x, y] of sp) for (let k = Math.max(x, c.win[0]); k <= Math.min(y, c.win[1] - 1); k++) { const e = c.txtEl.children[k - c.win[0]]; if (e) e.classList.add('rg'); }
      }
      if (c.bar && c.ready) this.drawBar(c); c.phDirty = true;
    }
    paintAllRange() { this.all.forEach((c) => { if (c.el && c.ready) this.paintRange(c); }); this.syncTops(); }
    clearTrace() { if (this.tr) this.setTrace(null); }
    maxTop(c) { return Math.max(0, c.rows.length - this.VR + 2); }
    syncTops() {
      const f = this.front; if (!f || !f.ready) return;
      const ft = this.tr && this.tr.per[f.i], anchor = ft && ft.row >= 0 ? ft.row - f.top : null;
      this.all.forEach((c) => {
        if (c === f || c.noop || !c.ready) return; const t = this.tr && this.tr.per[c.i];
        if (!t && this.rg && !this.rg.dragging) { const sp = this.rangeSpans(c); if (sp && sp.length) { const top = Math.max(0, Math.min(this.maxTop(c), sp[0][0] - 3)); if (top !== c.top) { c.top = top; c.phDirty = true; this.placeText(c); } return; } }
        let top = t && t.row >= 0 && anchor != null ? t.row - anchor : (f.top / Math.max(1, f.rows.length)) * c.rows.length;
        top = Math.max(0, Math.min(this.maxTop(c), top)); if (top !== c.top) { c.top = top; c.phDirty = true; this.placeText(c); }
      });
    }
    scrollFront(dr) { const f = this.front; if (!f || !f.ready) return; f.top = Math.max(0, Math.min(this.maxTop(f), f.top + dr)); f.phDirty = true; this.placeText(f); this.syncTops(); this.kick(); }
    revealTraceRow() {
      const f = this.front, t = this.tr && f && this.tr.per[f.i];
      if (!t && this.rg && !this.rg.dragging && f && f.ready) { const sp = this.rangeSpans(f); if (sp && sp.length && (sp[0][0] < f.top || sp[0][0] > f.top + this.VR - 4)) { f.top = Math.max(0, Math.min(this.maxTop(f), sp[0][0] - 3)); f.phDirty = true; this.placeText(f); } }
      if (!t || t.row < 0 || !f.ready) return;
      if (t.row < f.top + 2 || t.row > f.top + this.VR - 3) { f.top = Math.max(0, Math.min(this.maxTop(f), t.row - Math.floor(this.VR * 0.4))); f.phDirty = true; this.placeText(f); }
      this.syncTops();
    }

    // ---- input ----
    wheel(e) {
      e.preventDefault(); const d = (e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? 400 : 1) * (Math.abs(e.deltaY) >= Math.abs(e.deltaX) ? e.deltaY : e.deltaX);
      const f = this.front;
      if (Math.abs(e.deltaX) > Math.abs(e.deltaY)) {
        if (e.altKey && f && f.ready) { f.hx = Math.max(0, Math.min(3000, (f.hx || 0) + e.deltaX)); this.placeText(f); return; }
        // two-finger horizontal swipe: accumulate, turn one page past the threshold, then ignore the trackpad's inertia for a while
        const now = performance.now(); if (now - (this.swipeT || 0) < 450) { this.swipeT = now - 300; return; }
        this.swipeAcc = (now - (this.swipeLast || 0) > 200 ? 0 : this.swipeAcc || 0) + e.deltaX; this.swipeLast = now;
        if (Math.abs(this.swipeAcc) > 60) { const d = this.swipeAcc > 0 ? 1 : -1; this.swipeAcc = 0; this.swipeT = now; this.onPage(d); }
        return;
      }
      if (e.altKey || e.shiftKey) { this.scrollFront(d / LH); return; }
      this.target = Math.max(0, Math.min(this.sMax, this.target + d / 110)); this.lastWheel = performance.now(); this.kick();
    }
    key(e) {
      if (e.target && /INPUT|SELECT|TEXTAREA/.test(e.target.tagName)) return;
      const k = e.key, idx = this.list.indexOf(this.front), go = (j) => { j = Math.max(0, Math.min(this.list.length - 1, j)); this.flyTo(this.list[j]); };
      if (k === 'ArrowDown' || k === 'PageDown') go(idx - (k === 'PageDown' ? 10 : 1)); else if (k === 'ArrowUp' || k === 'PageUp') go(idx + (k === 'PageUp' ? 10 : 1));
      else if (k === 'Home') go(this.list.length - 1); else if (k === 'End') go(0);
      else if (k === 'j') this.scrollFront(3); else if (k === 'k') this.scrollFront(-3);
      else if (k === 'ArrowRight' && e.shiftKey) go(idx + 1); else if (k === 'ArrowLeft' && e.shiftKey) go(idx - 1);   // small step: one version (later phase = next card)
      else if (k === 'ArrowRight' || k === 'i') this.onPage(1); else if (k === 'ArrowLeft' || k === 'I') this.onPage(-1);
      else return;
      e.preventDefault(); e.stopPropagation();
    }
    flyTo(c) { if (!c) return; this.target = c.s; this.lastWheel = 0; this.kick(); }
    jumpTo(s, immediate) { this.target = Math.max(0, Math.min(this.sMax, s)); if (immediate) this.u = this.target; this.lastWheel = performance.now(); this.kick(); }
    pmove(e) { const el = e.target.closest && e.target.closest('.card'); const c = el && el._card; this.hover = c && c !== this.front ? c : null; if (this.hover) this.kick(); }
    click(e) {
      const el = e.target.closest && e.target.closest('.card'); const c = el && el._card; if (!c) return;
      if (c !== this.front) { this.flyTo(c); return; }
      if (e.target.closest('.ln') || this._rgDrag) return;   // the gutter selects line ranges, tokens trace
      const row = e.target.closest('.r'); if (!row || c.noop || !c.ready) return; const r = c.rows[+row.dataset.r]; if (!r || r.nl < 0) return;
      let pos = null; const cp = document.caretPositionFromPoint ? document.caretPositionFromPoint(e.clientX, e.clientY) : document.caretRangeFromPoint && document.caretRangeFromPoint(e.clientX, e.clientY);
      if (cp) { const node = cp.offsetNode || cp.startContainer, off = cp.offset ?? cp.startOffset; if (node && row.contains(node)) { const rg = document.createRange(); rg.setStart(row, 0); rg.setEnd(node, off); const col = rg.toString().length - row.querySelector('.ln').textContent.length; if (col > r.t.length - r.t.trimStart().length && col < r.t.length) pos = c.lineStart[r.nl] + col; } }
      const n = nodeAt(c, r.nl, pos); if (n == null) return;
      this.traceNode(c, n); this.onSelect(n);
    }
    traceNode(c, n) {
      const t0 = performance.now(), tr = trace(this.D, this.all, c.i, n); tr.ms = performance.now() - t0;
      if (tr.switchClass && this.onSwitchClass && !this._switching) { this._switching = true; try { this.onSwitchClass(tr.switchClass, c, n); } finally { this._switching = false; } return; }
      this.setTrace(tr);
    }

    // ---- frame loop (rAF with a timer fallback for hidden views) ----
    kick() { if (this.raf || !this.active) return; this.raf = requestAnimationFrame((t) => this.frame(t)); this.fb = setTimeout(() => { if (this.raf) { cancelAnimationFrame(this.raf); this.raf = null; this.frame(performance.now()); } }, 60); }
    frame(now) {
      this.raf = null; clearTimeout(this.fb); if (!this.active) return;
      const raw = now - this.tPrev, dt = Math.min(0.05, raw / 1000); this.tPrev = now; if (raw < 100) { this.frames.push(raw); if (this.frames.length > 600) this.frames.shift(); }
      const tStart = performance.now(), reduced = this.reduced;
      if (this.lastWheel && now - this.lastWheel > 140) { this.lastWheel = 0; this.target = this.nearest(this.target).s; }
      if (reduced) this.u = this.target; else { const a = 1 - Math.exp(-dt * 13 / this.speed); this.u += (this.target - this.u) * a; if (Math.abs(this.target - this.u) < 1e-3) this.u = this.target; }
      let moving = this.u !== this.target || !!this.lastWheel;
      const front = this.nearest(this.u);
      if (front && front !== this.front) { this.front = front; this.ensure(front); this.onFront(front); this.syncTops(); this.revealTraceRow(); }
      let built = 0, pending = false;
      for (const c of this.all) {
        const k = c.s != null ? c.s - this.u : 1e9;
        if (k < -1.1 || k > KMAX) { if (c.el && c.el.style.display !== 'none') c.el.style.display = 'none'; if (c.txt && Math.abs(k) > NEAR + 4) this.dropText(c); continue; }
        if (!c.el) this.make(c);
        if (c.el.style.display === 'none') c.el.style.display = '';
        if (!c.ready && !c.noop && performance.now() - tStart < ROW_BUDGET) this.ensure(c);
        if (!c.ready && (!c.noop || c === front)) pending = true;
        const hvT = c === this.hover ? 1 : 0; if (c.hv !== hvT) { c.hv = reduced ? hvT : c.hv + (hvT - c.hv) * Math.min(1, dt * 14); if (Math.abs(c.hv - hvT) < 0.01) c.hv = hvT; else moving = true; }
        const ke = k - c.hv * 0.45; let z, y, op = 1;
        if (ke >= 0) { z = -ke * GAP; y = -ke * RISE - c.hv * 26; op = k > KMAX - 6 ? Math.max(0, (KMAX - k) / 6) : 1; } else { z = -ke * GAP * 3; y = -ke * 40; op = Math.max(0, 1 + ke * 4.5); }
        c.z = z; c.y = y; const st = c.el.style;
        st.transform = `translate3d(0,${y.toFixed(1)}px,${z.toFixed(1)}px)`; st.opacity = op.toFixed(3); st.zIndex = String(10000 - Math.round(k * 100)); st.pointerEvents = k < -0.3 ? 'none' : '';
        c.el.classList.toggle('front', c === front);
        c.dim.style.opacity = (ke <= 0 ? 0 : Math.min(0.78, ke * (c.star ? 0.045 : 0.075)) * (1 - c.hv * 0.85)).toFixed(3);
        if ((!c.noop || c === front) && c.ready) {
          const ak = Math.abs(k);
          if (ak < NEAR + 1 && !c.txt && built < 2) { this.buildText(c); built++; } else if (c.txt && ak > NEAR + 4) this.dropText(c);
          if (c.txt && c.winDirty) { c.winDirty = false; this.buildText(c); built++; }
          const ta = c.txt ? Math.max(0, Math.min(1, (NEAR - ak) / 2.5)) : 0;
          c.txtEl.style.opacity = ta.toFixed(3); c.ph.style.opacity = (1 - ta).toFixed(3); if (ta < 1 && c.phDirty) this.drawPh(c);
        }
      }
      if (built || pending) moving = true;
      this.drawThread(); this.drawLabels(); this.opts.onFrame && this.opts.onFrame(this.u, this.sMax);
      this.lastFrameWork = performance.now() - tStart; this.works.push(this.lastFrameWork); if (this.works.length > 600) this.works.shift();
      if (moving || this.all.some((c) => c.el && c.el.style.display !== 'none' && !c.noop && c.ready && !c.txt && Math.abs(c.s - this.u) < NEAR + 1)) this.kick();
    }
    nearest(u) { let best = null, bd = Infinity; for (const c of this.list) { const d = Math.abs(c.s - u); if (d < bd) { bd = d; best = c; } } return best; }
    proj(c, px, py) { const X = this.L + px, Y = this.T + c.y + py, f = P / (P - c.z); return [this.ox + (X - this.ox) * f, this.oy + (Y - this.oy) * f]; }
    drawThread() {
      if (!this.tr) { if (this.thr.firstChild) this.thr.innerHTML = ''; return; }
      const pts = [], cards = this.list.filter((c) => c.el && c.el.style.display !== 'none' && c.ready && c.s - this.u > -0.35).sort((a, b) => a.s - b.s); let prev = null;
      for (const c of cards) {
        const t = this.tr.per[c.i]; if (!t || !t.how) { prev = null; continue; }
        const op = +c.el.style.opacity, ty = c.noop ? (c === this.front ? HDR / 2 : 2) : HDR + BAR / 2;
        if (t.how === 'gone') { const [sx, sy] = this.proj(c, prev ? prev.x : this.W / 2, ty); pts.push({ sx, sy, how: 'gone', op, c }); prev = null; continue; }
        const n = c.rows.length || 1, x = c.noop ? (prev ? prev.x : this.W / 2) : ((t.row + 0.5) / n) * this.W, [sx, sy] = this.proj(c, x, ty);
        if (c === this.front && c.ready) { const ry = HDR + BAR + PADT + (t.row - c.top) * LH + LH / 2; if (ry > HDR + BAR && ry < this.H - PADB) { const [ax, ay] = this.proj(c, 40, ry); pts.push({ sx: ax, sy: ay, how: 'anchor', op, c }); } }
        pts.push({ sx, sy, how: t.how, op, c, x }); prev = { x };
      }
      let h = '';
      for (let k = 1; k < pts.length; k++) {
        const a = pts[k - 1], b = pts[k]; if (b.how === 'anchor') continue; if (a.c !== b.c && this.tr.per[b.c.i].how === 'born') continue;
        const col = a.how === 'anchor' ? '#ffd666' : HOW_COL[a.how === 'born' && a.c !== b.c ? 'same' : a.how] || '#888';
        h += `<line x1="${a.sx.toFixed(1)}" y1="${a.sy.toFixed(1)}" x2="${b.sx.toFixed(1)}" y2="${b.sy.toFixed(1)}" stroke="${col}" stroke-opacity="${Math.min(a.op, b.op).toFixed(2)}" stroke-width="${a.how === 'anchor' ? 1.5 : 2}"${a.how === 'anchor' ? ' stroke-dasharray="3 4"' : ''}/>`;
      }
      for (const p of pts) {
        const o = p.op.toFixed(2), r = p.how === 'anchor' ? 4 : p.how === 'same' || p.how === 'copy' ? 2.2 : 4;
        if (p.how === 'gone') h += `<path d="M${p.sx - 5},${p.sy - 5}L${p.sx + 5},${p.sy + 5}M${p.sx + 5},${p.sy - 5}L${p.sx - 5},${p.sy + 5}" stroke="${HOW_COL.gone}" stroke-width="2.5" opacity="${o}"/>`;
        else h += `<circle cx="${p.sx.toFixed(1)}" cy="${p.sy.toFixed(1)}" r="${r}" fill="${p.how === 'anchor' ? '#ffd666' : HOW_COL[p.how]}" opacity="${o}" ${p.how === 'born' ? 'stroke="#fff" stroke-width="1.2"' : ''}/>`;
      }
      this.thr.innerHTML = h;
    }
    // labels: one thin label per run of no-change phases, and the marks set by the overlay (e.g. where the bytecode segment starts)
    drawLabels() {
      let h = '', used = [];
      // nearest bands first; a label is dropped when it would sit within a line of one already drawn (receding bands converge)
      for (const g of [...this.groups].sort((x, y) => x.anchor.s - y.anchor.s)) {
        const c = g.anchor; if (g.n < 2 || !c.el || c.el.style.display === 'none' || c === this.front || c.s - this.u < -0.3) continue;
        const [sx, sy] = this.proj(c, 6, -2); if (used.some((y) => Math.abs(y - sy) < 13)) continue; used.push(sy);
        h += `<text x="${sx.toFixed(1)}" y="${(sy - 3).toFixed(1)}" fill="#7f8ba0" font-size="9" opacity="${(0.8 * +c.el.style.opacity).toFixed(2)}">${g.n} phase${g.n > 1 ? 's' : ''}, no change</text>`;
      }
      // card headers on receding cards: hide the text when the next nearer card's header is less than a line away
      let lastY = null; for (const c of [...this.list].sort((a, b) => a.s - b.s)) { if (!c.el || c.el.style.display === 'none' || c.noop) continue; const y = this.proj(c, 0, 0)[1]; const cull = c !== this.front && lastY != null && lastY - y < 13 && c.s > this.u; c.el.classList.toggle('cull', !!cull); if (!cull) lastY = y; }
      for (const m of this.marks) {
        const c = m.card; if (!c.el || c.el.style.display === 'none' || c.s - this.u < -0.3) continue;
        const [sx, sy] = this.proj(c, this.W - 8, -2); h += `<text x="${sx.toFixed(1)}" y="${(sy - 4).toFixed(1)}" text-anchor="end" fill="#b197fc" font-size="10" font-weight="600" opacity="${(0.95 * +c.el.style.opacity).toFixed(2)}">${m.text}</text>`;
      }
      this.lbl.innerHTML = h;
    }
    // flags changed underneath (trivial no-op option, line counts arriving): recompute the layout in place, keep the front card
    relayout(flags) {
      const f = this.front; this.all.forEach((c, i) => { if (flags && flags[i] != null && flags[i] !== c.noop) { c.noop = flags[i]; c.ready = false; c.rows = null; if (c.el) { c.el.classList.toggle('noop', c.noop); this.dropText(c); c.el.remove(); c.el = null; } } });
      this.rebuild(); this.layout(); if (f) { this.u = this.target = f.s; } this.front = null; this.kick();
    }
  }
  M.Stack = Stack;
})(window.R);
