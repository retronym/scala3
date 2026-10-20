#!/usr/bin/env bash
# LLM-assisted (Claude), see LLM_POLICY.md.
# Byte-identity check: compile each corpus file with plain scalac and with the recorder (agent + plugin), compare
# the class files. identity.sh <work dir> [extra scalac options...]
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
V=${REPLAY_SCALA:-2.13.18}
W=$1; shift
JAVA=${JAVA_HOME_REPLAY:-$HOME/.sdkman/candidates/java/21.0.12+1.1-tem}/bin/java
CP=$(coursier fetch -p "org.scala-lang:scala-compiler:$V")
LIB=$(coursier fetch -p "org.scala-lang:scala-library:$V")
rm -rf "$W"; mkdir -p "$W"
fail=0
for f in "$HERE"/corpus/*.scala; do
  n=$(basename "$f" .scala)
  mkdir -p "$W/$n/plain" "$W/$n/rec"
  "$JAVA" -Xss8m -cp "$CP" scala.tools.nsc.Main -classpath "$LIB" "$@" -d "$W/$n/plain" "$f"
  "$HERE/run1.sh" "$W/$n/$n.jsonl" "$W/$n/rec" "$@" "$f" 2>"$W/$n/rec.log"
  if diff -r "$W/$n/plain" "$W/$n/rec" >/dev/null; then echo "$n: identical ($(ls "$W/$n/plain" | wc -l | tr -d ' ') classes)"
  else echo "$n: DIFFERENT"; diff -r "$W/$n/plain" "$W/$n/rec" | head -5; fail=1; fi
done
exit $fail
