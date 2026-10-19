// Page wiring: loading (default recording, file picker, unit selector), the depth minimap, the lineage chronicle, the HUD.
import { loadUrl, loadFile, buildCards, unitLabel } from './model.js';
import { Stack, HOW_COL, HOW_TXT } from './stack.js';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const DEFAULT = '../ui/data/Givens.jsonl';
const S = { D: null, unit: null, cards: [], list: [], perf: {} };

const stack = new Stack($('stage'), {
  onList: (list) => { S.list = list; drawMini(); },
  onFront: (c) => { document.querySelectorAll('#chron .ce').forEach((e) => e.classList.toggle('cur', c && +e.dataset.i === c.i)); $('front').innerHTML = c ? `<b>${c.i + 1}/${S.cards.length}</b> ${esc(c.name)}${c.noop ? ' <span class=dimt>(no change)</span>' : ''}` : ''; },
  onTrace: (tr) => { renderChron(tr); drawMini(); },
  onFrame: (u, sMax) => { const h = $('mini').clientHeight; $('mark').style.transform = `translateY(${(sMax ? u / sMax : 0) * (h - 16) + 8}px)`; },
});
window.__stack = stack; window.__S = S;

// ---- loading ------------------------------------------------------------------------------------------------------------
async function load(src, name) {
  $('status').textContent = `loading ${name}…`;
  const t0 = performance.now();
  try {
    const D = typeof src === 'string' ? await loadUrl(src, (n, got, tot) => { $('status').textContent = `loading ${n} ${tot ? Math.round((100 * got) / tot) + '%' : (got >> 10) + ' KB'}`; }) : await loadFile(src);
    S.perf.loadMs = performance.now() - t0; S.D = D; S.name = name;
    const units = D.unitsWithVersions.filter((u) => D.versionsOf(u).some((v) => !v.ir));
    $('unit').innerHTML = units.map((u) => `<option value="${u}">${esc(unitLabel(D, u))} (${D.versionsOf(u).filter((v) => !v.ir).length})</option>`).join('');
    $('unit').disabled = units.length < 2;
    setUnit(units[0]);
  } catch (e) { $('status').textContent = 'load failed: ' + e.message; throw e; }
}
function setUnit(u) {
  S.unit = u; const r = buildCards(S.D, u); S.cards = r.cards; S.perf.buildMs = r.ms;
  stack.setData(S.D, S.cards); renderChron(null);
  const nn = S.cards.filter((c) => c.noop).length, ns = S.cards.filter((c) => c.star).length;
  $('status').textContent = `${S.name} · ${unitLabel(S.D, u)} · ${S.cards.length} versions (${nn} no-op, ${ns} ★) · load ${S.perf.loadMs.toFixed(0)} ms, diffs ${r.ms.toFixed(0)} ms`;
}
$('unit').addEventListener('change', (e) => setUnit(+e.target.value));
$('file').addEventListener('change', (e) => { const f = e.target.files[0]; if (f) load(f, f.name); });
$('skip').addEventListener('change', (e) => { stack.setSkipNoop(e.target.checked); });

// ---- minimap ----------------------------------------------------------------------------------------------------------------
function drawMini() {
  const cv = $('minic'), box = $('mini'), dpr = devicePixelRatio || 1, w = box.clientWidth, h = box.clientHeight;
  cv.width = w * dpr; cv.height = h * dpr; cv.style.width = w + 'px'; cv.style.height = h + 'px';
  const g = cv.getContext('2d'); g.scale(dpr, dpr); g.clearRect(0, 0, w, h);
  const list = S.list; if (!list.length) return;
  const sMax = stack.sMax || 1, mx = Math.max(1, ...list.map((c) => c.add + c.del)), y = (c) => (c.s / sMax) * (h - 16) + 8;
  g.font = '10px ui-monospace, Menlo, monospace'; g.textBaseline = 'middle';
  const tr = stack.tr;
  for (const c of list) {
    const yy = y(c), amt = (c.add + c.del) / mx;
    g.fillStyle = c.star ? '#ffd666' : c.noop ? 'rgba(140,160,190,0.25)' : 'rgba(170,190,220,0.6)';
    const len = c.noop ? 6 : 10 + 30 * Math.sqrt(amt);
    g.fillRect(w - 8 - len, yy - (c.star ? 1 : 0.5), len, c.star ? 2 : 1);
    if (c.star) { g.fillStyle = '#ffd666'; g.textAlign = 'left'; g.fillText('★', 2, yy); }
    const t = tr && tr.per[c.i];
    if (t && t.how && t.how !== 'same' && t.how !== 'copy') { g.fillStyle = HOW_COL[t.how]; g.beginPath(); g.arc(w - 4, yy, 2.5, 0, 7); g.fill(); }
    else if (t && t.how) { g.fillStyle = 'rgba(111,129,150,0.8)'; g.fillRect(w - 5, yy - 0.5, 2, 1); }
  }
}
{
  const box = $('mini'); let drag = false;
  const at = (e) => { const r = box.getBoundingClientRect(); const f = Math.max(0, Math.min(1, (e.clientY - r.top - 8) / (r.height - 16))); stack.jumpTo(f * stack.sMax, false); };
  box.addEventListener('pointerdown', (e) => { drag = true; box.setPointerCapture(e.pointerId); at(e); });
  box.addEventListener('pointermove', (e) => {
    if (drag) at(e);
    const r = box.getBoundingClientRect(), u = ((e.clientY - r.top - 8) / (r.height - 16)) * stack.sMax, c = stack.nearest(u);
    box.title = c ? `${c.i + 1}. ${c.name}${c.noop ? ' (no change)' : ` +${c.add} −${c.del}`}` : '';
  });
  box.addEventListener('pointerup', () => { drag = false; });
  addEventListener('resize', drawMini);
}

// ---- lineage chronicle ----------------------------------------------------------------------------------------------------
function renderChron(tr) {
  const box = $('chron');
  if (!tr) { box.hidden = true; return; }
  const ev = []; tr.per.forEach((t, i) => { if (t.how && t.how !== 'same' && t.how !== 'copy') ev.push({ i, t }); });
  const life = tr.per.filter((t) => t.set.size).length;
  box.hidden = false;
  box.innerHTML = `<div class=ch><b>${esc(tr.kind)}</b> #${tr.node} <span class=dimt>in ${life}/${tr.per.length} versions · trace ${tr.ms.toFixed(1)} ms</span><button id=chx title="Esc">✕</button></div>`
    + ev.map(({ i, t }) => { const c = S.cards[i]; const l = t.line >= 0 ? c.lines[t.line].trim() : '';
      return `<div class=ce data-i="${i}"><span class=hw style="color:${HOW_COL[t.how]}">${HOW_TXT[t.how]}</span><span class=cp>${esc(c.short)}</span><code>${esc(l.slice(0, 90))}</code></div>`; }).join('');
  box.querySelector('#chx').onclick = () => stack.clearTrace();
  box.querySelectorAll('.ce').forEach((e) => { e.onclick = () => { const c = S.cards[+e.dataset.i]; if (stack.skipNoop && c.noop) return; stack.flyTo(c); }; });
}

// ---- HUD ------------------------------------------------------------------------------------------------------------------
setInterval(() => {
  const f = stack.frames.slice(-60); if (!f.length) return;
  const avg = f.reduce((a, b) => a + b, 0) / f.length, worst = Math.max(...f);
  $('fps').textContent = `${(1000 / avg).toFixed(0)} fps · worst ${worst.toFixed(0)} ms · work ${(stack.lastFrameWork || 0).toFixed(1)} ms · text cards ${stack.all ? stack.all.filter((c) => c.txt).length : 0}`;
}, 500);

const qs = new URLSearchParams(location.search);
load(qs.get('src') || DEFAULT, (qs.get('src') || DEFAULT).split('/').pop());
