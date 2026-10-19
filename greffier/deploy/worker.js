// Serves /data/<name>.jsonl: small recordings come from static assets, large ones
// from R2 where they are stored pre-gzipped (content-encoding: gzip metadata).
// Written with LLM assistance (see LLM_POLICY.md).
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (!url.pathname.startsWith('/data/')) return env.ASSETS.fetch(request);

    // Prefer the static asset (fast path for files under the 25 MiB asset limit).
    const asset = await env.ASSETS.fetch(request);
    if (asset.status !== 404) return asset;

    // Stale deep link to a previous content hash: redirect to the current file for that base name.
    const m = /^\/data\/(.+?)(?:\.[0-9a-f]{8})?\.jsonl$/.exec(url.pathname); // stale hash or unhashed base name
    if (m) {
      const idx = await env.ASSETS.fetch(new Request(new URL('/recordings.json', url)));
      if (idx.ok) {
        const base = m[1];
        const hit = (await idx.json()).flatMap((r) => [r, ...(r.variants || [])])
          .map((r) => r.url).find((u) => u && u.startsWith('data/' + base + '.'));
        if (hit && '/' + hit !== url.pathname) return Response.redirect(new URL('/' + hit, url), 302);
      }
    }

    if (!env.RECORDINGS) return asset; // R2 not enabled on this account yet
    const key = decodeURIComponent(url.pathname.slice('/data/'.length));
    const head = request.method === 'HEAD';
    const obj = head ? await env.RECORDINGS.head(key) : await env.RECORDINGS.get(key);
    if (!obj) return new Response('not found', { status: 404 });

    const h = new Headers();
    obj.writeHttpMetadata(h); // content-type, content-encoding, cache-control as uploaded
    h.set('etag', obj.httpEtag);
    h.set('accept-ranges', 'none'); // encoded body; byte ranges would be wrong
    if (!h.has('cache-control')) h.set('cache-control', 'public, max-age=31536000, immutable');
    // The viewer reads content-length to choose text vs streaming; with a gzip body
    // the browser hides the length anyway, so streaming is what it gets. Fine.
    return new Response(head ? null : obj.body, { headers: h });
  },
};
