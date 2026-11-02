#!/bin/sh
# Compiles the non-bootstrapped compiler, then extracts tools/state-map/state-map.json (and .data.js for index.html)
# from its TASTy. Links go to the last commit that changed compiler/src (on GitHub if that commit is pushed). JAVA_HOME: a JDK 21; SBT_OPTS defaults to -Xmx8g.
set -e
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:?set JAVA_HOME to a JDK 21}"
export SBT_OPTS="${SBT_OPTS:--Xmx8g}"
CP=$(sbt --client -java-home "$JAVA_HOME" 'export scala3-compiler-nonbootstrapped/Compile/fullClasspath' | grep '^/' | tail -1)
CLASSES=$(echo "$CP" | tr ':' '\n' | grep 'scala3-compiler-nonbootstrapped' | head -1)
scala-cli run tools/state-map/StateMap.scala --java-home "$JAVA_HOME" -- \
  --repo . --classes "$CLASSES" --cp "$CP" --tip "$(git log -1 --format=%H -- compiler/src)" --out tools/state-map/state-map.json "$@"
