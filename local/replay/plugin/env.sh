# Source me. LLM-assisted (Claude), see LLM_POLICY.md.
# Runs the locally built nonbootstrapped compiler directly with java (no sbt per compile).
# REPLAY_COMPILER_CLASSES overrides the compiler classes dir (e.g. a build without the TypeApply fix).
export JAVA_HOME=${JAVA_HOME_REPLAY:-$HOME/.sdkman/candidates/java/21.0.12+1.1-tem}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)
export REPLAY_HOME=$HERE
export DOTC_CP=$(cat "$HERE/compiler.classpath")
if [ -n "${REPLAY_COMPILER_CLASSES:-}" ]; then
  DOTC_CP=$(echo "$DOTC_CP" | tr ':' '\n' | sed "s#.*/compiler/target/scala3-compiler-nonbootstrapped/scala-3.10.0/classes\$#$REPLAY_COMPILER_CLASSES#" | paste -sd: -)
fi
export LIB_CP=$(tr ':' '\n' < "$HERE/compiler.classpath" | grep '/library/target/' | paste -sd: -)
dotc() { "$JAVA_HOME/bin/java" -Xss8m -Xmx4g -cp "$DOTC_CP" dotty.tools.dotc.Main -color:never -classpath "$LIB_CP" "$@"; }
