#!/usr/bin/env bash
# LLM-assisted (Claude), see LLM_POLICY.md.
# Build target/replay-agent-api.jar (bootstrap) and target/replay-agent.jar (-javaagent), against the
# stock compiler classes (../classes-stock). Byte Buddy is pinned and fetched with coursier.
set -euo pipefail
AGENT=$(cd "$(dirname "$0")" && pwd)
source "$AGENT/../plugin/env.sh"
BB_VERSION=1.17.5
BB=$(coursier fetch --intransitive "net.bytebuddy:byte-buddy:$BB_VERSION")
BBA=$(coursier fetch --intransitive "net.bytebuddy:byte-buddy-agent:$BB_VERSION")
STOCK=${REPLAY_STOCK_CLASSES:-$AGENT/../classes-stock}
T=$AGENT/target
rm -rf "$T"; mkdir -p "$T/api" "$T/agent"
cp "$BB" "$T/byte-buddy.jar"; cp "$BBA" "$T/byte-buddy-agent.jar"

"$JAVA_HOME/bin/javac" --release 17 -d "$T/api" $(find "$AGENT/src/api" -name '*.java')
"$JAVA_HOME/bin/jar" --create --file "$T/replay-agent-api.jar" -C "$T/api" .

CP="$T/api:$BB:$(echo "$DOTC_CP" | sed "s|$COMPILER_CLASSES|$STOCK|")"
# -parameters is not needed: advice binds by annotation. Debug info kept for readable dumps.
"$JAVA_HOME/bin/javac" --release 17 -g -cp "$CP" -d "$T/agent" $(find "$AGENT/src/agent" -name '*.java')
cat > "$T/MANIFEST.MF" <<EOF
Premain-Class: replay.agent.ReplayAgent
Agent-Class: replay.agent.ReplayAgent
Boot-Class-Path: replay-agent-api.jar
Class-Path: byte-buddy.jar
Can-Retransform-Classes: true
Can-Redefine-Classes: true
EOF
"$JAVA_HOME/bin/jar" --create --file "$T/replay-agent.jar" --manifest "$T/MANIFEST.MF" -C "$T/agent" .
echo "built $T/replay-agent.jar (Byte Buddy $BB_VERSION)"
