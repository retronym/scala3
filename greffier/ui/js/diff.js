// Shared line diff of printed versions. One implementation serves the heat strip / phases table (scores.js) and the stack overlay:
// rows are {k: 's' same | 'a' added | 'd' removed (ghost), t: text, nl: line index in the new text or -1}. Common prefix and suffix are
// trimmed, the middle is an LCS when small and a greedy monotone match when large (keeps 5k-line versions in the millisecond range).
(function (R) {
  const LCS_MAX = 1.5e6;
  // emits matched index pairs (i in A, j in B) in increasing order for A[a0,a1) vs B[b0,b1)
  function patience(A, a0, a1, B, b0, b1, emit) {
    while (a0 < a1 && b0 < b1 && A[a0] === B[b0]) { emit(a0++, b0++); }
    const tail = []; while (a0 < a1 && b0 < b1 && A[a1 - 1] === B[b1 - 1]) { a1--; b1--; tail.push([a1, b1]); }
    const n = a1 - a0, m = b1 - b0;
    if (n > 0 && m > 0) {
      if (n * m <= LCS_MAX / 4) {   // small gap: plain LCS
        const W = m + 1, T = new Uint16Array((n + 1) * W);
        for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) T[i * W + j] = A[a0 + i] === B[b0 + j] ? T[(i + 1) * W + j + 1] + 1 : Math.max(T[(i + 1) * W + j], T[i * W + j + 1]);
        let i = 0, j = 0; while (i < n && j < m) { if (A[a0 + i] === B[b0 + j]) { emit(a0 + i, b0 + j); i++; j++; } else if (T[(i + 1) * W + j] >= T[i * W + j + 1]) i++; else j++; }
      } else {
        const ca = new Map(), cb = new Map();
        for (let i = a0; i < a1; i++) { const t = A[i]; ca.set(t, ca.has(t) ? -1 : i); }
        for (let j = b0; j < b1; j++) { const t = B[j]; cb.set(t, cb.has(t) ? -1 : j); }
        const cand = []; for (let j = b0; j < b1; j++) { const t = B[j]; if (!t.trim()) continue; const i = ca.get(t); if (i != null && i >= 0 && cb.get(t) === j) cand.push([i, j]); }
        // longest increasing subsequence of the candidates by i (they are already ordered by j)
        const tails = [], prev = new Array(cand.length).fill(-1), tIdx = [];
        for (let k = 0; k < cand.length; k++) { let lo = 0, hi = tails.length; while (lo < hi) { const mid = (lo + hi) >> 1; if (cand[tIdx[mid]][0] < cand[k][0]) lo = mid + 1; else hi = mid; } if (lo > 0) prev[k] = tIdx[lo - 1]; tIdx[lo] = k; tails[lo] = cand[k][0]; }
        const lis = []; for (let k = tIdx.length ? tIdx[tIdx.length - 1] : -1; k >= 0; k = prev[k]) lis.push(cand[k]); lis.reverse();
        if (!lis.length) { /* nothing unique: leave the gap unmatched */ }
        else { let pi = a0, pj = b0; for (const [i, j] of lis) { patience(A, pi, i, B, pj, j, emit); emit(i, j); pi = i + 1; pj = j + 1; } patience(A, pi, a1, B, pj, b1, emit); }
      }
    }
    for (let k = tail.length - 1; k >= 0; k--) emit(tail[k][0], tail[k][1]);
  }
  R.diffRows = function (A, B) {
    const rows = []; let p = 0;
    while (p < A.length && p < B.length && A[p] === B[p]) p++;
    let ea = A.length, eb = B.length; while (ea > p && eb > p && A[ea - 1] === B[eb - 1]) { ea--; eb--; }
    for (let i = 0; i < p; i++) rows.push({ k: 's', t: B[i], nl: i });
    const n = ea - p, m = eb - p;
    if (n * m > LCS_MAX) {   // patience diff: anchor on lines that are unique on both sides (longest increasing run), recurse into the gaps
      const pair = new Array(m).fill(-1); patience(A, p, ea, B, p, eb, (i, j) => { pair[j - p] = i - p; });
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
