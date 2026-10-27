#!/usr/bin/env bash
# Vendors the production sources of scala-sharing (https://github.com/retronym/scala-sharing) into sharing/src,
# renaming its package io.github.retronym.sharing to dotty.tools.sharing. Usage: vendorSharing.sh <scala-sharing checkout>
set -euo pipefail

upstream=${1:?usage: vendorSharing.sh <scala-sharing checkout>}
here=$(cd "$(dirname "$0")/../.." && pwd)
src="$upstream/src/io/github/retronym/sharing"
dest="$here/sharing/src/dotty/tools/sharing"

[ -d "$src" ] || { echo "no $src" >&2; exit 1; }
commit=$(git -C "$upstream" rev-parse HEAD)
if [ -n "$(git -C "$upstream" status --porcelain -- src)" ]; then
  echo "warning: $upstream/src has uncommitted changes; recording $commit anyway" >&2
fi

rm -rf "$dest"
mkdir -p "$dest"
for f in "$src"/*; do
  sed -e 's/io\.github\.retronym\.sharing/dotty.tools.sharing/g' "$f" > "$dest/$(basename "$f")"
done

cat > "$here/sharing/VENDORED.md" <<NOTE
# Vendored from scala-sharing

\`src/dotty/tools/sharing\` is the production code of <https://github.com/retronym/scala-sharing> at commit \`$commit\`, with the package \`io.github.retronym.sharing\` renamed to \`dotty.tools.sharing\`. Its tests, README and build are not vendored.

Change it upstream and re-vendor with \`project/scripts/vendorSharing.sh <scala-sharing checkout>\`.
NOTE
echo "vendored $commit into $dest"
