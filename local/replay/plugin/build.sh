#!/usr/bin/env bash
# LLM-assisted (Claude), see LLM_POLICY.md.
# Compile the plugin against this checkout's nonbootstrapped compiler classes.
set -euo pipefail
source "$(dirname "$0")/env.sh"
rm -rf "$REPLAY_HOME/classes"; mkdir -p "$REPLAY_HOME/classes"
"$JAVA_HOME/bin/java" -cp "$DOTC_CP" dotty.tools.dotc.Main -color:never -classpath "$DOTC_CP" \
  -d "$REPLAY_HOME/classes" "$REPLAY_HOME"/src/*.scala
cp "$REPLAY_HOME/plugin.properties" "$REPLAY_HOME/classes/"
