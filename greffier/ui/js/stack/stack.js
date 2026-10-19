// The 3D stack: cards on the z axis (newest in front), a depth cursor moved by wheel/keys/minimap with a damped follow, rows computed
// lazily, text DOM only for rows near the window of the nearest cards (canvas placeholders elsewhere), a lineage thread projected over
// the stack. Ported from the stack PoC; changes: runs inside the overlay (listeners only while active), speed / reduced motion come from
// the viewer's options, rows come lazily from the shared diff, text is windowed so 5,000-line versions stay cheap.
(function (R) {
  const M = (R.stk = R.stk || {}), { nodeAt, lineOf, trace } = M;
  const LH = 16, NEAR = 7, KMAX = 30, GAP = 95, RISE = 13, P = 1000, W_NOOP = 0.22, W_STAR = 1.4, HDR = 22, BAR = 9, CW = 7.4;
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

  class Stack {
    constructor(el, opts) {
      this.el = el; this.opts = opts; this.svg = el.querySelector('#thread'); this.active = false;
      this.u = 0; this.target = 0; this.lastWheel = 0; this.hover = null; this.tr = null;
      this.frames = []; this.works = []; this.textBuilt = 0; this.all = []; this.list = [];
      this.onFront = opts.onFront || (() => {}); this.onTrace = opts.onTrace || (() => {}); this.onSelect = opts.onSelect || (() => {});
      el.addEventListener('wheel', (e) => this.active && this.wheel(e), { passive: false });
      el.addEventListener('pointermove', (e) => this.pmove(e));
      el.addEventListener('pointerleave', () => { this.hover = null; });
      el.addEventListener('click', (e) => this.click(e));
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
      this.D = D; this.all = cards; this.tr = null; this.front = null;
      this.el.querySelectorAll('.card').forEach((c) => c.remove());
      cards.forEach((c) => { c.el = null; c.txt = null; c.top = 0; c.hv = 0; c.phDirty = true; c.win = null; });
      this.rebuild(); this.layout(); this.u = this.target = 0; this.kick();
    }
    rebuild() {
      this.all.forEach((c) => { if (c.el) c.el.style.display = 'none'; c.s = null; });
      this.list = this.all.slice(); let s = 0;
      for (let k = this.list.length - 1; k >= 0; k--) { const c = this.list[k]; c.s = s; s += c.noop ? W_NOOP : c.star ? W_STAR : 1; }
      this.sMax = this.list.length ? this.list[0].s : 0; this.opts.onList && this.opts.onList(this.list);
    }
    layout() {
      const r = this.el.getBoundingClientRect(); this.Sw = r.width; this.Sh = r.height;
      const left = this.Sw >= 1200 ? 300 : 0, side = 96;
      this.W = Math.max(300, Math.min(1040, this.Sw - left - side - 48)); this.L = Math.round(left + (this.Sw - left - side - this.W) / 2);
      this.T = Math.round(this.Sh * 0.34); this.H = this.Sh - this.T - 14; this.bodyH = this.H - HDR - BAR; this.VR = Math.max(4, Math.floor(this.bodyH / LH));
      this.ox = this.L + this.W / 2; this.oy = Math.round(this.Sh * 0.015);
      this.el.style.perspective = P + 'px'; this.el.style.perspectiveOrigin = `${this.ox}px ${this.oy}px`; this.svg.setAttribute('viewBox', `0 0 ${this.Sw} ${this.Sh}`);
      this.all.forEach((c) => { if (c.el) this.size(c); c.phDirty = true; });
    }
    size(c) {
      const st = c.el.style; st.left = this.L + 'px'; st.top = this.T + 'px'; st.width = this.W + 'px'; st.height = (c.noop ? HDR : this.H) + 'px';
      if (c.ph) { c.ph.width = Math.round(this.W / 2); c.ph.height = Math.round(this.bodyH / 2); }
      if (c.bar) { c.bar.width = this.W; c.bar.height = BAR; if (c.ready) this.drawBar(c); }
    }

    // ---- card DOM ----
    hdrCount(c) { return !c.ready ? '…' : c.noop ? 'no change' : c.i === 0 ? c.lines.length + ' lines' : `<b class=a>+${c.add}</b> <b class=d>−${c.del}</b>`; }
    make(c) {
      const el = document.createElement('div');
      el.className = 'card' + (c.noop ? ' noop' : '') + (c.star ? ' star' : '');
      el.innerHTML = `<div class=hdr><span class=ix>${c.i + 1}</span><span class=ph title="${esc(c.name)}">${c.star ? '★ ' : ''}${esc(c.short)}</span><span class=tr></span><span class=ct>${c.noop ? 'no change' : '…'}</span></div>`
        + (c.noop ? '' : `<canvas class=bar></canvas><div class=body><canvas class=phc></canvas><div class=txt></div></div>`) + `<div class=dim></div>`;
      c.el = el; c.dim = el.querySelector('.dim'); c.trEl = el.querySelector('.tr'); c.ctEl = el.querySelector('.ct'); c.bar = el.querySelector('.bar'); c.ph = el.querySelector('.phc'); c.txtEl = el.querySelector('.txt'); el._card = c;
      this.el.insertBefore(el, this.svg); this.size(c); if (c.ready) this.rowsReady(c);
    }
    rowsReady(c) { if (c.ctEl) c.ctEl.innerHTML = this.hdrCount(c); if (c.bar) this.drawBar(c); this.paintTrace(c); c.phDirty = true; }
    ensure(c) { if (c.ready) return true; const t0 = performance.now(); M.ensureRows(c); this.rowMs = performance.now() - t0; if (c.el) this.rowsReady(c); return true; }
    buildText(c) {
      if (c.noop || !c.ready) return; const t0 = performance.now();
      const n = c.rows.length, a = Math.max(0, Math.floor(c.top) - WIN), b = Math.min(n, Math.floor(c.top) + this.VR + WIN);
      let h = ''; for (let k = a; k < b; k++) { const r = c.rows[k]; h += `<div class="r ${r.k}${r.w ? ' w' : ''}" data-r="${k}"><span class=ln>${r.nl >= 0 ? r.nl + 1 : ''}</span>${tint(r.t) || ' '}</div>`; }
      c.txtEl.innerHTML = h; c.txt = true; c.win = [a, b]; c.txtEl.style.paddingTop = a * LH + 'px'; this.textBuilt++;
      this.paintTrace(c); this.placeText(c); this.lastBuildMs = performance.now() - t0;
    }
    dropText(c) { if (!c.txt) return; c.txtEl.innerHTML = ''; c.txt = false; c.win = null; }
    placeText(c) {
      if (!c.txtEl) return; c.txtEl.style.transform = `translate3d(0,${-c.top * LH}px,0)`;
      if (c.txt && c.win && (c.top < c.win[0] + 4 || c.top + this.VR > c.win[1] - 4) && !(c.win[0] === 0 && c.top < 4) && !(c.win[1] >= c.rows.length)) { c.winDirty = true; }
    }
    drawBar(c) {
      const g = c.bar.getContext('2d'), n = c.rows.length, w = c.bar.width;
      g.clearRect(0, 0, w, BAR); g.fillStyle = 'rgba(140,160,190,0.16)'; g.fillRect(0, BAR / 2 - 0.5, w, 1);
      c.rows.forEach((r, k) => { if (r.k === 's' || r.w) return; g.fillStyle = r.k === 'a' ? '#4cc38a' : '#ff5c7a'; g.fillRect((k / n) * w, 0, Math.max(1.5, w / n), BAR); });
    }
    drawPh(c) {
      c.phDirty = false; if (!c.ph || !c.ready) return;
      const g = c.ph.getContext('2d'), sx = c.ph.width / this.W, sy = c.ph.height / this.bodyH, t = this.tr && this.tr.per[c.i];
      g.clearRect(0, 0, c.ph.width, c.ph.height); const r0 = Math.floor(c.top), off = (c.top - r0) * LH;
      for (let k = 0; k <= this.VR + 1; k++) {
        const r = c.rows[r0 + k]; if (!r) break; const y = (k * LH - off) * sy;
        if (r.k !== 's' && !r.w) { g.fillStyle = r.k === 'a' ? 'rgba(76,195,138,0.22)' : 'rgba(255,92,122,0.18)'; g.fillRect(0, y, c.ph.width, LH * sy); }
        if (t && t.rows && t.rows.has(r0 + k)) { g.fillStyle = 'rgba(255,214,102,0.30)'; g.fillRect(0, y, c.ph.width, LH * sy); }
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
    clearTrace() { if (this.tr) this.setTrace(null); }
    maxTop(c) { return Math.max(0, c.rows.length - this.VR + 2); }
    syncTops() {
      const f = this.front; if (!f || f.noop || !f.ready) return;
      const ft = this.tr && this.tr.per[f.i], anchor = ft && ft.row >= 0 ? ft.row - f.top : null;
      this.all.forEach((c) => {
        if (c === f || c.noop || !c.ready) return; const t = this.tr && this.tr.per[c.i];
        let top = t && t.row >= 0 && anchor != null ? t.row - anchor : (f.top / Math.max(1, f.rows.length)) * c.rows.length;
        top = Math.max(0, Math.min(this.maxTop(c), top)); if (top !== c.top) { c.top = top; c.phDirty = true; this.placeText(c); }
      });
    }
    scrollFront(dr) { const f = this.front; if (!f || f.noop || !f.ready) return; f.top = Math.max(0, Math.min(this.maxTop(f), f.top + dr)); f.phDirty = true; this.placeText(f); this.syncTops(); this.kick(); }
    revealTraceRow() {
      const f = this.front, t = this.tr && f && this.tr.per[f.i]; if (!t || t.row < 0 || f.noop || !f.ready) return;
      if (t.row < f.top + 2 || t.row > f.top + this.VR - 3) { f.top = Math.max(0, Math.min(this.maxTop(f), t.row - Math.floor(this.VR * 0.4))); f.phDirty = true; this.placeText(f); }
      this.syncTops();
    }

    // ---- input ----
    wheel(e) {
      e.preventDefault(); const d = (e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? 400 : 1) * (Math.abs(e.deltaY) >= Math.abs(e.deltaX) ? e.deltaY : e.deltaX);
      if (e.altKey || e.shiftKey) { this.scrollFront(d / LH); return; }
      this.target = Math.max(0, Math.min(this.sMax, this.target + d / 110)); this.lastWheel = performance.now(); this.kick();
    }
    key(e) {
      if (e.target && /INPUT|SELECT|TEXTAREA/.test(e.target.tagName)) return;
      const k = e.key, idx = this.list.indexOf(this.front), go = (j) => { j = Math.max(0, Math.min(this.list.length - 1, j)); this.flyTo(this.list[j]); };
      if (k === 'ArrowDown' || k === 'PageDown') go(idx - (k === 'PageDown' ? 10 : 1)); else if (k === 'ArrowUp' || k === 'PageUp') go(idx + (k === 'PageUp' ? 10 : 1));
      else if (k === 'Home') go(this.list.length - 1); else if (k === 'End') go(0);
      else if (k === 'j') this.scrollFront(3); else if (k === 'k') this.scrollFront(-3);
      else if (k === 'i' || k === 'I') { const st = this.list.filter((c) => c.star); const nx = k === 'i' ? [...st].reverse().find((c) => c.s > this.front.s + 1e-6) : st.find((c) => c.s < this.front.s - 1e-6); if (nx) this.flyTo(nx); }
      else return;
      e.preventDefault(); e.stopPropagation();
    }
    flyTo(c) { if (!c) return; this.target = c.s; this.lastWheel = 0; this.kick(); }
    jumpTo(s, immediate) { this.target = Math.max(0, Math.min(this.sMax, s)); if (immediate) this.u = this.target; this.lastWheel = performance.now(); this.kick(); }
    pmove(e) { const el = e.target.closest && e.target.closest('.card'); const c = el && el._card; this.hover = c && c !== this.front ? c : null; if (this.hover) this.kick(); }
    click(e) {
      const el = e.target.closest && e.target.closest('.card'); const c = el && el._card; if (!c) return;
      if (c !== this.front) { this.flyTo(c); return; }
      const row = e.target.closest('.r'); if (!row || c.noop || !c.ready) return; const r = c.rows[+row.dataset.r]; if (!r || r.nl < 0) return;
      let pos = null; const cp = document.caretPositionFromPoint ? document.caretPositionFromPoint(e.clientX, e.clientY) : document.caretRangeFromPoint && document.caretRangeFromPoint(e.clientX, e.clientY);
      if (cp) { const node = cp.offsetNode || cp.startContainer, off = cp.offset ?? cp.startOffset; if (node && row.contains(node)) { const rg = document.createRange(); rg.setStart(row, 0); rg.setEnd(node, off); const col = rg.toString().length - row.querySelector('.ln').textContent.length; if (col > r.t.length - r.t.trimStart().length && col < r.t.length) pos = c.lineStart[r.nl] + col; } }
      const n = nodeAt(c, r.nl, pos); if (n == null) return;
      this.traceNode(c, n); this.onSelect(n);
    }
    traceNode(c, n) { const t0 = performance.now(), tr = trace(this.D, this.all, c.i, n); tr.ms = performance.now() - t0; this.setTrace(tr); }

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
        if (!c.ready && !c.noop) pending = true;
        const hvT = c === this.hover ? 1 : 0; if (c.hv !== hvT) { c.hv = reduced ? hvT : c.hv + (hvT - c.hv) * Math.min(1, dt * 14); if (Math.abs(c.hv - hvT) < 0.01) c.hv = hvT; else moving = true; }
        const ke = k - c.hv * 0.45; let z, y, op = 1;
        if (ke >= 0) { z = -ke * GAP; y = -ke * RISE - c.hv * 26; op = k > KMAX - 6 ? Math.max(0, (KMAX - k) / 6) : 1; } else { z = -ke * GAP * 3; y = -ke * 40; op = Math.max(0, 1 + ke * 4.5); }
        c.z = z; c.y = y; const st = c.el.style;
        st.transform = `translate3d(0,${y.toFixed(1)}px,${z.toFixed(1)}px)`; st.opacity = op.toFixed(3); st.zIndex = String(10000 - Math.round(k * 100)); st.pointerEvents = k < -0.3 ? 'none' : '';
        c.el.classList.toggle('front', c === front);
        c.dim.style.opacity = (ke <= 0 ? 0 : Math.min(0.78, ke * (c.star ? 0.045 : 0.075)) * (1 - c.hv * 0.85)).toFixed(3);
        if (!c.noop && c.ready) {
          const ak = Math.abs(k);
          if (ak < NEAR + 1 && !c.txt && built < 2) { this.buildText(c); built++; } else if (c.txt && ak > NEAR + 4) this.dropText(c);
          if (c.txt && c.winDirty) { c.winDirty = false; this.buildText(c); built++; }
          const ta = c.txt ? Math.max(0, Math.min(1, (NEAR - ak) / 2.5)) : 0;
          c.txtEl.style.opacity = ta.toFixed(3); c.ph.style.opacity = (1 - ta).toFixed(3); if (ta < 1 && c.phDirty) this.drawPh(c);
        }
      }
      if (built || pending) moving = true;
      this.drawThread(); this.opts.onFrame && this.opts.onFrame(this.u, this.sMax);
      this.lastFrameWork = performance.now() - tStart; this.works.push(this.lastFrameWork); if (this.works.length > 600) this.works.shift();
      if (moving || this.all.some((c) => c.el && c.el.style.display !== 'none' && !c.noop && c.ready && !c.txt && Math.abs(c.s - this.u) < NEAR + 1)) this.kick();
    }
    nearest(u) { let best = null, bd = Infinity; for (const c of this.list) { const d = Math.abs(c.s - u); if (d < bd) { bd = d; best = c; } } return best; }
    proj(c, px, py) { const X = this.L + px, Y = this.T + c.y + py, f = P / (P - c.z); return [this.ox + (X - this.ox) * f, this.oy + (Y - this.oy) * f]; }
    drawThread() {
      if (!this.tr) { if (this.svg.firstChild) this.svg.innerHTML = ''; return; }
      const pts = [], cards = this.list.filter((c) => c.el && c.el.style.display !== 'none' && c.ready && c.s - this.u > -0.35).sort((a, b) => a.s - b.s); let prev = null;
      for (const c of cards) {
        const t = this.tr.per[c.i]; if (!t || !t.how) { prev = null; continue; }
        const op = +c.el.style.opacity, ty = c.noop ? (c === this.front ? HDR / 2 : 2) : HDR + BAR / 2;
        if (t.how === 'gone') { const [sx, sy] = this.proj(c, prev ? prev.x : this.W / 2, ty); pts.push({ sx, sy, how: 'gone', op, c }); prev = null; continue; }
        const n = c.rows.length || 1, x = c.noop ? (prev ? prev.x : this.W / 2) : ((t.row + 0.5) / n) * this.W, [sx, sy] = this.proj(c, x, ty);
        if (c === this.front && !c.noop) { const ry = HDR + BAR + (t.row - c.top) * LH + LH / 2; if (ry > HDR + BAR && ry < this.H) { const [ax, ay] = this.proj(c, 40, ry); pts.push({ sx: ax, sy: ay, how: 'anchor', op, c }); } }
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
      this.svg.innerHTML = h;
    }
  }
  M.Stack = Stack;
})(window.R);
