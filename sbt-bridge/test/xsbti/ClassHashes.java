package xsbti;

import xsbti.api.NameHash;

/** A test copy of Zinc's `xsbti.ClassHashes`; see `AnalysisCallback5`. */
public final class ClassHashes {
    private final int apiHash;
    private final int extraHash;
    private final NameHash[] nameHashes;
    private final boolean hasMacro;

    public ClassHashes(int apiHash, int extraHash, NameHash[] nameHashes, boolean hasMacro) {
        this.apiHash = apiHash;
        this.extraHash = extraHash;
        this.nameHashes = nameHashes;
        this.hasMacro = hasMacro;
    }

    public int apiHash() { return apiHash; }
    public int extraHash() { return extraHash; }
    public NameHash[] nameHashes() { return nameHashes; }
    public boolean hasMacro() { return hasMacro; }
}
