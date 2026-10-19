#!/usr/bin/env bash
# LLM-assisted (Claude), see LLM_POLICY.md.
# Acceptance experiment: record the corpus and dotc/util with the fork and with stock+agent, and
# compare the streams after the `meta` line (which holds the output path and compiler git hash).
# usage: compare.sh [agent opts, default fixTypeApply]   outputs in ../out-fork, ../out-agent[-<opts>]
set -uo pipefail
R=$(cd "$(dirname "$0")/.." && pwd)
OPTS=${1-fixTypeApply}
OUTA=$R/out-agent${OPTS:+-${OPTS//,/-}}; [ "$OPTS" = fixTypeApply ] && OUTA=$R/out-agent
mkdir -p "$R/out-fork" "$OUTA"
UTIL=$(ls "$R"/../../compiler/src/dotty/tools/dotc/util/*.scala)
# util is compiled against the stock compiler classes in both runs, so the inputs are the same
UCP=$(REPLAY_VARIANT=stock; source "$R/plugin/env.sh"; echo "$DOTC_CP")
run() { # variant out srcs... -- args
  local v=$1 out=$2; shift 2
  REPLAY_VARIANT=$v REPLAY_AGENT_OPTS=$OPTS "$R/plugin/record.sh" "$out" "$@" >/dev/null 2>&1 || echo "  ($v exit $?)"
}
for f in Closures ForDesugar Givens Mixins Patmat; do
  [ -n "${SKIP_FORK:-}" ] || run fork "$R/out-fork/$f.jsonl" "$R/corpus/$f.scala"
  run agent "$OUTA/$f.jsonl" "$R/corpus/$f.scala"
done
[ -n "${SKIP_FORK:-}" ] || DOTC_USER_CP=$UCP run fork "$R/out-fork/util.jsonl" $UTIL -- -Yexplicit-nulls -nowarn
DOTC_USER_CP=$UCP run agent "$OUTA/util.jsonl" $UTIL -- -Yexplicit-nulls -nowarn
for f in Closures ForDesugar Givens Mixins Patmat util; do
  a=$R/out-fork/$f.jsonl; b=$OUTA/$f.jsonl
  if cmp -s <(tail -n +2 "$a") <(tail -n +2 "$b"); then r=identical
  else r="DIFFER: $(diff <(tail -n +2 "$a") <(tail -n +2 "$b") | grep -c '^[<>]') lines"; fi
  printf '%-10s %9d %9d  %s\n' "$f" "$(wc -c < "$a")" "$(wc -c < "$b")" "$r"
done
