package dotty.tools.xsbt;

import dotty.tools.dotc.util.SourceFile;
import dotty.tools.dotc.sbt.interfaces.ApiMode;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Function;

public final class IncrementalCallback implements dotty.tools.dotc.sbt.interfaces.IncrementalCallback {

  private final xsbti.AnalysisCallback delegate;
  private final Function<SourceFile, xsbti.VirtualFile> asVirtualFile;

  public IncrementalCallback(xsbti.AnalysisCallback delegate, Function<SourceFile, xsbti.VirtualFile> asVirtualFile) {
    this.delegate = delegate;
    this.asVirtualFile = asVirtualFile;
    this.callback5 = Callback5.of(delegate);
  }

  @Override
  public void api(SourceFile sourceFile, xsbti.api.ClassLike classApi) {
    delegate.api(asVirtualFile.apply(sourceFile), classApi);
  }

  @Override
  public void startSource(SourceFile sourceFile) {
    delegate.startSource(asVirtualFile.apply(sourceFile));
  }

  @Override
  public void mainClass(SourceFile sourceFile, String className) {
    delegate.mainClass(asVirtualFile.apply(sourceFile), className);
  }

  @Override
  public boolean enabled() {
    return delegate.enabled();
  }

  @Override
  public void usedName(String className, String name, java.util.EnumSet<xsbti.UseScope> useScopes) {
    delegate.usedName(className, name, useScopes);
  }

  @Override
  public void binaryDependency(java.nio.file.Path onBinaryEntry, String onBinaryClassName, String fromClassName, SourceFile fromSourceFile, xsbti.api.DependencyContext context) {
    delegate.binaryDependency(onBinaryEntry, onBinaryClassName, fromClassName, asVirtualFile.apply(fromSourceFile), context);
  }

  @Override
  public void classDependency(String onClassName, String sourceClassName, xsbti.api.DependencyContext context) {
    delegate.classDependency(onClassName, sourceClassName, context);
  }

  @Override
  public void generatedLocalClass(SourceFile source, java.nio.file.Path classFile) {
    delegate.generatedLocalClass(asVirtualFile.apply(source), classFile);
  }

  @Override
  public void generatedNonLocalClass(SourceFile source, java.nio.file.Path classFile, String binaryClassName, String srcClassName) {
    delegate.generatedNonLocalClass(asVirtualFile.apply(source), classFile, binaryClassName, srcClassName);
  }

  @Override
  public void apiPhaseCompleted() {
    delegate.apiPhaseCompleted();
  }

  @Override
  public void dependencyPhaseCompleted() {
    delegate.dependencyPhaseCompleted();
  }

  private java.lang.reflect.Method isSubprojectClassMethod;
  private boolean isSubprojectClassLookedUp = false;

  private java.lang.reflect.Method findIsSubprojectClass() {
    if (delegate == null) return null;
    try {
      return delegate.getClass().getMethod("isSubprojectClass", String.class);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  @Override
  public boolean isSubprojectClass(String binaryClassName) {
    if (!isSubprojectClassLookedUp) {
      isSubprojectClassMethod = findIsSubprojectClass();
      isSubprojectClassLookedUp = true;
    }
    if (isSubprojectClassMethod == null) return false;
    try {
      return (Boolean) isSubprojectClassMethod.invoke(delegate, binaryClassName);
    } catch (ReflectiveOperationException e) {
      return false;
    }
  }

  /**
   * `xsbti.AnalysisCallback5`, called reflectively: we build against a compiler-interface that
   * predates it, and run against whichever one Zinc provides. Null if Zinc doesn't implement it.
   */
  private static final class Callback5 {
    final MethodHandle apiMode;
    final MethodHandle useOptimizedSealed;
    final MethodHandle materialiseLibraryMembers;
    final MethodHandle api;
    final MethodHandle apiCheck;
    final MethodHandle newClassHashes;

    Callback5(Class<?> cb5, Class<?> classHashes) throws ReflectiveOperationException {
      MethodHandles.Lookup lookup = MethodHandles.publicLookup();
      apiMode = lookup.findVirtual(cb5, "apiMode", MethodType.methodType(cb5.getClassLoader().loadClass("xsbti.AnalysisCallback5$ApiMode")));
      useOptimizedSealed = lookup.findVirtual(cb5, "useOptimizedSealed", MethodType.methodType(boolean.class));
      materialiseLibraryMembers = lookup.findVirtual(cb5, "materialiseLibraryMembers", MethodType.methodType(boolean.class, String.class));
      api = lookup.findVirtual(cb5, "api", MethodType.methodType(void.class, xsbti.VirtualFileRef.class, xsbti.api.ClassLike.class, classHashes));
      apiCheck = lookup.findVirtual(cb5, "apiCheck", MethodType.methodType(void.class, xsbti.VirtualFileRef.class, xsbti.api.ClassLike.class, xsbti.api.ClassLike.class, classHashes));
      newClassHashes = lookup.findConstructor(classHashes, MethodType.methodType(void.class, int.class, int.class, xsbti.api.NameHash[].class, boolean.class));
    }

    static Callback5 of(xsbti.AnalysisCallback delegate) {
      if (delegate == null) return null;
      try {
        ClassLoader loader = xsbti.AnalysisCallback.class.getClassLoader();
        Class<?> cb5 = Class.forName("xsbti.AnalysisCallback5", false, loader);
        if (!cb5.isInstance(delegate)) return null;
        return new Callback5(cb5, Class.forName("xsbti.ClassHashes", false, loader));
      } catch (ReflectiveOperationException | LinkageError e) {
        return null;
      }
    }
  }

  private final Callback5 callback5;

  private static RuntimeException rethrow(Throwable t) {
    if (t instanceof RuntimeException) return (RuntimeException) t;
    if (t instanceof Error) throw (Error) t;
    return new RuntimeException(t);
  }

  private Object toXsbti(dotty.tools.dotc.sbt.interfaces.ClassHashes h) throws Throwable {
    return callback5.newClassHashes.invoke(h.apiHash(), h.extraHash(), h.nameHashes(), h.hasMacro());
  }

  @Override
  public ApiMode apiMode() {
    if (callback5 == null) return ApiMode.TREE;
    try {
      return ApiMode.valueOf(((Enum<?>) callback5.apiMode.invoke(delegate)).name());
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  @Override
  public boolean useOptimizedSealed() {
    if (callback5 == null) return false;
    try {
      return (boolean) callback5.useOptimizedSealed.invoke(delegate);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  @Override
  public boolean materialiseLibraryMembers(String binaryClassName) {
    if (callback5 == null) return true;
    try {
      return (boolean) callback5.materialiseLibraryMembers.invoke(delegate, binaryClassName);
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  @Override
  public void api(SourceFile sourceFile, xsbti.api.ClassLike thinClass, dotty.tools.dotc.sbt.interfaces.ClassHashes hashes) {
    try {
      callback5.api.invoke(delegate, (xsbti.VirtualFileRef) asVirtualFile.apply(sourceFile), thinClass, toXsbti(hashes));
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }

  @Override
  public void apiCheck(SourceFile sourceFile, xsbti.api.ClassLike fullClass, xsbti.api.ClassLike thinClass, dotty.tools.dotc.sbt.interfaces.ClassHashes hashes) {
    try {
      callback5.apiCheck.invoke(delegate, (xsbti.VirtualFileRef) asVirtualFile.apply(sourceFile), fullClass, thinClass, toXsbti(hashes));
    } catch (Throwable t) {
      throw rethrow(t);
    }
  }
}
