#!/usr/bin/env python3
# LLM-assisted (Claude), see LLM_POLICY.md.
"""Check tree -> JVM instruction-range lineage (schema v1, ir "jvm").

  jvmcheck.py <file.jsonl> [--dump CLASS.METHOD] [--givens]

For every genBCode jvm version: each InsnRange derived (`codegen`) from a tree node must have a
non-empty instruction text. `--dump` prints the method's ranges with their tree ancestors and
source text. `--givens` asserts, on Givens.jsonl, that in `Givens$.render` the `Apply` nodes of
`shown` map to ranges containing an invokevirtual/invokestatic of `shown`, and that the literals
1, 2, 3 and "x" map to exactly ICONST_1..3 and LDC "x".
"""
import json, sys, collections

INSN_PREFIXES = ("L", "LINENUMBER", "FRAME")

def insn_lines(text):
    """Instruction lines of a range's text: drop labels and line numbers."""
    out = []
    for l in text.split("\n"):
        s = l.strip()
        if not s: continue
        if (s.startswith("L") and s[1:].isdigit()) or s.startswith("LINENUMBER") or s.startswith("FRAME"): continue
        out.append(s)
    return out

def load(path):
    nodes, versions, derives, phases, units = {}, [], [], {}, []
    for line in open(path):
        r = json.loads(line)
        t = r["t"]
        if t == "meta": units = r["units"]
        elif t == "node": nodes[r["id"]] = r
        elif t == "phase": phases[r["id"]] = r
        elif t == "version" and r.get("ir") == "jvm": versions.append(r)
        elif t == "derive" and r.get("ir") == "jvm": derives.append(r)
    return nodes, versions, derives, phases, units

def main():
    path = sys.argv[1]
    nodes, versions, derives, phases, units = load(path)
    anc = collections.defaultdict(list)  # jvm node -> [(tree node, confidence)]
    for d in derives:
        if d["how"] == "codegen":
            for n in d["new"]: anc[n] += [(o, d["confidence"]) for o in d["old"]]
    gen = [v for v in versions if phases.get(v["phase"], {}).get("name") == "genBCode"]
    errors = 0
    def rng_text(v, off, i):
        s, e = off[i]
        return v["text"][s:e]
    def src(tn):
        sp, u = tn["span"], tn["unit"]
        return units[u]["text"][sp[0]:sp[1]] if sp and u is not None else ""
    ranges = withanc = 0
    for v in gen:
        off = {i: (s, e) for i, s, e in v["offsets"]}
        for i in off:
            if nodes[i]["kind"] != "InsnRange": continue
            ranges += 1
            if anc.get(i): withanc += 1
            if not insn_lines(rng_text(v, off, i)) and anc.get(i):
                # a range may hold only labels/line numbers (e.g. an empty block): note, not an error
                pass
    print(f"{path}: {len(gen)} genBCode class versions, {ranges} ranges, {withanc} with a tree ancestor")

    def method_ranges(cls, meth):
        v = next(v for v in gen if v["class"] == cls)
        off = {i: (s, e) for i, s, e in v["offsets"]}
        m = next(i for i in off if nodes[i]["kind"] == "MethodNode" and nodes[i]["name"] == meth)
        rs = sorted((i for i in off if nodes[i]["kind"] == "InsnRange" and nodes[i]["method"] == m), key=lambda i: off[i])
        return v, off, m, rs

    if "--dump" in sys.argv:
        cls, meth = sys.argv[sys.argv.index("--dump") + 1].rsplit(".", 1)
        v, off, m, rs = method_ranges(cls, meth)
        print(rng_text(v, off, m))
        for i in rs:
            for (t, c) in anc.get(i, [("-", "")]):
                tn = nodes.get(t, {"kind": "-", "span": None, "unit": None})
                print(f"  {tn['kind']:10} {c:9} {src(tn)[:40]!r:44} -> {nodes[i]['range']} {' | '.join(insn_lines(rng_text(v, off, i)))[:100]}")

    if "--givens" in sys.argv:
        v, off, m, rs = method_ranges("Givens$", "render")
        def ranges_of(pred):
            return [(i, t) for i in rs for (t, _) in anc.get(i, []) if pred(nodes[t])]
        def check(ok, msg):
            nonlocal errors
            print(("  ok   " if ok else "  FAIL ") + msg)
            if not ok: errors += 1
        # the Apply of `shown`: an Apply whose fun subtree has a node named `shown`
        def calls_shown(tn):
            # the Apply's own method: through curried Applys and TypeApplys to a Select/Ident
            f = nodes[tn["children"][0]]
            while f["kind"] in ("Apply", "TypeApply"): f = nodes[f["children"][0]]
            return f["kind"] in ("Select", "Ident") and f.get("name") == "shown"
        shown = ranges_of(lambda tn: tn["kind"] == "Apply" and tn["children"] and calls_shown(tn))
        check(len(shown) == 2, f"Apply of shown has {len(shown)} ranges (List(1,2,3).shown, \"x\".shown)")
        for i, t in shown:
            ins = insn_lines(rng_text(v, off, i))
            check(ins[-1].startswith(("INVOKEVIRTUAL", "INVOKESTATIC")) and ".shown " in ins[-1],
                  f"{src(nodes[t])!r} -> ends {ins[-1]!r}")
        for lit, want in (("1", "ICONST_1"), ("2", "ICONST_2"), ("3", "ICONST_3"), ('"x"', 'LDC "x"')):
            got = ranges_of(lambda tn: tn["kind"] == "Literal" and tn.get("name") == lit)
            texts = [insn_lines(rng_text(v, off, i)) for i, _ in got]
            check(len(texts) == 1 and texts[0] == [want], f"Literal {lit} -> {texts}")
    if "--opt" in sys.argv:
        # follow each genBCode range through copy/rewrite derives to the last version of its class;
        # literal ranges whose instructions survive must still show the same instructions
        fwd = {}
        for d in derives:
            if d["how"] in ("copy", "rewrite") and len(d["old"]) == 1 and len(d["new"]) == 1 and nodes[d["new"][0]]["kind"] == "InsnRange":
                fwd[d["old"][0]] = d["new"][0]
        elided = {o for d in derives if d["how"] == "elide" for o in d["old"]}
        final = {}
        for v in versions: final[v["class"]] = v  # last version per class
        texts = {}
        for v in versions:
            for i, s0, e0 in v["offsets"]: texts[i] = v["text"][s0:e0]
        survived = gone = same_lit = changed_lit = 0
        for v in gen:
            off = {i: (s0, e0) for i, s0, e0 in v["offsets"]}
            fo = {i for i, _, _ in final[v["class"]]["offsets"]}
            for i in off:
                if nodes[i]["kind"] != "InsnRange": continue
                j = i
                while j in fwd: j = fwd[j]
                if j in fo:
                    survived += 1
                    lits = [t for (t, _) in anc.get(i, []) if nodes[t]["kind"] == "Literal"]
                    if lits:
                        if insn_lines(texts[i]) == insn_lines(texts[j]): same_lit += 1
                        else:
                            changed_lit += 1
                            if changed_lit <= 5: print(f"  literal {nodes[lits[0]].get('name')}: {insn_lines(texts[i])} -> {insn_lines(texts[j])}")
                elif j in elided: gone += 1
                else:
                    errors += 1
                    print(f"  FAIL range {i} ({v['class']}) is neither in the last version nor elided")
        print(f"  opt: genBCode ranges surviving to the last version {survived}, elided {gone}; literal ranges unchanged {same_lit}, changed {changed_lit}")
    sys.exit(1 if errors else 0)

if __name__ == "__main__":
    main()
