"""Make an xctracenorm template that samples different PMU events.

  python3 make_template.py default.instruments.template.xml out.template.xml EVENT1 EVENT2 EVENT3 EVENT4

Starts from JMH's bundled template (jmh-core: default.instruments.template.xml), which samples
INST_ALL, INST_BRANCH, CORE_ACTIVE_CYCLE and BRANCH_MISPRED_NONSPEC. The event set lives in a nested
NSKeyedArchiver binary plist (XRCountersSetupEventOrFormula objects) inside a <data> blob, not in the
plain <string> entries, so those are rewritten too only for display.

Event names for this CPU: /usr/share/kpep/*.plist (M5 Pro is "as7").
"""
import plistlib
import sys

src, dst, *events = sys.argv[1:]
OLD = ["INST_ALL", "INST_BRANCH", "CORE_ACTIVE_CYCLE", "BRANCH_MISPRED_NONSPEC"]
assert len(events) == 4, "give four events (the template has four slots)"
mapping = dict(zip(OLD, events))

with open(src, "rb") as f:
    outer = plistlib.load(f)


def rewrite_inner(blob: bytes) -> bytes:
    inner = plistlib.loads(blob)
    objs = inner["$objects"]
    changed = False
    for i, o in enumerate(objs):
        if isinstance(o, str) and o in mapping:
            objs[i] = mapping[o]
            changed = True
    if not changed:
        return blob
    return plistlib.dumps(inner, fmt=plistlib.FMT_BINARY)


objs = outer["$objects"]
for i, o in enumerate(objs):
    if isinstance(o, bytes) and o.startswith(b"bplist00"):
        objs[i] = rewrite_inner(o)
    elif isinstance(o, dict) and isinstance(o.get("NS.data"), bytes) and o["NS.data"].startswith(b"bplist00"):
        o["NS.data"] = rewrite_inner(o["NS.data"])
    elif isinstance(o, str) and o in mapping:
        objs[i] = mapping[o]

with open(dst, "wb") as f:
    plistlib.dump(outer, f, fmt=plistlib.FMT_XML)
print("wrote", dst, "with", events)
