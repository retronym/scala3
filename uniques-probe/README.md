# Uniques probe (DO NOT MERGE)

Compiles a corpus with `Uniques.probe` installed and reports hash-consing traffic per table: hits, misses, hits whose caller allocated parts that the hit made garbage, and, for sampled events (1 in 16), the phase, call site, the "temporary" parts of the type (`TypeVar`, `TypeParamRef`, ...) and whether a newly entered type is still alive at the end of the run.

```bash
sbt "scala3-compiler-nonbootstrapped/compile" "export scala3-compiler-nonbootstrapped/Runtime/fullClasspath"
CP=<the exported classpath>
java -cp "$CP" dotty.tools.dotc.Main -classpath "$CP" -d /tmp/probe uniques-probe/Probe.scala
java -Xmx4g -Xss4m -cp "/tmp/probe:$CP" dotty.tools.dotc.core.ProbeMain <scala3-benchmarks>/bench-sources/scalaz
```
