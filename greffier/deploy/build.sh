#!/usr/bin/env bash
# Builds deploy/site from ui/ and uploads large recordings to R2.
# Usage: build.sh [--upload] [--deploy]   (default: build only, no network)
# Written with LLM assistance (see LLM_POLICY.md).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
UI="$HERE/../ui"
SITE="$HERE/site"
BUCKET=greffier-recordings
ASSET_LIMIT=$((24 * 1024 * 1024))   # stay under the 25 MiB static-asset cap

rm -rf "$SITE"; mkdir -p "$SITE/data"
cp "$UI/index.html" "$UI/fixture.js" "$UI/fixture.jsonl" "$SITE/"
cp -R "$UI/js" "$SITE/js"
[ -f "$UI/TOURS.md" ] && cp "$UI/TOURS.md" "$SITE/"

# Content-hashed names so recordings can be cached immutably.
# recordings.json is regenerated from the hashed copies.
mkdir -p "$HERE/r2"
: > "$HERE/r2/manifest.txt"
for f in "$UI"/data/*.jsonl; do
  [ -e "$f" ] || continue
  base=$(basename "$f" .jsonl)
  sha=$(shasum -a 256 "$f" | cut -c1-8)
  name="$base.$sha.jsonl"
  size=$(stat -f %z "$f")
  if [ "$size" -le "$ASSET_LIMIT" ]; then
    ln -f "$f" "$SITE/data/$name" 2>/dev/null || cp "$f" "$SITE/data/$name"
  else
    gz="$HERE/r2/$name.gz"
    [ -s "$gz" ] || gzip -9 -c "$f" > "$gz"
    echo "$name $gz" >> "$HERE/r2/manifest.txt"
    # placeholder so gen_index.py sees it; the Worker serves the real one from R2
    ln -f "$f" "$SITE/data/$name" 2>/dev/null || cp "$f" "$SITE/data/$name"
  fi
done
# gen_index.py writes next to itself (ui/recordings.json); move the result and restore ui's own index.
(cd "$SITE" && python3 "$UI/gen_index.py" data >/dev/null && mv "$UI/recordings.json" "$SITE/recordings.json")
# urls come out relative to ui/index.html; make them relative to site/index.html
python3 - "$SITE/recordings.json" <<'EOF'
import json, sys, os
p = sys.argv[1]; d = json.load(open(p))
import re
for r in d:
    r['url'] = 'data/' + os.path.basename(r['url'])
    r['name'] = re.sub(r'\.[0-9a-f]{8}$', '', r['name'])   # drop the content hash from the display name
json.dump(d, open(p, 'w'), indent=1)
EOF
(cd "$UI" && python3 gen_index.py data >/dev/null)
# drop R2-bound placeholders from the asset set; with NO_R2=1 also drop them from the index
while read -r name gz; do rm -f "$SITE/data/$name"; done < "$HERE/r2/manifest.txt"
if [[ "${NO_R2:-}" == 1 ]]; then
  python3 - "$SITE/recordings.json" "$HERE/r2/manifest.txt" <<'EOF'
import json, sys
p, m = sys.argv[1], sys.argv[2]
big = {l.split()[0] for l in open(m) if l.strip()}
d = [r for r in json.load(open(p)) if r['url'].split('/')[-1] not in big]
json.dump(d, open(p, 'w'), indent=1)
EOF
fi
export XDG_CONFIG_HOME="${XDG_CONFIG_HOME:-$HOME/.config}"   # where wrangler login stored its token here

# Stack view PoC at /stack/: shares the viewer's loader; rewrite its two relative paths for the site layout.
STACK="$HERE/../stack"
if [ -d "$STACK" ]; then
  mkdir -p "$SITE/stack"
  cp "$STACK/index.html" "$STACK/NOTES.md" "$SITE/stack/"; cp -R "$STACK/js" "$SITE/stack/js"
  sed -i '' 's|src="../ui/js/loader.js"|src="../js/loader.js"|' "$SITE/stack/index.html"
  givens=$(ls "$SITE/data" | grep -E '^Givens\.[0-9a-f]{8}\.jsonl$' | head -1)
  sed -i '' "s|'../ui/data/Givens.jsonl'|'../data/$givens'|" "$SITE/stack/js/main.js"
fi

echo "site: $(du -sh "$SITE" | cut -f1); r2 objects: $(wc -l < "$HERE/r2/manifest.txt")"

if [[ "${1:-}" == "--upload" || "${2:-}" == "--upload" ]]; then
  while read -r name gz; do
    echo "r2 put $name"
    npx --yes wrangler r2 object put "$BUCKET/$name" --file "$gz" \
      --content-encoding gzip --content-type application/x-ndjson \
      --cache-control "public, max-age=31536000, immutable"
  done < "$HERE/r2/manifest.txt"
fi
if [[ "${1:-}" == "--deploy" || "${2:-}" == "--deploy" ]]; then
  (cd "$HERE" && npx --yes wrangler deploy)
fi
