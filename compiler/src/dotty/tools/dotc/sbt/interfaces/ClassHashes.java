package dotty.tools.dotc.sbt.interfaces;

import xsbti.api.NameHash;

/**
 * The hashes of one class's API, mirroring `xsbti.ClassHashes`, which the compiler-interface we
 * build against does not have. Only equality between two versions of a class is meaningful.
 */
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
