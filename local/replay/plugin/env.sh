# Source me. LLM-assisted (Claude), see LLM_POLICY.md.
# Runs a locally built nonbootstrapped compiler directly with java (no sbt per compile).
#
# REPLAY_VARIANT selects compiler and hook delivery:
#   fork  (default) ../classes-fork  (TyperObserver fork), plugin classes-fork
#   agent           ../classes-stock (main, ae374e9583) + -javaagent, plugin classes-agent;
#                   REPLAY_AGENT_OPTS is passed as the agent's options (e.g. fixTypeApply,rejected)
#   stock           ../classes-stock, no agent (the plugin can still be built as classes-agent)
# REPLAY_COMPILER_CLASSES overrides the compiler classes dir; REPLAY_JAVA_OPTS adds JVM options;
# DOTC_USER_CP overrides the -classpath of the compiled sources (default: the library).
export JAVA_HOME=${JAVA_HOME_REPLAY:-$HOME/.sdkman/candidates/java/21.0.12+1.1-tem}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)
export REPLAY_HOME=$HERE
export REPLAY_VARIANT=${REPLAY_VARIANT:-fork}
export AGENT_JAR=$HERE/../agent/target/replay-agent.jar
case $REPLAY_VARIANT in
  fork)  _cc=$HERE/../classes-fork;  export PLUGIN_CLASSES=$HERE/classes-fork; _jo="" ;;
  agent) _cc=$HERE/../classes-stock; export PLUGIN_CLASSES=$HERE/classes-agent; _jo="-javaagent:$AGENT_JAR${REPLAY_AGENT_OPTS:+=$REPLAY_AGENT_OPTS}" ;;
  stock) _cc=$HERE/../classes-stock; export PLUGIN_CLASSES=$HERE/classes-agent; _jo="" ;;
  *) echo "unknown REPLAY_VARIANT=$REPLAY_VARIANT" >&2; return 1 ;;
esac
export COMPILER_CLASSES=${REPLAY_COMPILER_CLASSES:-$_cc}
export DOTC_CP=$(tr ':' '\n' < "$HERE/compiler.classpath" | sed "s#.*/compiler/target/scala3-compiler-nonbootstrapped/scala-3.10.0/classes\$#$COMPILER_CLASSES#" | paste -sd: -)
export LIB_CP=$(tr ':' '\n' < "$HERE/compiler.classpath" | grep '/library/target/' | paste -sd: -)
export DOTC_JAVA_OPTS="$_jo ${REPLAY_JAVA_OPTS:-}"
dotc() { "$JAVA_HOME/bin/java" -Xss8m -Xmx4g $DOTC_JAVA_OPTS -cp "$DOTC_CP" dotty.tools.dotc.Main -color:never -classpath "${DOTC_USER_CP:-$LIB_CP}" "$@"; }
