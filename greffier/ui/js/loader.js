// JSONL loader for v0, v0+trackA and v1. Streaming (line by line), tolerant, optional multi-file merge,
// lazy parsing of version lines (text/offsets/types are decoded on first use and kept in a small LRU).
(function (R) {
  const num = (x) => (typeof x === 'number' && isFinite(x) ? x : (typeof x === 'string' && x.trim() !== '' && isFinite(+x) ? +x : null));
  const arr = (x) => (Array.isArray(x) ? x : x == null ? [] : [x]);
  const span = (x) => (Array.isArray(x) && x.length >= 2 ? [num(x[0]) ?? 0, num(x[1]) ?? 0] : [0, 0]);
  const HEAD = /^\{"t":"version","phase":(\d+),"unit":(\d+|null),"seq":(\d+),"root":(\d+),/;
  const HEADIR = /^\{"t":"version","ir":"jvm","phase":(\d+),"unit":(\d+|null),"seq":(\d+),"class":"((?:[^"\\]|\\.)*)","root":(\d+),/;
  const TAIL = /"nodes":(\d+),"new":(\d+)\}\s*$/;

  // ---- versions: heavy fields are decoded lazily -------------------------------------------------------------
  const LRU = []; const LRU_MAX = 96;
  class Version {
    constructor(D, h) { Object.assign(this, h); this.D = D; this._l = false; }
    _load() {
      if (this._l) return; this._l = true;
      if (this.raw != null) {
        let r; try { r = JSON.parse(this.raw); } catch (e) { r = {}; }
        this._text = typeof r.text === 'string' ? r.text : null;
        this._off = Array.isArray(r.offsets) ? r.offsets.filter((o) => Array.isArray(o) && o.length >= 3) : null;
        this._types = r.types || null;
        if (r.textSameAs != null) this.textSameAs = num(r.textSameAs);
        if (r.offsetsSameAs != null) this.offsetsSameAs = num(r.offsetsSameAs);
        if (this.lazy) { LRU.push(this); if (LRU.length > LRU_MAX) { const o = LRU.shift(); if (o !== this) o._evict(); } }
      }
    }
    _evict() { this._l = false; this._text = this._off = this._types = this._ids = this._by = this._tm = undefined; }
    _ref(p) { return this.D.byPU.get(this.unit + ':' + p); }
    get text() { this._load(); if (this._text != null) return this._text; const r = this.textSameAs != null ? this._ref(this.textSameAs) : null; return r && r !== this ? r.text : ''; }
    get offsets() { this._load(); if (this._off) return this._off; const r = this.offsetsSameAs != null ? this._ref(this.offsetsSameAs) : null; return r && r !== this ? r.offsets : []; }
    _same() { this._load(); if (this._off || this.offsetsSameAs == null) return null; const r = this._ref(this.offsetsSameAs); return r && r !== this ? r : null; }
    get ids() { const r = this._same(); if (r) return r.ids; const o = this.offsets; if (!this._ids) this._ids = new Set(o.map((x) => x[0])); return this._ids; }
    get byId() { const r = this._same(); if (r) return r.byId; const o = this.offsets; if (!this._by) { this._by = new Map(); o.forEach((x) => this._by.set(x[0], x)); } return this._by; }
    get typeMap() { this._load(); if (!this._tm) { this._tm = new Map(); (this._types || []).forEach((t) => this._tm.set(t[0], t[1])); } return this._tm; }
  }

  // ---- loader ----------------------------------------------------------------------------------------------------
  R.createLoader = function (opts) {
    opts = opts || {};
    const D = { meta: null, units: new Map(), phases: new Map(), nodes: new Map(), versions: [], syms: new Map(), syminfo: new Map(),
      types: new Map(), retypes: new Map(), derives: [], evs: [], edges: [], unknown: {}, warnings: [], counts: {}, files: [], byPU: new Map(), schema: 'v0', jvmClasses: new Map(), irDerives: [] };
    const warn = (m) => { if (D.warnings.length < 200) D.warnings.push(m); };
    let fileNo = -1, off = { node: 0, sym: 0, type: 0, unit: 0, seq: 0 }, mx = { node: -1, sym: -1, type: -1, unit: -1, seq: -1 }, symMap = new Map(), fname = '';
    const symByFull = new Map();   // fullName -> id, for merging library/package symbols across files
    const O = (k, v) => (v == null ? null : v + off[k]);
    const S = (v) => (v == null ? null : symMap.has(v) ? symMap.get(v) : v + off.sym);
    const T = (v) => (typeof v === 'number' ? v + off.type : v);
    const track = (k, v) => { if (v != null && v > mx[k]) mx[k] = v; };

    function beginFile(name) {
      fileNo++; fname = name;
      if (fileNo > 0) off = { node: mx.node + 1, sym: mx.sym + 1, type: mx.type + 1, unit: mx.unit + 1, seq: mx.seq + 1 };
      symMap = new Map(); D.files.push(name);
    }

    function feed(line, lineNo) {
      if (!line || !line.trim()) return;
      const merging = fileNo > 0;
      // fast path: lazy version
      if (opts.lazy && !merging && line.startsWith('{"t":"version","ir":"jvm"')) {
        const h = HEADIR.exec(line), t = TAIL.exec(line);
        if (h && t) { D.counts.version = (D.counts.version || 0) + 1; addJvm(new Version(D, { ir: true, phase: +h[1], unit: h[2] === 'null' ? null : +h[2], seq: +h[3], cls: h[4], root: +h[5], file: fileNo, hdrNodes: +t[1], hdrNew: +t[2], raw: line, lazy: true })); return; }
      }
      if (opts.lazy && !merging && line.startsWith('{"t":"version"')) {
        const h = HEAD.exec(line), t = TAIL.exec(line);
        if (h && t) {
          D.counts.version = (D.counts.version || 0) + 1;
          const tm = /"textSameAs":(\d+)/.exec(line.slice(h[0].length, h[0].length + 40));
          const om = /"offsetsSameAs":(\d+)/.exec(line.slice(-200)) || (tm ? /"offsetsSameAs":(\d+)/.exec(line.slice(h[0].length, h[0].length + 80)) : null);
          const v = new Version(D, { phase: +h[1], unit: h[2] === 'null' ? null : +h[2], seq: +h[3], root: +h[4], file: fileNo, hdrNodes: +t[1], hdrNew: +t[2], textSameAs: tm ? +tm[1] : null, offsetsSameAs: om ? +om[1] : null, raw: line, lazy: true });
          D.versions.push(v); return;
        }
      }
      let r;
      try { r = JSON.parse(line); } catch (e) { warn(`${fname} line ${lineNo}: invalid JSON (${e.message})`); return; }
      if (!r || typeof r !== 'object' || typeof r.t !== 'string') { warn(`${fname} line ${lineNo}: record without "t"`); return; }
      D.counts[r.t] = (D.counts[r.t] || 0) + 1;
      switch (r.t) {
        case 'meta':
          if (!D.meta) D.meta = r; if (r.schema) D.schema = r.schema;
          arr(r.units).forEach((u) => { const id = O('unit', num(u.id)); track('unit', id); D.units.set(id, Object.assign({}, u, { id })); });
          break;
        case 'phase': { const id = num(r.id); if (!D.phases.has(id)) D.phases.set(id, { id, ir: r.ir ?? null, name: r.name || '#' + r.id, mini: !!r.mini, group: num(r.group) ?? id, megaOf: r.megaOf ?? null }); break; }
        case 'node': { const id = O('node', num(r.id)); track('node', id);
          D.nodes.set(id, { id, kind: r.kind || '?', span: span(r.span), unit: O('unit', num(r.unit)), children: arr(r.children).map((c) => O('node', num(c))), sym: S(num(r.sym)), tpe: T(num(r.tpe)), name: r.name ?? null, ir: r.ir ?? null, desc: r.desc ?? null, method: r.method != null ? O('node', num(r.method)) : null, range: r.range ?? null, insns: r.insns ?? null, mirror: !!r.mirror }); break; }
        case 'sym': {
          const id0 = num(r.id), full = r.fullName;
          if (merging && full != null && !r.local && symByFull.has(full)) { symMap.set(id0, symByFull.get(full)); break; }
          const id = id0 + off.sym; track('sym', id); if (full != null && !r.local) symByFull.set(full, id);
          D.syms.set(id, { id, name: r.name || '#' + r.id, fullName: full ?? null, kind: r.kind ?? null, local: !!r.local, unit: O('unit', num(r.unit)), owner: S(num(r.owner)),
            createdPhase: num(r.createdPhase ?? r.firstPhase), createdBy: num(r.createdBy ?? r.firstSeq) });
          break; }
        case 'syminfo': { const k = S(num(r.sym)); if (merging && symMap.has(num(r.sym))) break; if (!D.syminfo.has(k)) D.syminfo.set(k, []);
          D.syminfo.get(k).push({ name: r.name ?? null, fromPhase: num(r.fromPhase) ?? -1, info: r.info ?? '', flags: r.flags ?? '', owner: S(num(r.owner)) }); break; }
        case 'type': { const id = O('type', num(r.id)); track('type', id); D.types.set(id, r.text ?? ''); break; }
        case 'version': {
          if (r.ir === 'jvm') {
            const v = new Version(D, { ir: true, phase: num(r.phase), unit: O('unit', num(r.unit)), seq: num(r.seq) == null ? null : num(r.seq) + off.seq, cls: r.class, root: O('node', num(r.root)), file: fileNo, hdrNodes: num(r.nodes), hdrNew: num(r.new) });
            v._l = true; v._text = r.text ?? ''; v._off = (r.offsets || []).map((o) => [O('node', o[0]), o[1], o[2]]); v._types = null; addJvm(v); break;
          }
          const v = new Version(D, { phase: num(r.phase), unit: O('unit', num(r.unit ?? 0)), seq: num(r.seq) == null ? null : num(r.seq) + off.seq, root: O('node', num(r.root)),
            file: fileNo, hdrNodes: num(r.nodes), hdrNew: num(r.new), textSameAs: num(r.textSameAs), offsetsSameAs: num(r.offsetsSameAs) });
          v._l = true; v._text = typeof r.text === 'string' ? r.text : null;
          v._off = Array.isArray(r.offsets) ? r.offsets.filter((o) => Array.isArray(o) && o.length >= 3).map((o) => [O('node', o[0]), o[1], o[2]]) : null;
          v._types = Array.isArray(r.types) ? r.types.map((t) => [O('node', t[0]), t[1]]) : null;
          D.versions.push(v); break; }
        case 'retype': { const k = O('node', num(r.node)); if (!D.retypes.has(k)) D.retypes.set(k, []); D.retypes.get(k).push({ phase: num(r.phase), tpe: T(num(r.tpe)) }); break; }
        case 'derive': if (r.ir === 'jvm') { D.irDerives.push({ phase: num(r.phase), unit: O('unit', num(r.unit)), new: arr(r.new).map((n) => O('node', num(n))), old: arr(r.old).map((n) => O('node', num(n))), how: r.how, confidence: r.confidence ?? null, callee: r.callee ?? null }); break; }
          D.derives.push({ phase: num(r.phase), unit: O('unit', num(r.unit)), new: arr(r.new).map((n) => O('node', num(n))), old: arr(r.old).map((n) => O('node', num(n))), how: r.how || 'heuristic', confidence: r.confidence ?? null }); break;
        case 'ev': {
          const p = r.payload && typeof r.payload === 'object' ? r.payload : {};
          if (merging) { ['pt', 'tpe', 'ref'].forEach((k) => { if (typeof p[k] === 'number') p[k] += off.type; }); if (typeof p.forcedBy === 'number') p.forcedBy = S(p.forcedBy); }
          const seq = num(r.seq) + off.seq; track('seq', seq);
          D.evs.push({ file: fileNo, ts: num(r.ts ?? r.ns ?? p.ts ?? p.ns), seq, parent: r.parent == null ? null : num(r.parent) + off.seq, kind: r.kind || '?', phase: num(r.phase), unit: O('unit', num(r.unit)), span: span(r.span),
            nodes: arr(r.nodes).map((n) => O('node', num(n))), syms: arr(r.syms).map((s) => S(num(s))).filter((s) => s != null), payload: p });
          break; }
        case 'edge': D.edges.push({ kind: r.kind || '?', from: S(num(r.from)), to: S(num(r.to)), seq: (num(r.seq) ?? num(r.ev) ?? 0) + off.seq, weight: num(r.weight) ?? 1, phase: num(r.phase) }); break;
        case 'stats': D.stats = r; break;
        default: D.unknown[r.t] = (D.unknown[r.t] || 0) + 1;
      }
    }
    function addJvm(v) { let c = D.jvmClasses.get(v.cls); if (!c) D.jvmClasses.set(v.cls, (c = { name: v.cls, unit: v.unit, versions: [] })); c.versions.push(v); }
    return { D, beginFile, feed, finish: () => R.index(D) };
  };

  // Synchronous convenience for a whole text (fixture, tests).
  R.load = function (text, name) { const L = R.createLoader(); L.beginFile(name || 'text'); text.split(/\r?\n/).forEach((l, i) => L.feed(l, i + 1)); return L.finish(); };

  // Streaming: ReadableStream<Uint8Array> -> loader, with periodic yields so the page can paint progress.
  R.streamInto = async function (L, name, stream, onProgress, totalBytes) {
    L.beginFile(name);
    if (typeof stream === 'string') { let n = 0; stream.split(/\r?\n/).forEach((l) => L.feed(l, ++n)); return; }
    const rd = stream.getReader(), dec = new TextDecoder(); let buf = '', lineNo = 0, got = 0, tick = performance.now();
    for (;;) {
      const { done, value } = await rd.read();
      if (value) { got += value.length; buf += dec.decode(value, { stream: !done }); } else buf += dec.decode();
      let i, s = 0;
      while ((i = buf.indexOf('\n', s)) >= 0) { L.feed(buf.slice(s, i), ++lineNo); s = i + 1; }
      buf = buf.slice(s);
      if (done) break;
      if (performance.now() - tick > 80) { tick = performance.now(); onProgress && onProgress(name, got, totalBytes, L.D); await new Promise((r) => setTimeout(r, 0)); }
    }
    if (buf.trim()) L.feed(buf, ++lineNo);
  };

  // Several recordings loaded together are separate compiles with the same phase plan. Build ONE timeline: events are ordered by
  // phase, then file (load order), then original seq; each phase keeps a single PhaseBegin (first file's) and PhaseEnd (last file's),
  // so every phase is one band holding all files' events. Versions take the merged PhaseEnd of their phase as seq, edges follow.
  function mergeTimeline(D) {
    const byPhase = new Map(); D.evs.forEach((e) => { const k = e.phase == null ? -1 : e.phase; let a = byPhase.get(k); if (!a) byPhase.set(k, (a = [])); a.push(e); });
    const out = [], map = new Map();
    [...byPhase.keys()].sort((a, b) => a - b).forEach((p) => {
      const evs = byPhase.get(p), begins = evs.filter((e) => e.kind === 'PhaseBegin').sort((a, b) => a.seq - b.seq), ends = evs.filter((e) => e.kind === 'PhaseEnd').sort((a, b) => a.seq - b.seq);
      const rest = evs.filter((e) => e.kind !== 'PhaseBegin' && e.kind !== 'PhaseEnd').sort((a, b) => (a.file - b.file) || (a.seq - b.seq));
      const kb = begins[0], ke = ends[ends.length - 1], seq = [];
      if (kb) seq.push(kb); rest.forEach((e) => seq.push(e)); if (ke) seq.push(ke);
      seq.forEach((e) => out.push(e));
      begins.forEach((e) => map.set(e.seq, kb)); ends.forEach((e) => map.set(e.seq, ke)); rest.forEach((e) => map.set(e.seq, e));
    });
    const newSeq = new Map(); out.forEach((e, i) => newSeq.set(e, i));
    const oldToNew = new Map(); map.forEach((e, old) => oldToNew.set(old, newSeq.get(e)));
    const parentsOld = new Map(); out.forEach((e) => parentsOld.set(e, e.parent));
    out.forEach((e) => { e.of = e.file; e.file = 0; e.parent = parentsOld.get(e) == null ? null : (oldToNew.get(parentsOld.get(e)) ?? null); });
    out.forEach((e, i) => { e.seq = i; });
    const mp = (q) => (q == null ? q : oldToNew.has(q) ? oldToNew.get(q) : q);
    D.versions.forEach((v) => { v.seq = mp(v.seq); v.file = 0; }); D.jvmClasses.forEach((c) => c.versions.forEach((v) => { v.seq = mp(v.seq); v.file = 0; }));
    D.edges.forEach((e) => { e.seq = mp(e.seq); });
    D.evs = out; D.merged = true;
  }

  // The JVM phases extend the event axis: the genBCode bytecode appears at genBCode's PhaseEnd, every later JVM phase (optimiser
  // pass, jvm:classfile) gets one synthetic `JvmPhase` event after the last recorded event, and the class versions of that phase
  // take its seq (a recording has the genBCode seq on all of them).
  function addJvmEvents(D) {
    const phs = new Set(); let gs = Infinity; D.jvmClasses.forEach((c) => c.versions.forEach((v) => { phs.add(v.phase); if (v.seq != null && v.seq < gs) gs = v.seq; }));
    const ids = [...phs].sort((a, b) => a - b), gen = ids[0]; let last = D.evs.reduce((m, e) => Math.max(m, e.seq), -1);
    D.irSeq = new Map([[gen, gs === Infinity ? last : gs]]);
    ids.slice(1).forEach((p) => { last++; D.irSeq.set(p, last); D.evs.push({ file: 0, ts: null, seq: last, parent: null, kind: 'JvmPhase', phase: p, unit: null, span: [0, 0], nodes: [], syms: [], payload: { name: (D.phases.get(p) || {}).name || '#' + p } }); });
    D.jvmClasses.forEach((c) => c.versions.forEach((v) => { if (D.irSeq.has(v.phase)) v.seq = D.irSeq.get(v.phase); }));
  }

  // ---- indexes ---------------------------------------------------------------------------------------------------------
  R.index = function (D) {
    if (D.files.length > 1) mergeTimeline(D);
    if (D.jvmClasses.size) addJvmEvents(D);
    D.evs.sort((a, b) => a.seq - b.seq);
    D.evBySeq = new Map(D.evs.map((e) => [e.seq, e]));
    D.evChildren = new Map();
    D.evs.forEach((e) => { if (e.parent != null) { if (!D.evChildren.has(e.parent)) D.evChildren.set(e.parent, []); D.evChildren.get(e.parent).push(e); } });
    D.evByNode = new Map(); D.evBySym = new Map();
    const push = (m, k, e) => { if (k == null) return; let l = m.get(k); if (!l) m.set(k, (l = [])); if (l[l.length - 1] !== e) l.push(e); };
    D.evs.forEach((e) => { e.nodes.forEach((n) => push(D.evByNode, n, e)); e.syms.forEach((s) => push(D.evBySym, s, e)); });
    // phase bands: PhaseBegin..PhaseEnd seq per (file, phase group)
    D.bands = []; { const open = new Map();
      D.evs.forEach((e) => { const k = e.file + ':' + e.phase;
        if (e.kind === 'PhaseBegin') open.set(k, { phase: e.phase, file: e.file, begin: e.seq, end: null, t0: e.ts });
        else if (e.kind === 'PhaseEnd' && open.has(k)) { const b = open.get(k); b.end = e.seq; b.t1 = e.ts; D.bands.push(b); open.delete(k); } });
      D.evs.forEach((e) => { if (e.kind === 'JvmPhase') D.bands.push({ phase: e.phase, file: 0, begin: e.seq, end: e.seq, t0: null, t1: null, ir: true }); });
      D.bands.sort((a, b) => a.begin - b.begin);
      // time axis, only if every band has timestamps (ev.ts / ev.ns / payload.ts|ns); files are concatenated
      D.hasTime = D.bands.length > 0 && D.bands.every((b) => b.ir || (b.t0 != null && b.t1 != null && b.t1 >= b.t0));
      if (D.hasTime) {
        const byFile = new Map(); D.bands.forEach((b) => { if (b.ir) return; const f = byFile.get(b.file) || { lo: Infinity, hi: -Infinity }; f.lo = Math.min(f.lo, b.t0); f.hi = Math.max(f.hi, b.t1); byFile.set(b.file, f); });
        let acc = 0; [...byFile.keys()].sort((a, b) => a - b).forEach((k) => { const f = byFile.get(k); f.off = acc - f.lo; acc += f.hi - f.lo; });
        D.hasTime = acc > 0; D.bands.forEach((b) => { if (b.ir) { b.x0 = b.x1 = 1; b.ms = 0; return; } const f = byFile.get(b.file); b.x0 = (b.t0 + f.off) / acc; b.x1 = (b.t1 + f.off) / acc; b.ms = (b.t1 - b.t0); });
      } }
    D.syminfo.forEach((l) => l.sort((a, b) => a.fromPhase - b.fromPhase));
    D.versions.sort((a, b) => (a.phase - b.phase) || ((a.unit ?? -1) - (b.unit ?? -1)));
    D.byPU = new Map(D.versions.map((v) => [v.unit + ':' + v.phase, v]));
    const perUnit = new Map();
    D.versions.forEach((v) => { if (!perUnit.has(v.unit)) perUnit.set(v.unit, []); v.vi = perUnit.get(v.unit).length; perUnit.get(v.unit).push(v); });
    D.perUnit = perUnit; D.unitOfFile = new Map(); D.versions.forEach((v) => { if (v.file != null && v.unit != null && !D.unitOfFile.has(v.file)) D.unitOfFile.set(v.file, v.unit); });
    D.unitsWithVersions = [...perUnit.keys()];
    D.versionsOf = (u) => perUnit.get(u) || [];
    D.retypes.forEach((l) => l.sort((a, b) => a.phase - b.phase));
    D.hasVersionTypes = D.schema === 'v1' || D.versions.some((v) => v._l && v._types);
    // lineage
    D.deriveByNew = new Map(); D.deriveByOld = new Map();
    D.derives.forEach((d) => {
      d.new.forEach((n) => { let l = D.deriveByNew.get(n); if (!l) D.deriveByNew.set(n, (l = [])); l.push(d); });
      d.old.forEach((n) => { let l = D.deriveByOld.get(n); if (!l) D.deriveByOld.set(n, (l = [])); l.push(d); });
    });
    // number of non-copy new nodes per (unit, phase), for the no-op test without decoding offsets
    D.nonCopyNew = new Map();
    D.derives.forEach((d) => { if (d.how !== 'copy') { const k = d.unit + ':' + d.phase; D.nonCopyNew.set(k, (D.nonCopyNew.get(k) || 0) + d.new.length); } });
    D.symName = (s) => (D.syms.get(s) ? D.syms.get(s).name : s == null ? null : '#' + s);
    D.typeText = (t) => (t == null ? null : D.types.has(t) ? D.types.get(t) : '#' + t);
    // printed type of node n as of version v: v1 per-version `types` (latest entry at or before v), else v0 node.tpe + retype
    D.tpeText = (n, v) => {
      if (D.hasVersionTypes && v) {
        const vs = D.versionsOf(v.unit);
        for (let i = v.vi, c = 0; i >= 0 && c < 120; i--, c++) { const t = vs[i].typeMap.get(n); if (t !== undefined) return t; }
        return null;
      }
      let t = D.nodes.has(n) ? D.nodes.get(n).tpe : null; (D.retypes.get(n) || []).forEach((r) => { if (!v || r.phase <= v.phase) t = r.tpe; }); return D.typeText(t);
    };
    D.infoAt = (s, phase) => { const l = D.syminfo.get(s); if (!l) return null; let r = null; for (const i of l) if (i.fromPhase <= phase) r = i; return r; };
    D.phaseRaw = (p) => (D.phases.has(p) ? D.phases.get(p).name : '#' + p);
    // a phase group can fuse several miniphases: label it with all of them
    const grp = new Map(); [...D.phases.values()].sort((a, b) => a.id - b.id).forEach((ph) => { if (!grp.has(ph.group)) grp.set(ph.group, []); grp.get(ph.group).push(ph.name); });
    D.phaseMembers = (p) => grp.get(p) || [D.phaseRaw(p)];
    D.phaseName = (p) => D.phaseMembers(p).join(' + ');
    D.phaseShort = (p) => { const m = D.phaseMembers(p); return m.length > 1 ? `${m[0]} +${m.length - 1}` : m[0]; };
    D.pkgOf = (s) => { const sy = D.syms.get(s), f = sy && sy.fullName; if (!f) return null; const i = f.lastIndexOf('.'); return i > 0 ? f.slice(0, i) : f; };
    // pure copy: v1 says so (derive how=copy); v0 data is recomputed from node/derive tables
    const pc = new Map();
    D.isPureCopy = (n) => {
      if (pc.has(n)) return pc.get(n);
      const ds = D.deriveByNew.get(n); let ok = false;
      if (ds && ds.some((d) => d.how === 'copy')) ok = true;
      else if (D.schema !== 'v1' && ds) {
        pc.set(n, false); const nd = D.nodes.get(n);
        for (const d of ds) {
          if (!nd || d.old.length !== 1 || d.new.length !== 1) continue;
          const od = D.nodes.get(d.old[0]); if (!od) continue;
          if (od.kind !== nd.kind || od.name !== nd.name || od.sym !== nd.sym || od.span[0] !== nd.span[0] || od.span[1] !== nd.span[1] || od.children.length !== nd.children.length) continue;
          if (D.typeText(od.tpe) !== D.typeText(nd.tpe)) continue;
          if (nd.children.every((c, i) => c === od.children[i] || D.isPureCopy(c))) { ok = true; break; }
        }
      }
      pc.set(n, ok); return ok;
    };
    // ---- JVM IR index ----
    D.hasIR = D.jvmClasses.size > 0;
    if (D.hasIR) {
      D.jvmClasses.forEach((c) => c.versions.sort((a, b) => a.phase - b.phase));
      D.irByNew = new Map(); D.irByOld = new Map(); D.irFromTree = new Map(); D.irToTree = new Map(); D.irClassOf = new Map();
      D.irDerives.forEach((d) => {
        d.new.forEach((n) => { let l = D.irByNew.get(n); if (!l) D.irByNew.set(n, (l = [])); l.push(d); });
        d.old.forEach((n) => { let l = D.irByOld.get(n); if (!l) D.irByOld.set(n, (l = [])); l.push(d); });
        if (d.how === 'codegen') d.old.forEach((t) => d.new.forEach((n) => { let a = D.irFromTree.get(t); if (!a) D.irFromTree.set(t, (a = [])); a.push(n); let b = D.irToTree.get(n); if (!b) D.irToTree.set(n, (b = [])); b.push(t); }));
      });
      // class membership of IR nodes: ClassNode.children are fields/methods; ranges belong to their method
      D.nodes.forEach((nd) => { if (nd.ir && nd.kind === 'ClassNode') nd.children.forEach((c) => D.irClassOf.set(c, nd.name)); });
      D.nodes.forEach((nd) => { if (nd.ir && nd.kind === 'InsnRange' && nd.method != null) { const c = D.irClassOf.get(nd.method); if (c) D.irClassOf.set(nd.id, c); } });
      D.nodes.forEach((nd) => { if (nd.ir && nd.kind === 'ClassNode') D.irClassOf.set(nd.id, nd.name); });
      const phs = new Set(); D.jvmClasses.forEach((c) => c.versions.forEach((v) => phs.add(v.phase))); D.irPhaseIds = [...phs].sort((a, b) => a - b);
      D.irGenPhase = D.irPhaseIds[0]; let gs = Infinity; D.jvmClasses.forEach((c) => c.versions.forEach((v) => { if (v.seq != null && v.seq < gs) gs = v.seq; })); D.irGenSeq = gs === Infinity ? null : gs;
      // class version at IR phase p: the latest version with phase <= p
      D.irClassVersion = (name, p) => { const c = D.jvmClasses.get(name); if (!c) return null; let r = null; for (const v of c.versions) { if (v.phase <= p) r = v; else break; } return r; };
    }
    // validation (cheap checks only; version offsets are not decoded here)
    let dn = 0, ds2 = 0, bad = 0;
    D.evs.forEach((e) => { e.nodes.forEach((n) => { if (n != null && !D.nodes.has(n)) dn++; }); e.syms.forEach((s) => { if (!D.syms.has(s)) ds2++; }); if (e.parent != null && !D.evBySeq.has(e.parent)) bad++; });
    D.edges.forEach((e) => { if (!D.syms.has(e.from) || !D.syms.has(e.to)) ds2++; });
    D.versions.forEach((v) => { if (v._l && v._off) v._off.forEach((o) => { if (o[1] > o[2] || o[2] > v.text.length) bad++; if (!D.nodes.has(o[0])) dn++; }); });
    if (dn) D.warnings.push(`${dn} references to node ids with no node record`);
    if (ds2) D.warnings.push(`${ds2} references to sym ids with no sym record`);
    if (bad) D.warnings.push(`${bad} malformed offsets / dangling parent seqs`);
    if (!D.meta) D.warnings.push('no meta record');
    return D;
  };

  // lineage helpers ------------------------------------------------------------------------------------------
  // Closure of `start` through derive edges in one direction, stopping at nodes satisfying `stop` (null: collect everything).
  R.walk = function (D, start, dir, stop, maxPhase) {
    const out = new Set(), seen = new Set([start]), q = [start];
    for (let qi = 0; qi < q.length && seen.size < 5000; qi++) {
      const n = q[qi];
      const hit = stop ? stop(n) : true;
      if (hit) out.add(n);
      if (stop && hit) continue;
      const ds = (dir === 'fwd' ? D.deriveByOld : D.deriveByNew).get(n) || [];
      for (const d of ds) {
        if (maxPhase != null && d.phase > maxPhase) continue;
        for (const m of dir === 'fwd' ? d.new : d.old) if (!seen.has(m)) { seen.add(m); q.push(m); }
      }
    }
    return out;
  };
})(window.R = window.R || {});
