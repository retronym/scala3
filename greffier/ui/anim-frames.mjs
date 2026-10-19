// usage: node anim-frames.mjs outdir 'hash' 'js that triggers the transition' [frames] [intervalMs]
// Captures frames of the scrubber pane (CDP screencast by repeated captureScreenshot) at 0.25x speed, for a filmstrip.
import { spawn } from 'node:child_process';
import fs from 'node:fs';
const [dir, hash, trigger, N = '8', IV = '140'] = process.argv.slice(2);
const port = 9333 + Math.floor(Math.random() * 500), PORT = process.env.PORT || 8766;
const p = spawn('/Applications/Brave Browser.app/Contents/MacOS/Brave Browser', ['--headless=new', '--disable-gpu', '--hide-scrollbars', `--remote-debugging-port=${port}`, `--user-data-dir=/private/tmp/claude-501/brave-${port}`, 'about:blank'], { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
try {
  let tgt; for (let i = 0; i < 50; i++) { try { tgt = await (await fetch(`http://127.0.0.1:${port}/json/new?${encodeURIComponent(`http://localhost:${PORT}/ui/index.html#${hash}`)}`, { method: 'PUT' })).json(); break; } catch { await sleep(200); } }
  const ws = new WebSocket(tgt.webSocketDebuggerUrl); await new Promise((r) => (ws.onopen = r));
  let id = 0; const pend = new Map(); ws.onmessage = (m) => { const d = JSON.parse(m.data); if (d.id && pend.has(d.id)) pend.get(d.id)(d); };
  const send = (method, params = {}) => new Promise((r) => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
  const ev = async (expr) => (await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })).result?.result?.value;
  await send('Emulation.setDeviceMetricsOverride', { width: 1400, height: 900, deviceScaleFactor: 1, mobile: false });
  for (let i = 0; i < 400; i++) { if (await ev('!!(window.R && R.ST && R.ST.D && R.lastRender && R.hashApplied)')) break; await sleep(250); }
  await sleep(500);
  await ev('(()=>{const s=document.getElementById("animspeed"); s.value="4"; return 1})()');
  const clip = await ev('(()=>{const r=document.getElementById("text").parentNode.getBoundingClientRect(); return {x:r.left,y:r.top,width:Math.min(r.width,560),height:Math.min(r.height,420),scale:1}})()');
  fs.mkdirSync(dir, { recursive: true });
  const f0 = await send('Page.captureScreenshot', { format: 'png', clip }); fs.writeFileSync(`${dir}/f0.png`, Buffer.from(f0.result.data, 'base64'));
  await ev(trigger);
  for (let i = 1; i <= +N; i++) { const f = await send('Page.captureScreenshot', { format: 'png', clip }); fs.writeFileSync(`${dir}/f${i}.png`, Buffer.from(f.result.data, 'base64')); await sleep(+IV); }
  console.log('frames', +N + 1, JSON.stringify(await ev('R.animStats')));
  ws.close();
} finally { p.kill(); }
