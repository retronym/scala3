"""Interleaved A/B of builds rather than JVM flags: each variant is a worktree with its own sbt server, run by that
worktree's ab.py, round by round, alternating variants. Results are gathered into this checkout's out/ab/<name> and
summarised by ab.py report.

  python3 -I bench-lab/scripts/abtrees.py <spec.json>

The spec is ab.py's, plus "trees": {"<variant>": "<worktree, relative to this checkout>"}. A variant's flags come from
"variants" (default none). Each worktree needs an sbt server already running (`sbt --client` there).
"""
import json, os, shutil, subprocess, sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def tree_spec(spec, variant, rounds, tree):
    s = {k: v for k, v in spec.items() if k != "trees"}
    s["rounds"] = rounds
    s["variants"] = {variant: spec.get("variants", {}).get(variant, "")}
    path = os.path.join(tree, "out", "ab", f"{spec['name']}.{variant}.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    json.dump(s, open(path, "w"), indent=2)
    return path


def main():
    spec_path = sys.argv[1]
    spec = json.load(open(spec_path))
    trees = {v: os.path.abspath(os.path.join(REPO, t)) for v, t in spec["trees"].items()}
    for r in range(1, spec["rounds"] + 1):
        for variant, tree in trees.items():
            path = tree_spec(spec, variant, r, tree)
            rc = subprocess.run([sys.executable, "-I", os.path.join(tree, "bench-lab", "scripts", "ab.py"), "run", path]).returncode
            if rc != 0:
                sys.exit(f"abtrees: {variant} round {r} failed ({tree})")
    out = os.path.join(REPO, "out", "ab", spec["name"])
    for variant, tree in trees.items():
        if tree == REPO:
            continue
        src = os.path.join(tree, "out", "ab", spec["name"])
        for corpus in os.listdir(src):
            v = os.path.join(src, corpus, variant)
            if os.path.isdir(v):
                dest = os.path.join(out, corpus, variant)
                shutil.rmtree(dest, ignore_errors=True)
                shutil.copytree(v, dest)
    combined = {k: v for k, v in spec.items() if k != "trees"}
    combined["variants"] = {v: spec.get("variants", {}).get(v, "") for v in trees}
    path = os.path.join(out, "combined.json")
    json.dump(combined, open(path, "w"), indent=2)
    subprocess.run([sys.executable, "-I", os.path.join(REPO, "bench-lab", "scripts", "ab.py"), "report", path])


if __name__ == "__main__":
    main()
