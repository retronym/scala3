// LLM-assisted (Claude), see LLM_POLICY.md.
package replay.nsc.api;

/**
 * Hooks into an unmodified nsc (scalac 2.13), delivered by the agent. On the bootstrap class path, so it
 * names no compiler types: compiler objects are {@code Object}s that the plugin's implementation casts.
 * See ../../DESIGN.md for the site behind each method.
 */
public interface Observer {
  /** {@code Profiler.beforePhase} / {@code afterPhase} around {@code globalPhase.run()} in {@code Run.compileUnitsInternal}. */
  void phaseBegin(Object phase);
  void phaseEnd(Object phase);
  /** {@code Run.compileUnitsInternal} returned or threw. */
  void runEnd();

  /** {@code Typer.typed(tree, mode, pt)}; {@code result} is null if it threw. */
  void typedEnter(Object typer, Object tree, int mode, Object pt);
  void typedExit(Object typer, Object tree, int mode, Object pt, Object result);
  /** {@code Typer.adapt(tree, mode, pt, original)}. */
  void adaptEnter(Object typer, Object tree, int mode, Object pt);
  void adaptExit(Object typer, Object tree, int mode, Object pt, Object result);

  /** {@code Namers.LockingTypeCompleter.complete(sym)}: every namer completer. */
  void completeEnter(Object completer, Object sym);
  void completeExit(Object completer, Object sym, boolean ok);

  /** {@code ImplicitSearch.bestImplicit()}; {@code result} is null if it threw. */
  void implicitSearchEnter(Object search);
  void implicitSearchExit(Object search, Object result);
  /** {@code ImplicitSearch.typedImplicit(info, ptChecked, local)}. */
  void candidateEnter(Object search, Object info);
  void candidateExit(Object search, Object info, Object result);

  /** {@code Reporter.filteredInfo(pos, msg, severity, actions)}: every report that reaches the reporter. */
  void report(Object reporter, Object pos, String msg, Object severity);
  /** {@code Contexts.BufferingReporter.issue(err, context)}: an error buffered by {@code silent} typing. */
  void bufferedReport(Object err, Object context);

  /** {@code Tree.copyAttrs(from)}: the end of every strict tree copy. */
  void copied(Object from, Object copy);

  // ---- JVM backend (agent option `nojvm` turns them off) ----

  /** {@code CodeGen.genClass(cd, unit)} returned {@code classNode}. */
  void classNode(Object classDef, Object classNode);
  /** {@code CodeGen.genMirrorClass(moduleClass, unit)} returned {@code classNode}. */
  void mirrorNode(Object moduleClass, Object classNode);
  /** {@code genDefDef(dd)} created {@code method} ({@code initJMethod} returned). */
  void genMethod(Object dd, Object method);
  /** {@code genStat(tree)} and {@code genLoad(tree, expectedType)}, with the method being emitted. */
  void genLoadEnter(Object tree, Object method);
  void genLoadExit(Object tree, Object method);
  /** {@code traceIfChanged(pass)} in {@code LocalOpt.methodOptimizations(method, owner)}. */
  void optimizerPass(String owner, Object method, String pass);
  /** {@code Inliner.inlineCallsite(callsite, ...)} returned the instruction map. */
  void inlined(Object callsite, Object insnMap);
  /** {@code PostProcessor.serializeClass(classNode)} (the ClassWriter does not change the node). */
  void classWritten(Object classNode);
}
