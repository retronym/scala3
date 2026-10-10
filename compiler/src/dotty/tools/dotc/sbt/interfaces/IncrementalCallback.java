package dotty.tools.dotc.sbt.interfaces;

import dotty.tools.dotc.util.SourceFile;

import java.util.EnumSet;
import java.nio.file.Path;

/* User code should not implement this interface, it is intended to be a wrapper around xsbti.AnalysisCallback. */
public interface IncrementalCallback {

  default void api(SourceFile sourceFile, xsbti.api.ClassLike classApi) {
  }

  default void startSource(SourceFile sourceFile) {
  }

  default void mainClass(SourceFile sourceFile, String className) {
  }

  default boolean enabled() {
    return false;
  }

  default void usedName(String className, String name, EnumSet<xsbti.UseScope> useScopes) {
  }

  default void binaryDependency(Path onBinaryEntry, String onBinaryClassName, String fromClassName,
      SourceFile fromSourceFile, xsbti.api.DependencyContext context) {
  }

  default void classDependency(String onClassName, String sourceClassName, xsbti.api.DependencyContext context) {
  }

  default void generatedLocalClass(SourceFile source, Path classFile) {
  }

  default void generatedNonLocalClass(SourceFile source, Path classFile, String binaryClassName,
      String srcClassName) {
  }

  default void apiPhaseCompleted() {
  }

  default void dependencyPhaseCompleted() {
  }

  /** Whether the class with this binary name is defined by another subproject that Zinc has analysed. */
  default boolean isSubprojectClass(String binaryClassName) {
    return false;
  }

  /** How to report class APIs: `TREE` unless Zinc implements `xsbti.AnalysisCallback5`. */
  default ApiMode apiMode() {
    return ApiMode.TREE;
  }

  /** Whether name hashes include sealed children only in the pattern-matching scope. */
  default boolean useOptimizedSealed() {
    return false;
  }

  /**
   * Whether the API of a class inheriting from the library class with this binary name should
   * include its members in full, rather than as stubs (name, access, modifiers).
   */
  default boolean materialiseLibraryMembers(String binaryClassName) {
    return true;
  }

  /** Report a class's API as Zinc stores it, with the hashes of the full API (`ApiMode.HASHES`). */
  default void api(SourceFile sourceFile, xsbti.api.ClassLike thinClass, ClassHashes hashes) {
  }

  /** As `api(SourceFile, ClassLike, ClassHashes)`, with the full API as well (`ApiMode.CHECK`). */
  default void apiCheck(SourceFile sourceFile, xsbti.api.ClassLike fullClass, xsbti.api.ClassLike thinClass,
      ClassHashes hashes) {
  }
}
