#!/usr/bin/env bash
# LLM-assisted (Claude), see LLM_POLICY.md.
# Records the corpus (default and -opt -opt-inline:<sources>) and dotc/util into ../out, then checks:
# schema validation, tree->jvm lineage checks, reproducibility, and that .class/.tasty output is
# identical with JVM recording on and off (and, for the corpus, with and without the plugin).
set -uo pipefail
source "$(dirname "$0")/env.sh"
cd "$REPLAY_HOME/.."
S=${SCRATCH:-$(mktemp -d)}
OPT=(-opt '-opt-inline:<sources>')
for f in Closures ForDesugar Givens Mixins Patmat; do
  for mode in "" -opt; do
    extra=(); [ "$mode" = -opt ] && extra=("${OPT[@]}")
    d=$S/v/$f$mode; rm -rf "$d"; mkdir -p "$d"/{rec,nojvm,plain}
    CLASSES=$d/rec plugin/record.sh out/$f$mode.jsonl corpus/$f.scala -- "${extra[@]}" >/dev/null 2>&1 || echo "FAIL record $f$mode"
    CLASSES=$d/nojvm plugin/record.sh $d/nojvm.jsonl corpus/$f.scala -- "${extra[@]}" -P:replay-record:nojvm >/dev/null 2>&1
    dotc "${extra[@]}" -d $d/plain corpus/$f.scala >/dev/null 2>&1
    plugin/record.sh $d/again.jsonl corpus/$f.scala -- "${extra[@]}" >/dev/null 2>&1
    r1=$(diff -rq $d/rec $d/nojvm >/dev/null && echo same || echo DIFF)
    r2=$(diff -rq $d/rec $d/plain >/dev/null && echo same || echo DIFF)
    r3=$(cmp -s <(tail -n +2 out/$f$mode.jsonl) <(tail -n +2 $d/again.jsonl) && echo ok || echo DIFF)
    v=$(python3 plugin/analyze.py out/$f$mode.jsonl | head -1 | sed 's/.*errors=/errors=/')
    c=$(python3 plugin/jvmcheck.py out/$f$mode.jsonl --opt | tail -1 | sed 's/^ *//')
    echo "$f$mode: bytecode jvm/nojvm $r1, plugin/plain $r2; repro $r3; $v; $c"
  done
done
python3 plugin/jvmcheck.py out/Givens.jsonl --givens | tail -7
SCRATCH=$S plugin/util-run.sh "" | tail -3
SCRATCH=$S plugin/util-run.sh -opt "${OPT[@]}" | tail -3
for u in util util-opt; do python3 plugin/analyze.py out/$u.jsonl | grep -E "errors=|jvm:"; done
