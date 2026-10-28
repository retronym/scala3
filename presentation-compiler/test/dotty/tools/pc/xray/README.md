# dotc session x-ray: a toy IDE over the patched `InteractiveDriver`

A web page that calls into the real Scala 3 compiler with [`dotc-session.patch`](https://github.com/retronym/jvm-lang-models/blob/main/docs/upstream/dotc-session.patch) applied (typing only the path to the request with `runUpTo`, a resumable run per file, cancellation that unwinds with `CancelledCompilation`), and shows an x-ray of what the driver did for each request: run id, which top-level statements are typed and which of their members were skipped, where a cancellation unwound, `Run.typedTreeCount` consumed, wall time, how many symbols of the file are still `Touched` (`isCompleting`) afterwards, and whether the run is tainted (a cyclic reference prevented: the next request starts fresh). Every number is read from the driver; nothing is simulated. [track-i-dotc-session-design.md](https://github.com/retronym/jvm-lang-models/blob/main/packages/scala/docs/track-i-dotc-session-design.md) is the design these numbers illustrate.

![the page after the cancel story](https://raw.githubusercontent.com/retronym/jvm-lang-models/main/packages/scala/probes/interrupt/xray/xray.png)

## Files

- `XrayServer.scala`: a `com.sun.net.httpserver` server (no new dependencies) that owns one `InteractiveDriver` on the presentation-compiler test classpath and one virtual file `A.scala`. `GET /` serves the page, `GET /source` the generated file (60 top-level objects in the shape of `CompletionSessionSuite.bigSource`), `POST /request` runs one request (`complete`: `runUpTo` at the cursor then `Completion.completions`; `full`: `run`) with an optional cancel checker that fires after N ms, and answers with the x-ray as JSON. The driver's `typedTreeCount` is read reflectively so that the compiler patch stays exactly [`dotc-session.patch`](https://github.com/retronym/jvm-lang-models/blob/main/docs/upstream/dotc-session.patch).
- `xray.html`: the page. CodeMirror 5 for the editor and highlight.js for the statement under the cursor, both from cdnjs; the palette and editor theme are borrowed from the viewer of [retronym/greffier](https://github.com/retronym/greffier) (its compile drawer is the natural home for this page later). Four stories, each stating what the design predicts before a step and checking it against what the driver reported: *session* (complete in object 10, 40, 10 again, then inside `method20`: one run, only the statements at the cursor typed), *cancel, deterministically* (a latched request, see below, cancelled while the typer is blocked, then the same request again), *edit* (a fresh run), *whole file* (`driver.run`, for comparison).
- `tests.macros.Latch` (in the scratch worktree's `presentation-compiler-testcases/src/tests/macros/Latch.scala`, part of the patch): a `transparent inline` macro that blocks the typer while the latch file exists. The generated file's last object has `def giant` whose first statement is `val latched = Latch.await`, and whose last statement depends on `latched`; a "latched" request at that last statement arms the file, the page shows the typer blocked there with the tree count so far, and *Cancel* or *Release* decides what happens next. This is how a cancellation lands at a known point, deterministically, instead of at whatever the 1,024-tree checkpoint happens to hit. Since targeting applies to the statements of a block too, a request elsewhere in `giant` does not block: the macro is expanded only when the statement at the cursor depends on it, which is the honest demonstration of the laziness. (Finding I19 was found here: the macro's inlined trees carry its own source, and the skip rule had compared their spans with the request's offset.)

## The page

Four columns: an explorer over a snapshot of the compiler's sources (see below), the editor, the x-ray of the last request, and the request log; the borders between them drag, and the log collapses into the status bar at the bottom, which shows the latest request in one line, the file and cursor, the session's run id, typed top-level count and skipped count, and the hover state. The editor header has back/forward buttons (Alt+←/→) over the positions a file load or a definition jump came from. The demos (session, cancel, edit, whole file) narrate each step with the design's prediction and a ✓ or ✗ against what the driver reported; *Clear* empties the transcript, the log and the x-ray. The x-ray's last box shows the innermost enclosing definition at the cursor (a `def`, `val`, class or object), not the whole top-level statement.

The outcomes are `fresh` (a new run: new content or no session), `resumed` (the same run typed the statement or member at the cursor), `served` (nothing typed), `full` (the whole file), `cancelled-resume` / `cancelled-fresh`.

## Hover mode

On by default: resting the pointer on an identifier for 150 ms sends a `typeAt` request (`runUpTo` at the identifier's end, then the innermost typed tree there: its symbol with its signature, its widened type, its span and enclosing definition), run on the server's request thread so that it can be cancelled; moving to another identifier before the answer arrives sets the driver's cancel flag, the typer unwinds at its next checkpoint, the answer is dropped, and the popup for the new identifier replaces it. Staying shows the popup with the type and the x-ray summary (`served` / `resumed` / `fresh`, trees, ms). Every hover request is a row in the log, cancelled ones included, so the log is the trace of what the compiler did while the pointer wandered: on the generated file a first hover is a fresh run of 67 trees (51 ms), the next in another object a resume of 65 trees (6 ms); on `Typer.scala` a hover into an untyped method is a resume of that method, and one that moves on before the 450 ms fresh run finishes is cancelled inside it.

## Go to definition

⌘/Ctrl-click on an identifier, or *Go to definition* in the right-click menu (which also offers *Type at the pointer* and *Complete here*). A `definition` request runs `runUpTo` at the identifier and takes the symbol of the innermost typed tree there. A symbol of the current file is found by its own span. Any other is found through an index of the snapshot's top-level definitions, built by one parse pass over the 478 files on the first request (917 definitions, 634 ms): the symbol's top-level class gives the file, then a parse of that file walks the owner chain of names to the member. The page loads the file and highlights the definition's line; the x-ray's request row says which route was taken. Symbols outside the snapshot (the standard library) report their TASTy path instead. From `QuotesAndSplices.scala`: `inferImplicitArg` → `typer/Implicits.scala`, `defn` → `core/Symbols.scala`, `QuotesClass` → `core/Definitions.scala`, each a few milliseconds after the first.

![after a Cmd-click on inferImplicitArg](https://raw.githubusercontent.com/retronym/jvm-lang-models/main/packages/scala/probes/interrupt/xray/xray-definition.png)

## Real compiler sources

The explorer panel on the left lists a snapshot of `compiler/src/dotty/tools/dotc` at the base commit (478 files); a click loads the file into the editor, and every request on it runs against the compiler's own classes (the server's driver uses the whole test JVM's classpath: `scala3-compiler`, `scala3-library`, `scala-library`, `scala-reflect`, `tasty-core`, the asm jars). A completion inside `Typer.typedSelect` on `Typer.scala` (5,532 lines) is a fresh run that types the 53 imports and `class Typer` with 129 of its members skipped, `object Typer` skipped: 1,362 trees, 450 ms cold; a second request in the same method is served from the run. The snapshot is made once from the worktree root, so that the files do not move under the page and the patch's own edits are not what is shown:

```bash
mkdir -p out/xray-sources && git archive d168df2a76 compiler/src/dotty/tools/dotc | tar -x -C out/xray-sources
```

(`-Dxray.sources=<dir>` points the server elsewhere.) The stories run on the generated file only.

![Typer.scala in the x-ray](https://raw.githubusercontent.com/retronym/jvm-lang-models/main/packages/scala/probes/interrupt/xray/xray-typer.png)

## Running it

The live copy runs inside a scala3 worktree at `d168df2a76` with [`dotc-session.patch`](https://github.com/retronym/jvm-lang-models/blob/main/docs/upstream/dotc-session.patch) applied (`git apply`), with `XrayServer.scala` and `xray.html` copied to `presentation-compiler/test/dotty/tools/pc/xray/` (the patch carries the latch macro):

```bash
sbt --mem 8192 -batch "scala3-presentation-compiler/Test/runMain dotty.tools.pc.xray.XrayServer"
```

Then open <http://127.0.0.1:8791/> (`-Dxray.port=...` to change; `-Dxray.page=<path>` if the page is not found relative to the working directory; `-Dxray.latch=<file>` enables latched requests, the sbt route sets neither, so for the latch run the server directly: `sbt "export scala3-presentation-compiler/Test/fullClasspath"`, then `java -Dxray.latch=/tmp/xray-latch -cp <that classpath> dotty.tools.pc.xray.XrayServer` from the worktree root). The first build is about 2.5 minutes; the server warms the driver up on a 5-object file before listening. `Ctrl/⌘-Space` in the editor is "Complete at cursor". The page is read from disk on every request, so it can be edited without a rebuild.

## How to demo the replay

The replay plays a Metals editing session recorded from an `lsp.trace.json` (by `trace_to_events.py` on `claude/mr-measure-37f089`, the format `QueueReplaySuite` reads) against a fresh driver once per mode, with the trace's own timing, and tabulates what each mode did per request kind. Start the server on the presentation compiler's test classpath with the events file, from the worktree root. First the classpath:

```bash
sbt --client "export scala3-presentation-compiler/Test/fullClasspath" | grep '^/' | tail -1 > out/pc-test-classpath.txt
```

Then the server:

```bash
java -Xms20g -Xmx20g -XX:+UseParallelGC -XX:-UseAdaptiveSizePolicy -Xmn10g -XX:+AlwaysPreTouch -Xss16m -Dxray.port=8801 -Dxray.latch=/tmp/xray-latch-8801 -Dxray.replay.events=/Users/jzaugg-virtus/code/scratch/mr-measure/replay-events.json -Dxray.replay.bodyEditEvents=local/body-edit-scenario.json -cp "$(cat out/pc-test-classpath.txt)" dotty.tools.pc.xray.XrayServer
```

The heap and the collector are the perflab campaigns' (a fixed heap with a large young generation and the parallel collector, pre-touched; sized here for a 48 GB machine), so that the throughput demo's numbers are comparable with them: the demo reuses one driver across its runs, as `Throughput` does across its cells (the first open cold, the rest warm; `-Dtp.fresh` for a fresh driver per cell). The page shows the flags the server runs with.

**Throughput.** In the Replay view, *Run throughput* types every file of the source set in one fresh run (`openAll`, every statement skipped, `-Yinteractive-parallel`, the build's options) with N reader threads for each N in the list (1,2,4,6,8 by default), after one single-reader warm-up, and plots the speed-up against N=1 (or the wall time, with the batch typer's time on the same files as a reference line) in the *Throughput* tab of the bottom panel. Per N: wall, worker CPU, root waits, the files whose errors differ from N=1's (should be 0), cycleAborts, raised cycles, and the wall split into the open (serial), typing (until the first thread runs out of files) and the tail; and a timeline with one lane per thread: the open, each file typed (hover for its name), idle at the end.

Open <http://127.0.0.1:8801/>, pick the Replay view (the circular arrow in the activity bar), choose a window of the trace, and press *Run all modes*. The view says which files and how many events and requests the window replays. While a mode runs, the editor shows the replayed file with the request in flight marked, the mode's card shows its progress, and the *Replay findings* tab fills in, per mode and request kind:

- count, cancellations and errors;
- latency (from when the request was due to its answer) median, p90 and max, and the time it queued behind the request before it in its lane;
- time waiting on the completion lock (step 3) or on a completion root another reader holds (step 4), and on a statement another reader was typing; in the baseline, the wait for the other lane's run (`CachingDriver` has one compiler);
- trees typed, runs started and how many of them on the whole file;
- for the whole-file kinds, whether the diagnostics equal those of a whole-file run of the same content in a fresh driver;
- answers that differ from the baseline's answer to the same event, with the first few (fresh-name numbers such as `contextual$3` are left out of the comparison: they follow the order of typing, which targeting changes).

Each mode's card has *Run* to replay that mode again; *Export markdown* downloads the table (`/replay/report.md`), *Copy markdown* copies it.

The modes:

| mode | flags | what it is |
|---|---|---|
| baseline | `-Yno-targeted-requests` | Metals today: `CachingDriver` over the driver, so the whole file is typed once per edit or file switch and same-content requests are served from it (the replay applies the same rule; the hand-driven driver is a `CachingDriver`) |
| default | none | targeting, the session, several readers serialised by the completion lock (step 3) |
| readers | `-Yinteractive-parallel` | parallel readers (step 4) |
| bodyEdits | `-Yinteractive-body-edits` | the session survives an edit inside a body (§12) |
| readersEdits | `-Yinteractive-parallel -Yinteractive-body-edits` | parallel readers, and the session survives an edit inside a body: the fastest, and the freeform driver's default (`-Dxray.mode=<key>` picks another) |
| noCache | `-Yno-targeted-requests` | the plain driver without the cache: every request types the whole file; not what Metals does, and not part of *Run all modes* |

Hover, definition and completion call `readUpTo`, as the providers' `runUpTo` does; document highlight, inlay hints and semantic tokens call `readAll`, as `run` does. The replay does not go through Metals' job queue, which serialises every request: *lanes* 1 is that one queue, 2 (the default) gives the cursor requests a lane of their own beside the whole-file ones, as a presentation compiler with several readers would. didClose drops the file's text only.

On the `demo/integration` branch (this branch with `claude/body-edit` merged; local, not pushed), a fourth mode, `bodyEdits` (`-Yinteractive-body-edits`), and a second scenario: the seeded edit-then-hover session `BodyEditScenarioSuite` writes to `local/body-edit-scenario.json` (Types.scala, 10 edits inside bodies of methods with a declared result type, each followed by a hover elsewhere, a hover inside the edited body and a document highlight, and 2 controls that must take a fresh run: a signature edit and an edit inside a method whose result type is inferred). Start the server with `-Dxray.replay.bodyEditEvents=local/body-edit-scenario.json` as well. The findings add groups for the requests after an edit, and a column with how many of the first requests after an edit found the session's run kept or took a fresh run, against what the scenario expects. The editor's tab strip shows the same for the request in flight, and for requests driven by hand: *run kept* with what the edit did (`body edit of foldOver (typed), shifted …`), or *fresh run*.

Freeform: the selector next to the reset button sets the flags of the driver you drive by hand and resets it; the hovers, the background pass and the latch work as before, and the strip at the left of the status bar shows the latency of each request (the replay's too, coloured by mode).

## What the numbers were (60 objects, warm JVM)

| request | typed | `typedTreeCount` | ms |
|---|---|---|---|
| fresh completion in object 10, prefix version of the patch | 11 statements whole | 791 | 20–40 |
| fresh completion in object 10, path version | `val r` of object 10 only | 43 | 12–60 |
| resume into object 40 | one more statement | 41 | 7 |
| served from what is typed | nothing | 8 | 1–3 |
| whole file | 60 statements | 12,097 | 357 |
| latched request cancelled inside `giant` | nothing; object 59 re-parsed | 981 before the abort | |
