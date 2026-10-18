#!/usr/bin/env python3
# LLM-assisted (Claude), see LLM_POLICY.md.
"""Validate and summarise schema v1 replay JSONL: analyze.py <file.jsonl>... [--phases]

Validation (see ../SCHEMA-v1.md): every id a record refers to was defined by an earlier record;
ev lines are in strictly increasing seq order with no gaps, parents are earlier events;
version.seq is a PhaseEnd of the same phase group; textSameAs/offsetsSameAs resolve to an earlier
version of the same unit; offsets lie in the text, are pre-order and properly nested.
"""
import json, sys, collections

def summarise(path, show_phases):
    by_type = collections.Counter(); bytes_by_type = collections.Counter()
    nodes, syms, types, evs = {}, set(), set(), {}
    phases, groups = {}, set()
    errors = []
    last_version = {}
    versions = []
    ev_kind = collections.Counter(); ev_kind_bytes = collections.Counter()
    derive = collections.Counter()
    last_seq = -1
    edges = 0; edge_weight = 0
    def err(m):
        if len(errors) < 2000: errors.append(m)
    def need(kind, s, i, rec):
        if i is not None and i not in s: err(f"{rec['t']} refers to undefined {kind} {i}")
    with open(path) as f:
        for line in f:
            r = json.loads(line); t = r["t"]
            by_type[t] += 1; bytes_by_type[t] += len(line.encode())
            if t == "phase": phases[r["id"]] = r; groups.add(r["group"])
            elif t == "node":
                for c in r["children"]: need("node", nodes, c, r)
                need("sym", syms, r["sym"], r)
                nodes[r["id"]] = r
            elif t == "sym": need("sym", syms, r["owner"], r); syms.add(r["id"])
            elif t == "type": types.add(r["id"])
            elif t == "syminfo": need("sym", syms, r["sym"], r); need("sym", syms, r["owner"], r)
            elif t == "ev":
                s = r["seq"]
                if s != last_seq + 1: err(f"ev seq {s} after {last_seq}")
                last_seq = s
                if r["parent"] is not None and r["parent"] not in evs: err(f"ev {s} parent {r['parent']} undefined")
                if r["phase"] not in groups and r["phase"] != -1: err(f"ev {s} phase {r['phase']} is not a group")
                for n in r["nodes"]: need("node", nodes, n, r)
                for y in r["syms"]: need("sym", syms, y, r)
                for k in ("pt", "tpe", "ref"):
                    need("type", types, r["payload"].get(k), r)
                evs[s] = (r["kind"], r["phase"])
                ev_kind[r["kind"]] += 1; ev_kind_bytes[r["kind"]] += len(line.encode())
            elif t == "edge":
                need("sym", syms, r["from"], r); need("sym", syms, r["to"], r)
                if r["ev"] not in evs: err(f"edge ev {r['ev']} undefined")
                edges += 1; edge_weight += r["weight"]
            elif t == "derive":
                for i in r["new"] + r["old"]: need("node", nodes, i, r)
                derive[(r["how"], r["confidence"])] += max(len(r["new"]), len(r["old"]))
            elif t == "version":
                need("node", nodes, r["root"], r)
                if evs.get(r["seq"]) != ("PhaseEnd", r["phase"]): err(f"version {r['phase']}/{r['unit']} seq {r['seq']} is not its PhaseEnd")
                earlier = last_version.setdefault(r["unit"], {})
                for k in ("text", "offsets"):
                    if k not in r and k + "SameAs" in r:
                        src = earlier.get(r[k + "SameAs"])
                        if src is None or k not in src: err(f"bad {k}SameAs {r[k + 'SameAs']}")
                        else: r[k] = src[k]
                text = r.get("text", "")
                stack = []
                for (i, s, e) in r.get("offsets", []):
                    need("node", nodes, i, r)
                    if not (0 <= s <= e <= len(text)): err(f"bad offset {i} {s} {e}")
                    while stack and stack[-1] <= s and not (stack[-1] == s == e): stack.pop()
                    if stack and e > stack[-1]: err(f"offsets not nested at node {i}")
                    stack.append(e)
                for (i, _) in r.get("types", []): need("node", nodes, i, r)
                earlier[r["phase"]] = r
                versions.append(r)
            elif t == "stats": stats = r
    total = sum(bytes_by_type.values())
    print(f"== {path}: {total/1024:.0f} KiB, {len(versions)} versions, {len(nodes)} nodes, {len(syms)} syms, {len(types)} types, {last_seq+1} events, errors={len(errors)}")
    for e in errors[:8]: print("   ERROR", e)
    print("   records:", ", ".join(f"{t}={by_type[t]} ({bytes_by_type[t]*100/total:.0f}%)" for t in sorted(by_type, key=lambda t: -bytes_by_type[t])))
    print("   ev:", ", ".join(f"{k}={ev_kind[k]}" for k in sorted(ev_kind, key=lambda k: -ev_kind[k])),
          f"; ev bytes/event {sum(ev_kind_bytes.values())/max(1,sum(ev_kind.values())):.0f}")
    print(f"   edges: {edges} records, total weight {edge_weight}")
    print("   derive (nodes):", ", ".join(f"{h}/{c}={n}" for (h, c), n in sorted(derive.items())))
    printed = sum(1 for v in versions if "text" in v and "textSameAs" not in v or "types" in v)
    typer = [phases[g]["id"] for g in phases if phases[g]["name"] == "typer"]
    typed_nodes = sum(v["nodes"] for v in versions if typer and v["phase"] == typer[0])
    print(f"   typed trees {typed_nodes} nodes; arena {len(nodes)} = {len(nodes)/max(1,typed_nodes):.2f}x typed; "
          f"versions with new nodes {sum(1 for v in versions if v['new'] > 0)}/{len(versions)}; "
          f"printed/skipped (stats) {stats.get('versionsPrinted')}/{stats.get('versionsSkipped')}; perturbations {stats.get('perturbations')}")
    if show_phases:
        name = {g: phases[g]["name"] for g in phases}
        print(f"   {'phase':<28}{'nodes':>8}{'new':>7}{'types':>7}")
        for v in versions:
            print(f"   {name.get(v['phase'], v['phase']):<28}{v['nodes']:>8}{v['new']:>7}{len(v.get('types', [])):>7}")
    return len(errors)

if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    bad = sum(summarise(p, "--phases" in sys.argv) for p in args)
    sys.exit(1 if bad else 0)
