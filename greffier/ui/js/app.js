// Shared state (one cursor for all views), file loading, keyboard, wiring.
(function (R) {
  const ST = (R.ST = { D: null, unit: 0, verIdx: 0, evIdx: 0, selNode: null, selSym: null, follow: true, skip: false, collapse: false, showRetyped: false, level: 'file', gscope: 'ego', gkind: 'all', ghops: 1, byTime: true, syntax: true, structOnly: false, anim: true, metric: 'struct' });
  const views = (R.views = []);
  const $ = (id) => document.getElementById(id);

  const kidsHidden = () => !ST.showRetyped;
  // New node ids of vs[k] relative to vs[k-1]; with `collapse`, pure copies are not counted as new.
  R.newIds = (vs, k) => {
    if (k === 0) return new Set();
    const prev = vs[k - 1].ids, out = new Set();
    vs[k].ids.forEach((n) => { if (!prev.has(n) && !(ST.collapse && ST.D.isPureCopy(n))) out.add(n); });
    return out;
  };
  // Does version v (previous version in its unit: prev) add anything? v1 headers answer without decoding offsets.
  // number of nodes new in v relative to the unit's previous version (pure copies excluded with `collapse`)
  R.newCount = (v) => {
    const D = ST.D;
    if (v.irPseudo) return v.hdrNew;
    if (v.vi === 0) return v.hdrNodes ?? v.ids.size;
    if (D.schema === 'v1' && v.hdrNew != null) return ST.collapse ? (D.nonCopyNew.get(v.unit + ':' + v.phase) || 0) : v.hdrNew;
    const all = D.versionsOf(v.unit), i = all.indexOf(v), key = '_nc' + ST.collapse; if (v[key] != null) return v[key];
    if (i <= 0) return (v[key] = v.ids.size);
    const prev = all[i - 1].ids; let c = 0; v.ids.forEach((n) => { if (!prev.has(n) && !(ST.collapse && D.isPureCopy(n))) c++; }); return (v[key] = c);
  };
  R.hasNew = (v, prev) => {
    const D = ST.D;
    if (D.schema === 'v1' && v.hdrNew != null) return v.hdrNew > 0 && (!ST.collapse || (D.nonCopyNew.get(v.unit + ':' + v.phase) || 0) > 0);
    let any = false; v.ids.forEach((n) => { if (!any && !prev.ids.has(n) && !(ST.collapse && D.isPureCopy(n))) any = true; }); return any;
  };
  // Versions of the current unit; with `skip`, versions that add nothing are dropped. With a JVM extension an "IR segment"
  // follows: one pseudo version per JVM phase (the class text is chosen by the class selector, see ir.js).
  let vcache = null;
  R.irVers = () => {
    const D = ST.D; if (!D.hasIR) return [];
    const u = ST.unit, classes = [...D.jvmClasses.values()].filter((c) => c.unit === u); if (!classes.length) return [];
    const out = [];
    D.irPhaseIds.forEach((p, i) => { const n = classes.filter((c) => c.versions.some((v) => v.phase === p)).length; if (n) out.push({ ir: true, irPseudo: true, phase: p, unit: u, seq: D.irSeq && D.irSeq.has(p) ? D.irSeq.get(p) : (D.irGenSeq ?? 0) + 0.01 * (i + 1), ids: new Set(), offsets: [], byId: new Map(), text: '', hdrNew: n, hdrNodes: 0, vi: -1 }); });
    return out;
  };
  const treeVers = () => {
    const D = ST.D, all = D.versionsOf(ST.unit);
    if (!ST.skip) return all;
    return all.filter((v, i) => i === 0 || R.hasNew(v, all[i - 1]));
  };
  R.vers = () => {
    const D = ST.D, key = ST.unit + '|' + ST.collapse + '|' + ST.skip;
    if (vcache && vcache.D === D && vcache.key === key) return vcache.vs;
    const vs = treeVers(), out = D.hasIR ? vs.concat(R.irVers()) : vs;
    vcache = { D, key, vs: out }; return out;
  };
  R.treeCount = () => R.vers().filter((v) => !v.irPseudo).length;
  R.verName = (v) => (v.irPseudo ? (v.phase === ST.D.irGenPhase ? 'genBCode (bytecode)' : ST.D.phaseRaw(v.phase)) : ST.D.phaseName(v.phase));
  R.ver = () => { const vs = R.vers(); return vs[Math.min(ST.verIdx, vs.length - 1)]; };
  R.prevVer = () => { const vs = R.vers(); return ST.verIdx > 0 ? vs[ST.verIdx - 1] : null; };
  // Event list the cursor walks: Retyped events are hidden unless the toggle is on.
  let ecache = null;
  R.evl = () => {
    const D = ST.D; if (ecache && ecache.D === D && ecache.k === ST.showRetyped) return ecache.l;
    const l = ST.showRetyped ? D.evs : D.evs.filter((e) => e.kind !== 'Retyped'); ecache = { D, k: ST.showRetyped, l }; return l;
  };
  R.evIndexAtOrAfter = (seq) => { const l = R.evl(); let lo = 0, hi = l.length; while (lo < hi) { const m = (lo + hi) >> 1; if (l[m].seq < seq) lo = m + 1; else hi = m; } return Math.min(lo, l.length - 1); };
  // bands positioned on the (filtered) event list: indexes i0..i1 into evl()
  let bcache = null;
  R.bands = () => {
    const D = ST.D, l = R.evl(); if (bcache && bcache.l === l && bcache.D === D) return bcache.b;
    const b = D.bands.map((x) => ({ ...x, i0: R.evIndexAtOrAfter(x.begin), i1: R.evIndexAtOrAfter(x.end) })); bcache = { D, l, b }; return b;
  };
  R.curFile = () => { const e = R.ev(); return e && e.file != null ? e.file : 0; };
  R.curBand = () => { const i = ST.evIdx, f = R.curFile(); let best = null; R.bands().forEach((b) => { if (b.file === f && b.i0 <= i && i <= b.i1 && (!best || b.i1 - b.i0 < best.i1 - best.i0)) best = b; }); return best; };
  // Move the cursor to the begin or end of a phase group (in the current file), following phase/unit.
  R.gotoPhase = (g, where, ir) => { const f = R.curFile(), bs = ST.D.bands, b = bs.find((x) => x.phase === g && x.file === f && !!x.ir === !!ir) || bs.find((x) => x.phase === g && x.file === f && !x.ir); if (!b) return false; R.setEvBySeq(where === 'end' ? b.end : b.begin, true); return true; };
  R.stepPhase = (d) => {
    const cv = R.ver(), vv = R.vers();
    if (cv && cv.irPseudo) { const k = ST.verIdx + d; if (k >= 0 && vv[k] && (vv[k].irPseudo || d < 0)) R.setPhase(k); return true; }   // inside the IR segment: step the JVM phases
    const D = ST.D, f = R.curFile(), ph = new Set(R.vers().map((v) => v.phase)), l = D.bands.filter((b) => b.file === f && (!ST.skip || ph.has(b.phase))), q = R.seq();
    if (!l.length) return false;
    let c = -1; l.forEach((b, i) => { if (b.begin <= q) c = i; });
    // land on a phase's output (PhaseEnd). Inside a phase, "next" is that phase's own end.
    const atEnd = c >= 0 && q >= l[c].end, t = d > 0 ? (atEnd || c < 0 ? c + 1 : c) : c - 1;
    if (t >= l.length && D.hasIR && d > 0) { const k = vv.findIndex((v) => v.irPseudo); if (k >= 0) R.setPhase(k); return true; }   // past the last tree phase: enter the IR segment
    if (t < 0 || t >= l.length) return true;
    R.setEvBySeq(l[t].end, true); return true;
  };
  // to the end of the current phase (again: end of the next phase)
  R.phaseEnd = () => { const b = R.curBand(); if (!b) return; if (R.seq() < b.end) R.setEvBySeq(b.end, true); else { const f = R.curFile(), nx = ST.D.bands.find((x) => x.file === f && x.begin > b.end); if (nx) R.setEvBySeq(nx.end, true); } };
  // jump to the version of `phase` (its output) with node n selected; used by lineage links
  R.jumpTo = (phase, n) => {
    if (!R.vers().some((v) => v.phase === phase)) { ST.skip = false; $('skip').checked = false; }
    ST.follow = true; $('follow').checked = true;
    const nd = ST.D.nodes.get(n); R.set({ selNode: n, selSym: nd ? nd.sym : null });
    if (!(ST.D.bands.length && R.gotoPhase(phase, 'end'))) { const k = R.vers().findIndex((v) => v.phase === phase); if (k >= 0) R.set({ verIdx: k }); }
  };
  // the class shown in IR mode: the selected class if it belongs to the unit, else the unit's first class (non-mirror first)
  R.curClass = () => {
    const D = ST.D; if (!D.hasIR) return null;
    if (ST.cls) { const c = D.jvmClasses.get(ST.cls); if (c && c.unit === ST.unit) return ST.cls; }
    const cl = [...D.jvmClasses.values()].filter((c) => c.unit === ST.unit); const pick = cl.find((c) => /\$$/.test(c.name) && !/\$package\$/.test(c.name)) || cl.find((c) => !/\$package\$/.test(c.name)) || cl[0]; return pick ? pick.name : null;
  };
  // jump to a JVM node (class text at `phase`, default genBCode) with the node selected
  R.jumpToIR = (id, phase) => {
    const D = ST.D, cls = D.irClassOf.get(id), c = cls && D.jvmClasses.get(cls); if (!c) return;
    ST.unit = c.unit; ST.cls = cls; ST.follow = true; vcache = null;
    const vs = R.vers(), p = phase ?? D.irGenPhase; let k = vs.findIndex((v) => v.irPseudo && v.phase === p); if (k < 0) k = vs.findIndex((v) => v.irPseudo);
    R.set({ verIdx: Math.max(0, k), selNode: id, selSym: null });
  };
  // jump to the last tree version that holds (a descendant/ancestor of) tree node n, with n selected
  R.jumpToTree = (n) => {
    const D = ST.D, nd = D.nodes.get(n); if (!nd) return; if (nd.unit != null) ST.unit = nd.unit; vcache = null;
    const vs = R.vers(); let k = -1;
    for (let i = vs.length - 1; i >= 0; i--) if (!vs[i].irPseudo && vs[i].ids.has(n)) { k = i; break; }
    if (k < 0) for (let i = vs.length - 1; i >= 0; i--) if (!vs[i].irPseudo && R.present(n, vs[i]).size) { k = i; break; }
    R.set({ verIdx: Math.max(0, k), selNode: n, selSym: nd.sym });
  };
  R.ev = () => R.evl()[ST.evIdx] || null;
  R.seq = () => (R.ev() ? R.ev().seq : 0);
  R.firstNode = (e) => (e ? e.nodes.find((n) => n != null) ?? null : null);

  // Nodes of `ver` that stand for `n` (itself, its descendants through derive, else its ancestors).
  R.present = function (n, ver) {
    const D = ST.D;
    if (n == null || !ver || ver.irPseudo) return new Set();
    if (ver.ids.has(n)) return new Set([n]);
    let s = R.walk(D, n, 'fwd', (m) => ver.ids.has(m), ver.phase);
    if (!s.size) s = R.walk(D, n, 'bwd', (m) => ver.ids.has(m), ver.phase);
    return s;
  };

  R.render = function () {
    const t = [], t0 = performance.now();
    views.forEach((v) => { if (R.stackOpen && !v.light) { t.push(0); return; } const a = performance.now(); v(); t.push(Math.round(performance.now() - a)); });   // the stack overlay hides the viewer: only light views run
    R.lastRender = { total: Math.round(performance.now() - t0), parts: t }; if (R.scheduleHash) R.scheduleHash();
    const p = document.getElementById('perf'); if (p) p.textContent = `render ${R.lastRender.total} ms`;
  };
  R.set = function (patch) { Object.assign(ST, patch); R.render(); };

  // Version index (in the current version list) visible to a cursor at event e: the last version whose seq <= e.seq.
  function versionAt(e) {
    const vs = R.vers();
    if (vs.length && vs[0].seq != null) { let k = 0; vs.forEach((v, i) => { if (v.seq != null && v.seq <= e.seq) k = i; }); return k; }
    const k = vs.findIndex((v) => v.phase === e.phase); return k;   // v0: versions carry no seq
  }
  // Move the event cursor. followPhase: let the cursor drag the unit and the phase scrubber with it.
  R.setEv = function (i, followPhase) {
    const l = R.evl(); i = Math.max(0, Math.min(l.length - 1, i));
    const old = l[ST.evIdx], e = l[i], patch = { evIdx: i };
    if (followPhase && ST.follow && e) {
      const D = ST.D;
      const eu = e.unit != null ? e.unit : (D.files.length > 1 && !D.merged ? D.unitOfFile.get(e.file) : null);
      if (eu != null && eu !== ST.unit && D.versionsOf(eu).length) { patch.unit = eu; Object.assign(ST, { unit: eu }); }
      const v1 = D.versionsOf(ST.unit)[0] && D.versionsOf(ST.unit)[0].seq != null;
      if (v1 || !old || old.phase !== e.phase) { const k = versionAt(e); if (k >= 0) patch.verIdx = k; }
    }
    R.set(patch);
  };
  R.setEvBySeq = (seq, fp) => { const l = R.evl(), i = R.evIndexAtOrAfter(seq); if (l[i]) R.setEv(i, fp); };

  // Phase pick moves the event cursor to that version's seq (v1) or last event of the phase (v0).
  R.setPhase = function (k) {
    const D = ST.D, vs = R.vers(); k = Math.max(0, Math.min(vs.length - 1, k));
    if (ST.follow && vs[k] && D.bands.length && R.gotoPhase(vs[k].phase, 'end', !!vs[k].irPseudo)) return;   // a phase pick shows that phase's output (cursor at PhaseEnd); shift-click / the start button go to its start
    const patch = { verIdx: k };
    if (ST.follow && vs[k]) {
      if (vs[k].seq != null) patch.evIdx = R.evIndexAtOrAfter(vs[k].seq);
      else { let j = -1; R.evl().forEach((e, i) => { if (e.phase === vs[k].phase) j = i; }); if (j >= 0) patch.evIdx = j; }
    }
    R.set(patch);
  };

  // Jump every view to a symbol: first (visible) event that touches it, and that event's first node.
  R.jumpToSym = function (s) {
    const D = ST.D, es = (D.evBySym.get(s) || []).filter((e) => ST.showRetyped || e.kind !== 'Retyped');
    const patch = { selSym: s, selNode: null };
    if (es.length) { patch.selNode = R.firstNode(es[0]); R.set(patch); R.setEvBySeq(es[0].seq, true); return; }
    R.set(patch);
  };

  R.setData = function (D, label) {
    const vs = D.unitsWithVersions;
    ecache = null; vcache = null;
    Object.assign(ST, { D, unit: vs.length ? vs[0] : 0, verIdx: 0, evIdx: 0, selNode: null, selSym: null });
    { const mn = $('mergenote'); mn.hidden = !D.merged; if (D.merged) mn.textContent = `${D.files.length} recordings, merged by phase; not a joint compile`; }
    const mb = performance.memory ? ` · heap ${Math.round(performance.memory.usedJSHeapSize / 1048576)} MB` : '';
    $('recname').textContent = label.replace(/^data(-jvm)?\//, '').replace(/ \(\d+ ms.*$/, ''); $('loadinfo').textContent = label + ' · ' + D.schema + ' · ' + Object.entries(D.counts).map(([k, v]) => v + ' ' + k).join(', ') + mb;
    const w = $('warnings'); w.innerHTML = '';
    const items = D.warnings.slice(); Object.entries(D.unknown).forEach(([k, v]) => items.push(`unknown record kind "${k}" ×${v} (ignored)`));
    w.hidden = !items.length; if (items.length) { w.append(Object.assign(document.createElement('b'), { textContent: items.length + ' load warning(s): ' })); w.append(items.slice(0, 6).join(' · ') + (items.length > 6 ? ' …' : '')); w.title = items.join('\n'); }
    const us = $('unit'); us.innerHTML = '';
    vs.forEach((u) => { const pth = (D.units.get(u) || {}).path || 'unit ' + u, o = new Option(pth.replace(/^.*\//, ''), u); o.title = pth; us.append(o); });
    ST.byTime = !!D.hasTime; $('bytime').checked = ST.byTime; $('bytime').disabled = !D.hasTime; $('bytime').title = D.hasTime ? 'phase bands proportional to wall time' : 'this recording has no timestamps (PhaseBegin/PhaseEnd without ts): bands are proportional to event count';
    document.querySelector('main').classList.toggle('single', !D.evs.length && !D.edges.length);   // scrubber only, when the file has no ev/edge records
    R.initViews && R.initViews(); ST.gsel = null; ST.gselFor = null; ST.multi = new Set(); ST.multiOn = false; $('level').value = ST.level; $('gscope').value = ST.gscope;
    R.render();
  };

  // #src=…&unit=…&pname=…&seq=…&node=…&sym=…&level=…&skip=1&collapse=1&retyped=1: deep link both ways (also `phase=<index>`)
  R.applyHash = function () {
    const h = new URLSearchParams(location.hash.slice(1)), D = ST.D, patch = {};
    const flag = (k, f) => { if (h.has(k)) { ST[f] = h.get(k) === '1'; $(k).checked = ST[f]; } };
    flag('skip', 'skip'); flag('collapse', 'collapse'); flag('retyped', 'showRetyped'); flag('structonly', 'structOnly');
    if (h.has('level')) { ST.level = h.get('level'); $('level').value = ST.level; R.initViews && R.initViews(); }
    if (h.has('unit')) { const u = D.unitsWithVersions.find((x) => ((D.units.get(x) || {}).path || '').endsWith(h.get('unit'))); if (u != null) ST.unit = u; }
    // the cursor first: it decides unit and displayed version (follow), then an explicit pname/phase overrides the version
    if (h.has('seq')) { const i = R.evIndexAtOrAfter(+h.get('seq')); ST.evIdx = i; const e = R.evl()[i]; if (e) { const eu = e.unit != null ? e.unit : (D.files.length > 1 && !D.merged ? D.unitOfFile.get(e.file) : null); if (eu != null && !h.has('unit') && D.versionsOf(eu).length) ST.unit = eu; const k = versionAt(e); if (k >= 0) patch.verIdx = k; } }
    if (h.has('cls')) { ST.cls = h.get('cls'); const c = D.hasIR && D.jvmClasses.get(ST.cls); if (c && !h.has('unit')) ST.unit = c.unit; vcache = null; }
    if (h.has('phase')) patch.verIdx = +h.get('phase');
    if (h.has('pname')) {
      const wantIR = h.get('ir') === '1', k = R.vers().findIndex((v) => (D.phaseRaw(v.phase) === h.get('pname') || D.phaseName(v.phase) === h.get('pname')) && !!v.irPseudo === wantIR);
      if (k >= 0) { patch.verIdx = k; if (!h.has('seq')) { const v = R.vers()[k]; if (v.seq != null) patch.evIdx = R.evIndexAtOrAfter(v.seq); } }   // pname alone lands on that phase's output
    } else if (h.has('phase') && !h.has('seq')) { const v = R.vers()[patch.verIdx]; if (v && v.seq != null) patch.evIdx = R.evIndexAtOrAfter(v.seq); }
    if (h.has('node')) patch.selNode = +h.get('node');
    if (h.has('gran')) { patch.gran = h.get('gran'); $('gran').value = patch.gran; } if (h.has('chn')) { patch.chThrNodes = +h.get('chn'); $('chnodes').value = h.get('chn'); } if (h.has('chmode')) { patch.chMode = h.get('chmode'); $('chmode').value = patch.chMode; } if (h.has('chm')) { patch.chMetric = h.get('chm'); $('chmetric').value = patch.chMetric; } if (h.has('chp')) { patch.chThrPct = +h.get('chp'); $('chpct').value = h.get('chp'); }
    if (h.has('range')) {   // range=<phase>:<lo>-<hi> (1-based lines of that phase's tree text, in the selected unit)
      const m = /^(.+):(\d+)-(\d+)$/.exec(h.get('range')), v = m && R.stk && D.versionsOf(ST.unit).find((x) => !x.ir && D.phaseRaw(x.phase) === m[1]);
      if (v) patch.range = { unit: ST.unit, phase: v.phase, lo: +m[2] - 1, hi: +m[3] - 1, nodes: R.stk.nodesInLines(v, +m[2] - 1, +m[3] - 1), ir: false, cls: null };
    }
    if (h.get('stack') === '1') setTimeout(() => R.openStack && R.openStack(), 0);
    if (h.has('sym')) patch.selSym = +h.get('sym');
    R.set(patch);
  };
  // current state as hash parameters (used for the address bar, `copy link` and tours)
  R.buildHash = function () {
    const D = ST.D, p = new URLSearchParams();
    if (R.srcUrls) p.set('src', R.srcUrls.join(','));
    const u = D.units.get(ST.unit); if (u && D.unitsWithVersions.length > 1) p.set('unit', (u.path || '').replace(/^.*\//, ''));
    const v = R.ver(); if (v) { p.set('pname', D.phaseRaw(v.phase)); if (v.irPseudo) { p.set('ir', 1); p.set('cls', R.curClass()); } } if (R.ev()) p.set('seq', R.seq());
    if (ST.selNode != null) p.set('node', ST.selNode); else if (ST.selSym != null) p.set('sym', ST.selSym);
    if (ST.level !== 'file') p.set('level', ST.level);
    if (R.stackOpen) p.set('stack', 1);
    if (ST.gran && ST.gran !== 'chapters') p.set('gran', ST.gran); if (ST.chThrNodes != null && ST.chThrNodes !== 6) p.set('chn', ST.chThrNodes); if (ST.chThrPct != null && ST.chThrPct !== 4) p.set('chp', ST.chThrPct); if (ST.chMetric && ST.chMetric !== 'lines') p.set('chm', ST.chMetric); if (ST.chMode && ST.chMode !== 'groups') p.set('chmode', ST.chMode);
    if (ST.range && !ST.range.ir && ST.range.unit === ST.unit) p.set('range', D.phaseRaw(ST.range.phase) + ':' + (ST.range.lo + 1) + '-' + (ST.range.hi + 1));
    if (ST.skip) p.set('skip', 1); if (ST.collapse) p.set('collapse', 1); if (ST.showRetyped) p.set('retyped', 1);
    return p.toString().replace(/%2C/g, ',').replace(/%2F/g, '/').replace(/%3A/g, ':').replace(/%24/g, '$');
  };
  let hashTimer = null; R.scheduleHash = () => scheduleHash();
  function scheduleHash() {
    if (!R.hashApplied || !ST.D || !(R.srcUrls || R.isFixture)) return;
    clearTimeout(hashTimer); hashTimer = setTimeout(() => { try { history.replaceState(null, '', '#' + R.buildHash()); } catch (e) { /* file:// or sandbox */ } }, 250);
  }

  // ---- loading ----------------------------------------------------------------------------------------------------------------
  // sources: [{name, size?, open: () => Promise<ReadableStream>}]
  async function loadSources(sources, label) {
    const t0 = performance.now(), L = R.createLoader({ lazy: sources.length === 1 });
    R.srcUrls = sources.every((x) => x.url) ? sources.map((x) => x.url) : null; R.isFixture = sources.length === 1 && /^fixture/.test(sources[0].name); R.hashApplied = false;
    let bytes = 0;
    try {
      for (const src of sources) {
        const s = await src.open();
        await R.streamInto(L, src.name, s, (n, got, tot) => { $('loadinfo').textContent = `loading ${n}: ${(got / 1048576).toFixed(1)} MB${tot ? ' / ' + (tot / 1048576).toFixed(1) + ' MB' : ''}…`; }, src.size);
        bytes += src.size || 0;
      }
      const tp = performance.now();
      const D = L.finish();
      const ms = Math.round(performance.now() - t0);
      R.loadStats = { ms, indexMs: Math.round(performance.now() - tp), files: sources.length };
      R.setData(D, `${label} (${ms} ms, index ${R.loadStats.indexMs} ms)`);
      if (sources[0].hash) R.applyHash();
      R.hashApplied = true; R.scheduleHash();
    } catch (e) { $('loadinfo').textContent = 'load failed: ' + e.message; console.error(e); }
  }
  const textSource = (name, text) => ({ name, size: text.length, open: async () => text });
  const urlSource = (url) => ({ name: url, url, hash: true, open: async () => { const r = await fetch(url); if (!r.ok) throw new Error(url + ': HTTP ' + r.status); return (+r.headers.get('content-length') || 0) < 8e6 ? r.text() : r.body; } });
  async function urlSources(urls) {
    const out = [];
    for (const u of urls) { const r = await fetch(u, { method: 'HEAD' }).catch(() => null); if (!r || !r.ok) throw new Error('cannot fetch ' + u); out.push(Object.assign(urlSource(u), { size: +r.headers.get('content-length') || 0 })); }
    return out;
  }

  // index in the version list that the cursor's phase stands for (last version with phase <= cursor phase)
  function phaseSel() {
    const vs = R.vers(), e = R.ev();
    if (vs[ST.verIdx] && vs[ST.verIdx].irPseudo) return ST.verIdx;
    if (!(ST.follow && e && ST.D.bands.length)) return ST.verIdx;
    let k = 0; vs.forEach((v, i) => { if (!v.irPseudo && v.phase <= e.phase) k = i; }); return k;
  }
  // ---- the event axis: a phase-equalised, piecewise-linear scale ----
  // Every phase band gets at least MINPX (capped so that all minimums together take at most 60% of the axis); the rest is shared
  // by visible event count (or time). The slider position and the band track use the same mapping; the cursor maps linearly
  // inside a band. Zoom: one band spans the whole axis, ticks are single events.
  const MINPX = 6;
  R.zoomBand = () => { if (!ST.zoomKey) return null; return R.bands().find((b) => b.file + ':' + b.phase === ST.zoomKey) || null; };
  R.axis = (W) => {
    const D = ST.D, l = R.evl(), zb = R.zoomBand(), byTime = !!(D.hasTime && ST.byTime);
    const key = [D, l, zb && zb.i0, W, byTime].map(String).join('|'); if (R._axis && R._axis.key === key) return R._axis;
    const n = Math.max(1, l.length - 1); let segs;
    if (zb) segs = [{ b: zb, a0: 0, a1: 1 }];
    else {
      const bs = R.bands().slice().sort((x, y) => x.i0 - y.i0), tot = bs.reduce((t, b) => t + (byTime ? b.x1 - b.x0 : Math.max(0, b.i1 - b.i0)), 0) || 1;
      const minF = Math.min(MINPX / W, 0.6 / Math.max(1, bs.length)), rest = 1 - bs.length * minF; let a = 0; segs = [];
      bs.forEach((b) => { const w = minF + rest * (byTime ? b.x1 - b.x0 : Math.max(0, b.i1 - b.i0)) / tot; segs.push({ b, a0: a, a1: a + w }); a += w; });
      if (segs.length) segs[segs.length - 1].a1 = 1;
    }
    const toPos = (i) => {
      if (!segs.length) return i / n;
      if (zb) return zb.i1 > zb.i0 ? Math.max(0, Math.min(1, (i - zb.i0) / (zb.i1 - zb.i0))) : 0;
      let sg = segs.find((g) => g.b.i0 <= i && i <= g.b.i1); if (!sg) sg = segs.reduce((best, g) => (Math.abs(g.b.i0 - i) < Math.abs(best.b.i0 - i) ? g : best), segs[0]);
      return sg.a0 + (sg.b.i1 > sg.b.i0 ? Math.max(0, Math.min(1, (i - sg.b.i0) / (sg.b.i1 - sg.b.i0))) : 0) * (sg.a1 - sg.a0);
    };
    const toIdx = (p) => {
      if (!segs.length) return Math.round(p * n);
      const sg = segs.find((g) => p >= g.a0 && p <= g.a1) || segs[segs.length - 1], u = sg.a1 > sg.a0 ? (p - sg.a0) / (sg.a1 - sg.a0) : 0;
      return Math.round(sg.b.i0 + u * (sg.b.i1 - sg.b.i0));
    };
    return (R._axis = { key, segs, zb, toPos, toIdx, n });
  };
  const KCOL = { Typed: '#1c7ed6', Retyped: '#868e96', Adapt: '#0c8599', ImplicitSearch: '#7048e8', Candidate: '#9c36b5', Complete: '#e8590c', Report: '#c92a2a', Resolve: '#2f9e44' };
  R.zoomTo = (b) => {
    if (!b) return; const D = ST.D;
    if (!ST.zoomKey) ST._retypedBefore = ST.showRetyped;
    ST.zoomKey = b.file + ':' + b.phase;
    // lowering phases are mostly Retyped events: show them while zoomed into such a band
    if (!ST.showRetyped && D.evs.some((e) => e.phase === b.phase && e.file === b.file && e.kind === 'Retyped')) { ST.showRetyped = true; $('retyped').checked = true; }
    const seq = (R.ev() || {}).seq ?? b.begin; ST.evIdx = R.evIndexAtOrAfter(seq);
    const nb = R.zoomBand(); if (nb && !(ST.evIdx >= nb.i0 && ST.evIdx <= nb.i1)) ST.evIdx = nb.i0;
    R.render();
  };
  R.zoomOut = () => {
    if (!ST.zoomKey) return false; const seq = R.seq();
    ST.zoomKey = null; if (ST._retypedBefore === false && ST.showRetyped) { ST.showRetyped = false; $('retyped').checked = false; } ST._retypedBefore = undefined;
    ST.evIdx = R.evIndexAtOrAfter(seq); R.render(); return true;
  };
  // arrow stepping: a step into a band without visible events steps by version instead (the keyboard never sticks in empty phases)
  R.stepEvent = (d) => {
    const l = R.evl(), i = Math.max(0, Math.min(l.length - 1, ST.evIdx + d)), f = R.curFile();
    if (Math.abs(d) === 1 && ST.D.bands.length && !ST.zoomKey) {
      const cur = R.curBand(), tb = R.bands().find((b) => b.file === f && b.i0 <= i && i <= b.i1);
      if (tb && tb !== cur && tb.i1 - tb.i0 <= 1 && R.stepPhase(d)) return;
    }
    R.setEv(i, true);
  };
  function renderBands() {
    const D = ST.D, box = $('bands'), l = R.evl();
    box.hidden = !D.bands.length; $('bands2').hidden = true; $('zoombar').hidden = true; $('zoom').hidden = !D.bands.length; if (!D.bands.length) return;
    let cur = R.curBand();
    if (ST.zoomKey && cur && cur.file + ':' + cur.phase !== ST.zoomKey) { ST.zoomKey = cur.file + ':' + cur.phase; }   // the cursor left the zoomed band: follow it
    const W = box.clientWidth || 1000, f = R.curFile(), byTime = !!(D.hasTime && ST.byTime), ax = R.axis(W), zb = ax.zb;
    const key = ax.key;
    if (box._key !== key || box._D !== D || box._W !== W) {
      box._key = key; box._D = D; box._W = W; box.innerHTML = '';
      ax.segs.forEach(({ b, a0, a1 }, i) => {
        const el = document.createElement('div'), w = Math.max(2, (a1 - a0) * W);
        el.className = 'band' + (i % 2 ? ' odd' : '') + (b.ir ? ' jvm' : ''); el.style.left = (a0 * 100) + '%'; el.style.width = w + 'px'; el._b = b;
        el.title = `${D.phaseName(b.phase)} · seq ${b.begin}–${b.end} · ${Math.max(0, b.i1 - b.i0 - 1)} visible events` + (D.hasTime ? ` · ${(b.ms / (D.timeUnit || 1)).toFixed(1)} ${D.timeUnitName || 'units'}` : '') + ' · click: output, shift-click: start, double-click: zoom';
        if (w >= 56) el.textContent = zb ? D.phaseName(b.phase) : D.phaseShort(b.phase);
        el.onclick = (ev) => { ST.follow = true; $('follow').checked = true; const w2 = ev.shiftKey ? 'begin' : 'end'; if (ST.zoomKey) { /* zoomed: a click puts the cursor at that x */ const r = el.getBoundingClientRect(); R.setEv(ax.toIdx(Math.max(0, Math.min(1, (ev.clientX - r.left) / r.width))), true); return; } R.gotoPhase(b.phase, w2, !!b.ir) || R.setEvBySeq(w2 === 'end' ? b.end : b.begin, true); };
        el.ondblclick = () => { if (!ST.zoomKey && !b.ir) R.zoomTo(b); };
        box.append(el);
      });
      if (zb) {   // zoomed: one tick per visible event, coloured by kind (capped)
        const span = Math.max(1, zb.i1 - zb.i0), stride = Math.max(1, Math.ceil(span / 900)); let k = 0;   // sampled when the band has more than ~900 events
        for (let i = zb.i0; i <= zb.i1 && k < 1000; i += stride) { const e = l[i]; if (!e || e.kind === 'PhaseBegin' || e.kind === 'PhaseEnd') continue; const t = document.createElement('div'); t.className = 'etick'; t.style.left = ((i - zb.i0) / span * 100) + '%'; t.style.background = KCOL[e.kind] || '#868e96'; if (R.isProblem && R.isProblem(e)) t.style.height = '100%'; t.title = `${e.kind} @ seq ${e.seq}`; box.append(t); k++; }
      } else if (R.isProblem) {   // problems (Reports red, failed searches orange) on the global scale
        let k = 0; D.evs.forEach((e) => { if (k < 800 && R.isProblem(e) && (ST.showRetyped || e.kind !== 'Retyped')) { const t = document.createElement('div'); t.className = 'tick ' + (e.kind === 'Report' ? 'rep' : 'fail'); t.style.left = (ax.toPos(R.evIndexAtOrAfter(e.seq)) * 100) + '%'; t.title = `${e.kind} @ seq ${e.seq}`; box.append(t); k++; } });
      }
      const m = document.createElement('div'); m.id = 'bandcursor'; box.append(m);
    }
    // equal-width index strip: every phase, also the minimap while zoomed
    const b2 = $('bands2'); b2.hidden = false;
    const fb = R.bands().filter((b) => b.file === f);
    if (b2._key !== D.bands.length + '|' + String(l) || b2._D !== D || b2._f !== f) {
      b2._key = D.bands.length + '|' + String(l); b2._D = D; b2._f = f; b2.innerHTML = '';
      fb.forEach((b, i) => { if (b.ir && !b2.querySelector('.sep')) { b2.append(Object.assign(document.createElement('div'), { className: 'sep' })); } const el = document.createElement('div'); el.className = 'cell' + (i % 2 ? ' odd' : '') + (b.ir ? ' jvm' : ''); el._b = b; el.title = `${D.phaseName(b.phase)} · seq ${b.begin}–${b.end} (click: output, shift-click: start)`; el.onclick = (ev) => { ST.follow = true; $('follow').checked = true; if (ST.zoomKey && !b.ir) R.zoomTo(b); R.gotoPhase(b.phase, ev.shiftKey ? 'begin' : 'end', !!b.ir); }; b2.append(el); });
      if (b2.querySelector('.sep')) { const cap = document.createElement('div'); cap.className = 'jvmcap'; cap.textContent = 'JVM'; b2.append(cap); cap._pos = true; }
    }
    cur = R.curBand();
    { const vs = R.vers(), mx = Math.max(1, ...vs.map((v) => R.metricVal(R.scoreOf(v)))), top = new Set(R.interesting ? R.interesting().map((x) => x.i) : []);
      [...b2.children].forEach((el) => { if (!el._b || !el._b.ir) return; const i = vs.findIndex((v) => v.irPseudo && v.phase === el._b.phase), v = vs[i], hv = v ? R.metricVal(R.scoreOf(v)) : 0; el.style.background = i === ST.verIdx ? '' : `rgba(112,72,232,${(0.18 + 0.82 * Math.sqrt(hv / mx)).toFixed(2)})`; el.classList.toggle('star', top.has(i)); });
      const cap = b2.querySelector('.jvmcap'), sp = b2.querySelector('.sep'); if (cap && sp) cap.style.left = (sp.offsetLeft + 2) + 'px'; }
    [...b2.children].forEach((el) => el._b && el.classList.toggle('cur', el._b === cur));
    const topPh = new Set(R.interesting ? R.interesting().map((x) => x.v.phase) : []);
    [...box.children].forEach((el) => { if (el._b) { el.classList.toggle('cur', el._b === cur && !zb); el.classList.toggle('star', topPh.has(el._b.phase)); } });
    box.classList.toggle('zoomed', !!zb);
    const zbar = $('zoombar'); zbar.hidden = !zb;
    if (zb) { zbar.innerHTML = `<a id="zout">all phases</a> › <b>${D.phaseName(zb.phase)}</b> <span class="dim">· ${Math.max(0, zb.i1 - zb.i0 - 1)} visible events · seq ${zb.begin}–${zb.end} · Esc zooms out · the strip below is the minimap</span>`; $('zout').onclick = () => R.zoomOut(); }
    $('zoom').textContent = zb ? '⤡ zoom out' : '⤢ zoom phase'; $('zoom').title = zb ? 'back to all phases (Esc)' : 'zoom into the current phase band (or double-click a band)';
    // cursor marker
    const mk = $('bandcursor'); if (mk) mk.style.left = ax.toPos(ST.evIdx) * 100 + '%';
    const inN = cur ? Math.max(0, cur.i1 - cur.i0 - 1) : 0;
    $('bandlabel').textContent = (cur ? `phase ${D.phaseShort(cur.phase)}${inN ? ` · ${Math.min(inN, Math.max(0, ST.evIdx - cur.i0))}/${inN} events in` : ''}` : '') + (byTime ? ' · by time' : D.hasTime ? ' · by event count' : '');
  }
  function renderControls() {
    const D = ST.D, e = R.ev(), vs = R.vers(), l = R.evl();
    { const W = $('bands').clientWidth || 1000, ax = D.bands.length ? R.axis(W) : null; $('evslider').max = 10000; $('evslider').value = ax ? Math.round(ax.toPos(ST.evIdx) * 10000) : Math.round(ST.evIdx / Math.max(1, l.length - 1) * 10000); }
    $('evlabel').textContent = e ? `seq ${e.seq}/${D.evs[D.evs.length - 1].seq} · ${e.kind}` : 'no events';
    renderBands();
    const ps = phaseSel();
    $('phslider').max = Math.max(0, vs.length - 1); $('phslider').value = ps;
    if ($('unit').value !== String(ST.unit)) $('unit').value = ST.unit;
    const cs = $('clsel'); cs.hidden = $('clslabel').hidden = !D.hasIR;
    if (D.hasIR) { if (cs._D !== D) { cs._D = D; cs.innerHTML = ''; const byU = new Map(); D.jvmClasses.forEach((c) => { if (!byU.has(c.unit)) byU.set(c.unit, []); byU.get(c.unit).push(c); }); byU.forEach((cl, u) => { const g = document.createElement('optgroup'); g.label = ((D.units.get(u) || {}).path || 'unit ' + u).replace(/^.*\//, ''); cl.forEach((c) => g.append(Object.assign(new Option(c.name, c.name), { title: c.name }))); cs.append(g); }); } cs.value = R.curClass() || ''; }
    const box = $('phases'); box.innerHTML = '';
    if (vs.length > 12 || D.hasIR) {
      const sel = document.createElement('select'); sel.title = 'phase (picking one shows its output; ⇤ start moves to its beginning)'; sel.setAttribute('aria-label', 'phase');
      const allSc = R.versionScores ? R.versionScores() : []; const tg = document.createElement('optgroup'); tg.label = 'tree phases'; sel.append(tg); let ig = null;
      vs.forEach((v, k) => { if (v.irPseudo && !ig) { ig = document.createElement('optgroup'); ig.label = 'IR · JVM bytecode'; sel.append(ig); } (v.irPseudo ? ig : tg).append(new Option(`${k + 1}/${vs.length} ${R.verName(v)} ${k > 0 && R.dots ? R.dots(v, allSc) : ''} (${R.newCount(v)} ${v.irPseudo ? 'classes' : 'new'})`, k)); });
      sel.value = ps; sel.onchange = () => R.setPhase(+sel.value); box.append(sel); return;
    }
    vs.forEach((v, k) => { const b = document.createElement('button'); b.textContent = `${D.phaseName(v.phase)} (${R.newCount(v)})`; b.title = `${R.newCount(v)} new nodes`; b.className = k === ps ? 'on' : ''; b.onclick = () => R.setPhase(k); box.append(b); });
  }
  renderControls.light = true; views.push(renderControls);

  document.addEventListener('DOMContentLoaded', () => {
    $('file').onchange = (ev) => { addLocal([...ev.target.files]); ev.target.value = ''; };
    $('dir').onchange = (ev) => { addLocal([...ev.target.files].filter((f) => /\.(jsonl|ndjson)$/.test(f.name))); ev.target.value = ''; };
    $('phstart').onclick = () => { const vs = R.vers(), v = vs[phaseSel()]; if (v) { ST.follow = true; $('follow').checked = true; R.gotoPhase(v.phase, 'begin'); } };
    document.querySelectorAll('input,select,button').forEach((el) => { if (!el.getAttribute('aria-label') && el.tagName !== 'BUTTON') { const l = el.closest('label'); el.setAttribute('aria-label', ((l && l.textContent) || el.title || el.id).trim().replace(/\s+/g, ' ')); } });
    // layout: resizable splits (persisted) and per-view full-height mode
    const main = document.querySelector('main'); try { const L = JSON.parse(localStorage.getItem('replay-layout') || '{}'); if (L.cl) main.style.setProperty('--cl', L.cl); if (L.rt) main.style.setProperty('--rt', L.rt); } catch (e) { /* no storage */ }
    const drag = (id, axis) => { const h = $(id); h.onmousedown = (ev) => { ev.preventDefault(); h.classList.add('drag'); const r = main.getBoundingClientRect(); const mv = (e) => { if (axis === 'x') main.style.setProperty('--cl', Math.max(15, Math.min(85, ((e.clientX - r.left) / r.width) * 100)).toFixed(1)); else main.style.setProperty('--rt', Math.max(12, Math.min(88, ((e.clientY - r.top) / r.height) * 100)).toFixed(1)); }; const up = () => { h.classList.remove('drag'); document.removeEventListener('mousemove', mv); document.removeEventListener('mouseup', up); try { localStorage.setItem('replay-layout', JSON.stringify({ cl: main.style.getPropertyValue('--cl'), rt: main.style.getPropertyValue('--rt') })); } catch (e) { /* ignore */ } R.render(); }; document.addEventListener('mousemove', mv); document.addEventListener('mouseup', up); }; };
    drag('hs', 'x'); drag('vs', 'y');
    R.setView = (v) => { if (v === 'stack') { R.openStack && R.openStack(); return; } main.classList.remove('max-scrub', 'max-insp', 'max-proj'); if (v !== 'all') main.classList.add('max-' + v); document.querySelectorAll('#viewtabs button').forEach((b) => b.classList.toggle('on', b.dataset.v === v)); if (ST.D) R.render(); };
    document.querySelectorAll('#viewtabs button').forEach((b) => (b.onclick = () => R.setView(b.dataset.v))); R.setView('all');
    $('btn-recs').onclick = () => openRecs(); $('btn-recs2').onclick = () => { $('opts').hidden = true; openRecs(); };
    $('btn-opts').onclick = (ev) => { ev.stopPropagation(); $('opts').hidden = !$('opts').hidden; };
    document.addEventListener('mousedown', (ev) => { const o = $('opts'); if (!o.hidden && !o.contains(ev.target) && ev.target !== $('btn-opts')) o.hidden = true; });
    document.addEventListener('mousedown', (ev) => { const t = $('phtab'); if (!t.hidden && !t.contains(ev.target) && !ev.target.closest('#btn-phtab')) t.hidden = true; }); $('btn-help').onclick = () => { $('help').hidden = false; }; $('btn-about').onclick = openAbout; $('btn-link').onclick = copyLink;
    document.querySelectorAll('[data-close]').forEach((b) => (b.onclick = () => { $(b.dataset.close).hidden = true; }));
    document.querySelectorAll('.modal').forEach((m) => m.addEventListener('mousedown', (ev) => { if (ev.target === m && ST.D) m.hidden = true; }));
    // drag and drop anywhere
    ['dragenter', 'dragover'].forEach((t) => document.addEventListener(t, (ev) => { ev.preventDefault(); document.body.classList.add('dragging'); if ($('recs').hidden) openRecs(); }));
    document.addEventListener('dragleave', (ev) => { if (!ev.relatedTarget) document.body.classList.remove('dragging'); });
    document.addEventListener('drop', (ev) => { ev.preventDefault(); document.body.classList.remove('dragging'); const fs = [...ev.dataTransfer.files].filter((f) => /\.(jsonl|ndjson|json|txt)$/.test(f.name)); if (fs.length) loadLocal(fs); });
    $('rec-load').onclick = () => { const sel = RECS.filter((r) => r.checked); if (sel.length) loadEntries(sel); };
    $('cheat').innerHTML = $('cheat2').innerHTML = CHEAT;
    $('replay').onclick = () => R.replay(); $('animon').onchange = (ev) => { ST.anim = ev.target.checked; }; $('animspeed').title = 'animation speed: 1× or 0.25× for teaching';
    $('structonly').onchange = (ev) => R.set({ structOnly: ev.target.checked });
    $('syntax').onchange = (ev) => R.set({ syntax: ev.target.checked }); $('bykind').onchange = (ev) => { $('text').classList.toggle('bykind', ev.target.checked); };
    $('evslider').oninput = (ev) => { const p = +ev.target.value / 10000, W = $('bands').clientWidth || 1000, l = R.evl(); R.setEv(ST.D.bands.length ? R.axis(W).toIdx(p) : Math.round(p * Math.max(1, l.length - 1)), true); };
    $('zoom').onclick = () => { if (ST.zoomKey) R.zoomOut(); else R.zoomTo(R.curBand()); };
    $('phslider').oninput = (ev) => R.setPhase(+ev.target.value);
    document.addEventListener('change', (ev) => { if (ev.target.tagName === 'SELECT') ev.target.blur(); });
    $('unit').onchange = (ev) => R.set({ unit: +ev.target.value, verIdx: 0, cls: null });
    $('clsel').onchange = (ev) => { const D = ST.D, c = D.jvmClasses.get(ev.target.value); if (!c) return; const wasIR = (R.ver() || {}).irPseudo, ph = (R.ver() || {}).phase; ST.unit = c.unit; ST.cls = c.name; vcache = null; const vs = R.vers(); let k = vs.findIndex((v) => v.phase === ph && !!v.irPseudo === !!wasIR); if (k < 0) k = wasIR ? vs.findIndex((v) => v.irPseudo) : 0; R.set({ verIdx: Math.max(0, k) }); };
    $('skip').onchange = (ev) => R.set({ skip: ev.target.checked, verIdx: 0 });
    $('collapse').onchange = (ev) => R.set({ collapse: ev.target.checked, verIdx: 0 });
    $('retyped').onchange = (ev) => { const seq = R.seq(); ST.showRetyped = ev.target.checked; R.set({ evIdx: R.evIndexAtOrAfter(seq) }); };
    $('bytime').onchange = (ev) => R.set({ byTime: ev.target.checked });
    $('gran').onchange = (ev) => R.set({ gran: ev.target.value }); $('chnodes').onchange = (ev) => R.set({ chThrNodes: Math.max(1, +ev.target.value || 8) }); $('chpct').onchange = (ev) => R.set({ chThrPct: Math.max(0, +ev.target.value || 0) }); $('chmetric').onchange = (ev) => R.set({ chMetric: ev.target.value }); $('chmode').onchange = (ev) => R.set({ chMode: ev.target.value });
    $('follow').onchange = (ev) => { ST.follow = ev.target.checked; };
    // two-finger horizontal swipe over the scrubber / explorer turns pages; when the scrubber text overflows horizontally the gesture scrolls it unless Shift is held
    { let acc = 0, last = 0, lock = 0;
      const swipe = (e) => {
        if (R.stackOpen || Math.abs(e.deltaX) <= Math.abs(e.deltaY)) return;
        const body = $('text') && $('text').parentNode; if (!e.shiftKey && body && body.scrollWidth > body.clientWidth + 2 && e.currentTarget === $('scrub')) return;
        e.preventDefault(); const now = performance.now(); if (now - lock < 450) { lock = now - 300; return; }
        acc = (now - last > 200 ? 0 : acc) + e.deltaX; last = now; if (Math.abs(acc) > 60) { const d = acc > 0 ? 1 : -1; acc = 0; lock = now; R.pageTurn(d); }
      };
      ['scrub', 'explorer'].forEach((id) => { const el = $(id); if (el) el.addEventListener('wheel', swipe, { passive: false }); }); }
    document.addEventListener('keydown', (ev) => {
      if (R.stackOpen) { if (ev.key === 'Escape') { R.stackEsc(); ev.preventDefault(); } else if (ev.key === 's' && !/^(INPUT|TEXTAREA|SELECT)$/.test(ev.target.tagName)) { R.closeStack(); ev.preventDefault(); } else if (ev.key === '?') { $('help').hidden = !$('help').hidden; } return; }
      if (ev.key === 's' && !ev.metaKey && !ev.ctrlKey && ST.D && !/^(INPUT|TEXTAREA|SELECT)$/.test(ev.target.tagName) && $('recs').hidden) { R.openStack && R.openStack(); ev.preventDefault(); return; }
      if (ev.key === 'Escape' && !$('opts').hidden) { $('opts').hidden = true; ev.preventDefault(); return; }
      if (ev.key === 'Escape' && !$('phtab').hidden) { $('phtab').hidden = true; ev.preventDefault(); return; }
      if (ev.key === '?' && (ev.target.tagName === 'TEXTAREA' || (ev.target.tagName === 'INPUT' && /^(text|search|number)$/.test(ev.target.type)))) return;
      if (ev.key === '?' || (ev.key === 'Escape' && (!$('help').hidden || (!$('recs').hidden && ST.D)))) { if (true) { const open = ev.key === '?' ? $('help').hidden : false; $('help').hidden = !open; if (ST.D) $('recs').hidden = true; ev.preventDefault(); return; } }
      const t = ev.target; if (!ST.D || ev.metaKey || ev.ctrlKey || ev.altKey || t.tagName === 'TEXTAREA' || t.isContentEditable || (t.tagName === 'INPUT' && /^(text|search|number|url|email)$/.test(t.type))) return;
      const step = ev.shiftKey ? 10 : 1;
      if (ev.key === 'ArrowRight') { if (ev.shiftKey) R.stepEvent(1); else R.pageTurn(1); }   // ← → turn pages (chapters), ⇧ ← ⇧ → small steps: one event
      else if (ev.key === 'ArrowLeft') { if (ev.shiftKey) R.stepEvent(-1); else R.pageTurn(-1); }
      else if (ev.key === ']' || ev.key === '.') { if (!(ST.follow && R.stepPhase(1))) R.setPhase(ST.verIdx + 1); }
      else if (ev.key === '[' || ev.key === ',') { if (!(ST.follow && R.stepPhase(-1))) R.setPhase(ST.verIdx - 1); }
      else if (ev.key === 'e') R.phaseEnd();
      else if (ev.key === 'i') R.jumpInteresting(1);
      else if (ev.key === 'I') R.jumpInteresting(-1);
      else if (ev.key === 'P') { $('phtab').hidden = !$('phtab').hidden; R.renderPhaseTable(); }
      else if (ev.key === 'r') R.replay();
      else if (ev.key === '0' || ev.key === '1' || ev.key === '2' || ev.key === '3') R.setView(['all', 'scrub', 'insp', 'proj'][+ev.key]);
      else if (ev.key === 'b') { const b = R.curBand(); if (b) R.setEvBySeq(b.begin, true); }
      else if (ev.key === 'n' || ev.key === 'p') { const b = R.curBand(), i = ST.evIdx + (ev.key === 'n' ? 1 : -1); if (b && i >= b.i0 && i <= b.i1) R.setEv(i, true); }
      else if (ev.key === 'Escape') { if (!R.zoomOut()) R.set({ selNode: null, selSym: null, gsel: null }); }
      else return;
      ev.preventDefault();
    });
    window.addEventListener('hashchange', () => {
      const h = new URLSearchParams(location.hash.slice(1)), src = h.get('src') || '', cur = R.srcUrls ? R.srcUrls.join(',') : '';
      if (!ST.D || (src && src !== cur) || (!src && !R.isFixture && location.hash.length > 1)) start(); else if (ST.D) R.applyHash();
    });
    start();
  });

  // ---- recordings panel -------------------------------------------------------------------------------------------------------------
  const CHEAT = `<h3>Keys</h3><div class="keys">
    <span><kbd>←</kbd> <kbd>→</kbd></span><span>previous / next page (chapter, ★ or change, per Pages granularity); <kbd>⇧</kbd> <kbd>←</kbd> <kbd>→</kbd> small steps: one event (stack: one version)</span>
    <span><kbd>[</kbd> <kbd>]</kbd></span><span>previous / next phase output (cursor at its PhaseEnd)</span>
    <span><kbd>b</kbd> <kbd>e</kbd></span><span>start / end of the current phase (<kbd>e</kbd> again: end of the next phase)</span>
    <span><kbd>p</kbd> <kbd>n</kbd></span><span>previous / next event inside the current phase</span>
    <span><kbd>1</kbd> <kbd>2</kbd> <kbd>3</kbd> <kbd>0</kbd></span><span>full height: tree / events / graph / all views</span>
    <span><kbd>i</kbd> <kbd>I</kbd></span><span>next / previous interesting phase (most changed by the chosen metric, ★ on the bands)</span>
    <span><kbd>⇧P</kbd></span><span>phases table (sortable)</span>
    <span><kbd>r</kbd></span><span>replay the last transition (header: speed 1× / 0.25×)</span>
    <span><kbd>x</kbd></span><span>show / hide the project explorer (Ctrl/Cmd-click or tick boxes: several units or classes at once)</span>
    <span><kbd>s</kbd></span><span>open / close the Stack overlay (also the Stack button in the header)</span>
    <span>Stack: <kbd>wheel</kbd> <kbd>↑</kbd> <kbd>↓</kbd> <kbd>PgUp</kbd> <kbd>PgDn</kbd> <kbd>Home</kbd> <kbd>End</kbd></span><span>move through versions (newest in front); scrolling sets the viewer's phase live</span>
    <span>Stack: click a line</span><span>trace that node through every version (and select it in the viewer); <kbd>Esc</kbd> clears the trace, then closes</span>
    <span>Stack: <kbd>⌥</kbd>+wheel, <kbd>j</kbd> <kbd>k</kbd></span><span>scroll the text of the front card</span>
    <span>Stack: hover / click a receding card, minimap, <kbd>i</kbd> <kbd>I</kbd></span><span>bring it forward; jump anywhere; next / previous ★ phase</span>
    <span><kbd>Esc</kbd></span><span>zoom out of a phase band, else clear the selection / close a dialog</span>
    <span><kbd>?</kbd></span><span>this help</span></div>
    <h3>Mouse</h3><div class="keys"><span>click a span</span><span>select that node (events, lineage, source origin)</span>
    <span>hover a span</span><span>kind, type, symbol and derive ancestry (old text in the previous version)</span>
    <span>click a phase band / cell / phase button</span><span>that phase's output (cursor at PhaseEnd); shift-click or <b>⇤</b>: its start</span>
    <span>click a graph node</span><span>jump to the first event that touches it</span>
    <span>click in the source pane</span><span>select the innermost node at that position</span></div>`;
  const RECS = [];
  const fmtSize = (n) => (n > 1e6 ? (n / 1e6).toFixed(1) + ' MB' : Math.round(n / 1e3) + ' kB');
  function renderRecs() {
    const box = $('reclist'); box.innerHTML = '';
    RECS.forEach((r, i) => {
      const d = document.createElement('div'); d.className = 'rec';
      const cb = Object.assign(document.createElement('input'), { type: 'checkbox', checked: !!r.checked }); cb.onchange = () => { r.checked = cb.checked; $('rec-load').disabled = !RECS.some((x) => x.checked); };
      const nm = document.createElement('b'); nm.textContent = r.name; nm.title = r.url || r.file?.name || '';
      const sz = document.createElement('span'); sz.className = 'dim'; sz.textContent = r.size ? fmtSize(r.size) : '';
      const ld = Object.assign(document.createElement('button'), { textContent: 'Load', onclick: () => loadEntries([r]) });
      const meta = document.createElement('div'); meta.className = 'meta';
      meta.textContent = r.fixture ? 'synthetic: hand-written v0 data modelled on Givens.scala, useful as a quick demo only' : r.events != null ? `${r.schema} · ${r.units.length} unit${r.units.length > 1 ? 's' : ''} (${r.units.slice(0, 3).join(', ')}${r.units.length > 3 ? ', …' : ''}) · ${r.phases} phases · ${r.events} events · ${r.nodes} nodes${r.jvm ? ` · + JVM bytecode: ${r.classes} classes, ${r.jvm} class versions` : ''}` : 'local file';
      d.append(cb, nm, sz, ld, meta); box.append(d);
    });
    if (!RECS.length) box.innerHTML = '<span class="dim">No recordings listed (no <code>recordings.json</code>; run <code>gen_index.py</code>). Drop files below.</span>';
    $('rec-load').disabled = !RECS.some((x) => x.checked);
  }
  function openRecs() { $('recs').hidden = false; renderRecs(); }
  function addLocal(files) { files.forEach((f) => RECS.push({ name: f.name.replace(/\.jsonl$/, ''), file: f, size: f.size, checked: true })); openRecs(); }
  function loadLocal(files) { loadEntries(files.map((f) => ({ name: f.name, file: f, size: f.size }))); }
  async function loadEntries(es) {
    $('recs').hidden = true;
    const label = es.length > 1 ? es.length + ' recordings merged' : es[0].name;
    if (es.length === 1 && es[0].fixture) return loadFixture();
    const sources = [];
    for (const e of es) {
      if (e.file) sources.push({ name: e.file.name, size: e.file.size, open: async () => e.file.stream() });
      else { const [s] = await urlSources([e.url]); sources.push(s); }
    }
    loadSources(sources, label);
  }
  async function start() {
    const h = location.hash.slice(1), srcParam = new URLSearchParams(h).get('src');
    if (srcParam) { const urls = srcParam.split(','); urlSources(urls).then((ss) => loadSources(ss, urls.length > 1 ? urls.length + ' files merged' : urls[0])).catch((e) => { $('loadinfo').textContent = String(e.message || e); openRecs(); }); return; }
    if (h) return loadFixture();                     // deep link without src: the built-in fixture (screenshots)
    RECS.push({ name: 'synthetic fixture (v0, hand-written, not a real compile)', fixture: true });
    for (const u of ['recordings.json']) { try { const r = await fetch(u); if (r.ok) { (await r.json()).forEach((x) => RECS.push(x)); break; } } catch (e) { /* none */ } }
    openRecs();
  }
  function loadFixture() {
    return fetch('fixture.jsonl').then((r) => { if (!r.ok) throw 0; return r.text(); }).then((t) => loadSources([Object.assign(textSource('fixture.jsonl', t), { hash: true })], 'fixture.jsonl'))
      .catch(() => { if (window.REPLAY_FIXTURE) loadSources([Object.assign(textSource('fixture.js', window.REPLAY_FIXTURE), { hash: true })], 'fixture.js (embedded copy of fixture.jsonl)'); else $('loadinfo').textContent = 'no fixture available'; });
  }
  function copyLink() {
    const D = ST.D; if (!D) return;
    const url = location.origin + location.pathname + '#' + R.buildHash();
    (navigator.clipboard ? navigator.clipboard.writeText(url) : Promise.reject()).then(() => flash('link copied' + (R.srcUrls || R.isFixture ? '' : ' (recording was loaded from local files: the link cannot reload it)')), () => window.prompt('copy this link', url));
  }
  // about: what a recording is, plus TOURS.md rendered in-page (tiny markdown subset)
  const ABOUT = `<h3>What a recording is</h3><p>A recording is a JSONL file written by a <code>dotc</code> compiler plugin while it compiles Scala 3 sources: the printed tree after every phase with node spans, how each node derives from its predecessor, the typer's decisions (typing, adaptation, implicit search, completions), the dependencies between classes (with when each was established), and optionally the JVM bytecode and the optimiser's passes. Greffier only reads these files; nothing is compiled in the browser.</p><p>Load recordings from the recordings panel (top left), or drop <code>.jsonl</code> files on the page.</p>`;
  const md = (t) => t.replace(/[&<]/g, (c) => (c === '&' ? '&amp;' : '&lt;')).split('\n').map((l) => { l = l.replace(/`([^`]+)`/g, '<code>$1</code>').replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>'); if (/^## /.test(l)) return '<h3>' + l.slice(3) + '</h3>'; if (/^# /.test(l)) return ''; if (/^\d+\. /.test(l) || /^- /.test(l)) return '<li>' + l.replace(/^(\d+\.|-) /, '') + '</li>'; return l.trim() ? '<p>' + l + '</p>' : ''; }).join('\n');
  async function openAbout() {
    $('about').hidden = false; const b = $('aboutbody'); b.innerHTML = ABOUT + '<h3>Guided tours</h3><p class="dim">loading…</p>';
    try { const r = await fetch('TOURS.md'); if (!r.ok) throw 0; b.innerHTML = ABOUT + '<h3>Guided tours</h3>' + md(await r.text()); } catch (e) { b.innerHTML = ABOUT + '<h3>Guided tours</h3><p class="dim">TOURS.md is not available on this server.</p>'; }
  }
  function flash(t) { const el = $('perf'), old = el.textContent; el.textContent = t; setTimeout(() => (el.textContent = old), 2500); }
})(window.R);
