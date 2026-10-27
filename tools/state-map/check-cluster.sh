#!/bin/sh
# Compiles the non-bootstrapped compiler and reports the state map for the given source files (paths under
# compiler/src/dotty/tools/dotc, e.g. core/Contexts.scala): each field's kind, protection, whether an annotation or the
# rules placed it, and the annotation check; then the counts still unclassified or unprotected. Writes the model to
# local/state-map.json, not to tools/state-map. JAVA_HOME: a JDK 21; SBT_OPTS defaults to -Xmx4g.
set -e
cd "$(dirname "$0")/../.."
: "${JAVA_HOME:?set JAVA_HOME to a JDK 21}"
export SBT_OPTS="${SBT_OPTS:--Xmx4g}"
mkdir -p local
CP=$(sbt --client -java-home "$JAVA_HOME" 'export scala3-compiler-nonbootstrapped/Compile/fullClasspath' | grep '^/' | tail -1)
[ -n "$CP" ] || { echo "compilation failed: run sbt --client scala3-compiler-nonbootstrapped/compile"; exit 1; }
CLASSES=$(echo "$CP" | tr ':' '\n' | grep 'scala3-compiler-nonbootstrapped' | head -1)
scala-cli run tools/state-map/StateMap.scala --java-home "$JAVA_HOME" -- \
  --repo . --classes "$CLASSES" --cp "$CP" --tip "$(git log -1 --format=%H -- compiler/src)" --out local/state-map.json > local/state-map-summary.txt 2>&1
python3 - "$@" <<'PY'
import json, sys
files = ['compiler/src/dotty/tools/dotc/' + f for f in sys.argv[1:]]
d = json.load(open('local/state-map.json'))
fs = [f for f in d['fields'] if f['file'] in files]
open_ = 0
for f in sorted(fs, key=lambda f: (f['file'], f['line'])):
    bad = f['reach'] == 'typer' and (f['kind'] == 'unclassified' or f['protection'].split(':')[0] in ('none', 'partial', 'unknown'))
    open_ += bad
    print(f"{'!' if bad else ' '} {f['owner']}#{f['name']:28} {f['kind']:12} {f['kindSource']:9} {f['protection']:28} {f['protectionSource']:9} {f['reach']:10} {f.get('annotationCheck') or ''}")
    if f['protection'].split(':')[0] in ('none', 'partial'):
        for s in f['unprotectedSites'][:8]: print('      unprotected:', s.replace('compiler/src/dotty/tools/dotc/', ''))
print(f"\n{len(fs)} fields in {len(files)} files; {open_} reachable from the typer still unclassified or without protection (!)")
PY
