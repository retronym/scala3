package dotty.tools.dotc.sbt.interfaces;

/** How `sbt-api` reports class APIs, mirroring `xsbti.AnalysisCallback5.ApiMode`. */
public enum ApiMode {
  /** The full API, hashed by Zinc. */
  TREE,
  /** The API as Zinc stores it (see `APIHashing.thin`), with its hashes. */
  HASHES,
  /** Both, so that Zinc can compare its hashing with ours. */
  CHECK
}
