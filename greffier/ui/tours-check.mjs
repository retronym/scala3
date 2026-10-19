// Opens every tour URL listed below in headless Brave and prints the displayed phase, cursor and unit. usage: node tours-check.mjs
import { execFileSync } from 'node:child_process';
const T = {
  A: ['data/Givens.jsonl', ['pname=parser', 'pname=typer', 'pname=typer&seq=334', 'pname=typer&seq=363', 'pname=typer&seq=414', 'pname=erasure']],
  B: ['data/ForDesugar.jsonl', ['pname=parser', 'pname=typer', 'pname=elimRepeated', 'pname=interpolators', 'pname=vcInlineMethods', 'pname=erasure', 'pname=mixin', 'pname=lambdaLift']],
  C: ['data/Mixins.jsonl', ['pname=typer', 'pname=getters', 'pname=resolveSuper', 'pname=erasure', 'pname=mixin', 'pname=constructors']],
};
for (const [k, [src, steps]] of Object.entries(T)) steps.forEach((s, i) => {
  const out = execFileSync('node', ['shoot.mjs', `/private/tmp/claude-501/tour-${k}${i + 1}.png`, `src=${src}&skip=1&collapse=1&${s}`, 'JSON.stringify([R.ST.D.phaseName(R.ver().phase), R.seq(), document.getElementById("text").innerText.slice(0,60).replace(/\\n/g," "), document.getElementById("verinfo").innerText.includes("in progress")])'], { encoding: 'utf8', env: process.env });
  console.log(k + (i + 1), s, '->', (out.match(/js -> (.*)/) || [])[1]);
});
