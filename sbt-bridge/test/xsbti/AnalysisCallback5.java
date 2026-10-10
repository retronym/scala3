package xsbti;

import xsbti.api.ClassLike;

/**
 * A test copy of Zinc's `xsbti.AnalysisCallback5`, which the compiler-interface we build against
 * does not have. The bridge finds it reflectively, by name. It extends `AnalysisCallback2` here,
 * not `AnalysisCallback4`, which is not in that compiler-interface either.
 */
public interface AnalysisCallback5 extends AnalysisCallback2 {
    enum ApiMode { TREE, HASHES, CHECK }

    ApiMode apiMode();

    default boolean materialiseLibraryMembers(String binaryClassName) {
        return true;
    }

    boolean useOptimizedSealed();

    void api(VirtualFileRef sourceFile, ClassLike thinClass, ClassHashes hashes);

    void apiCheck(VirtualFileRef sourceFile, ClassLike fullClass, ClassLike thinClass, ClassHashes hashes);
}
