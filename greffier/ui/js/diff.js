// Shared line diff of printed versions. One implementation serves the heat strip / phases table (scores.js) and the stack overlay:
// rows are {k: 's' same | 'a' added | 'd' removed (ghost), t: text, nl: line index in the new text or -1}. Common prefix and suffix are
// trimmed, the middle is an LCS when small and a greedy monotone match when large (keeps 5k-line versions in the millisecond range).
(function (R) {
  const LCS_MAX = 1.5e6;
  R.diffRows = function (A, B) {
    const rows = []; let p = 0;
    while (p < A.length && p < B.length && A[p] === B[p]) p++;
    let ea = A.length, eb = B.length; while (ea > p && eb > p && A[ea - 1] === B[eb - 1]) { ea--; eb--; }
    for (let i = 0; i < p; i++) rows.push({ k: 's', t: B[i], nl: i });
    const n = ea - p, m = eb - p;
    if (n * m > LCS_MAX) {
      const q = new Map(); for (let i = 0; i < n; i++) { const t = A[p + i]; if (!t.trim()) continue; let l = q.get(t); if (!l) q.set(t, (l = [])); l.push(i); }
      const used = new Set(), pair = new Array(m).fill(-1); let last = -1;
      for (let j = 0; j < m; j++) { const l = q.get(B[p + j]); const k = l && l.find((i) => i > last && !used.has(i)); if (k != null) { used.add(k); last = k; pair[j] = k; } }
      let i = 0;
      for (let j = 0; j < m; j++) {
        if (pair[j] >= 0) { while (i < pair[j]) { rows.push({ k: 'd', t: A[p + i], nl: -1 }); i++; } rows.push({ k: 's', t: B[p + j], nl: p + j }); i = pair[j] + 1; }
        else rows.push({ k: 'a', t: B[p + j], nl: p + j });
      }
      while (i < n) { rows.push({ k: 'd', t: A[p + i], nl: -1 }); i++; }
    } else if (n || m) {
      const W = m + 1, T = new Uint16Array((n + 1) * W);
      for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) T[i * W + j] = A[p + i] === B[p + j] ? T[(i + 1) * W + j + 1] + 1 : Math.max(T[(i + 1) * W + j], T[i * W + j + 1]);
      let i = 0, j = 0;
      while (i < n || j < m) {
        if (i < n && j < m && A[p + i] === B[p + j]) { rows.push({ k: 's', t: B[p + j], nl: p + j }); i++; j++; }
        else if (i < n && (j >= m || T[(i + 1) * W + j] >= T[i * W + j + 1])) { rows.push({ k: 'd', t: A[p + i], nl: -1 }); i++; }
        else { rows.push({ k: 'a', t: B[p + j], nl: p + j }); j++; }
      }
    }
    for (let i = eb; i < B.length; i++) rows.push({ k: 's', t: B[i], nl: i });
    return rows;
  };
  // lines of a version's printed text, cached on the version (the text getter returns the same string for versions that share it)
  R.linesOf = (v) => { const t = v.text; if (v._lns && v._lnsT === t) return v._lns; v._lns = t.split('\n'); v._lnsT = t; return v._lns; };
  // diff of version v against `prev` (null: no diff), cached per (v, prev) pair; counts are of rows, blank lines included as in the text
  R.versionDiff = (v, prev) => {
    if (!v._vd) v._vd = new Map(); let r = v._vd.get(prev || null); if (r) return r;
    const B = R.linesOf(v);
    if (!prev || prev.text === v.text) { const rows = B.map((t, nl) => ({ k: 's', t, nl })); r = { rows, add: 0, del: 0, same: !!prev }; }
    else { const rows = R.diffRows(R.linesOf(prev), B); let add = 0, del = 0; rows.forEach((x) => { if (x.k === 'a') add++; else if (x.k === 'd') del++; }); r = { rows, add, del, same: false }; }
    v._vd.set(prev || null, r); return r;
  };
  // added / removed counts only (for instruction lines etc.): rows are not kept
  R.countDiff = (a, b, keep) => { let add = 0, del = 0; R.diffRows(a.split('\n'), b.split('\n')).forEach((x) => { if (x.k === 's' || (keep && !keep(x.t))) return; if (x.k === 'a') add++; else del++; }); return { add, del }; };
})(window.R = window.R || {});
