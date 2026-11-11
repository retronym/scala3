# A-classpath-io: Scala 3 classpath / io vs Scala 2.13.x

Scala 3 checkout: /Users/jzaugg-virtus/code/.worktrees/scala3/scala3-untracked-fork-debt-803909 (HEAD a98b104aaa). Scala 2 ref: origin/2.13.x.

## Pairs

"Later s2 commits" = non-merge commits on the s2 path since the Dotty port date (2017-04-05 for classpath/, 2017-04-27 for io/).

| s3 file | s2 file | port date | later s2 commits | notes |
|---|---|---|---|---|
| dotc/classpath/ZipAndJarFileLookupFactory.scala | nsc/classpath/ZipAndJarFileLookupFactory.scala | 2017-04-05 (2b04d2a961) | 33 | `FileBasedCache` heavily simplified: no release in key, no size in stamp, no refcount/close, no closeable registry, no ManifestResources. See F1-F3 |
| dotc/classpath/ZipArchiveFileLookup.scala | nsc/classpath/ZipArchiveFileLookup.scala | 2017-04-05 | 15 | Rewritten to use `AbstractFile.lookupPath`; no `close()`. F3 |
| dotc/classpath/DirectoryClassPath.scala (incl. Jrt/CtSym) | nsc/classpath/DirectoryClassPath.scala | 2017-04-05 | 34 | JrtClassPath/CtSymClassPath rewritten 2026; no caching/closing of ct.sym FS (F4), no `-javabootclasspath` override, no `--system`/unsafe packages (F7) |
| dotc/classpath/VirtualDirectoryClassPath.scala | nsc/classpath/VirtualDirectoryClassPath.scala | 2017-04-05 | 12 | Scala-3-side regression from da99eb87b0 (F5) |
| dotc/classpath/AggregateClassPath.scala | nsc/classpath/AggregateClassPath.scala | 2017-04-05 | 18 | Re-done; no IOException->FatalError wrap but SymbolLoader catches IOException. OK |
| dotc/classpath/ClassPath.scala | nsc/util/ClassPath.scala | 2017-04-05 | 14 | Rewritten; manifest/URISyntax handling present |
| dotc/classpath/ClassPathFactory.scala | nsc/classpath/ClassPathFactory.scala | 2017-04-05 | 13 | Rewritten (release threaded through `getDirectory`) |
| dotc/classpath/FileUtils.scala | nsc/classpath/FileUtils.scala | 2017-04-05 | 12 | Rewritten for TASTy (`classNameToTasty`, betasty). `.sig` is handled only in CtSym |
| dotc/classpath/PackageNameUtils.scala | nsc/classpath/PackageNameUtils.scala | 2017-04-05 | 4 | Near identical. Shared latent bug in `packageContains` (see Method notes) |
| io/ZipArchive.scala | reflect/io/ZipArchive.scala | 2017-04-27 (693351ff85) | 38 | Rewritten around `JarFile(..., Runtime.Version)`; no ZipFile pool, no `URLZipArchive`/`ManifestResources`. F3, F6, F7 |
| io/AbstractFile.scala | reflect/io/AbstractFile.scala | 2017-04-27 | 18 | Heavily reworked in 2026 (no `getURL`, no `sizeOption`/`unsafeToByteArray`) |
| io/PlainFile.scala, Path.scala, File.scala, Directory.scala | reflect/io/{PlainFile,Path,File,Directory}.scala | 2017-04-27 | 10 / 14 / 6 / 5 | Reworked on `java.nio.file.Path`; the `Path.parent` logic matches 2.13. No findings |
| io/VirtualFile.scala | reflect/io/VirtualFile.scala | 2017-04-27 | 8 | `equals`/`hashCode` deliberately removed (3de898e55a). F6 (name via `Path.of`) |
| io/VirtualDirectory.scala | reflect/io/VirtualDirectory.scala | 2017-04-27 | 7 | Same. S2 `clear()` not ported (feature) |
| io/JarArchive.scala | none (S2: `scala.tools.nsc.util.JarFactory`) | 2017-11-15 | n/a | Dotty-only (zipfs-based output jar) |
| io/FileWriters.scala | nsc/backend/jvm/ClassfileWriters.scala | 2023-03-28 (cd43ea3658) | 28 | Re-synced in 2023 and later. Contains the 2.13 fixes (symlink `-d`, interrupt cleanup, Windows devices, `STORED` jars, `!` path for Zinc). No findings |
| io/AbstractFileClassLoader.scala | reflect/internal/util/AbstractFileClassLoader.scala | 2025-10-22 (ff05ec8abe) | 9 | Ported 2025; JDK20 URL deprecation already there. Intentionally simpler (child-first `loadClass`, no ProtectionDomain/package defs) |
| dotc/config/PathResolver.scala | tools/util/PathResolver.scala | pre-2017 | 26 | See F7 |
| dotc/config/JavaPlatform.scala, Platform.scala | nsc/backend/JavaPlatform.scala, Platform.scala | pre-2017 | 7 | Different responsibilities (Dotty's `Platform` is a different shape). No findings |
| sbt-bridge/src | scala/scala src/sbt-bridge | n/a | n/a | Not compared. The Dotty bridge is Java and Dotty-specific; low priority per brief |

## Findings

### F1: ZipAndJar classpath cache ignores `-release`, so a cached jar view from one `-release` leaks into another compiler in the same JVM
- severity: medium
- confidence: high
- s3: compiler/src/dotty/tools/dotc/classpath/ZipAndJarFileLookupFactory.scala:22-29 (`cache.getOrCreate(jFile.toPath, ...)`, `FileBasedCache` at :76)
- s2: src/compiler/scala/tools/nsc/classpath/ZipAndJarFileLookupFactory.scala ; fix commits: 493f985520 2021-05-04 "Include --release version in cache key for classpath cache"; 31d8d058fa 2021-06-17 "Include -release arg in cache key for ct.sym classpath element" (scala/scala#9671)
- what: `FileBasedCache` is a process-global singleton on the `ZipAndJarClassPathFactory` object. Its key is only the jar path, but the cached `ZipArchiveClassPath` bakes `release` into the `JarFile(..., Runtime.Version)` it opened. A second Run/ContextBase in the same JVM with a different `-release` (sbt server, Bloop, Metals, mixed-JDK cross-builds) gets the first compiler's multi-release view (wrong `META-INF/versions/N` class versions). Scala 2 hit exactly this and added the release to the key (and fixed a related size/buffer bug for the versioned entry).
- verified: read the whole factory; cache key is the `java.nio.file.Path` only, `release` is passed solely to `createForZipFile`. `git log -S` shows no later change. The only caller, `ClassPathFactory.newClassPath`, does not clear the cache. Existing test (`compiler/test/.../MultiReleaseJarTest.scala`) does not exercise two releases in one JVM. Not reproduced at runtime (needs a Context), but the code path is unambiguous.

### F2: Cache stamp lacks file size (and a vanished jar throws instead of being treated as a miss)
- severity: low
- confidence: high
- s3: compiler/src/dotty/tools/dotc/classpath/ZipAndJarFileLookupFactory.scala:77-86
- s2: same s2 file ; fix commit: 3e500b21a5 2019-03-04 "Use file size as part of the cache invalidation"; later NoSuchFileException handling in `getOrCreate`
- what: `Stamp(lastModified, fileKey)` only. On filesystems with coarse mtime (FAT, some NFS/docker bind mounts, Windows without `fileKey`) a jar rewritten within the mtime tick with a different size keeps a stale `FileZipArchive` index, which gives wrong entry listings or "invalid LOC header". Scala 2 also catches `NoSuchFileException` and uses a dummy stamp. Scala 3's `Files.readAttributes` throws if the jar is deleted between `getDirectory` and `create`.
- verified: read the Stamp definition. No `size`, no try/catch. No Scala 3 commit touches it since the port.

### F3: Zip/jar handles are never closed, and cache entries are never refcounted or evicted
- severity: medium
- confidence: high
- s3: compiler/src/dotty/tools/io/ZipArchive.scala:99-200 (`LeakyEntry`, `closeables`, `close()`); compiler/src/dotty/tools/dotc/classpath/ZipArchiveFileLookup.scala:20 (`archive`, no `close`); ZipAndJarFileLookupFactory.scala:95-99 (`clear()` "TODO support closing")
- s2: ZipAndJarFileLookupFactory.scala / ZipArchive.scala / ZipArchiveFileLookup.scala / PathResolver.scala ; commits: cfbb2a2d16 2019-01-15 "[backport] Resource management for macro/plugin classloaders, classpath JARs" (scala/scala#7366, #7644); 683f698459 2025-03-08 "Fix file handle leak under the non-default classpath mode" (scala/bug#13094); 211cc41a15 2025-06-24 "Try and avoid potential race condition" (Metals); 33a331c52b 2022-02-21 "Reduce default capacity of classpath's pool of ZipFile"
- what: Scala 2 threads a `CloseableRegistry` through `PathResolver`/`ClassPathFactory`, refcounts shared cache entries and closes them (deferred 1s) when the last compiler is closed. In Scala 3 `FileZipArchive.close()` has no caller anywhere (grep over compiler/src, repl, sbt-bridge). With the cache enabled, the `ZipFile` of a replaced/modified jar is dropped from the map but stays open until GC (JDK Cleaner); with `-Ydisable-flat-cp-caching` every Run leaks one `ZipFile` per jar until GC. `-Dscala.classpath.closeZip=true` is the only mitigation (and the property is undocumented). Observable effects: file locks on jars on Windows (cannot overwrite or delete a module jar while a long-lived compiler runs), fd growth in servers, and the cache grows monotonically (no eviction).
- verified: `grep -rn "\.close()\|closeables\|FileBasedCache\|FileZipArchive" compiler/src repl sbt-bridge` shows no close path; `clear()` is not called anywhere either.

### F4: `--release` classpath element (ct.sym) is rebuilt per Run, and its zip FileSystem is never closed
- severity: medium
- confidence: high
- s3: compiler/src/dotty/tools/dotc/classpath/DirectoryClassPath.scala:103-118 (`JrtClassPath.apply`), :202-230 (`CtSymClassPath`: `FileSystems.newFileSystem(ctSym, ...)`, no close)
- s2: nsc/classpath/DirectoryClassPath.scala ; commits: 0714941e30 2021-05-04 "Cache JRT and --release (ct.sym) classpath elements" (scala/scala#9557); 31d8d058fa (release in key)
- what: every `JavaPlatform.classPath` (one per ContextBase, i.e. every compile in sbt/Bloop/Metals) with `-release N` below the running JDK constructs a new `CtSymClassPath`: opens a new `ZipFileSystem` on `$JAVA_HOME/lib/ct.sym` and walks the whole release tree to build `packageIndex`. Scala 2 caches it (key = release, with ct.sym stamp) and closes it via the registry. Scala 3 repeats the walk and leaks the FS.
- verified: ran against the compiled classes in /Users/jzaugg-virtus/code/scala/scala3/compiler/target (JDK 21.0.12): script `<scratchpad>/work/A-classpath-io/CT.java` constructs `CtSymClassPath(ct.sym, 11)` 4 times: 127 ms, 24, 19, 16 ms, and `lsof` shows 3 handles still open on ct.sym afterwards (4th/earlier ones GC-dependent).

### F5: `VirtualDirectoryClassPath.hasPackage`/`packages` fail for every non-root package (Scala 3 regression, not a 2.13 divergence)
- severity: medium
- confidence: high (reproduced)
- s3: compiler/src/dotty/tools/dotc/classpath/VirtualDirectoryClassPath.scala:11-12 (`getSubDir` -> `dir.lookupPath(packageDirName, File.separatorChar, directory = true)`); compiler/src/dotty/tools/io/AbstractFile.scala `lookupPath`; `DirectoryLookup.getDirectory` passes `PackageNameUtils.dirPathTrailingSlash(pkg)` (e.g. `"p/q/"`)
- s2: nsc/classpath/VirtualDirectoryClassPath.scala splits on `/` (`String.split` drops the trailing empty piece) ; related s2 fix 8e58ea0bc6 2019-01-15 "Valid URL for VirtualDirectory classpath"
- what: before da99eb87b0 "Improve IO package encapsulation (#26398)" (2026-07-06) the code did `packageDirName.split(separator)`, which discards the trailing empty segment. The refactor replaced it with `AbstractFile.lookupPath`, which does not: for `"p/"` the final component is `""`, `lookupName("", directory = true)` returns null, so the result is `None`. Consequently `hasPackage("p")` is false, `packages("p")` is empty, and `AggregateClassPath.aggregatesForPackage` filters the entry out, so `findClassFile("p.q.C")` through an aggregate classpath also returns None. Root-package classes still work (REPL wrappers live in the root package, which is why it is not obviously noticed). Any in-memory output used as an input classpath with packaged classes is affected.
- verified: Java harness `.../scratchpad/work/A-classpath-io/VT.java` with a `VirtualDirectory` containing `p/q/C.class`: `hasPackage("p") = false`, `hasPackage("p.q") = false`, `packages("p") = List()`, `packages("") = List(p)`, `findClassFile("p.q.C") = Some(...)` (direct), `lookupPath("p/", '/', "", true) = None`. (Compiled classes are from the main checkout /Users/jzaugg-virtus/code/scala/scala3 at d168df2a76; the relevant sources are byte-identical to this worktree.)

### F6: Zip entry `name` is computed with `java.nio.file.Path.of(path)` (regression from the 2026 IO refactor)
- severity: low
- confidence: medium (Windows behaviour not run)
- s3: compiler/src/dotty/tools/io/VirtualFile.scala:19-26, used by `ZipArchive.Entry` (compiler/src/dotty/tools/io/ZipArchive.scala:55)
- s2: reflect/io/ZipArchive.scala (`Entry(path) extends VirtualFile(baseName(path), path)`, string-based `baseName`)
- what: since da99eb87b0 each jar entry's name goes through `Path.of(entryName).getFileName`. Entry names are not native paths: on Windows an entry containing `<>:"|?*` or a backslash throws `InvalidPathException` (unchecked, not caught by `SymbolLoader`'s `IOException` handler) while indexing the jar, and a name with `\` gets split at the wrong place. The old code used the plain string `baseName`. It also adds a `Path` allocation per entry on the hot indexing path. The code comment shows the authors already hit this for `<example>` names.
- verified: read git diff of da99eb87b0; the old Entry used `VirtualFile(baseName(path), path)`. Not reproduced (macOS host).

### F7: `-javabootclasspath` no longer suppresses the JRT classpath; no `--system` / unsafe-package escape hatch
- severity: low
- confidence: medium
- s3: compiler/src/dotty/tools/dotc/config/PathResolver.scala:207-210 (`JrtClassPath(release)` unconditional)
- s2: tools/util/PathResolver.scala ; commits: f69089cb7d 2023-03-06 "javabootclasspath supersedes jrt"; 8f55db50f9 2023-09-13 "-Yrelease:sun.misc opens packages in jrt hidden by --release"; 98726c0e1f 2024-07-04 "Add support for alternative system images"
- what: Scala 2 drops the jrt/ct.sym element when `-javabootclasspath` is set by the user, so an explicit boot classpath is respected. Scala 3 always puts JRT first, so a user-supplied boot classpath is shadowed. Scala 3 also has no way to see `sun.misc.*`/`jdk.internal.*` packages hidden by `--release` (Scala 2: `-Yrelease:`), nor a `--system` image option. Feature gaps rather than crashes.
- verified: read basis list; no reference to `javabootclasspath.isSetByUser` or equivalent in `PathResolver`/`JrtClassPath`.

### F8: A single shared `ZipFile` per cached jar (read contention under concurrent compilers)
- severity: low
- confidence: medium
- s3: compiler/src/dotty/tools/io/ZipArchive.scala:116-127,153-166 (`LeakyEntry` uses one `ZipFile`)
- s2: reflect/io/ZipArchive.scala ; commits: 31255c38f0 2021-05-04 "Avoid contention in classpath access"; 33a331c52b 2022-02-21 (pool capacity)
- what: the cache shares one `ZipArchive` across concurrent compilers (sbt parallel modules, Bloop). `ZipFile.getInputStream`/reads synchronize on the single `ZipFile`; Scala 2 switched to a bounded per-archive pool of `ZipFile`s. Only matters with several Dotty compilers in one JVM.
- verified: read code; single `ZipFile` captured in `LeakyEntry`.

## Already ported / not applicable

- Root-entry seeding for zips (db2b70b7de, 965f6ed447/cac58b53cc/411d548fb8): `allDirs("/")`/root handled in `FileZipArchive`.
- `a.b` beside `a/b` jar regression (55308dcd44): Dotty indexes a slash tree and resolves packages by `lookupPath`, so the dotted-key bug cannot occur.
- Multi-release jars: versioned `JarFile(..., Version)` plus `META-INF/versions/` skip is present (Dotty also versions for the default release, unlike 2.13).
- Dir named `x.jar`/`x.zip` (eef877c370): `AbstractFile.getDirectory` checks `isDirectory` first.
- `ClassPath.expandManifestPath` URISyntaxException (bb50eed0d7), wildcard expansion on Windows: handled.
- `FileUtils.mayBeValidPackage`, tasty sibling suppression, `Nothing$`/`Null$`/`$` special cases: handled (Dotty-specific).
- ClassfileWriters fixes: symlinked `-d` (9051019cfd), CREATE_NEW fast path/avoid double open (1181fdd24b), interrupt cleanup (8fe05cc853), Windows `-d out.jar` Zinc separator (146a1a4915), STORED jars CRC (22def724e4 area), Windows device names (69c8c81f5c; Dotty throws instead of warning): all in `FileWriters.scala`.
- `PathResolver.searchForBootClasspath` CME-safe property scan (f6859f28bb): ported.
- ct.sym layout for JDK < 12 (7c4747703f): not needed, Dotty requires JDK 17+.
- `VirtualFile.equals/hashCode` and `VirtualDirectory.clear`: removed on purpose in Dotty (3de898e55a).
- `AbstractFileClassLoader` JDK 20 deprecation (258a13bab7): in place.
- `PlainFile.lastModified` throws on a missing file whereas Scala 2 returns 0: only reachable for deleted files, not listed as a finding.

## Method notes

- Similarity tables show 5-45% shared lines for this area: nearly everything was rewritten into Scala 3 syntax or restructured (2026 "Simplify classpath code", "Improve IO package encapsulation", "Centralize JAR handling"). Judged semantically, not by line diff. The two 2026 Dotty refactors (da99eb87b0, 3718b0cf7e/dcc910fe0b) are where the Dotty-only regressions (F5, F6) come from.
- `FileWriters.scala` and `AbstractFileClassLoader.scala` were re-synced from Scala 2 after the original port (2023 and 2025), so the 2017-2022 2.13 history is mostly already included.
- Latent bug shared by both compilers (not a divergence): `PackageNameUtils.packageContains(inPackage, name)` tests `name.startsWith(inPackage)` without requiring the next char to be `.`, so `packageContains("scala", "scalaz.foo")` is true. Currently only used by `CtSymClassPath.packages`; harmless for the JDK package set.
- Unverified speculation: `JrtClassPath.classes` uses `inPackage.replace('.', JFile.separatorChar)` to resolve inside the jrt filesystem (3718b0cf7e/aac9dc1c71). jrtfs uses `/`, so on Windows this looks wrong, but Windows CI presumably passes; not reproduced here, not listed as a finding.
- Runtime checks used classes from /Users/jzaugg-virtus/code/scala/scala3/compiler/target/scala3-compiler-bootstrapped/scala-3.10.2-RC1-bin-SNAPSHOT-nonbootstrapped/classes, run with plain `java` against scala-library 3.10.2 nightly jars. Scratch files: <scratchpad>/work/A-classpath-io/ (VT.java, CT.java, env.sh).
- sbt-bridge/src was not compared (Java, Dotty-specific, lower priority per brief).
