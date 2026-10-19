#!/usr/bin/env bash
# LLM-assisted (Claude), see LLM_POLICY.md.
# usage: record.sh <out.jsonl> <sources...> [-- extra dotc args / -P:replay-record:<opt>...]
# One compile of all sources, one combined JSONL. Default plan: unfused except mixin..capturedVars.
# REPLAY_VARIANT (env.sh) picks fork or stock+agent.
set -euo pipefail
source "$(dirname "$0")/env.sh"
out=$1; shift
srcs=(); while [ $# -gt 0 ] && [ "$1" != "--" ]; do srcs+=("$1"); shift; done
[ "${1:-}" = "--" ] && shift
CLS=${CLASSES:-$(mktemp -d)}; mkdir -p "$CLS"
dotc -Xplugin:"$PLUGIN_CLASSES" -P:replay-record:out="$out" "$@" -d "$CLS" "${srcs[@]}"
