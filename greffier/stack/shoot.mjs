// usage: node shoot.mjs [steps.json]  — drives headless Brave through CDP against http://localhost:8766/stack/ (1500x900),
// runs each step's JS (awaited), then writes shots/<name>.png. A step without `name` only runs JS and prints its result.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
const W = +(process.env.W || 1500), H = +(process.env.H || 900), PAGE = process.env.PAGE || 'stack/';
const steps = JSON.parse(fs.readFileSync(process.argv[2] || new URL('./steps.json', import.meta.url), 'utf8'));
const port = 9333 + Math.floor(Math.random() * 500);
const B = '/Applications/Brave Browser.app/Contents/MacOS/Brave Browser';
const args = ['--headless=new', '--hide-scrollbars', `--remote-debugging-port=${port}`, `--user-data-dir=/private/tmp/claude-501/brave-stack-${port}`, `--window-size=${W},${H}`, 'about:blank'];
if (process.env.NOGPU) args.unshift('--disable-gpu');
const p = spawn(B, args, { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
try {
  let tgt;
  for (let i = 0; i < 50; i++) { try { const r = await fetch(`http://127.0.0.1:${port}/json/new?${encodeURIComponent(`http://localhost:8766/${PAGE}`)}`, { method: 'PUT' }); tgt = await r.json(); break; } catch { await sleep(200); } }
  const ws = new WebSocket(tgt.webSocketDebuggerUrl); await new Promise((r) => (ws.onopen = r));
  let id = 0; const pend = new Map(), errs = [];
  ws.onmessage = (m) => { const d = JSON.parse(m.data); if (d.method === 'Runtime.exceptionThrown') errs.push(d.params.exceptionDetails.exception?.description || d.params.exceptionDetails.text); if (d.id && pend.has(d.id)) pend.get(d.id)(d); };
  const send = (method, params = {}) => new Promise((r) => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
  const ev = async (expr) => { const r = await send('Runtime.evaluate', { expression: `(async () => { ${expr} })()`, awaitPromise: true, returnByValue: true }); if (r.result?.exceptionDetails) throw new Error(r.result.exceptionDetails.exception?.description || 'eval failed'); return r.result?.result?.value; };
  await send('Runtime.enable'); await send('Page.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile: false });
  for (let i = 0; i < 80; i++) { if (await ev('return !!(window.__S && window.__S.cards && window.__S.cards.length && window.__stack.front)')) break; await sleep(250); }
  for (const s of steps) {
    const r = await ev(s.js || ''); if (r !== undefined) console.log(s.name || '-', '->', JSON.stringify(r));
    await sleep(s.wait ?? 900);
    if (s.name) { const shot = await send('Page.captureScreenshot', { format: 'png' }); fs.mkdirSync(new URL('./shots/', import.meta.url), { recursive: true }); fs.writeFileSync(new URL(`./shots/${s.name}.png`, import.meta.url), Buffer.from(shot.result.data, 'base64')); console.log('wrote shots/' + s.name + '.png'); }
  }
  if (errs.length) console.log('PAGE ERRORS:', errs.slice(0, 3).join(' | ').slice(0, 800));
  ws.close();
} finally { p.kill(); }
