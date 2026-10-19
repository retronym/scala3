// usage: node shoot.mjs out.png 'hash' ['js to run after load'] [W H]
// Drives headless Brave through CDP; waits until the page reports a loaded dataset. Needs the static server (PORT, PAGE).
import { spawn } from 'node:child_process';
import fs from 'node:fs';
const [out, hash, js, W = '1600', H = '1000'] = process.argv.slice(2);
const port = 9333 + Math.floor(Math.random() * 500), PORT = process.env.PORT || 8766, PAGE = process.env.PAGE || 'ui/index.html';
const B = '/Applications/Brave Browser.app/Contents/MacOS/Brave Browser';
const p = spawn(B, ['--headless=new', '--disable-gpu', '--hide-scrollbars', `--remote-debugging-port=${port}`, `--user-data-dir=/private/tmp/claude-501/brave-${port}`, 'about:blank'], { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
try {
  let tgt;
  for (let i = 0; i < 50; i++) { try { const r = await fetch(`http://127.0.0.1:${port}/json/new?${encodeURIComponent(`http://localhost:${PORT}/${PAGE}#${hash}`)}`, { method: 'PUT' }); tgt = await r.json(); break; } catch { await sleep(200); } }
  const ws = new WebSocket(tgt.webSocketDebuggerUrl); await new Promise((r) => (ws.onopen = r));
  let id = 0; const pend = new Map(); const errs = []; ws.onmessage = (m) => { const d = JSON.parse(m.data); if (d.method === 'Runtime.exceptionThrown') errs.push((d.params.exceptionDetails.exception || {}).description || d.params.exceptionDetails.text); if (d.id && pend.has(d.id)) pend.get(d.id)(d); };
  const send = (method, params = {}) => new Promise((r) => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
  const ev = async (expr) => (await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })).result?.result?.value;
  await send('Runtime.enable'); await send('Emulation.setDeviceMetricsOverride', { width: +W, height: +H, deviceScaleFactor: 1, mobile: false });
  const READY = `!!(window.R && ((R.ST && R.ST.D && R.lastRender && R.hashApplied) || (!location.hash.includes('src=') && !document.getElementById('recs').hidden && document.getElementById('reclist').children.length > 1)))`;
  for (let i = 0; i < 120; i++) { if (await ev(READY)) break; if (errs.length && i > 8) break; await sleep(250); }
  if (errs.length) console.log('PAGE ERRORS:', errs.slice(0, 3).join(' | ').slice(0, 800));
  await sleep(500);
  if (js) { const r = await ev(js); if (r !== undefined) console.log('js ->', JSON.stringify(r)); await sleep(600); }
  const shot = await send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(out, Buffer.from(shot.result.data, 'base64')); console.log('wrote', out);
  ws.close();
} finally { p.kill(); }
