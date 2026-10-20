// Compile drawer: write Scala, compile it on the compile server, and load the resulting recording into the viewer.
// API (frozen, see local/replay/server/README.md): GET /api/versions, POST /api/compile, GET /api/compile/<id>, GET /api/recordings[/<id>.jsonl].
// The server is the same origin if /api/versions answers there, else `api=` (hash), the view-options field (localStorage), default http://localhost:8790.
(function (R) {
  const $ = (id) => document.getElementById(id), esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const DEF_API = 'http://localhost:8790', LS = 'greffier.api';
  const API = (R.api = { base: null, versions: null, tried: [], initialApi: new URLSearchParams(location.hash.slice(1)).get('api') });
  const PRESETS = [['-opt', '-opt'], ['-opt-inline:<sources>', '-opt-inline:<sources>'], ['-opt-inline:**', '-opt-inline:**'], ['-Wall', '-Wall'], ['-explain', '-explain']];
  const EXAMPLE = `object Demo:\n  def applyTwice(f: Int => Int, x: Int): Int = f(f(x))\n  def demo = applyTwice(x => x * 2, 21)\n  def local(xs: List[Int], k: Int): List[Int] =\n    def scale(x: Int): Int = x * k\n    xs.map(scale)\n`;
  const C = (R._compile = { files: [], active: 0, docs: [], built: false, collapsed: false, job: null, dirty: false });
  const store = (k, v) => { try { if (v == null) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) { return null; } };
  const hashApi = () => new URLSearchParams(location.hash.slice(1)).get('api') || API.initialApi || null;   // remembered: the address bar is rewritten after the first render
  const abs = (u) => (/^https?:/.test(u) ? u : (API.base || '') + u);

  // ---- server discovery ----------------------------------------------------------------------------------------------------------
  async function resolve(force) {
    if (API.base != null && !force) return API.base;
    const field = $('apiurl') && $('apiurl').value.trim(), cands = [];
    // the page's own origin first, then what the user configured; the default local ports only from a page that is itself local (a deployed page must not probe localhost)
    const local = /^(localhost|127\.0\.0\.1|\[::1\])$/.test(location.hostname);
    const explicit = hashApi();   // an explicit api= is the only candidate (so a typo is an error, not a silent fallback)
    (explicit ? [explicit] : [field, store(LS), '', ...(local ? [DEF_API, 'http://localhost:8777'] : [])]).forEach((c) => { if (c != null && c !== '' + 'x' && !cands.includes(c) && (c !== '' || /^https?:/.test(location.protocol))) cands.push(c); });
    API.tried = [];
    for (const c of cands) {
      const base = String(c).replace(/\/+$/, ''); API.tried.push(base || location.origin);
      try { const r = await fetch(base + '/api/versions', { cache: 'no-store' }); if (!r.ok) continue; const j = await r.json(); if (j && Array.isArray(j.versions)) { API.base = base; API.versions = j; return base; } } catch (e) { /* next */ }
    }
    API.base = null; return null;
  }
  R.apiBaseForHash = () => (API.base && API.base !== '' && API.base !== DEF_API ? API.base : null);

  // ---- recordings of the server -> the recordings panel ----------------------------------------------------------------------------
  R.refreshLocalRecs = async () => {
    const base = await resolve(); if (base == null) return;
    try {
      const r = await fetch(base + '/api/recordings'); if (!r.ok) return; const list = await r.json();
      const fix = (e) => Object.assign({}, e, { url: abs(e.url), variants: e.variants && e.variants.map((v) => Object.assign({}, v, { url: abs(v.url) })) });
      R.addLocalRecs(list.map(fix)); R.renderRecs();
    } catch (e) { /* ignore */ }
  };

  // ---- drawer ------------------------------------------------------------------------------------------------------------------------
  function build() {
    if (C.built) return; C.built = true;
    const d = document.createElement('div'); d.id = 'cdrawer'; d.hidden = true;
    d.innerHTML = `<div class="chead"><b>Compile</b><span id="csum" class="dim"></span><button id="ccollapse" title="collapse to a strip">▾</button><button id="cclose" title="close (c)">✕</button></div>
      <div id="cbody"><div id="ctabs"></div><div id="ced"></div>
      <div class="crow"><label>scala <select id="cver"></select></label><label>label <input id="clabel" placeholder="(first file)" size="14"></label></div>
      <div class="crow" id="copts">${PRESETS.map(([k, t], i) => `<label title="${esc(k)}"><input type="checkbox" data-opt="${esc(k)}"> ${esc(t)}</label>`).join('')}<input id="cfree" placeholder="more options, e.g. -Xprint:typer" size="26"></div>
      <div class="crow"><button id="cgo" class="primary" title="Ctrl/⌘-Enter">Compile ⌘↵</button><span id="cstat"></span></div>
      <pre id="clog" hidden></pre><div id="cdiags"></div><div id="cerr" hidden></div></div><div id="cresize" title="drag to resize"></div>`;
    document.body.append(d);
    const w = +store('greffier.cw'); if (w) d.style.setProperty('--cw', w + 'px');
    $('cclose').onclick = () => R.compileToggle(false); $('ccollapse').onclick = () => collapse(!C.collapsed);
    $('csum').onclick = () => collapse(false); $('cgo').onclick = go; $('cver').onchange = () => { C.ver = $('cver').value; verNote(); };
    d.addEventListener('keydown', (e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); go(); } else if (e.key === 'Escape') { e.stopPropagation(); collapse(true); } }, true);
    let drag = null; const rs = $('cresize');
    rs.addEventListener('pointerdown', (e) => { drag = { x: e.clientX, w: d.getBoundingClientRect().width }; rs.setPointerCapture(e.pointerId); });
    rs.addEventListener('pointermove', (e) => { if (!drag) return; const nw = Math.max(320, Math.min(window.innerWidth - 120, drag.w + e.clientX - drag.x)); d.style.setProperty('--cw', nw + 'px'); if (C.cm) C.cm.refresh(); });
    rs.addEventListener('pointerup', () => { if (drag) store('greffier.cw', String(Math.round(d.getBoundingClientRect().width))); drag = null; });
    if (typeof CodeMirror !== 'undefined') { C.cm = CodeMirror($('ced'), { mode: 'text/x-scala', lineNumbers: true, indentUnit: 2, tabSize: 2, indentWithTabs: false, extraKeys: { Tab: (cm) => cm.replaceSelection('  ', 'end') } }); C.cm.setSize('100%', '100%'); }
    else { C.ta = Object.assign(document.createElement('textarea'), { spellcheck: false, className: 'cfallback' }); $('ced').append(C.ta); }
  }
  const curText = () => (C.cm ? C.cm.getValue() : C.ta.value);
  function saveActive() { const f = C.files[C.active]; if (f && C.built) f.text = curText(); }
  function showFile(i) {
    saveActive(); C.active = Math.max(0, Math.min(C.files.length - 1, i)); const f = C.files[C.active];
    if (C.cm) { if (!f.doc) f.doc = CodeMirror.Doc(f.text, 'text/x-scala'); C.cm.swapDoc(f.doc); setTimeout(() => C.cm.refresh(), 0); } else C.ta.value = f.text;
    tabs();
  }
  function tabs() {
    const t = $('ctabs'); t.innerHTML = C.files.map((f, i) => `<span class="ctab${i === C.active ? ' on' : ''}" data-i="${i}" title="double-click to rename">${esc(f.name)}${C.files.length > 1 ? '<i data-x="' + i + '" title="delete this file">×</i>' : ''}</span>`).join('') + '<span class="ctab add" data-add="1" title="add a file">+</span>';
    t.onclick = (e) => { const x = e.target.closest('[data-x]'), a = e.target.closest('[data-add]'), s = e.target.closest('.ctab[data-i]');
      if (x) { saveActive(); C.files.splice(+x.dataset.x, 1); showFile(Math.min(C.active, C.files.length - 1)); }
      else if (a) { saveActive(); let n = C.files.length + 1, nm; do nm = `File${n++}.scala`; while (C.files.some((f) => f.name === nm)); C.files.push({ name: nm, text: '' }); showFile(C.files.length - 1); }
      else if (s) showFile(+s.dataset.i); };
    t.ondblclick = (e) => { const s = e.target.closest('.ctab[data-i]'); if (!s) return; const f = C.files[+s.dataset.i], nm = prompt('file name', f.name); if (nm && nm.trim()) { f.name = nm.trim(); tabs(); } };
  }
  function setFiles(files) { C.files = files.map((f) => ({ name: f.name, text: f.text })); C.active = 0; const f0 = C.files[0]; if (C.cm) { C.files.forEach((f) => { f.doc = CodeMirror.Doc(f.text, 'text/x-scala'); }); C.cm.swapDoc(f0.doc); setTimeout(() => C.cm.refresh(), 0); } else C.ta.value = f0.text; tabs(); }
  // prefill from the recording in the viewer (its sources), else the example
  function prefill() {
    const D = R.ST.D, us = D ? [...D.units.values()].filter((u) => u.text) : [];
    setFiles(us.length ? us.map((u) => ({ name: String(u.path || 'Unit.scala').replace(/^.*\//, ''), text: u.text })) : [{ name: 'Demo.scala', text: EXAMPLE }]); C.dirty = false;
  }
  function collapse(on) { C.collapsed = !!on; $('cdrawer').classList.toggle('collapsed', C.collapsed); $('ccollapse').textContent = C.collapsed ? '▸' : '▾'; if (!C.collapsed && C.cm) setTimeout(() => C.cm.refresh(), 0); }
  const isLocalPage = /^(localhost|127\.0\.0\.1|\[::1\])$/.test(location.hostname);
  const RUN_CMD = 'python3 local/replay/server/server.py --ui local/replay/ui';
  // the entry point exists where a server answers (or on a local page, where the drawer can say how to start one); the deployed site without a server shows nothing but a hint in the recordings panel
  async function probe() {
    const base = await resolve(); const b = $('btn-compile'); if (b) b.hidden = !(base != null || isLocalPage);
    R.compileHint = base != null ? '' : `Compile your own Scala here: needs the local compile server (<code>${RUN_CMD}</code>, see local/replay/server/README.md); none answers${isLocalPage ? '' : ' for this site'}.`; R.renderRecs && R.renderRecs();
  }
  document.addEventListener('DOMContentLoaded', () => { const b = $('btn-compile'); if (b) b.hidden = true; setTimeout(probe, 0); });
  async function fillVersions() {
    const base = await resolve(), sel = $('cver'), er = $('cerr');
    if (base == null) { er.hidden = false; er.innerHTML = `Compile server not reachable (tried ${API.tried.map((u) => '<code>' + esc(u) + '</code>').join(', ')}). Start it from the repository root:<br><code>${RUN_CMD}</code><br>(<a href="../server/README.md" target="_blank">local/replay/server/README.md</a>; with <code>--ui</code> the viewer is served by the same server. The address can be set in view options or with <code>api=</code> in the link.)`; sel.innerHTML = '<option>(no server)</option>'; $('cgo').disabled = true; return false; }
    er.hidden = true; $('cgo').disabled = false;
    const v = API.versions; sel.innerHTML = v.versions.map((x) => { const jp = x.joinPoints, tag = !x.ready ? ` (${esc(x.state || 'not ready')}${x.notes ? ': ' + esc(String(x.notes).slice(0, 60)) : ''})` : jp ? ` · ${jp.found}/${jp.total} join points${jp.found < jp.total ? ' · no JVM records' : ''}` : ''; return `<option value="${esc(x.scala)}"${x.ready ? '' : ' disabled'}>${esc(x.scala)}${tag}</option>`; }).join('');
    sel.value = C.ver && v.versions.some((x) => x.scala === C.ver && x.ready) ? C.ver : v.default; C.ver = sel.value; verNote(); return true;
  }
  const verInfo = () => (API.versions && API.versions.versions.find((x) => x.scala === $('cver').value)) || null;
  const noBytecode = (x) => !!(x && x.joinPoints && x.joinPoints.found < x.joinPoints.total && x.kind === 'released');
  function verNote() { let n = $('cvernote'); if (!n) { n = document.createElement('div'); n.id = 'cvernote'; n.className = 'dim'; $('cver').closest('.crow').after(n); } const x = verInfo(); n.textContent = noBytecode(x) ? "no bytecode: this version's backend hooks are not available (the tree, typer events and diagnostics are recorded)" : ''; n.hidden = !n.textContent; }
  R.compileToggle = async (on) => {
    build(); const d = $('cdrawer'); const show = on == null ? d.hidden : on;
    if (!show) { d.hidden = true; return; }
    d.hidden = false; if (!C.files.length || !C.dirty && !C.fromCompile) prefill(); collapse(false); if (C.cm) { C.cm.refresh(); C.cm.focus(); } fillVersions();
  };
  if (document.getElementById('btn-compile')) $('btn-compile').onclick = () => R.compileToggle();
  document.addEventListener('input', (e) => { if (e.target.closest && e.target.closest('#cdrawer')) C.dirty = true; });
  document.addEventListener('DOMContentLoaded', () => {
    const f = $('apiurl'); if (f) { f.value = hashApi() || store(LS) || ''; f.onchange = () => { store(LS, f.value.trim()); API.base = null; fillVersions(); R.refreshLocalRecs(); }; }
  });

  // ---- compile ---------------------------------------------------------------------------------------------------------------------------
  const opts = () => [...document.querySelectorAll('#copts [data-opt]')].filter((c) => c.checked).map((c) => c.dataset.opt).concat(($('cfree').value || '').split(/\s+/).filter(Boolean));
  async function go() {
    saveActive(); const base = await resolve(true); if (base == null) { fillVersions(); return; }
    const files = C.files.map((f) => ({ name: f.name, text: f.text })), label = $('clabel').value.trim() || files[0].name.replace(/\.scala$/, ''), body = { scala: $('cver').value, options: opts(), files, label };
    $('cgo').disabled = true; $('clog').hidden = false; $('clog').textContent = ''; $('cdiags').innerHTML = ''; setStat('queued…', 'q'); $('csum').textContent = '';
    let id; try { const r = await fetch(base + '/api/compile', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) }); if (!r.ok) throw new Error('HTTP ' + r.status); id = (await r.json()).id; }
    catch (e) { setStat('could not start the compile: ' + e.message, 'err'); $('cgo').disabled = false; return; }
    C.job = id; const t0 = performance.now();
    for (;;) {
      await new Promise((r) => setTimeout(r, 450)); let j; try { const r = await fetch(`${base}/api/compile/${id}`); j = await r.json(); } catch (e) { setStat('lost the server: ' + e.message, 'err'); break; }
      const sec = ((j.elapsedMs != null ? j.elapsedMs : performance.now() - t0) / 1000).toFixed(1);
      $('clog').textContent = (j.log || '').split('\n').slice(-10).join('\n'); $('clog').scrollTop = 1e9;
      if (j.status === 'queued' || j.status === 'running') { setStat(`${j.status} · ${sec} s`, 'q'); continue; }
      finished(j, id, body, sec); break;
    }
    $('cgo').disabled = false;
  }
  function setStat(t, cls) { const s = $('cstat'); s.textContent = t; s.className = cls || ''; }
  const sevCount = (ds, k) => ds.filter((d) => d.severity === k).length;
  function finished(j, id, body, sec) {
    const ds = j.diagnostics || [], nw = sevCount(ds, 'warning'), ne = sevCount(ds, 'error');
    const sum = `compiled ${body.scala} · ${sec} s · ${nw} warning${nw === 1 ? '' : 's'}${ne ? ' · ' + ne + ' error' + (ne > 1 ? 's' : '') : ''}`;
    diags(ds);
    if (j.status === 'error' || !j.recording) { setStat(`failed (exit ${j.exitCode ?? '?'}) · ${sec} s`, 'err'); $('csum').textContent = ''; return; }
    const nb = noBytecode((API.versions && API.versions.versions.find((x) => x.scala === body.scala)) || null), sum2 = sum + (nb ? ' · no bytecode' : '');
    setStat(sum2, 'ok'); $('csum').textContent = sum2; C.fromCompile = true; C.dirty = false;
    R.loadRecordingUrl(abs(j.recording), body.label, id).then(async () => { collapse(true); await R.refreshLocalRecs(); R.updateVariantUI && R.updateVariantUI(); });
  }
  function diags(ds) {
    $('cdiags').innerHTML = ds.length ? ds.map((d, i) => `<div class="cdiag ${esc(d.severity)}" data-i="${i}" title="select the node at ${esc(d.file)}:${d.line}${d.column != null ? ':' + (d.column + 1) : ''}"><b>${esc(d.severity)}</b>${d.kind ? ' <span class="dkind">' + esc(d.kind) + '</span>' : ''} ${esc(d.file)}:${d.line}${d.column != null ? ':' + (d.column + 1) : ''} ${esc(d.message)}</div>`).join('') : '<div class="dim">no diagnostics</div>';
    $('cdiags').onclick = (e) => { const x = e.target.closest('.cdiag'); if (x) selectDiag(ds[+x.dataset.i]); };
  }
  // file / line of a diagnostic -> the innermost node of that unit's parser version at the first token of the line
  function selectDiag(d) {
    const D = R.ST.D; if (!D) return; const u = [...D.units.values()].find((x) => String(x.path || '').replace(/^.*\//, '') === d.file) || [...D.units.values()][0]; if (!u || !u.text) return;
    const lines = u.text.split('\n'); let off = 0; for (let i = 0; i < Math.min(d.line - 1, lines.length); i++) off += lines[i].length + 1; { const ln = lines[Math.max(0, d.line - 1)] || ''; off += d.column != null ? Math.min(ln.length, Math.max(0, d.column)) : ln.length - ln.trimStart().length; } /* the server's column is 0-based */
    const pv = D.versionsOf(u.id).find((v) => D.phaseRaw(v.phase) === 'parser'); if (!pv) return; const W = R._s.viewOf(pv); let best = null;
    for (const o of W.offs) { if (o[1] > off) break; if (o[2] > off && (!best || o[2] - o[1] <= best[2] - best[1])) best = o; }
    R.set({ unit: u.id }); const k = R.vers().findIndex((v) => v === pv || (v.unit === u.id && D.phaseRaw(v.phase) === 'parser')); if (k >= 0) R.setPhase(k); if (best) R.set({ selNode: best[0] });
  }

  // ---- deep link: compile=<id> ------------------------------------------------------------------------------------------------------
  R.compileLink = async (id) => {
    build(); const base = await resolve(true);
    if (base == null) { R.compileToggle(true); R.notice('The compile server for this link is not reachable.', true); return; }
    try {
      const r = await fetch(`${base}/api/compile/${id}`); if (!r.ok) throw new Error('HTTP ' + r.status); const j = await r.json();
      if (j.status !== 'done' || !j.recording) throw new Error(`compile ${id} is ${j.status}`);
      await R.loadRecordingUrl(abs(j.recording), 'compile ' + id, id); await R.refreshLocalRecs(); R.updateVariantUI && R.updateVariantUI();
      $('cdrawer').hidden = false; prefill(); C.fromCompile = true; await fillVersions(); C.dirty = false; const ds = j.diagnostics || [], nw = sevCount(ds, 'warning'), ne = sevCount(ds, 'error');
      $('csum').textContent = `compile ${id} · ${j.scala || ''} · ${((j.elapsedMs || 0) / 1000).toFixed(1)} s · ${nw} warning${nw === 1 ? '' : 's'}${ne ? ' · ' + ne + ' error' + (ne > 1 ? 's' : '') : ''}`; diags(ds); collapse(true);
    } catch (e) { R.notice('Could not load compile ' + id + ': ' + e.message, true); R.compileToggle(true); }
  };
})(window.R = window.R || {});
