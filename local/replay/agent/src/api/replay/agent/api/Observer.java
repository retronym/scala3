// LLM-assisted (Claude), see LLM_POLICY.md.
package replay.agent.api;

/**
 * The agent-side mirror of the fork's {@code dotty.tools.dotc.typer.TyperObserver}.
 *
 * This interface lives on the bootstrap class path, so it can name no compiler types: every
 * compiler object is passed as {@code Object} and the implementation (the plugin's bridge, loaded
 * by the plugin's class loader) casts. A {@code Span} is passed as its {@code long} coordinates.
 * The trailing {@code ctx} is the {@code Context} the hooked method was called with.
 */
public interface Observer {
  void phaseBegin(Object phase, Object ctx);
  void phaseEnd(Object phase, Object units, Object ctx);

  void typedEnter(Object tree, Object pt, Object ctx);
  void typedExit(Object tree, Object pt, Object result, Object ctx);

  void adaptEnter(Object tree, Object pt, Object ctx);
  void adaptExit(Object tree, Object pt, Object result, Object ctx);

  void completeEnter(Object denot, Object ctx);
  void completeExit(Object denot, boolean ok, Object ctx);

  void implicitSearchEnter(Object pt, Object argument, long span, Object ctx);
  void implicitSearchExit(Object pt, Object argument, long span, Object result, Object ctx);

  /** {@code cand} is an {@code Implicits.Candidate}; the fork passes {@code cand.ref}. */
  void candidateEnter(Object cand, long span, Object ctx);
  void candidateExit(Object cand, long span, Object result, Object ctx);

  void report(Object diagnostic, Object reporter, Object ctx);

  /** {@code TreeCopier.finalize}; the stock method has no Context. */
  void copied(Object tree, Object copy);

  // ---- JVM backend (the fork's backend hooks; option `nojvm` turns them off) ----

  /** {@code CodeGen.generateClassNodes} returned {@code nodes}: a {@code List} of (ClassNode, metadata) pairs, the mirror
   *  second. On 3.3 to 3.10 ({@code genClassDef}) the metadata is null. */
  void classNodes(Object typeDef, Object nodes, Object ctx);
  /** {@code genDefDef} created {@code method} for {@code dd} ({@code initJMethod} returned). */
  void genMethod(Object dd, Object method, Object ctx);
  void genLoadEnter(Object tree, Object method, Object ctx);
  void genLoadExit(Object tree, Object method, Object ctx);
  /** {@code traceIfChanged(pass)} in {@code LocalOptimizer.methodOptimizations}. No Context. */
  void optimizerPass(String owner, Object method, String pass);
  /** {@code inlineCallsite(callsite)} called from {@code GlobalOptimizer}'s {@code doInline} into {@code method}. */
  void inlined(Object method, Object callsite, Object insnMap);
  /** {@code CodeGen.serializeClassNode(classNode)}, {@code PostProcessor.serializeClass} on 3.3 to 3.10 (after the ClassWriter, which does not change the node). */
  void classWritten(Object classNode);

  // ---- not in the fork (agent options `rejected`, `tentative`) ----

  /** An implicit reference was dropped before {@code tryImplicit} ({@code ref}: TermRef or Candidate). */
  void rejected(Object ref, String reason, Object ctx);

  /** {@code ImplicitSearch.searchImplicit(eligible, contextual)}: one ranking over one scope. */
  void scopeEnter(Object eligible, boolean contextual, Object ctx);
  void scopeExit(Object eligible, boolean contextual, Object result, Object ctx);
  /** Each step of {@code rank(pending, found, rfailures)} inside the current scope. */
  void rankStep(Object pending, Object found, Object ctx);

  /** {@code Typer.tryEither}: typing under a fresh TyperState that is committed or dropped. */
  void tentativeEnter(Object ctx);
  void tentativeExit(boolean threw, Object ctx);
  /** The FreshContext made by the first {@code setNewTyperState} inside the current tryEither. */
  void tentativeContext(Object freshCtx);
  void typerStateCommit(Object typerState);
  /** {@code StoreReporter.report} on a TyperState's buffering reporter (the fork's hook never sees it). */
  void bufferedReport(Object diagnostic, Object reporter, Object ctx);
}
