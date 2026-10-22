# Shared brief: Scala 2 -> Scala 3 "untracked fork" audit

Context: Scala 3 (dotty) copy/pasted large parts of the Scala 2 compiler (and recently the whole 2.13 standard library) years ago. There is no process to cross-port fixes that later landed in scala/scala 2.13.x. Your job: for your assigned area, find divergences between the Scala 3 copy and current Scala 2.13.x, and mine scala/scala history to find bug fixes in Scala 2 that were NEVER ported to Scala 3 (or the reverse, where the port direction was Scala 3 -> Scala 2).

Locations (absolute):
- Scala 3 checkout (work here, read only, do NOT edit sources or commit): /Users/jzaugg-virtus/code/.worktrees/scala3/scala3-untracked-fork-debt-803909
- Scala 2 git repo: /Users/jzaugg-virtus/code/scala/scala  — use ref `origin/2.13.x` ONLY, via `git -C /Users/jzaugg-virtus/code/scala/scala log origin/2.13.x -- <path>` / `git show origin/2.13.x:<path>`. Do NOT checkout, stash or otherwise change its working tree.
- Plain export of Scala 2.13.x sources (for diff/grep): <scratchpad>/scala2/src/{compiler,reflect,library}
- Precomputed similarity tables (s3 file -> best s2 match, shared count, ratio): .../scratchpad/out/sim.tsv (exact normalized lines) and .../scratchpad/out/sim_tok.tsv (identifier 6-gram shingles). grep them for your area.
- Local scratch dir for your temp files: .../scratchpad/work/<your-area>/ (mkdir it).

Method (actually run things; don't just reason):
1. Pair up files: s3 file <-> s2 origin. Use the sim tables plus your own judgement (ports were often renamed/split/merged, and syntax was rewritten to Scala 3 style so exact-line similarity understates).
2. Find the port date: `git -C <scala3> log --diff-filter=A --format='%h %ad %s' --date=short -- <s3 path>` (and follow renames with `--follow`). Note: some areas were re-synced later; check `git log --grep` for words like "port", "backport", "sync", "scala/scala#", "from scalac".
3. List Scala 2 commits to the origin file(s) AFTER the port date: `git -C /Users/jzaugg-virtus/code/scala/scala log origin/2.13.x --since=<date> --format='%h %ad %an %s' --date=short -- <s2 path>`. For each commit that looks like a bug fix / behaviour change (not pure refactor/formatting/deprecation churn), check whether the equivalent change exists in Scala 3: diff the hunks against the s3 file, grep for distinctive identifiers/strings, and `git -C <scala3> log --all --grep=<issue number or keyword>`.
4. Also diff the current files semantically (normalize whitespace/braces as needed, e.g. `diff -w`), and look for logic divergences beyond what the history explains: conditions inverted, missing cases, different constants, missing null checks, missing `close()`, different error handling.
5. For each candidate missed fix, VERIFY it is really absent in Scala 3 (and not implemented elsewhere, differently). State what you checked.

Output: write a markdown report to .../scratchpad/out/findings/<your-area>.md with:
- `## Pairs` table: s3 file | s2 file | port date | later s2 commits (count) | notes
- `## Findings` one entry per candidate issue, in this exact form:
  ### F<n>: <one line title>
  - severity: high|medium|low   (high = likely user-visible bug / crash / data loss / wrong code; medium = correctness edge case or perf; low = hygiene)
  - confidence: high|medium|low
  - s3: <path>:<line>
  - s2: <path> ; fix commit: <sha> <date> <subject> (and scala/bug#NNNN or scala/scala PR if named)
  - what: 2-5 sentences, mechanism-first: what the Scala 2 fix changed, why Scala 3 lacks it, and the observable consequence in Scala 3.
  - verified: what you did to confirm absence
- `## Already ported / not applicable` short bullet list so we don't re-check them
- `## Method notes` anything a reviewer should know (e.g. port direction reversed, area rewritten beyond comparison)

Also return the same report content as your final message. Be concrete: shas, paths, line numbers. Prefer fewer, well-verified findings over many speculative ones, but DO list medium/low confidence ones clearly labelled. Time budget: be thorough but stop when you've covered the main files; don't try to review thousands of commits — filter by subject (fix, bug, NPE, leak, crash, regression, scala/bug#, wrong, incorrect, race, Windows, jrt, multi-release, encoding, overflow).
