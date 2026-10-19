// Multi view: several units (tree text) and/or classes (bytecode) at the same phase, shown side by side (<= 3 panes on a wide window)
// or stacked with sticky headers. One shared phase, selection and cursor. Simplified panes: no windowing (first LINE_CAP lines), no animation.
(function (R) {
  const $ = (id) => document.getElementById(id);
  const esc = (x) => String(x).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const LINE_CAP = 1500;
  R.selectedUnits = () => {
    const D = R.ST.D, out = new Set(); (R.ST.multi || new Set()).forEach((k) => { if (k[0] === 'u') out.add(+k.slice(2)); else { const c = D.jvmClasses && D.jvmClasses.get(k.slice(2)); if (c) out.add(c.unit); } });
    if (!out.size) out.add(R.ST.unit); return out;
  };
  R.multiActive = () => !!(R.ST.multiOn && R.ST.multi && R.ST.multi.size);
  const treeVersionAt = (unit, phase, ir) => { const vs = R.ST.D.versionsOf(unit); if (!vs.length) return null; if (ir) return vs[vs.length - 1]; let r = vs[0]; vs.forEach((x) => { if (x.phase <= phase) r = x; }); return r; };

  function pane(key, v0) {
    const D = R.ST.D, S = R._s, ir = !!v0.irPseudo, isCls = key[0] === 'c';
    let ver, label, lang = 'scala', unit;
    if (isCls) { const name = key.slice(2), c = D.jvmClasses.get(name); if (!c) return null; unit = c.unit; ver = D.irClassVersion(name, ir ? v0.phase : D.irGenPhase); label = `${name.replace(/^.*\//, '')} · ${ir ? R.verName(v0) : 'genBCode'}`; lang = 'bc'; }
    else { unit = +key.slice(2); ver = treeVersionAt(unit, v0.phase, ir); label = `${((D.units.get(unit) || {}).path || '').replace(/^.*\//, '')} · ${ver ? D.phaseName(ver.phase) : '—'}`; }
    if (!ver) return null;
    const W = S.viewOf(ver), el = document.createElement('div'); el.className = 'mpane';
    const head = document.createElement('div'); head.className = 'mphead'; head.innerHTML = `<b>${esc(label)}</b><span class="dim"> ${ver.ids.size} nodes</span><button class="x" title="remove from the selection" data-mk="${esc(key)}">×</button>`; el.append(head);
    // highlights: nodes new relative to the unit's previous version (tree), selection
    const cls = new Map(), vs = isCls ? [] : D.versionsOf(unit), i = vs.indexOf(ver), prev = i > 0 ? vs[i - 1] : null;
    if (prev) ver.ids.forEach((n) => { if (!prev.ids.has(n)) cls.set(n, S.classOf(D, n, ver)); });
    const sel = isCls ? new Set() : R.present(R.ST.selNode, ver), links = R.irLinks ? R.irLinks() : { ir: new Set() }, lnk = isCls ? new Set([...links.ir].filter((x) => ver.ids.has(x))) : new Set();
    const clsF = (id) => { const nd = D.nodes.get(id), c = cls.get(id); return (nd ? 'k-' + nd.kind.replace(/[^A-Za-z]/g, '') + ' ' : '') + (c && !(R.ST.collapse && c === 'copy') && !(R.ST.structOnly && c !== 'struct') ? 'new nc-' + c + ' ' : '') + (sel.has(id) ? 'sel ' : '') + (lnk.has(id) ? 'lnk ' : ''); };
    const body = document.createElement('div'); body.className = 'mpbody2' + (lang === 'bc' ? ' bc' : ''); body.dataset.unit = unit; body.dataset.ver = ''; el.append(body);
    const ls = S.lineStarts(W.text), hi = ls.length > LINE_CAP ? ls[LINE_CAP] : W.text.length;
    S.setLang(lang); body.append(S.lineify(S.build(W.text, W.offs, clsF, 0, hi))); S.setLang('scala');
    if (ls.length > LINE_CAP) body.append(Object.assign(document.createElement('div'), { className: 'dim', textContent: `… ${ls.length - LINE_CAP} more lines (select only this unit to see everything)` }));
    el._ver = ver; el._unit = unit; el._cls = isCls ? key.slice(2) : null;
    body.addEventListener('click', (ev) => { const n = ev.target.closest('.n'); if (!n) return; const id = +n.dataset.n, nd = D.nodes.get(id); if (el._unit !== R.ST.unit) { R.ST.unit = el._unit; } if (el._cls) R.ST.cls = el._cls; R.set({ selNode: id, selSym: nd ? nd.sym : null }); });
    body.addEventListener('mousemove', (ev) => { const n = ev.target.closest('.n'), t = $('tip'); if (!n) { t.hidden = true; return; } const id = +n.dataset.n, d = el._cls ? null : R.describeNode(id, ver); t.innerHTML = el._cls ? R.irTip(id) : d ? R._s.infoHtml(d, ver, false) : ''; t.hidden = !t.innerHTML; t.style.left = Math.min(ev.clientX + 14, innerWidth - t.offsetWidth - 8) + 'px'; t.style.top = Math.min(ev.clientY + 16, innerHeight - t.offsetHeight - 8) + 'px'; });
    body.addEventListener('mouseleave', () => { $('tip').hidden = true; });
    return el;
  }

  function render() {
    const on = R.multiActive(), mb = $('multi'), v = R.ver();
    $('panes').hidden = on; $('mpanel').hidden = on || $('mpanel').hidden; mb.hidden = !on;
    document.body.classList.toggle('multion', on);
    if (!on || !v) return;
    const keys = [...R.ST.multi], wide = mb.clientWidth >= 1000 && keys.length <= 3;
    mb.className = wide ? 'wide' : 'stack'; mb.style.gridTemplateColumns = wide ? `repeat(${keys.length}, minmax(0, 1fr))` : '1fr';
    mb.replaceChildren(); keys.forEach((k) => { const p = pane(k, v); if (p) mb.append(p); });
    mb.querySelectorAll('.mphead .x').forEach((b) => (b.onclick = () => { R.ST.multi.delete(b.dataset.mk); if (!R.ST.multi.size) R.ST.multiOn = false; R.render(); }));
  }
  document.addEventListener('DOMContentLoaded', () => { });
  R.views.push(render);
})(window.R);
