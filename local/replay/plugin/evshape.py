"""Print the event skeleton (seq, parent, kind, phase, unit, span) to compare streams independent of type text/ids."""
import json, sys
for raw in open(sys.argv[1]):
    r = json.loads(raw)
    if r['t'] == 'ev': print(r['seq'], r['parent'], r['kind'], r['phase'], r['unit'], r['span'])
