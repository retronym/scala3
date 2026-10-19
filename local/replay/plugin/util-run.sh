#!/usr/bin/env bash
# LLM-assisted (Claude), see LLM_POLICY.md.
# usage: util-run.sh <tag> [extra dotc args]
# Records compiler/src/dotty/tools/dotc/util/*.scala (one compile, -Yexplicit-nulls) to out/util<tag>.jsonl,
# and compiles the same plainly; compares bytecode and prints wall times.
set -euo pipefail
source "$(dirname "$0")/env.sh"
tag=$1; shift
S=${SCRATCH:-$(mktemp -d)}
OUT=$REPLAY_HOME/../out
SRC=$(cd "$REPLAY_HOME/../../.." && pwd)/compiler/src/dotty/tools/dotc/util
rm -rf "$S/util$tag"; mkdir -p "$S/util$tag/rec" "$S/util$tag/plain"
t0=$(date +%s.%N 2>/dev/null || python3 -c 'import time;print(time.time())')
dotc -Yexplicit-nulls -classpath "$DOTC_CP" "$@" -d "$S/util$tag/plain" "$SRC"/*.scala > "$S/util$tag/plain.log" 2>&1
t1=$(python3 -c 'import time;print(time.time())')
CLASSES="$S/util$tag/rec" "$REPLAY_HOME/record.sh" "$OUT/util$tag.jsonl" "$SRC"/*.scala -- -Yexplicit-nulls -classpath "$DOTC_CP" "$@" > "$S/util$tag/rec.log" 2>&1
t2=$(python3 -c 'import time;print(time.time())')
echo "wall: plain $(python3 -c "print(round($t1-$t0,1))")s, record $(python3 -c "print(round($t2-$t1,1))")s"
diff -rq "$S/util$tag/rec" "$S/util$tag/plain" && echo "bytecode identical" || echo "bytecode DIFFERS (above)"
