# Greffier compile server

Written with LLM assistance (Claude), see `LLM_POLICY.md`.

A local HTTP server that compiles Scala sources with a chosen Scala 3 version, with the recorder attached (the plugin in `../plugin`, hooks delivered by the agent in `../agent`), and serves the recordings to the viewer. Python 3 standard library only; the JDK is `JAVA_HOME_REPLAY` or SDKMAN's 21.0.12+1.1-tem; compiler artifacts come from `coursier`.

## Run

```bash
python3 local/replay/server/server.py --ui <viewer dir>
```

Listens on `127.0.0.1:8777` (`--port`, `--host`). With `--ui`, the viewer is served at `/` from the same origin, and `/recordings.json` is the viewer's own index followed by everything compiled here. Open a recording with `http://127.0.0.1:8777/#src=/api/recordings/<id>.jsonl`. `--default` sets the default version (otherwise the latest 3.7.x).

`client.py` compiles files through the API and waits, e.g. `client.py --scala 3.3.8 --each --download out/ corpus/*.scala`.

## API

Every response carries `Access-Control-Allow-Origin: *`; `OPTIONS` is answered for preflight.

- `GET /api/versions` → `{"versions":[{"scala","ready","notes", "kind","state","joinPoints","warmupMs"}…],"default"}`. Listed: the local `main-<sha>` workspaces, the latest 3.3.x (LTS), the latest 3.7.x and the latest 3.x (from Maven Central, cached a day), and any other workspace. `ready` means the compiler is fetched, the agent and plugin are built for it, and the warm-up compile passed. `notes` names the source overlays and the join points that are missing.
- `POST /api/compile` with `{"scala","options":[…],"files":[{"name","text"}],"label"?}` → `202 {"id"}`. The id is a hash of (scala, options, files): an identical request returns the existing compile. Any exact `3.x.y` version is accepted; the first compile for a version without a workspace builds it (about 10 s plus the download). Limits: 256 KiB of source, 32 files, plain `*.scala`/`*.java` names, 64 options; options the server sets or that reach outside the job directory (`-d`, `-classpath`, `-Xplugin`, `-P:`, `@argfile`, …) are rejected with 400.
- `GET /api/compile/<id>` → `{"id","status":"queued|running|done|error","log","elapsedMs","recording","diagnostics":[{"severity","message","file","line","column","kind"}],"exitCode","scala"}`. Compile errors are `done`, with diagnostics and the stream the recorder wrote (it stops after typer). `error` means no recording: the 120 s timeout (`GREFFIER_TIMEOUT_S`), a crash, or a version that could not be prepared. `log` is the last 8 kB of the compiler's output. Diagnostics are parsed from dotc's `-color:never` output; messages without a position (`bad option '-opt' was ignored`, the recorder's plan warnings) have `file: null`.
- `GET /api/recordings` → the viewer's `recordings.json` format (`gen_index.py`'s fields: name, url, size, units, phases, events, nodes, versions, edges, jvm, classes, schema, compiler), plus `id`, `scala`, `when`, `options`, `label`, `elapsedMs`, `errors`. Compiles of the same files with the same version are one entry with `variants` (base = fewest options; label = the extra options). Newest first.
- `GET /api/recordings/<id>.jsonl` → the stream, `application/x-ndjson`.

One compile runs at a time (a queue and one worker), each in a fresh JVM:

```
java -javaagent:versions/<v>/agent/replay-agent.jar=fixTypeApply,rejected,tentative,research[,lenient]
     -cp <compiler classpath> dotty.tools.dotc.Main -color:never -classpath <library>
     -Xplugin:versions/<v>/plugin -P:replay-record:out=<id>.jsonl <options> -d <tmp> <files>
```

`recordings/` holds `<id>.jsonl`, `<id>.request.json`, `<id>.result.json` and `<id>.log` (command line and full output).

## Versions

A workspace is `versions/<label>/`: `version.json` (classpaths, overlays, state, join-point summary), `agent/` (the agent jars built against this compiler), `plugin/` (the plugin compiled by this compiler), `src/` (the sources as built), `jointable.txt` (the agent's verify table from the warm-up) and `warmup/`.

```bash
python3 local/replay/server/versions.py setup 3.6.4
python3 local/replay/server/versions.py add-local main-ae374e95 <classes dir> <compiler.classpath>
python3 local/replay/server/versions.py rebuild <label>
python3 local/replay/server/versions.py list
```

`setup` fetches `scala3-compiler_3` and `scala3-library_3` with coursier, builds, and runs the warm-up compile. `add-local` snapshots a local nonbootstrapped build (the classes dir replaces the `compiler/target/…` entry of the classpath file; directory entries are copied, jars are referenced). The main workspace here is `classes-stock` (main at ae374e9583) from the reconcile worktree. `rebuild` rebuilds the agent and plugin after their sources change.

A local workspace is *pinned*: the join-point table was written against it, and a missing site fails the compile. A released version runs the agent with `lenient`: a missing site is listed in the table and the hook does not fire.

### What differs between versions, and how it is handled

- **The research-plugin gate.** `Plugins.addPluginPhases` runs research plugins only when the version string contains SNAPSHOT, NIGHTLY or nonbootstrapped: `Properties.researchPluginEnabled` (3.5+) or `Feature.isExperimentalEnabled` (3.3, 3.4). The agent option `research` replaces that one call, in that one method, with `replay.agent.api.Gate` (a `MemberSubstitution`, checked in the emitted bytes like the `Run` substitution). Experimental language features stay as they were.
- **Phase ids.** Before 3.8 a period has 7 bits of phase id (126 usable), and the plan has about 104 miniphases, so there is room neither for a Record phase per miniphase nor per fused group. The plugin (with `MaxPossiblePhaseId` inlined from the compiler it was compiled against) unfuses MegaPhases from the front while ids last, then keeps a Record phase after the last group, the MegaPhases, `typer`, `inlining`, `posttyper`, … and further groups from the front, within the budget. A Record phase then records the trees after all groups since the previous one. It warns with the list. On 3.3.8 and 3.7.4 that is 23 and 20 Record phases (19 tree versions per unit) against 85 on main.
- **Join points.** A site may list alternatives (another method, descriptor, class, advice or field) for other versions; the table says `as …` when one matched. Added: `Run.runPhases$1$$anonfun$1` (3.4 to 3.7 put the per-phase loop in a lambda, and Byte Buddy skips synthetic methods unless told otherwise), 3.3's `inferImplicit` without `ignored`, `ImplicitSearch` with its Context in `x$4`, 3.3's `rank$1`, and `tryEither` on class `Typer`.
- **Plugin sources.** The ASM package (`scala.tools.asm` in released compilers up to 3.7, `org.objectweb.asm` on main and 3.10) is rewritten in the copied agent and plugin sources. `../plugin/compat/` holds the overlays: `opt` or `noopt` (the JVM optimiser API, 3.8+) and `skipIfJava` or `noSkipIfJava` (`Phase.skipIfJava`, 3.4+), chosen by feature tests on the compiler's class files. The shared sources became version-neutral (`Text.Str` by type pattern, `Phase.run` public, `javaSimpleName` via the name).

### Status (October 2026)

| version | join points | missing | tree versions per unit (corpus) | compile (corpus file, fresh JVM) |
|---|---|---|---|---|
| main-ae374e95 | 42/42 | – | 85 | 2.6–4.6 s, 2.5–9 s with `-opt` |
| 3.10.0 | 35/42 | `generateClassNodes`, optimiser (4), `serializeClassNode`, `SimpleName.hashCode` | 86 | 1.9 s |
| 3.7.4 | 30/42 | the 10 JVM backend sites, `SimpleName.hashCode`, `AvoidNameKind$$anon$11` | 19 | 2.2–3.1 s |
| 3.3.8 | 30/42 | the 10 JVM backend sites, `SimpleName.hashCode`, `comesTooLate$1` | 19 | 1.8–2.9 s |

- So released versions have the tree versions and all typer events (Typed, Adapt, Complete, ImplicitSearch, Candidate, Scope, Rejected except `comesTooLate` on 3.3, Tentative, Report), but no JVM records: the backend before 3.8 has different methods (`genClass(TypeDef, CompilationUnit)`, `genDefDef` without a Context, no `serializeClassNode`). 3.10.0 has the new backend without the optimiser and `generateClassNodes`, so it has no JVM records either.
- The Name content hashes only partly apply: `SimpleName` already declares `hashCode` (= `start`) before main, and 3.7's enum cases are numbered differently. Streams on those versions may not be reproducible run to run (implicit candidate order follows identity hashes).
- On main the server's streams are byte-identical (after the `meta` line) to the reconcile branch's `out/` for the five corpus files, with and without `-opt`.
