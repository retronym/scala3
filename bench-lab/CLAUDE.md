# bench-lab: notes for coding agents

bench-lab runs on [retronym/jvm-perf-lab](https://github.com/retronym/jvm-perf-lab)'s `perflab` (see README.md, Running); its AGENTS.md has the rules for benchmark runs.

## Share the live index before starting runs

Before starting any campaign or run, give the user the URL of the live index, so they can watch results arrive:

```bash
perflab serve
```

`perflab run` and `perflab ab run` also log it as `index (updates ...): ...` before they start; pass that line on. Never start another HTTP server for results. The page keeps itself current, so there is nothing to regenerate when a campaign or run finishes.

## Commits

Say in the commit message that the change was made with an LLM. Do not add `Co-Authored-By` or "Generated with" trailers.
