package p

// ExplicitJSClasses used to bring every nested class of `It` (here, the
// constructor proxy companion of `Grouped`) forward through ExtensionMethods
// while the base classes of `AbsIt` were being computed for `CollIt`.
trait It:
  class Grouped extends AbsIt

class AbsIt extends It

class Coll:
  private abstract class CollIt extends AbsIt
