# bench-lab: notes for coding agents

The general tooling is moving to [retronym/jvm-perf-lab](https://github.com/retronym/jvm-perf-lab) (`perflab serve`, `perflab ab`); its AGENTS.md has the same rules. Until bench-lab depends on it, use the scripts here.

## Share the live index before starting runs

Before starting any `scripts/ab.py` campaign or JMH run, build and serve the index, then give the user its URL:

```bash
python3 -I bench-lab/scripts/abindex.py
python3 -m http.server <port> --bind 127.0.0.1 --directory out/ab   # in the background; skip if one is already serving out/ab
```

Then send `http://127.0.0.1:<port>/index.html` before the first cell starts.

The page keeps itself current. `ab.py` rebuilds the index around every cell, the page refetches its tables every 20 s, and its timeline follows the running fork every 5 s. There is nothing to regenerate when a campaign finishes, so don't tell the user you will. Rerun `abindex.py` only for graphs rendered outside `ab.py` (`regraph.py`, `profdiff.py`).

## Commits

Say in the commit message that the change was made with an LLM. Do not add `Co-Authored-By` or "Generated with" trailers.
