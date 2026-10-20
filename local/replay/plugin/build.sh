#!/usr/bin/env bash
# LLM-assisted (Claude), see LLM_POLICY.md.
# Compile the plugin for one hook delivery (REPLAY_VARIANT=fork|agent, see env.sh) into $PLUGIN_CLASSES.
# src/ is shared; src-fork/ or src-agent/ supplies `Install` (how the recorder is attached).
set -euo pipefail
source "$(dirname "$0")/env.sh"
v=$REPLAY_VARIANT; [ "$v" = stock ] && v=agent
extra=""; [ "$v" = agent ] && extra=":$REPLAY_HOME/../agent/target/replay-agent-api.jar:$REPLAY_HOME/../agent/target/byte-buddy-agent.jar"
rm -rf "$PLUGIN_CLASSES"; mkdir -p "$PLUGIN_CLASSES"
"$JAVA_HOME/bin/java" -cp "$DOTC_CP" dotty.tools.dotc.Main -color:never -classpath "$DOTC_CP$extra" \
  -d "$PLUGIN_CLASSES" "$REPLAY_HOME"/src/*.scala "$REPLAY_HOME"/src-$v/*.scala "$REPLAY_HOME"/compat/opt/*.scala "$REPLAY_HOME"/compat/skipIfJava/*.scala
cp "$REPLAY_HOME/plugin.properties" "$PLUGIN_CLASSES/"
