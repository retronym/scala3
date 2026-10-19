#!/usr/bin/env bash
# LLM-assisted (Claude), see LLM_POLICY.md.
# Crude timings (not JMH; machine not quiet). Writes ../bench-results.txt.
#  A: recording on, fork vs stock+agent: wall clock per compile (fresh JVM), corpus and dotc/util.
#  B: no recorder, stock vs stock+agent vs fork: 10 compiles of dotc/util in one JVM.
set -uo pipefail
B=$(cd "$(dirname "$0")" && pwd); R=$B/../..
REPS=${REPS:-5}; ITERS=${ITERS:-10}
UTIL=$(ls "$R"/../../compiler/src/dotty/tools/dotc/util/*.scala)
UCP=$(REPLAY_VARIANT=stock; source "$R/plugin/env.sh"; echo "$DOTC_CP")
S=$(mktemp -d); mkdir -p "$S/cls"; OUT=$R/agent/bench-results.txt
source "$R/plugin/env.sh"; "$JAVA_HOME/bin/javac" -cp "$DOTC_CP" -d "$S" "$B/Loop.java"
ms() { local t0 t1; t0=$(perl -MTime::HiRes=time -e 'printf "%d", time*1000'); "$@" >/dev/null 2>&1; t1=$(perl -MTime::HiRes=time -e 'printf "%d", time*1000'); echo $((t1 - t0)); }
{
echo "# A: recording on, ms per compile (fresh JVM), $REPS reps, alternating variants"
for f in corpus util; do
  for v in fork agent; do eval "T_$v=''"; done
  for i in $(seq "$REPS"); do
    for v in fork agent; do
      if [ $f = corpus ]; then
        t=$(ms env REPLAY_VARIANT=$v REPLAY_AGENT_OPTS=fixTypeApply bash -c 'for c in '"$R"'/corpus/*.scala; do '"$R"'/plugin/record.sh '"$S"'/x.jsonl $c; done')
      else
        t=$(ms env REPLAY_VARIANT=$v REPLAY_AGENT_OPTS=fixTypeApply DOTC_USER_CP="$UCP" "$R/plugin/record.sh" "$S/x.jsonl" $UTIL -- -Yexplicit-nulls -nowarn)
      fi
      eval "T_$v=\"\$T_$v $t\""
    done
  done
  echo "$f fork :$T_fork"; echo "$f agent:$T_agent"
done
echo
echo "# B: no recorder, dotc/util compiled $ITERS times in one JVM, ms per iteration"
for i in 1 2 3; do
  for v in stock agent agent+fix fork; do
    REPLAY_VARIANT=${v%+fix}; source "$R/plugin/env.sh"
    jo=""; [ $v = agent ] && jo="-javaagent:$AGENT_JAR"; [ $v = agent+fix ] && jo="-javaagent:$AGENT_JAR=fixTypeApply"
    echo "$v: $("$JAVA_HOME/bin/java" -Xss8m -Xmx4g $jo -cp "$DOTC_CP:$S" Loop "$ITERS" -color:never -nowarn -Yexplicit-nulls -classpath "$UCP" -d "$S/cls" $UTIL)"
  done
done
} | tee "$OUT"
