// IR view: JVM bytecode versions (schema extension `ir: "jvm"`), tree <-> bytecode links, optimiser stepping per method.
// IR phases are pseudo versions at the end of the phase list (see app.js); the class text comes from the class selector.
(function (R) {
  const $ = (id) => document.getElementById(id);
  const S = () => R._s;
  const esc = (x) => String(x).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const isIR = (D, id) => { const n = D.nodes.get(id); return !!(n && n.ir); };

  // ---- links between tree nodes and JVM nodes (derive how=codegen) ----
  const cache = { key: null, val: null };
  function bfs(D, start, map, pick, stop, limit = 4000) {
    const out = new Set(), seen = new Set(start), q = [...start];
    for (let i = 0; i < q.length && seen.size < limit; i++) {
      const n = q[i]; if (stop && stop(n)) { out.add(n); continue; }
      (map.get(n) || []).forEach((d) => pick(d).forEach((m) => { if (!seen.has(m)) { seen.add(m); q.push(m); } }));
    }
    return out;
  }
  // tree node (any version) -> JVM node ids as emitted at genBCode
  R.irFromTreeNode = (n) => {
    const D = R.ST.D; if (!D.hasIR) return [];
    const reach = R.walk(D, n, 'fwd', (m) => D.irFromTree.has(m), null), out = new Set();
    reach.forEach((m) => (D.irFromTree.get(m) || []).forEach((x) => out.add(x)));
    return [...out];
  };
  // JVM node (any phase) -> tree nodes it was generated from (follows rewrite/copy/inlined-from back to codegen)
  R.irToTreeNodes = (id) => {
    const D = R.ST.D; if (!D.hasIR) return [];
    const back = bfs(D, [id], D.irByNew, (d) => d.old, (m) => D.irToTree.has(m)), out = new Set();
    back.forEach((m) => (D.irToTree.get(m) || []).forEach((t) => out.add(t)));
    return [...out];
  };
  // JVM node ids of the class version `cv` that stand for `ids` (itself, successors through derives, else predecessors)
  function irPresent(D, ids, cv) {
    const out = new Set();
    ids.forEach((id) => {
      if (cv.ids.has(id)) { out.add(id); return; }
      const f = bfs(D, [id], D.irByOld, (d) => d.new, (m) => cv.ids.has(m)); if (f.size) { f.forEach((m) => out.add(m)); return; }
      bfs(D, [id], D.irByNew, (d) => d.old, (m) => cv.ids.has(m)).forEach((m) => out.add(m));
    });
    return out;
  }
  // links for the current selection: tree nodes (genBCode input) and raw genBCode JVM ids
  R.irLinks = () => {
    const st = R.ST, D = st.D; if (!D.hasIR || st.selNode == null) return { tree: new Set(), ir: new Set() };
    const key = D.nodes.size + ':' + st.selNode; if (cache.key === key) return cache.val;
    const n = st.selNode, val = isIR(D, n) ? { tree: new Set(R.irToTreeNodes(n)), ir: new Set([n]) } : { tree: new Set(), ir: new Set(R.irFromTreeNode(n)) };
    cache.key = key; cache.val = val; return val;
  };

  // ---- class text per phase, change classification ----
  const lastTreeVersion = (unit) => { const vs = R.ST.D.versionsOf(unit); return vs[vs.length - 1]; };
  function treeSnip(unit, n, max = 100) {
    const D = R.ST.D, vs = D.versionsOf(unit);
    for (let i = vs.length - 1, c = 0; i >= 0 && c < 6; i--, c++) if (vs[i].ids.has(n)) return S().snip(vs[i], n, max);
    return null;
  }
  const rangeText = (cv, id, max = 140) => { const w = S().viewOf(cv), o = w.byId.get(id); if (!o) return ''; const t = w.text.slice(o[1], o[2]).split('\n').map((x) => x.trim()).filter((x) => x && !/^(LINENUMBER|FRAME|L\d+$)/.test(x)).join(' ; '); return t.length > max ? t.slice(0, max) + '…' : t; };
  const deriveAt = (D, id, phase) => (D.irByNew.get(id) || []).find((d) => d.phase === phase);

  // method key (name+desc) -> text history through the class versions
  function methodTexts(cls) {
    const D = R.ST.D, c = D.jvmClasses.get(cls); if (!c) return new Map();
    if (c._mt && c._mtN === c.versions.length) return c._mt;
    const out = new Map();
    c.versions.forEach((cv) => {
      const w = S().viewOf(cv);
      w.offs.forEach((o) => { const nd = D.nodes.get(o[0]); if (nd && nd.kind === 'MethodNode') { const key = nd.name + nd.desc, h = out.get(key) || []; const t = w.text.slice(o[1], o[2]); if (!h.length || h[h.length - 1].text !== t) h.push({ phase: cv.phase, text: t, node: o[0] }); out.set(key, h); } });
    });
    c._mt = out; c._mtN = c.versions.length; return out;
  }
  // LCS line diff, collapsed to changed lines with context
  function diffLines(a, b) {
    const A = a.split('\n'), B = b.split('\n'), n = A.length, m = B.length, out = [];
    if (n * m > 4e6) { A.forEach((l) => out.push(['-', l])); B.forEach((l) => out.push(['+', l])); return out; }
    const L = Array.from({ length: n + 1 }, () => new Int32Array(m + 1));
    for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) L[i][j] = A[i] === B[j] ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);
    let i = 0, j = 0; while (i < n && j < m) { if (A[i] === B[j]) { out.push([' ', A[i]]); i++; j++; } else if (L[i + 1][j] >= L[i][j + 1]) out.push(['-', A[i++]]); else out.push(['+', B[j++]]); }
    while (i < n) out.push(['-', A[i++]]); while (j < m) out.push(['+', B[j++]]); return out;
  }
  function diffHtml(rows, ctx = 2) {
    const keep = new Array(rows.length).fill(false); rows.forEach((r, i) => { if (r[0] !== ' ') for (let k = Math.max(0, i - ctx); k <= Math.min(rows.length - 1, i + ctx); k++) keep[k] = true; });
    let h = '', gap = false;
    rows.forEach((r, i) => { if (!keep[i]) { gap = true; return; } if (gap) { h += '<div class="df-gap">⋯</div>'; gap = false; } const cl = r[0] === '+' ? 'df-add' : r[0] === '-' ? 'df-del' : 'df-same'; h += `<div class="${cl}">${esc(r[0] === ' ' ? '  ' : r[0] + ' ')}${r[0] === '-' ? '<s>' + esc(r[1]) + '</s>' : esc(r[1])}</div>`; });
    return h || '<div class="dim">no textual difference</div>';
  }

  // ---- tooltip / side panel html for a JVM node ----
  const dh = (d) => `<span class="how how-${esc(d.how)}">${esc(d.how)}</span>${d.confidence ? ` <span class="dim">${esc(d.confidence)}</span>` : ''}`;
  function irInfo(id, links) {
    const D = R.ST.D, nd = D.nodes.get(id), v = R.ver(); if (!nd) return '';
    const cls = D.irClassOf.get(id), unit = nd.unit ?? (D.jvmClasses.get(cls) || {}).unit;
    let h = `<div class="k">${esc(nd.kind)} ${nd.name ? '<b>' + esc(nd.name) + '</b>' : ''}${nd.desc ? ' <span class="dim">' + esc(nd.desc) + '</span>' : ''} <span class="dim">#${id}</span></div>`;
    if (nd.kind === 'InsnRange') { const m = D.nodes.get(nd.method); h += `<div><span class="dim">method</span> <b>${esc(m ? m.name : '?')}</b> <span class="dim">${esc(m ? m.desc : '')} · instructions ${nd.range[0]}–${nd.range[1]}</span></div>`; }
    if (nd.kind === 'MethodNode') h += `<div><span class="dim">instructions</span> ${nd.insns}</div>`;
    if (cls) h += `<div><span class="dim">class</span> ${esc(cls)}</div>`;
    const ds = (D.irByNew.get(id) || []).slice().sort((a, b) => a.phase - b.phase);
    if (ds.length) h += '<div class="anc"><span class="dim">history</span>' + ds.map((d) => `<div class="ar">${dh(d)} <span class="dim">@${esc(D.phaseRaw(d.phase))}</span>${d.how === 'inlined-from' ? ` ← ${esc(d.callee || 'callee')}` : ''}</div>`).join('') + '</div>';
    const trees = R.irToTreeNodes(id).slice(0, 6);
    if (trees.length) h += '<div class="anc"><span class="dim">generated from tree</span>' + trees.map((t) => { const tn = D.nodes.get(t); return `<pre class="old${links ? ' lk-tree' : ''}" data-tn="${t}" title="show this tree node">${esc((tn ? tn.kind : '#' + t) + ': ' + (treeSnip(unit, t) || ''))}</pre>`; }).join('') + '</div>';
    const inl = (D.irByNew.get(id) || []).filter((d) => d.how === 'inlined-from');
    inl.forEach((d) => { h += `<div class="anc"><span class="dim">inlined from</span> <b>${esc(d.callee || '(unnamed)')}</b>${d.old.length && links ? ` <a class="lk-ir" data-ir="${d.old[0]}" title="open the callee's bytecode (genBCode)">show callee</a>` : d.old.length ? '' : ' <span class="dim">(not compiled in this run)</span>'}</div>`; });
    return h;
  }
  R.irInfoHtml = (id) => irInfo(id, true);
  R.irTip = (id) => irInfo(id, false) + '<div class="dim">click selects · Alt+click: parent</div>';
  // tree-node hover/panel: bytecode ranges generated from it
  R.irTreeTip = (n) => { const D = R.ST.D; if (!D.hasIR) return ''; const r = R.irFromTreeNode(n); return r.length ? `<div class="dim">bytecode: ${r.length} instruction range${r.length > 1 ? 's' : ''} (select the node, then “show bytecode”)</div>` : ''; };
  R.irTreeLink = (n) => { const D = R.ST.D; if (!D.hasIR) return ''; const r = R.irFromTreeNode(n); if (!r.length) return '<div class="dim">no bytecode generated from this node</div>'; return `<div class="anc"><span class="dim">bytecode</span> ${r.length} range${r.length > 1 ? 's' : ''}: <a class="lk-ir" data-ir="${r[0]}" title="open the class text at genBCode with these ranges highlighted">show bytecode</a>${r.length > 1 ? ` <span class="dim">(first of ${r.length})</span>` : ''}</div>`; };

  // ---- the IR scrubber ----
  function renderIR(v) {
    const D = R.ST.D, st = R.ST, vs = R.vers(), k = vs.indexOf(v), prevPseudo = k > 0 && vs[k - 1].irPseudo ? vs[k - 1] : null;
    const cls = R.curClass(), cv = cls && D.irClassVersion(cls, v.phase), pane = $('text');
    $('removed').hidden = true; $('mpanel').hidden = !cv;
    if (!cv) { pane.className = ''; pane.textContent = 'This class has no bytecode version at this phase (choose a class).'; $('verinfo').innerHTML = `<b>${esc(R.verName(v))}</b>`; return; }
    const pcv = prevPseudo ? D.irClassVersion(cls, prevPseudo.phase) : null, W = S().viewOf(cv);
    // what happened to this class at this step
    const hows = new Map(); let elided = []; const changedHere = cv.phase === v.phase;
    if (changedHere) {
      cv.ids.forEach((id) => { const d = deriveAt(D, id, v.phase); if (d) hows.set(id, d.how); });
      (D.irDerives || []).forEach((d) => { if (d.phase === v.phase && d.how === 'elide') d.old.forEach((o) => { if (D.irClassOf.get(o) === cls) elided.push(o); }); });
    }
    const cnt = { rewrite: 0, copy: 0, 'inlined-from': 0, codegen: 0 }; hows.forEach((h) => { cnt[h] = (cnt[h] || 0) + 1; });
    // selection and links
    const sel = new Set(), links = R.irLinks(), lnk = new Set();
    const selIds = st.selNode != null && isIR(D, st.selNode) ? [st.selNode] : []; irPresent(D, selIds, cv).forEach((x) => sel.add(x));
    irPresent(D, [...links.ir], cv).forEach((x) => lnk.add(x));
    const kc = (id) => { const n = D.nodes.get(id); return n ? 'k-' + n.kind + ' ' : ''; };
    const clsF = (id) => { const h = hows.get(id); const nk = (D.nodes.get(id) || {}).kind; return kc(id) + (nk === 'ClassNode' ? '' : h === 'rewrite' ? 'new ir-rw ' : h === 'inlined-from' ? 'new ir-inl ' : h === 'copy' ? 'ir-cp ' : '') + (sel.has(id) ? 'sel ' : '') + (lnk.has(id) ? 'lnk ' : ''); };
    const rgs = new Set(); { const r = st.range; if (r && !r.ir) { let k = 0; for (const n of r.nodes) { if (k++ > 500) break; irPresent(D, R.irFromTreeNode(n), cv).forEach((x) => rgs.add(x)); } } else if (r && r.ir && r.cls === cls) irPresent(D, [...r.nodes], cv).forEach((x) => rgs.add(x)); }
    const targets = [...sel, ...lnk, ...rgs];
    S().paint(W, clsF, targets, v.phase + ':' + cls + ':' + st.selNode + ':' + (st.range ? st.range.lo + '-' + st.range.hi + ':' + st.range.phase : ''), 'bc', 'ir:' + st.unit + ':' + cls);
    // header
    const chips = (changedHere ? (cnt.rewrite ? `<span class="chip2 cs" title="Ranges whose instruction text changed in this step">${cnt.rewrite} rewritten</span>` : '') + (cnt['inlined-from'] ? `<span class="chip2 ci" title="Instructions copied in by the inliner">${cnt['inlined-from']} inlined</span>` : '') + (elided.length ? `<span class="chip2 cm" title="Ranges none of whose instructions survived: listed below, struck through">${elided.length} elided</span>` : '') + (cnt.copy ? `<span class="chip2 cc" title="Ranges unchanged in a changed method">${cnt.copy} unchanged ranges</span>` : '') : '') + (v.phase === D.irGenPhase ? '<span class="dim"> straight from the code generator</span>' : '');
    const note = !changedHere ? `<div class="banner">${esc(cls)} was not changed by <b>${esc(R.verName(v))}</b>; showing its text from <b>${esc(R.verName({ irPseudo: true, phase: cv.phase }))}</b>.</div>` : '';
    const ops = cv.text.split('\n').filter((t) => /^\s+[A-Z][A-Z0-9_]+\b/.test(t) && !/^\s+(LINENUMBER|FRAME|LOCALVARIABLE|MAXSTACK|MAXLOCALS)\b/.test(t)).length;
    const clsSel = `<select id="irclass" aria-label="class">${[...D.jvmClasses.values()].filter((c) => c.unit === st.unit).map((c) => `<option${c.name === cls ? ' selected' : ''}>${esc(c.name)}</option>`).join('')}</select>`;
    $('verinfo').innerHTML = note + `<b>${esc(v.phase === D.irGenPhase ? 'genBCode' : R.verName(v))}</b> · class ${clsSel} · ${ops} instructions · ${cv.ids.size} nodes ${chips}`;
    $('irclass').onchange = (ev) => R.set({ cls: ev.target.value });
    // elided ranges, struck through (text from the class version before the step)
    const rl = $('removed');
    if (elided.length && pcv) {
      rl.hidden = false; $('remsum').textContent = `elided in ${R.verName(v)} (${elided.length})`;
      if (rl.open) $('remlist').innerHTML = elided.slice(0, 200).map((id) => { const nd = D.nodes.get(id), m = nd && D.nodes.get(nd.method); return `<pre class="old"><span class="dim">${esc(m ? m.name : '')} ${nd && nd.range ? nd.range[0] + '–' + nd.range[1] : ''}</span> <s>${esc(rangeText(pcv, id, 200) || '(instructions of the previous text)')}</s></pre>`; }).join('');
    }
    renderMethodPanel(v, cls, cv, hows);
    // side panel
    const bc = $('crumbs'), nd = $('nodeinfo');
    const shown = [...sel][0];
    if (shown != null) {
      const chain = []; for (let x = shown, i = 0; x != null && i < 20; x = S().parentMap(cv).get(x), i++) chain.unshift(x);
      bc.innerHTML = chain.map((id) => { const n = D.nodes.get(id); return `<a class="crumb${id === shown ? ' on' : ''}" data-n="${id}">${esc(n ? n.kind + (n.name ? ' ' + n.name : n.range ? ' ' + n.range.join('–') : '') : '#' + id)}</a>`; }).join('<span class="dim"> › </span>');
      nd.innerHTML = irInfo(shown, true);
    } else { bc.innerHTML = ''; nd.innerHTML = st.selNode != null && !isIR(D, st.selNode) ? '<span class="dim">A tree node is selected: its bytecode ranges are outlined (dashed). Select a range here for its origin.</span>' : '<span class="dim">Click an instruction range (Alt+click: parent). Hover for origin and history. The method panel steps the optimiser per method.</span>'; }
    $('srcpane').hidden = true;
    renderSplit(v, new Set());
  }
  R.renderIR = renderIR;

  // ---- per-method optimiser stepping ----
  function renderMethodPanel(v, cls, cv, hows) {
    const D = R.ST.D, st = R.ST, mp = $('mpanel'), box = $('mpbody'), texts = methodTexts(cls);
    // methods present in this class version
    const mn = [...texts.keys()]; if (!mn.length) { box.innerHTML = '<span class="dim">no methods</span>'; return; }
    // default method: selected node's method, else the first method changed at this step
    let key = st.irMethod && texts.has(st.irMethod) ? st.irMethod : null;
    const sn = st.selNode != null ? D.nodes.get(st.selNode) : null;
    if (sn && sn.ir) { const m = sn.kind === 'MethodNode' ? sn : sn.kind === 'InsnRange' ? D.nodes.get(sn.method) : null; if (m && texts.has(m.name + m.desc)) key = m.name + m.desc; }
    const changedAt = (kk) => (texts.get(kk) || []).some((e) => e.phase === v.phase);
    if (!key) key = mn.find(changedAt) || mn[0];
    const hist = texts.get(key), idx = (() => { let i = -1; hist.forEach((e, j) => { if (e.phase <= v.phase) i = j; }); return i; })();
    const prevE = idx > 0 ? hist[idx - 1] : null, curE = hist[Math.max(0, idx)], atThis = hist[idx] && hist[idx].phase === v.phase && idx > 0;
    const opts = mn.map((kk) => `<option value="${esc(kk)}"${kk === key ? ' selected' : ''}>${changedAt(kk) ? '● ' : ''}${esc(kk)}${(texts.get(kk).length > 1 ? ` (${texts.get(kk).length - 1} change${texts.get(kk).length > 2 ? 's' : ''})` : '')}</option>`).join('');
    const nameOf = (p) => R.verName({ irPseudo: true, phase: p });
    const dis = (b) => (b ? ' disabled' : '');
    const nextE = hist[idx + 1], pe = idx > 0 ? hist[idx - 1] : null;
    let h = `<div class="mp-bar"><select id="mpsel" aria-label="method">${opts}</select><button id="mpprev"${dis(!(idx > 0))} title="jump to the step before this method's latest change">◀ previous change</button><button id="mpnext"${dis(!nextE)} title="jump to the next step that changed this method">next change ▶</button><span class="dim">step ${Math.max(0, idx)}/${hist.length - 1}${hist.length > 1 ? ' · changed at ' + hist.slice(1).map((e) => esc(nameOf(e.phase))).join(', ') : ' · never changed by the optimiser'}</span></div>`;
    if (idx > 0) {
      const a = hist[idx - 1].text.replace(/\n$/, ''), b = curE.text.replace(/\n$/, '');
      h += `<div class="dim">${atThis ? `<b>${esc(nameOf(v.phase))}</b> changed this method:` : `last change was <b>${esc(nameOf(curE.phase))}</b> (not changed by ${esc(nameOf(v.phase))}):`} diff from ${esc(nameOf(hist[idx - 1].phase))}</div><div class="df">${diffHtml(diffLines(a, b))}</div>`;
    } else h += '<div class="dim">This is the generated text of the method (no optimiser change yet). Step with ▶.</div>';
    // inlined instructions in this method at or before this phase
    const inl = []; const mnode = [...cv.ids].map((i) => D.nodes.get(i)).find((n) => n && n.kind === 'MethodNode' && n.name + n.desc === key);
    if (mnode) cv.ids.forEach((id) => { const n = D.nodes.get(id); if (n && n.kind === 'InsnRange' && n.method === mnode.id) (D.irByNew.get(id) || []).forEach((d) => { if (d.how === 'inlined-from' && d.phase <= v.phase) inl.push([id, d]); }); });
    if (inl.length) h += '<div class="anc"><span class="dim">inlined into this method</span>' + inl.slice(0, 20).map(([id, d]) => `<div class="ar"><a class="lk-sel" data-n="${id}" title="select the copied range">range ${(D.nodes.get(id).range || []).join('–')}</a> ← <b>${esc(d.callee || '(unnamed)')}</b>${d.old.length ? ` <a class="lk-ir" data-ir="${d.old[0]}" title="open the callee's bytecode (genBCode)">show callee</a>` : ' <span class="dim">(not compiled in this run)</span>'}</div>`).join('') + (inl.length > 20 ? `<div class="dim">… ${inl.length - 20} more</div>` : '') + '</div>';
    box.innerHTML = h;
    st.irMethod = key;
    $('mpsel').onchange = (ev) => R.set({ irMethod: ev.target.value });
    const go = (e) => { const vs = R.vers(), k = vs.findIndex((x) => x.irPseudo && x.phase === e.phase); if (k >= 0) R.set({ verIdx: k }); };
    $('mpprev').onclick = () => go(hist[idx - 1]); $('mpnext').onclick = () => nextE && go(nextE);
  }

  // ---- split view: tree (final phase) | bytecode (genBCode), linked ----
  function renderSplit(v, selSet) {
    const D = R.ST.D, st = R.ST, bb = $('bcbody'), on = !!(D.hasIR && st.splitBc && !v.irPseudo);
    bb.hidden = !on; $('splitbc').closest('label').hidden = !D.hasIR; if (!on) return;
    const links = R.irLinks();
    // class: the one holding the selected node's ranges, else the current class
    let cls = null; for (const id of links.ir) { const c = D.irClassOf.get(id); if (c) { cls = c; break; } }
    if (cls && D.jvmClasses.get(cls).unit !== st.unit) cls = null; cls = cls || R.curClass(); st.cls = cls;
    const cv = cls && D.irClassVersion(cls, D.irGenPhase); if (!cv) { $('bctext').textContent = 'no class'; return; }
    const W = S().viewOf(cv), lk = new Set([...links.ir].filter((i) => cv.ids.has(i)));
    const ls = S().lineStarts(W.text), first = [...lk].map((i) => W.byId.get(i)).filter(Boolean).sort((a, b) => a[1] - b[1])[0];
    const fl = first ? S().lineOf(ls, first[1]) : 0, a = Math.max(0, fl - 40), b = Math.min(ls.length - 1, fl + 260), lo = ls[a], hi = b + 1 < ls.length ? ls[b + 1] : W.text.length;
    S().setLang('bc');
    const kc = (id) => { const n = D.nodes.get(id); return n ? 'k-' + n.kind + ' ' : ''; };
    $('bctext').classList.add('bc'); $('bctext').replaceChildren(S().lineify(S().build(W.text, W.offs, (id) => kc(id) + (lk.has(id) ? 'lnk ' : ''), lo, hi)));
    $('bcinfo').innerHTML = `<b>${esc(cls)}</b> at genBCode · lines ${a + 1}–${b + 1} of ${ls.length} <select id="bcclass" aria-label="class for the bytecode pane">${[...D.jvmClasses.values()].filter((c) => c.unit === st.unit).map((c) => `<option${c.name === cls ? ' selected' : ''}>${esc(c.name)}</option>`).join('')}</select> <span class="dim">dashed = generated from the selected tree node; hover links both ways</span>`;
    $('bcclass').onchange = (ev) => R.set({ cls: ev.target.value });
    const first_el = $('bctext').querySelector('.lnk'); if (first_el) { const body = bb, r = first_el.getBoundingClientRect(), br = body.getBoundingClientRect(); if (r.top < br.top || r.bottom > br.bottom) body.scrollTop += r.top - br.top - br.height / 3; }
  }

  // ---- wiring ----
  function wire() {
    $('splitbc').onchange = (ev) => {
      const st = R.ST; st.splitBc = ev.target.checked;
      if (st.splitBc && st.D) { const vs = R.vers(); let k = -1; vs.forEach((v, i) => { if (!v.irPseudo) k = i; }); if (k >= 0) { R.setPhase(k); return; } }   // the final tree phase
      R.render();
    };
    // hover linking across the two panes (and in IR mode, nothing extra: the scrubber shows the tooltip)
    const hov = (paneId, other) => {
      const pane = $(paneId); let last = null;
      pane.addEventListener('mousemove', (ev) => {
        if (!R.ST.D || !R.ST.D.hasIR || !R.ST.splitBc) return; const el = ev.target.closest && ev.target.closest('.n'); const id = el ? +el.dataset.n : null; if (id === last) return; last = id;
        document.querySelectorAll('.lnkh').forEach((x) => x.classList.remove('lnkh'));
        if (id == null) return; const D = R.ST.D; const ids = paneId === 'text' ? R.irFromTreeNode(id) : R.irToTreeNodes(id);
        ids.forEach((x) => document.querySelectorAll(`#${other} [data-n="${x}"]`).forEach((e2) => e2.classList.add('lnkh')));
        if (paneId === 'bctext') { /* tree side shows the tree nodes */ }
      });
      pane.addEventListener('mouseleave', () => { last = null; document.querySelectorAll('.lnkh').forEach((x) => x.classList.remove('lnkh')); });
    };
    hov('text', 'bctext'); hov('bctext', 'text');
    // bytecode pane: click selects (innermost), links in side panels
    $('bctext').addEventListener('click', (ev) => { const el = ev.target.closest('.n'); if (!el) return; let id = +el.dataset.n; if (ev.altKey) { const cv = R.ST.D.irClassVersion(R.curClass(), R.ST.D.irGenPhase); const p = cv && S().parentMap(cv).get(id); if (p != null) id = p; } R.set({ selNode: id, selSym: null }); });
    document.addEventListener('click', (ev) => {
      const t = ev.target.closest && ev.target.closest('.lk-tree'); if (t) { R.jumpToTree(+t.dataset.tn); return; }
      const i = ev.target.closest && ev.target.closest('.lk-ir'); if (i) { R.jumpToIR(+i.dataset.ir); return; }
      const sl = ev.target.closest && ev.target.closest('.lk-sel'); if (sl) R.set({ selNode: +sl.dataset.n });
    });
  }
  document.addEventListener('DOMContentLoaded', wire);
  // tree render hooks: the split pane follows the tree view
  R.renderSplitHook = (v, selSet) => renderSplit(v, selSet);
})(window.R);
