#!/usr/bin/env bash
# LLM-assisted (Claude), see LLM_POLICY.md.
# Build the nsc recorder against one scala-compiler 2.13.x (default 2.13.18), into target/<version>/:
#   agent/replay-nsc-agent-api.jar (bootstrap), agent/replay-nsc-agent.jar (-javaagent), plugin/ (-Xplugin dir).
# The agent is compiled against that compiler (advice reads a few accessors), the plugin by it; Byte Buddy is
# pinned and fetched with coursier.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
V=${1:-2.13.18}
OUT=${2:-$HERE/target/$V}
JAVA_HOME=${JAVA_HOME_REPLAY:-$HOME/.sdkman/candidates/java/21.0.12+1.1-tem}
BB_VERSION=1.17.5
CP=$(coursier fetch -p "org.scala-lang:scala-compiler:$V")
BB=$(coursier fetch --intransitive "net.bytebuddy:byte-buddy:$BB_VERSION")
rm -rf "$OUT"; mkdir -p "$OUT/agent/api" "$OUT/agent/classes" "$OUT/plugin"
cp "$BB" "$OUT/agent/byte-buddy.jar"
"$JAVA_HOME/bin/javac" --release 17 -d "$OUT/agent/api" $(find "$HERE/agent/src/api" -name '*.java')
"$JAVA_HOME/bin/jar" --create --file "$OUT/agent/replay-nsc-agent-api.jar" -C "$OUT/agent/api" .
"$JAVA_HOME/bin/javac" --release 17 -g -cp "$OUT/agent/api:$BB:$CP" -d "$OUT/agent/classes" $(find "$HERE/agent/src/agent" -name '*.java')
cat > "$OUT/agent/MANIFEST.MF" <<MF
Premain-Class: replay.nsc.agent.NscAgent
Boot-Class-Path: replay-nsc-agent-api.jar
Class-Path: byte-buddy.jar
Can-Retransform-Classes: true
MF
"$JAVA_HOME/bin/jar" --create --file "$OUT/agent/replay-nsc-agent.jar" --manifest "$OUT/agent/MANIFEST.MF" -C "$OUT/agent/classes" .
"$JAVA_HOME/bin/java" -Xss8m -cp "$CP" scala.tools.nsc.Main -deprecation -feature -classpath "$CP:$OUT/agent/replay-nsc-agent-api.jar" \
  -d "$OUT/plugin" $(find "$HERE/plugin/src" -name '*.scala')
cp "$HERE/plugin/scalac-plugin.xml" "$OUT/plugin/"
echo "built $OUT (scala-compiler $V, Byte Buddy $BB_VERSION)"
