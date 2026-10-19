#!/usr/bin/env python3
# LLM-assisted (Claude), see LLM_POLICY.md.
"""Lineage coverage of genBCode jvm versions: jvmcoverage.py <file.jsonl>...
Lists methods without a tree ancestor, and the opcodes of instructions outside every tree-derived range."""
import json, sys, collections
sys.path.insert(0, __import__("os").path.dirname(__file__))
from jvmcheck import load, insn_lines
miss_m = []; miss_op = collections.Counter(); tot = cov = 0
for path in sys.argv[1:]:
    nodes, versions, derives, phases, units = load(path)
    anc = {n for d in derives if d["how"] == "codegen" for n in d["new"]}
    for v in versions:
        if phases.get(v["phase"], {}).get("name") != "genBCode": continue
        off = {i: (s, e) for i, s, e in v["offsets"]}
        for m in off:
            if nodes[m]["kind"] != "MethodNode": continue
            if m not in anc: miss_m.append(f"{v['class']}.{nodes[m]['name']}")
            covered = [off[r] for r in off if nodes[r]["kind"] == "InsnRange" and nodes[r]["method"] == m and r in anc]
            # walk the method's instruction lines with their offsets
            s, e = off[m]; pos = s
            for line in v["text"][s:e].split("\n"):
                ls = pos; pos += len(line) + 1
                if not insn_lines(line) or line.startswith("  //") or not line.startswith("    ") : continue
                st = line.strip()
                if st.startswith(("LOCALVARIABLE", "MAXSTACK", "MAXLOCALS", "TRYCATCHBLOCK", "//", "@", "]", "\"", "java/")): continue
                if line.startswith("      "): continue  # continuation lines (indy args, switch tables)
                tot += 1
                if any(a <= ls < b for a, b in covered): cov += 1
                else: miss_op[st.split()[0]] += 1
print(f"instructions in a tree range: {cov}/{tot}")
print("methods without a tree ancestor:", ", ".join(miss_m))
print("uncovered opcodes:", ", ".join(f"{k}={n}" for k, n in miss_op.most_common()))
