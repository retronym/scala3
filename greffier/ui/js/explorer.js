// Project explorer (left, collapsible, draggable): unit -> top-level classes -> members, plus the unit's JVM classes.
// Each row shows how much this phase changed it (non-copy new nodes) and the total over all phases. Checkboxes (or Ctrl/Cmd-click)
// select several units/classes: the scrubber then shows them side by side or stacked (see multi.js).
(function (R) {
  const $ = (id) => document.getElementById(id);
  const esc = (x) => String(x).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const DEF = new Set(['ClassDef', 'TypeDef', 'ModuleDef', 'DefDef', 'ValDef']);
  const X = { D: null, parents: null, byPhase: null, phaseCache: new Map(), total: null, classLike: new Map(), exp: new Set(), filter: '', open: true, w: 250 };
  R.expl = X;
  try { const s = JSON.parse(localStorage.getItem('replay-explorer') || '{}'); if (s.exp) X.exp = new Set(s.exp); if (s.open === false) X.open = false; if (s.w) X.w = s.w; } catch (e) { /* no storage */ }
  const save = () => { try { localStorage.setItem('replay-explorer', JSON.stringify({ exp: [...X.exp], open: X.open, w: X.w })); } catch (e) { /* ignore */ } };

  function reset() {
    const D = R.ST.D; if (X.D === D) return; X.D = D; X.phaseCache = new Map(); X.total = null; X.byPhase = null; X.classLike = new Map(); X.struct = new Map();
    const p = new Map(); D.nodes.forEach((n, id) => { if (!n.ir) n.children.forEach((c) => p.set(c, id)); }); X.parents = p;
  }
  const isClassLike = (nd) => { if (nd.kind === 'ClassDef' || nd.kind === 'ModuleDef') return true; if (nd.kind !== 'TypeDef') return false; let r = X.classLike.get(nd.id); if (r === undefined) { r = nd.children.some((c) => (R.ST.D.nodes.get(c) || {}).kind === 'Template'); X.classLike.set(nd.id, r); } return r; };
  // member / class symbol a node belongs to (innermost class, and the outermost method directly below it)
  function keyOf(n) {
    const D = R.ST.D; let x = n, member = null, cls = null;
    for (let i = 0; i < 80 && x != null; i++, x = X.parents.get(x)) {
      const nd = D.nodes.get(x); if (!nd || !DEF.has(nd.kind) || nd.sym == null) continue;
      if (isClassLike(nd)) { cls = nd; break; } else member = nd;
    }
    return { member: member && member.sym, cls: cls && cls.sym };
  }
  function phaseCounts(phase) {
    reset(); if (X.phaseCache.has(phase)) return X.phaseCache.get(phase);
    const D = R.ST.D; if (!X.byPhase) { X.byPhase = new Map(); D.derives.forEach((d) => { let a = X.byPhase.get(d.phase); if (!a) X.byPhase.set(d.phase, (a = [])); a.push(d); }); }
    const res = { sym: new Map(), unit: new Map() };
    (X.byPhase.get(phase) || []).forEach((d) => { if (d.how === 'elide') return; d.new.forEach((n) => { if (D.isPureCopy(n)) return; const k = keyOf(n); [k.member, k.cls].forEach((s) => { if (s != null) res.sym.set(s, (res.sym.get(s) || 0) + 1); }); res.unit.set(d.unit, (res.unit.get(d.unit) || 0) + 1); }); });
    X.phaseCache.set(phase, res); return res;
  }
  function totals() {
    reset(); if (X.total) return X.total; const D = R.ST.D, res = { sym: new Map(), unit: new Map() };
    D.derives.forEach((d) => { if (d.how === 'elide') return; d.new.forEach((n) => { if (D.isPureCopy(n)) return; const k = keyOf(n); [k.member, k.cls].forEach((s) => { if (s != null) res.sym.set(s, (res.sym.get(s) || 0) + 1); }); res.unit.set(d.unit, (res.unit.get(d.unit) || 0) + 1); }); });
    return (X.total = res);
  }
  // definitions of a unit in version v: [{id, kind, name, sym, kids:[...]}]
  function structure(v) {
    if (X.struct.has(v)) return X.struct.get(v); const D = R.ST.D, out = [];
    (function walk(id, into, depth) {
      const nd = D.nodes.get(id); if (!nd) return;
      if (DEF.has(nd.kind) && nd.name && nd.name !== '_') {   // the template self ('_') is not a member
        const item = { id, kind: nd.kind, name: nd.name, sym: nd.sym, kids: [], cls: isClassLike(nd) }; into.push(item);
        if (item.cls && depth < 3) nd.children.forEach((c) => walk(c, item.kids, depth + 1)); return;
      }
      nd.children.forEach((c) => walk(c, into, depth));
    })(v.root, out, 0);
    X.struct.set(v, out); return out;
  }
  const versionFor = (unit, phase) => { const vs = R.ST.D.versionsOf(unit); let r = vs[0]; vs.forEach((x) => { if (x.phase <= phase) r = x; }); return r; };
  const base = (u) => ((R.ST.D.units.get(u) || {}).path || 'unit ' + u).replace(/^.*\//, '');

  // ---- selection ----
  const sameVersionIn = (unit) => { const cur = R.ver(), vs = (R.ST.unit = unit, R.vers()); const k = cur ? vs.findIndex((v) => v.phase === cur.phase && !!v.irPseudo === !!cur.irPseudo) : -1; return k >= 0 ? k : 0; };
  R.selectUnit = (u) => { const D = R.ST.D; if (u === R.ST.unit) return; R.ST.unit = u; R.ST.cls = null; const k = sameVersionIn(u); R.set({ unit: u, cls: null, verIdx: k }); };
  const multi = () => (R.ST.multi || (R.ST.multi = new Set()));
  function toggleMulti(key) { const m = multi(); if (m.has(key)) m.delete(key); else m.add(key); R.render(); }

  function render() {
    const D = R.ST.D, box = $('exptree'), ex = $('explorer'), work = $('work'); if (!D) return;
    ex.hidden = !X.open; $('exsplit').hidden = !X.open; ex.style.width = X.w + 'px'; $('btn-exp').classList.toggle('on', X.open);
    if (!X.open) return;
    reset(); const v = R.ver(), phase = v ? v.phase : -1, cur = phaseCounts(phase), tot = totals(), f = X.filter.toLowerCase();
    const units = D.unitsWithVersions.slice().sort((a, b) => base(a).localeCompare(base(b))).filter((u) => !f || base(u).toLowerCase().includes(f));
    const maxCur = Math.max(1, ...units.map((u) => cur.unit.get(u) || 0));
    let h = '';
    const cnt = (c, t) => `<span class="cnt" title="non-copy new nodes in this phase / over all phases"><span class="cb" style="width:${Math.min(40, Math.round(40 * c / Math.max(1, maxCur)))}px"></span>${c || ''}<span class="dim"> ${t ? '/ ' + t : ''}</span></span>`;
    const rowCls = (c, d) => `class="xr${c ? ' cur' : ''}" style="padding-left:${6 + d * 12}px"`;
    units.forEach((u) => {
      const isCur = u === R.ST.unit, open = X.exp.has(u) || isCur, mk = 'u:' + u, ck = multi().has(mk);
      h += `<div ${rowCls(isCur, 0)} data-u="${u}"><span class="tg" data-tg="${u}">${open ? '▾' : '▸'}</span><input type="checkbox" class="mc" data-mk="${mk}" ${ck ? 'checked' : ''} aria-label="select ${esc(base(u))} for the multi view" title="select several units/classes: shown side by side"><span class="nm" title="${esc((D.units.get(u) || {}).path || '')}">${esc(base(u))}</span>${cnt(cur.unit.get(u) || 0, tot.unit.get(u) || 0)}</div>`;
      if (open) {
        const sv = versionFor(u, phase), items = structure(sv);
        const emit = (it, d) => { const c = cur.sym.get(it.sym) || 0, t = tot.sym.get(it.sym) || 0; h += `<div ${rowCls(it.id === R.ST.selNode, d)} data-n="${it.id}" data-u="${u}" data-s="${it.sym ?? ''}"><span class="tg">${it.kids.length ? '▾' : ''}</span><span class="kd">${it.cls ? '◼' : '◦'}</span><span class="nm">${esc(it.name)}</span>${cnt(c, t)}</div>`; it.kids.slice(0, 200).forEach((k) => emit(k, d + 1)); };
        items.forEach((it) => emit(it, 1));
        if (!D.hasIR && R.curCompile) h += `<div class="xr sub dim" style="padding-left:18px" title="released compilers lack the backend join points of the recorder">no bytecode: this version's backend hooks are not available</div>`;
        if (D.hasIR) { const cl = [...D.jvmClasses.values()].filter((c) => c.unit === u); if (cl.length) { h += `<div class="xr sub" style="padding-left:18px">bytecode classes</div>`; cl.forEach((c) => { const ck2 = multi().has('c:' + c.name); h += `<div ${rowCls(R.curClass() === c.name && R.ver() && R.ver().irPseudo, 1)} data-c="${esc(c.name)}" data-u="${u}"><input type="checkbox" class="mc" data-mk="c:${esc(c.name)}" ${ck2 ? 'checked' : ''} aria-label="select class ${esc(c.name)}"><span class="kd">⬡</span><span class="nm" title="${esc(c.name)}">${esc(c.name.replace(/^.*\//, ''))}</span><span class="dim">${c.versions.length} v</span></div>`; }); } }
      }
    });
    if (!units.length) h = '<div class="dim" style="padding:6px">no unit matches</div>';
    box.innerHTML = h;
    const nsel = multi().size; $('expmulti').hidden = !nsel; $('expmulti').textContent = nsel ? `${nsel} selected · show together` : ''; $('expmulti').title = 'show the selection side by side / stacked';
    box.querySelectorAll('.mc').forEach((c) => (c.onclick = (ev) => { ev.stopPropagation(); toggleMulti(c.dataset.mk); }));
    box.querySelectorAll('.tg[data-tg]').forEach((t) => (t.onclick = (ev) => { ev.stopPropagation(); const u = +t.dataset.tg; if (X.exp.has(u)) X.exp.delete(u); else X.exp.add(u); save(); render(); }));
    box.querySelectorAll('.xr[data-u]').forEach((r) => (r.onclick = (ev) => {
      const u = +r.dataset.u, ctrl = ev.ctrlKey || ev.metaKey;
      if (r.dataset.c) { if (ctrl) return toggleMulti('c:' + r.dataset.c); R.ST.unit = u; R.set({ unit: u, cls: r.dataset.c }); return; }
      if (r.dataset.n) { if (u !== R.ST.unit) { R.selectUnit(u); } const id = +r.dataset.n, sym = r.dataset.s === '' ? null : +r.dataset.s; const v2 = R.ver(); if (v2 && !v2.irPseudo && !v2.ids.has(id)) { const p = R.present(id, v2); if (p.size) { R.set({ selNode: [...p][0], selSym: sym }); return; } } R.set({ selNode: id, selSym: sym }); return; }
      if (ctrl) return toggleMulti('u:' + u);
      R.selectUnit(u); X.exp.add(u); save();
    }));
    // keep the current unit's row in view
    const c = box.querySelector('.xr.cur'); if (c && X._lastCur !== R.ST.unit + ':' + R.ST.selNode) { X._lastCur = R.ST.unit + ':' + R.ST.selNode; c.scrollIntoView({ block: 'nearest' }); }
  }

  document.addEventListener('DOMContentLoaded', () => {
    $('btn-exp').onclick = () => { X.open = !X.open; save(); R.render(); };
    $('expclose').onclick = () => { X.open = false; save(); R.render(); };
    $('expfilter').oninput = (ev) => { X.filter = ev.target.value.trim(); render(); };
    $('expmulti').onclick = () => { R.ST.multiOn = !R.ST.multiOn; if (R.ST.multiOn && R.setView) R.setView('scrub'); else if (R.setView) R.setView('all'); R.render(); };
    $('expclear').onclick = () => { multi().clear(); R.ST.multiOn = false; R.render(); };
    const h = $('exsplit'); h.onmousedown = (ev) => { ev.preventDefault(); h.classList.add('drag'); const x0 = $('explorer').getBoundingClientRect().left; const mv = (e) => { X.w = Math.max(150, Math.min(600, e.clientX - x0)); $('explorer').style.width = X.w + 'px'; }; const up = () => { h.classList.remove('drag'); document.removeEventListener('mousemove', mv); document.removeEventListener('mouseup', up); save(); R.render(); }; document.addEventListener('mousemove', mv); document.addEventListener('mouseup', up); };
    document.addEventListener('keydown', (ev) => { if (ev.key === 'x' && !ev.metaKey && !ev.ctrlKey && !/^(INPUT|TEXTAREA|SELECT)$/.test(ev.target.tagName)) { X.open = !X.open; save(); R.render(); } });
  });
  R.views.push(render);
})(window.R);
