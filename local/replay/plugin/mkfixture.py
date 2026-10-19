#!/usr/bin/env python3
# LLM-assisted (Claude), see LLM_POLICY.md.
"""Build the viewer fixture out/jvm-fixture.jsonl from a real recording of

    object Hi:
      def inc(x: Int): Int =
        val y = x
        y + 1

compiled with `-opt` (mkfixture.py <recording.jsonl> <out.jsonl>). The fixture is trimmed by hand
to one class (`Hi$`), one method (`inc`), four instruction ranges, and two JVM phases: genBCode and
`jvm:r1:pushPop` (the recording's r1:copyProp and r1:staleStores steps are folded into it). Texts
are slices of the recording; offsets are recomputed here, so they are exact.
"""
import json, sys

rec = [json.loads(l) for l in open(sys.argv[1])]
ph = {r["id"]: r["name"] for r in rec if r["t"] == "phase"}
nodes = {r["id"]: r for r in rec if r["t"] == "node"}
meta = rec[0]
src = meta["units"][0]["text"]

def jvm_version(phase):
    return next(r for r in rec if r["t"] == "version" and r.get("ir") == "jvm" and r["class"] == "Hi$" and ph[r["phase"]] == phase)

def method(v, name):
    off = {i: (s, e) for i, s, e in v["offsets"]}
    m = next(i for i in off if nodes[i]["kind"] == "MethodNode" and nodes[i]["name"] == name)
    return m, off

# tree text of `def inc`, as printed after the last tree phase
vs = {}
for r in rec:
    if r["t"] == "version" and r.get("ir") is None:
        for k in ("text", "offsets"):
            if k not in r and k + "SameAs" in r: r[k] = vs[r[k + "SameAs"]][k]
        vs[r["phase"]] = r
tv = next(r for r in vs.values() if ph[r["phase"]] == "genBCode")
toff = {i: (s, e) for i, s, e in tv["offsets"]}
dd = next(i for i in toff if nodes[i]["kind"] == "DefDef" and nodes[i]["name"] == "inc")
ds, de = toff[dd]
def tslice(i): s, e = toff[i]; return (s - ds, e - ds)
def find(kind, name, under):
    stack = [under]
    while stack:
        i = stack.pop()
        if nodes[i]["kind"] == kind and nodes[i].get("name") == name: return i
        stack += nodes[i]["children"]
valy = find("ValDef", "y", dd); identx = find("Ident", "x", valy)
apply = next(i for i in toff if nodes[i]["kind"] == "Apply" and toff[i][0] > toff[valy][1] and toff[i][1] <= de)
identy = find("Ident", "y", apply); lit1 = find("Literal", "1", apply)

# codegen ancestors of the genBCode ranges of inc
anc = {}
for r in rec:
    if r["t"] == "derive" and r.get("how") == "codegen":
        for n in r["new"]: anc.setdefault(n, []).extend(r["old"])
g = jvm_version("genBCode")
gm, goff = method(g, "inc")
granges = {t: next(i for i in goff if nodes[i]["kind"] == "InsnRange" and nodes[i]["method"] == gm and t in anc.get(i, []))
           for t in (valy, identx, identy, lit1)}
# follow copy/rewrite/elide from genBCode to jvm:r1:pushPop
fwd, how, elided = {}, {}, set()
for r in rec:
    if r["t"] == "derive" and r.get("ir") == "jvm" and r["how"] in ("copy", "rewrite", "elide"):
        if r["how"] == "elide": elided.update(r["old"])
        elif len(r["new"]) == 1: fwd[r["old"][0]] = r["new"][0]; how[r["new"][0]] = r["how"]
p = jvm_version("jvm:r1:pushPop")
pm, poff = method(p, "inc")

# class text: the header up to the first field/method, the method, the closing brace
def class_text(v, m, off):
    head = v["text"][: v["text"].index("\n  // access flags", v["text"].index("{"))]
    s, e = off[m]
    return head, v["text"][s:e]

out = []
def emit(**r): out.append(r)
emit(t="meta", schema="v1", compiler=meta["compiler"], args=["-opt=true"], requires=["-Ybackend-parallelism:1"],
     units=[{"id": 0, "path": "Hi.scala", "text": src}])
emit(t="phase", id=0, name="genBCode", mini=False, group=0, megaOf=None, denotTransformer=False)
emit(t="phase", id=1, name="jvm:r1:pushPop", ir="jvm", mini=False, group=1, megaOf=None, denotTransformer=False)
emit(t="ev", seq=0, parent=None, kind="PhaseBegin", phase=0, unit=None, span=None, nodes=[], syms=[], payload={"name": "genBCode", "members": ["genBCode"]})
emit(t="ev", seq=1, parent=None, kind="PhaseEnd", phase=0, unit=None, span=None, nodes=[], syms=[], payload={})
emit(t="sym", id=0, name="inc", owner=None, fullName="Hi.inc", kind="term", local=True, unit=0, firstPhase=0, firstSeq=None)
# tree nodes (children trimmed to the nodes kept)
T = {identx: 0, valy: 1, identy: 2, lit1: 3, dd: 4}
for t, kids in ((identx, []), (valy, [identx]), (identy, []), (lit1, []), (dd, [valy, identy, lit1])):
    n = nodes[t]
    emit(t="node", id=T[t], kind=n["kind"], span=n["span"], unit=0, children=[T[k] for k in kids],
         sym=0 if t == dd else None, name=n.get("name"))
toffs = sorted([[T[t], *tslice(t)] for t in T], key=lambda x: (x[1], -x[2], x[0]))
emit(t="version", phase=0, unit=0, seq=1, root=T[dd], text=tv["text"][ds:de], offsets=toffs, nodes=len(T), new=len(T))

def jvm_block(v, m, off, ranges, phase, start_id, method_how):
    head, body = class_text(v, m, off)
    ms, _ = off[m]
    text = head + "\n" + body + "}\n"
    base = len(head) + 1
    ids = {}
    nid = start_id
    mid = nid; nid += 1
    emit(t="node", id=mid, ir="jvm", kind="MethodNode", span=None, unit=0, children=[], sym=0, name="inc",
         desc="(I)I", insns=nodes[m]["insns"])
    offs = [[mid, base + 1 if body.startswith("\n") else base, base + len(body)]]
    for t, r in ranges.items():
        ids[t] = nid
        emit(t="node", id=nid, ir="jvm", kind="InsnRange", span=None, unit=0, children=[], sym=None, name=None,
             method=mid, range=nodes[r]["range"])
        s, e = off[r]
        offs.append([nid, base + s - ms, base + e - ms])
        nid += 1
    cid = nid; nid += 1
    emit(t="node", id=cid, ir="jvm", kind="ClassNode", span=None, unit=0, children=[mid], sym=None, name="Hi$", mirror=False)
    offs.append([cid, 0, len(text)])
    offs.sort(key=lambda x: (x[1], -x[2], x[0]))
    emit(t="version", ir="jvm", phase=phase, unit=0, seq=1, **{"class": "Hi$"}, root=cid, text=text, offsets=offs,
         nodes=len(offs), new=len(offs))
    return mid, cid, ids, nid

gmid, gcid, gids, nid = jvm_block(g, gm, goff, granges, 0, len(T), None)
emit(t="derive", ir="jvm", phase=0, unit=0, new=[gmid], old=[T[dd]], how="codegen", confidence="exact")
for t in (valy, identx, identy, lit1):
    emit(t="derive", ir="jvm", phase=0, unit=0, new=[gids[t]], old=[T[t]], how="codegen", confidence="exact")
# the same ranges after pushPop
pset = {i for i, _, _ in p["offsets"]}
def follow(r):
    hows = []
    while r not in pset and r in fwd: r = fwd[r]; hows.append(how[r])
    return r, hows
pranges, phows, gone = {}, {}, []
for t, r in granges.items():
    r2, hows = follow(r)
    if r2 not in pset: gone.append(t)
    else: pranges[t] = r2; phows[t] = "rewrite" if "rewrite" in hows else "copy"
pmid, pcid, pids, nid = jvm_block(p, pm, poff, pranges, 1, nid, None)
emit(t="derive", ir="jvm", phase=1, unit=0, new=[pmid], old=[gmid], how="rewrite", confidence="exact")
for t in pranges:
    emit(t="derive", ir="jvm", phase=1, unit=0, new=[pids[t]], old=[gids[t]], how=phows[t], confidence="exact")
if gone: emit(t="derive", ir="jvm", phase=1, unit=0, new=[], old=[gids[t] for t in gone], how="elide", confidence="exact")
emit(t="derive", ir="jvm", phase=1, unit=0, new=[pcid], old=[gcid], how="rewrite", confidence="exact")
emit(t="stats", events=2, nodes=nid)
with open(sys.argv[2], "w") as f:
    for r in out: f.write(json.dumps(r, separators=(",", ":")) + "\n")
print(f"{len(out)} lines -> {sys.argv[2]}")
