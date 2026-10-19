// Event inspector: ev tree touching the selected node / symbol (children rendered lazily), and the parent chain at the cursor.
(function (R) {
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const SHOWN = new Set(['pt', 'step', 'name', 'accepted', 'why', 'reason', 'severity', 'level', 'msg', 'forcedBy', 'tpe', 'ref', 'result', 'ok', 'changed', 'members', 'scope', 'eligible', 'levels', 'ambiguous', 'outcome', 'reports']);
  const CAP = 120;
  const SEV = ['info', 'warning', 'error'];

  const tx = (D, v) => (typeof v === 'number' ? D.typeText(v) : v);
  const cut = (s, n = 160) => (s != null && String(s).length > n ? String(s).slice(0, n) + '…' : s);
  const nodeTag = (D, id) => { const n = D.nodes.get(id); return `<span class="dim">${esc(n ? n.kind : '?')}#${id}</span>`; };

  function summary(e) {
    const D = R.ST.D, p = e.payload || {}, sy = (i) => `<b>${esc(D.symName(i))}</b>`;
    const ns = e.nodes.filter((n) => n != null), nodeK = ns.length ? nodeTag(D, ns[ns.length - 1]) : '';
    const pt = (k = 'pt') => { const h = p[k] != null ? R.typeHtml(tx(D, p[k]), 160) : null; return h ? ` <span class="dim">${k}</span> ${h}` : ''; };
    switch (e.kind) {
      case 'Typed': case 'Retyped': return `${nodeK}${pt()}${(() => { const h = p.tpe != null ? R.typeHtml(tx(D, p.tpe), 160) : null; return h ? ' <span class="dim">⇒</span> ' + h : ''; })()}${p.aborted ? ' <span class="bad">aborted</span>' : ''}${e.syms.length ? ' ' + sy(e.syms[0]) : ''}`;
      case 'Adapt': return `${nodeK}${p.step ? ' <b>' + esc(p.step) + '</b>' : ''}${pt()}${p.changed === false ? ' <span class="dim">(unchanged)</span>' : ''}`;
      case 'Resolve': return `<code>${esc(p.name ?? '')}</code> → ${e.syms.length ? sy(e.syms[0]) : '?'}${p.ambiguity ? ' <span class="bad">ambiguous</span>' : ''}`;
      case 'Complete': { const f = p.forcedBy; return `${e.syms.length ? sy(e.syms[0]) : ''} <span class="dim">forced by ${esc(typeof f === 'number' ? D.symName(f) : f ?? 'root')}${p.reason ? ' (' + esc(p.reason) + ')' : ''}</span>${p.ok === false ? ' <span class="bad">failed</span>' : ''}`; }
      case 'ImplicitSearch': return `${pt()}${p.result ? ' <span class="' + (p.result === 'success' ? 'ok' : 'bad') + '">' + esc(p.result) + '</span>' : ''}${e.syms.length ? ' → ' + sy(e.syms[0]) : ''}`;
      case 'Candidate': return `<span class="${p.accepted ? 'ok' : 'bad'}">${p.accepted ? '✓ accepted' : '✗ rejected'}</span> ${e.syms.length ? sy(e.syms[0]) : ''}${(() => { const h = p.ref != null ? R.typeHtml(tx(D, p.ref), 80) : null; return h ? ' ' + h : ''; })()} <span class="why">${esc(p.reason ?? p.why ?? '')}</span>`;
      case 'Rejected': { const th = p.ref != null ? R.typeHtml(tx(D, p.ref), 80) : null, nm = e.syms.length ? sy(e.syms[0]) + (th ? ' <span class="dim">' + th + '</span>' : '') : (th || '');   /* ref is a TYPE id (as in Candidate), not a symbol */ return `<span class="bad">✗ rejected</span> ${nm} <span class="why">${esc(({ notEligible: 'not eligible', ambiguous: 'ambiguous' }[p.reason]) ?? p.reason ?? '')}</span>`; }
      case 'Scope': { const names = (a) => (a || []).map((i) => { const t = tx(D, i); return t != null ? '<span class="tp">' + esc(cut(t, 70)) + '</span>' : '#' + i; }).join(', ') || '∅';   /* eligible / ambiguous are type ids */ return `<span class="dim">scope</span> <b>${esc(p.scope ?? '')}</b> <span class="dim">eligible</span> ${names(p.eligible)}${p.levels && p.levels.length ? ' <span class="dim">(level' + (p.levels.length > 1 ? 's ' : ' ') + esc(p.levels.join(', ')) + ')</span>' : ''}${p.result ? ' <span class="' + (p.result === 'success' ? 'ok' : 'bad') + '">' + esc(p.result) + '</span>' : ''}${p.ambiguous && p.ambiguous.length ? ' <span class="bad">ambiguous: ' + names(p.ambiguous) + '</span>' : ''}`; }
      case 'Tentative': return `<span class="dim">tentative typing</span> <b>${esc(p.outcome ?? '')}</b>${p.reports ? ' <span class="bad">' + p.reports + ' report' + (p.reports > 1 ? 's' : '') + '</span>' : ' <span class="dim">(no reports)</span>'}`;
      case 'Report': { const sev = p.severity ?? SEV[p.level] ?? p.level; return `<span class="sev-${esc(sev)}">${esc(sev ?? '')}</span>${p.reporter ? ' <span class="dim">(' + esc(p.reporter) + ')</span>' : ''}<div class="rmsg">${esc(p.msg ?? '')}</div>`; }
      case 'JvmPhase': return `<span class="dim">JVM phase</span> <b>${esc(p.name ?? D.phaseName(e.phase))}</b> <span class="dim">(synthetic step: bytecode versions of this phase)</span>`;
      case 'PhaseBegin': return `<b>${esc(p.name ?? D.phaseName(e.phase))}</b>`;
      case 'PhaseEnd': return `<span class="dim">end of</span> <b>${esc(D.phaseName(e.phase))}</b>`;
      default: return esc(cut(JSON.stringify(p), 120));
    }
  }
  function extras(e) {
    const rest = Object.entries(e.payload || {}).filter(([k]) => !SHOWN.has(k));
    return rest.length ? rest.map(([k, v]) => `<span class="dim">${esc(k)}</span> ${esc(cut(Array.isArray(v) ? v.join(', ') || '∅' : typeof v === 'object' ? JSON.stringify(v) : v, 200))}`).join(' · ') : '';
  }

  // events worth marking: diagnostics, failed implicit searches, rejected candidates, failed completions, aborted typings
  R.isProblem = (e) => { const p = e.payload || {}; return e.kind === 'Report' || (e.kind === 'ImplicitSearch' && p.result && p.result !== 'success') || (e.kind === 'Candidate' && p.accepted === false) || (e.kind === 'Rejected' && p.reason !== 'notEligible') || (e.kind === 'Complete' && p.ok === false) || (e.kind === 'Typed' && p.aborted === true); };
  const visKids = (D, seq) => { const k = D.evChildren.get(seq) || []; return R.ST.showRetyped ? k : k.filter((x) => x.kind !== 'Retyped'); };

  function row(e, cursorPath, depth) {
    const D = R.ST.D, kids = visKids(D, e.seq);
    const isCur = e.seq === R.seq(), open = cursorPath.has(e.seq) || (depth < 2 && kids.length <= CAP);
    const d = document.createElement(kids.length ? 'details' : 'div');
    d.className = 'ev' + (isCur ? ' cur' : '');
    const head = document.createElement(kids.length ? 'summary' : 'div');
    head.className = 'evh';
    const ex = extras(e);
    head.innerHTML = `<span class="seq">${e.seq}</span><span class="chip k-${esc(e.kind)}">${esc(e.kind)}</span> ${summary(e)}${kids.length ? ` <span class="dim">(${kids.length})</span>` : ''}${ex ? `<div class="ex">${ex}</div>` : ''}`;
    head.addEventListener('click', (ev) => { if (ev.target.closest('.seq,.chip')) { ev.preventDefault(); R.setEvBySeq(e.seq, false); } });
    d.append(head);
    if (kids.length) {
      const box = document.createElement('div'); box.className = 'kids' + (depth >= 8 ? ' flat' : ''); d.append(box);
      let filled = 0;
      const fill = () => {
        const more = box.querySelector('.more'); if (more) more.remove();
        // always include the child on the cursor path even if beyond the cap
        const upto = Math.min(kids.length, filled + CAP);
        for (let i = filled; i < upto; i++) box.append(row(kids[i], cursorPath, depth + 1));
        filled = upto;
        if (filled < kids.length) { const b = document.createElement('div'); b.className = 'more dim'; b.textContent = `… ${kids.length - filled} more events (click to show)`; b.onclick = fill; box.append(b); }
      };
      let onPath = kids.findIndex((k) => cursorPath.has(k.seq)); if (onPath >= CAP) filled = onPath;   // reveal the cursor's branch
      if (open) { d.open = true; fill(); } else d.addEventListener('toggle', () => { if (d.open && !filled) fill(); }, { once: false });
    }
    return d;
  }

  function chain(e) { const D = R.ST.D, out = []; for (let x = e; x && out.length < 200; x = x.parent != null ? D.evBySeq.get(x.parent) : null) out.unshift(x); return out; }

  // text of an event for the free-text filter
  const evText = (D, e) => { const p = e.payload || {}; return [e.kind, ...e.syms.map((x) => D.symName(x)), p.name, p.msg, p.reason, p.why, p.result, p.step, ...e.nodes.map((n) => { const nd = D.nodes.get(n); return nd && nd.name; })].filter((x) => x != null).join(' ').toLowerCase(); };
  const filterState = () => ({ kind: $('evkind').value, text: $('evtext').value.trim().toLowerCase(), prob: $('evprob').checked, unit: $('evunit').value });
  const selUnitsActive = () => !!(R.multiActive && R.multiActive() && R.ST.selNode == null && R.ST.selSym == null);
  const filtering = (f) => !!(f.kind || f.text || f.prob) || selUnitsActive();

  function selectedRoots(D, v) {
    const { selNode, selSym } = R.ST, keep = (x) => R.ST.showRetyped || x.kind !== 'Retyped';
    let roots = [], title = '', tabs = null;
    if (selNode != null) {
      const S = R.walk(D, selNode, 'bwd', null, v ? v.phase : null); S.add(selNode);
      const nd = D.nodes.get(selNode), sym = nd ? nd.sym : null;
      const used = new Set(); S.forEach((n) => (D.evByNode.get(n) || []).forEach((x) => used.add(x)));
      // use-site events (search, adapt, diagnostics) are often attached to an enclosing tree: look at the tree ancestors
      if (v) { let x = [...R.present(selNode, v)][0] ?? selNode; for (let i = 0; i < 8 && x != null; i++) { (D.evByNode.get(x) || []).forEach((e) => { if (/^(ImplicitSearch|Candidate|Adapt|Report)$/.test(e.kind)) used.add(e); }); x = R.parentOf(v, x); } }
      const defd = sym != null ? (D.evBySym.get(sym) || []) : [];
      if (sym != null && defd.length) {
        const tab = R.ST.evTab || 'used';
        tabs = { tab, used: [...used].filter(keep).length, defd: defd.filter(keep).length };
        roots = (tab === 'defined' ? defd : [...used]).filter(keep);
        title = `${tab === 'defined' ? 'definition events of' : 'events at this use of'} <b>${esc(D.symName(sym))}</b> <span class="dim">(node #${selNode})</span>`;
      } else {
        roots = [...used].filter(keep);
        title = `events touching node #${selNode} <span class="dim">${esc(nd ? nd.kind + (nd.name ? ' ' + nd.name : '') : '')}</span>` + (S.size > 1 ? ` <span class="dim">(+${S.size - 1} ancestor node${S.size > 2 ? 's' : ''} via derive)</span>` : '');
      }
    } else if (selSym != null) { roots = (D.evBySym.get(selSym) || []).filter(keep); title = `events touching symbol <b>${esc(D.symName(selSym))}</b>`; }
    else title = 'select a span or graph node to list its events, or filter all events above';
    return { roots, title, tabs };
  }

  function render() {
    const { D, selNode, selSym } = R.ST, e = R.ev(), v = R.ver();
    // reports chip
    const nrep = D.evs.filter((x) => x.kind === 'Report').length, rc = $('repchip'); rc.hidden = !nrep; rc.textContent = `Reports (${nrep})`;
    const u = $('evunit'); if (u._D !== D) { u._D = D; u.innerHTML = '<option value="">all units</option><option value="cur">current unit</option>'; const k = $('evkind'); const kinds = [...new Set(D.evs.map((x) => x.kind))].filter((x) => x !== 'PhaseBegin' && x !== 'PhaseEnd').sort(); k.innerHTML = '<option value="">any kind</option>' + kinds.map((x) => `<option>${esc(x)}</option>`).join(''); }
    const st = $('stack'); st.innerHTML = '';
    if (e) {
      const ch = chain(e);
      ch.forEach((x, i) => { const r = document.createElement('div'); r.className = 'frame' + (x === e ? ' cur' : ''); r.style.paddingLeft = 6 + Math.min(i, 8) * 12 + 'px'; r.innerHTML = `<span class="seq">${x.seq}</span><span class="chip k-${esc(x.kind)}">${esc(x.kind)}</span> ${summary(x)}`; r.onclick = () => R.setEvBySeq(x.seq, false); st.append(r); });
      st.scrollTop = st.scrollHeight;
    }
    const box = $('evtree'); box.innerHTML = ''; const f = filterState(), tb = $('evtabs'); tb.innerHTML = '';
    $('evclear').hidden = !filtering(f);
    if (filtering(f)) {
      // flat result list over all events (optionally the current unit only)
      const keep = (x) => (R.ST.showRetyped || x.kind !== 'Retyped') && (!f.kind || x.kind === f.kind) && (!f.prob || R.isProblem(x)) && (f.unit !== 'cur' || x.unit == null || x.unit === R.ST.unit) && (!selUnitsActive() || (x.unit != null && R.selectedUnits().has(x.unit))) && (!f.text || evText(D, x).includes(f.text));
      const res = []; for (let i = 0; i < D.evs.length && res.length < 301; i++) if (keep(D.evs[i])) res.push(D.evs[i]);
      $('evtitle').innerHTML = `${selUnitsActive() ? 'events in the ' + R.selectedUnits().size + ' selected unit(s): ' : ''}${res.length > 300 ? '300+' : res.length} matching events <span class="dim">(click a row to put the cursor there)</span>`;
      if (!res.length) box.innerHTML = '<span class="dim">No event matches.</span>';
      res.slice(0, 300).forEach((x) => { const d = document.createElement('div'); d.className = 'ev flatrow' + (e && x.seq === e.seq ? ' cur' : ''); d.innerHTML = `<div class="evh"><span class="seq">${x.seq}</span><span class="chip k-${esc(x.kind)}">${esc(x.kind)}</span> ${summary(x)}</div>`; d.onclick = () => { const nd = R.firstNode(x); R.setEvBySeq(x.seq, false); if (nd != null) R.set({ selNode: nd, selSym: (D.nodes.get(nd) || {}).sym ?? null }); }; box.append(d); });
      return;
    }
    let { roots, title, tabs } = selectedRoots(D, v);
    if (tabs) {
      tb.innerHTML = `<button class="${tabs.tab === 'used' ? 'on' : ''}" data-tab="used" title="events at this use of the symbol: on this node and on its ancestors">used here (${tabs.used})</button><button class="${tabs.tab === 'defined' ? 'on' : ''}" data-tab="defined">defined (${tabs.defd})</button>`;
      tb.querySelectorAll('button').forEach((b) => (b.onclick = () => R.set({ evTab: b.dataset.tab })));
    }
    const set = new Set(roots.map((x) => x.seq));
    roots = roots.filter((x) => { for (let p = x.parent, i = 0; p != null && i < 500; i++) { if (set.has(p)) return false; p = (D.evBySeq.get(p) || {}).parent ?? null; } return true; }).sort((a, b) => a.seq - b.seq);
    $('evtitle').innerHTML = title + (roots.length ? ` · ${roots.length} root${roots.length > 1 ? 's' : ''}` : '');
    const path = new Set(e ? chain(e).map((x) => x.seq) : []);
    if (selNode == null && selSym == null) box.innerHTML = '<span class="dim">Nothing selected.</span>';
    else if (!roots.length) box.innerHTML = '<span class="dim">No visible ev record references this selection' + (R.ST.showRetyped ? '' : ' (Retyped events are hidden)') + '.</span>';
    else {
      roots.slice(0, 60).forEach((r) => box.append(row(r, path, 0)));
      if (roots.length > 60) box.append(Object.assign(document.createElement('div'), { className: 'dim', textContent: `… ${roots.length - 60} more roots not shown` }));
    }
    const c = box.querySelector('.ev.cur'); if (c) { const key = R.seq() + ':' + selNode + ':' + selSym + ':' + R.ST.evTab; if (box.dataset.k !== key) { box.dataset.k = key; c.scrollIntoView({ block: 'nearest' }); } }
  }
  function wire() {
    ['evkind', 'evunit', 'evprob'].forEach((id) => ($(id).onchange = () => R.render()));
    $('evtext').oninput = () => R.render();
    $('evclear').onclick = () => { $('evkind').value = ''; $('evtext').value = ''; $('evprob').checked = false; $('evunit').value = ''; R.render(); };
    $('repchip').onclick = () => {   // jump to the next Report after the cursor (wraps)
      const D = R.ST.D, reps = D.evs.filter((x) => x.kind === 'Report'); if (!reps.length) return;
      const nx = reps.find((x) => x.seq > R.seq()) || reps[0]; R.setEvBySeq(nx.seq, true);
      const nd = R.firstNode(nx); if (nd != null) R.set({ selNode: nd, selSym: (D.nodes.get(nd) || {}).sym ?? null });
    };
  }
  document.addEventListener('DOMContentLoaded', wire);
  R.views.push(render);
})(window.R);
