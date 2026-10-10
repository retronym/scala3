package dotty.tools.dotc
package core

object Flags {

  object opaques {

    /** A FlagSet represents a set of flags. Flags are encoded as follows:
    *  The first two bits indicate whether a flag set applies to terms,
    *  to types, or to both.  Bits 2..63 are available for properties
    *  and can be doubly used for terms and types.
    */
    opaque type FlagSet = Long
    def FlagSet(bits: Long): FlagSet = bits
    def toBits(fs: FlagSet): Long = fs

    /** A flag set that is statically known to be *kind-uniform*: every flag in it
     *  applies to exactly the kinds (terms/types) of the set itself.
     *
     *  `|` intersects the kinds of its operands, so a union of flags of different
     *  kinds (e.g. `Trait | Abstract`, a type-only set) is *kind-narrowed*. That is
     *  what we want for conjunctive tests (`isAllOf`), but a disjunctive test
     *  (`isOneOf`, `butNot`) on a narrowed set silently ignores the members whose
     *  kind was dropped: `termSym.isOneOf(Trait | Abstract | Deferred)` is always false.
     *  Disjunctive tests therefore require a `UniformFlagSet`. Use `isAnyOf(f1, f2, ...)`
     *  to test members individually, or `toTermFlags`/`toTypeFlags` to narrow explicitly.
     */
    opaque type UniformFlagSet <: FlagSet = Long
    opaque type CommonFlagSet <: UniformFlagSet = Long
    opaque type TermFlagSet <: UniformFlagSet = Long
    opaque type TypeFlagSet <: UniformFlagSet = Long
    inline def CommonFlagSet(bits: Long): CommonFlagSet = bits
    inline def TermFlagSet(bits: Long): TermFlagSet = bits
    inline def TypeFlagSet(bits: Long): TypeFlagSet = bits
    inline def assumeUniform(fs: FlagSet): UniformFlagSet = fs

    /** A flag set consisting of a single flag */
    opaque type Flag <: UniformFlagSet = Long
    opaque type CommonFlag <: Flag & CommonFlagSet = Long
    opaque type TermFlag <: Flag & TermFlagSet = Long
    opaque type TypeFlag <: Flag & TypeFlagSet = Long
    private[Flags] def CommonFlag(bits: Long): CommonFlag = bits
    private[Flags] def TermFlag(bits: Long): TermFlag = bits
    private[Flags] def TypeFlag(bits: Long): TypeFlag = bits
  }
  export opaques.{FlagSet, UniformFlagSet, CommonFlagSet, TermFlagSet, TypeFlagSet}

  type Flag = opaques.Flag
  type CommonFlag = opaques.CommonFlag
  type TermFlag = opaques.TermFlag
  type TypeFlag = opaques.TypeFlag

  /** Untyped union, see `|` */
  def union2(x: FlagSet, y: FlagSet): FlagSet =
    if (x.bits == 0) y
    else if (y.bits == 0) x
    else {
      val tbits = x.bits & y.bits & KINDFLAGS
      if (tbits == 0)
        assert(false, s"illegal flagset combination: ${x.flagsString} and ${y.flagsString}")
      FlagSet(tbits | ((x.bits | y.bits) & ~KINDFLAGS))
    }

  extension (x: FlagSet) {

    inline def bits: Long = opaques.toBits(x)

    /** The union of the given flag sets.
     *  Combining two FlagSets with `|` will give a FlagSet
     *  that has the intersection of the applicability to terms/types
     *  of the two flag sets. It is checked that the intersection is not empty.
     *
     *  The static result type is a `UniformFlagSet` only if both operands are
     *  statically of the same kind, i.e. if the union does not narrow either operand.
     *  Inlining is purely static: the runtime cost is a single call to `union2`.
     */
    transparent inline def | (y: FlagSet): FlagSet =
      inline x match
        case _: CommonFlagSet => inline y match
          case _: CommonFlagSet => opaques.CommonFlagSet(union2(x, y).bits)
          case _ => union2(x, y)
        case _: TermFlagSet => inline y match
          case _: TermFlagSet => opaques.TermFlagSet(union2(x, y).bits)
          case _ => union2(x, y)
        case _: TypeFlagSet => inline y match
          case _: TypeFlagSet => opaques.TypeFlagSet(union2(x, y).bits)
          case _ => union2(x, y)
        case _ => union2(x, y)

    /** The intersection of the given flag sets */
    def & (y: FlagSet): FlagSet = FlagSet(x.bits & y.bits)

    /** The intersection of a flag set with the complement of another flag set */
    def &~ (y: FlagSet): FlagSet = {
      val tbits = x.bits & KINDFLAGS
      if ((tbits & y.bits) == 0) x
      else FlagSet(tbits | ((x.bits & ~y.bits) & ~KINDFLAGS))
    }

    def ^ (y: FlagSet) =
      FlagSet((x.bits | y.bits) & KINDFLAGS | (x.bits ^ y.bits) & ~KINDFLAGS)

    /** Does the given flag set contain the given flag?
     *  This means that both the kind flags and the carrier bits have non-empty intersection.
     */
    def is (flag: Flag): Boolean = {
      val fs = x.bits & flag.bits
      (fs & KINDFLAGS) != 0 && (fs & ~KINDFLAGS) != 0
    }

    /** Does the given flag set contain the given flag
     *  and at the same time contain none of the flags in the `butNot` set?
     */
    def is (flag: Flag, butNot: UniformFlagSet): Boolean = x.is(flag) && !x.isOneOf(butNot)

    /** Does the given flag set have a non-empty intersection with another flag set?
     *  This means that both the kind flags and the carrier bits have non-empty intersection.
     */
    def isOneOf (flags: UniformFlagSet): Boolean = {
      val fs = x.bits & flags.bits
      (fs & KINDFLAGS) != 0 && (fs & ~KINDFLAGS) != 0
    }

    /** Does the given flag set have a non-empty intersection with another flag set,
     *  and at the same time contain none of the flags in the `butNot` set?
     */
    def isOneOf (flags: UniformFlagSet, butNot: UniformFlagSet): Boolean = x.isOneOf(flags) && !x.isOneOf(butNot)

    /** Does a given flag set have all of the flags of another flag set?
     *  Pre: The intersection of the term/type flags of both sets must be non-empty.
     */
    def isAllOf (flags: FlagSet): Boolean = {
      val fs = x.bits & flags.bits
      ((fs & KINDFLAGS) != 0 || flags.bits == 0) &&
      (fs >>> TYPESHIFT) == (flags.bits >>> TYPESHIFT)
    }

    /** Does a given flag set have all of the flags in another flag set
     *  and at the same time contain none of the flags in the `butNot` set?
     *  Pre: The intersection of the term/type flags of both sets must be non-empty.
     */
    def isAllOf (flags: FlagSet, butNot: UniformFlagSet): Boolean = x.isAllOf(flags) && !x.isOneOf(butNot)

    /** Does the given flag set contain any of the given flags?
     *  Unlike `isOneOf(f1 | f2 | ...)`, each argument is tested on its own,
     *  so no flag is lost when the union of the arguments would be kind-narrowed.
     */
    inline def isAnyOf(inline f1: UniformFlagSet, inline f2: UniformFlagSet): Boolean =
      x.isOneOf(f1) || x.isOneOf(f2)
    inline def isAnyOf(inline f1: UniformFlagSet, inline f2: UniformFlagSet, inline f3: UniformFlagSet): Boolean =
      x.isOneOf(f1) || x.isOneOf(f2) || x.isOneOf(f3)
    inline def isAnyOf(inline f1: UniformFlagSet, inline f2: UniformFlagSet, inline f3: UniformFlagSet, inline f4: UniformFlagSet): Boolean =
      x.isOneOf(f1) || x.isOneOf(f2) || x.isOneOf(f3) || x.isOneOf(f4)

    /** This flag set, trusted to be kind-uniform. Use only for flag sets whose kind
     *  is not known statically, but where all members are known to apply to all kinds of the set.
     */
    def assumeUniform: UniformFlagSet = opaques.assumeUniform(x)

    def isEmpty: Boolean = (x.bits & ~KINDFLAGS) == 0

    /** Is a given flag set a subset of another flag set? */
    def <= (y: FlagSet): Boolean = (x.bits & y.bits) == x.bits

    /** Does the given flag set apply to terms? */
    def isTermFlags: Boolean = (x.bits & TERMS) != 0

    /** Does the given flag set apply to terms? */
    def isTypeFlags: Boolean = (x.bits & TYPES) != 0

    /** The given flag set with all flags transposed to be type flags */
    def toTypeFlags: TypeFlagSet = opaques.TypeFlagSet(if (x.bits == 0) 0L else (x.bits & ~KINDFLAGS | TYPES))

    /** The given flag set with all flags transposed to be term flags */
    def toTermFlags: TermFlagSet = opaques.TermFlagSet(if (x.bits == 0) 0L else (x.bits & ~KINDFLAGS | TERMS))

    /** The given flag set with all flags transposed to be common flags */
    def toCommonFlags: CommonFlagSet = opaques.CommonFlagSet(if (x.bits == 0) 0L else (x.bits | KINDFLAGS))

    /** The number of non-kind flags in the given flag set */
    def numFlags: Int = java.lang.Long.bitCount(x.bits & ~KINDFLAGS)

    /** The lowest non-kind bit set in the given flag set */
    def firstBit: Int = java.lang.Long.numberOfTrailingZeros(x.bits & ~KINDFLAGS)

    /** The  list of non-empty names of flags with given index idx that are set in the given flag set */
    private def flagString(idx: Int): List[String] =
      if ((x.bits & (1L << idx)) == 0) Nil
      else {
        def halfString(kind: Int) =
          if ((x.bits & (1L << kind)) != 0) flagName(idx)(kind) else ""
        val termFS = halfString(TERMindex)
        val typeFS = halfString(TYPEindex)
        val strs = termFS :: (if (termFS == typeFS) Nil else typeFS :: Nil)
        strs filter (_.nonEmpty)
      }

    /** The list of non-empty names of flags that are set in the given flag set */
    def flagStrings(privateWithin: String = ""): Seq[String] = {
      var rawStrings = (2 to MaxFlag).flatMap(x.flagString(_)) // DOTTY problem: cannot drop with (_)
      if (!privateWithin.isEmpty && !x.is(Protected))
        rawStrings :+= "private"
      val scopeStr = if (x.is(Local)) "this" else privateWithin
      if (scopeStr != "")
        rawStrings.filter(_ != "<local>").map {
          case "private" => s"private[$scopeStr]"
          case "protected" => s"protected[$scopeStr]"
          case str => str
        }
      else rawStrings
    }

    /** The string representation of the given flag set */
    def flagsString: String = x.flagStrings("").mkString(" ")
  }

  // Temporary while extension names are in flux
  def or(x1: FlagSet, x2: FlagSet) = x1 | x2
  def and(x1: FlagSet, x2: FlagSet) = x1 & x2

  def termFlagSet(x: Long): TermFlagSet = opaques.TermFlagSet(TERMS | x)

  private inline val TYPESHIFT = 2
  private inline val TERMindex = 0
  private inline val TYPEindex = 1
  private inline val TERMS = 1 << TERMindex
  private inline val TYPES = 1 << TYPEindex
  private inline val KINDFLAGS = TERMS | TYPES

  private inline val FirstFlag = 2
  private inline val FirstNotPickledFlag = 48
  private inline val MaxFlag = 63

  private val flagName = Array.fill(64, 2)("")

  private def isDefinedAsFlag(idx: Int) = flagName(idx).exists(_.nonEmpty)

  /** The flag set containing all defined flags of either kind whose bits
   *  lie in the given range
   */
  private def flagRange(start: Int, end: Int): CommonFlagSet =
    opaques.CommonFlagSet((start until end).foldLeft(KINDFLAGS.toLong) ((bits, idx) =>
      if (isDefinedAsFlag(idx)) bits | (1L << idx) else bits))

  /** The union of all flags in given flag set */
  def union(flagss: FlagSet*): FlagSet = {
    var flag: FlagSet = EmptyFlags
    for (f <- flagss)
      flag |= f
    flag
  }

  def commonFlags(flagss: FlagSet*): CommonFlagSet = union(flagss.map(_.toCommonFlags)*).toCommonFlags

  /** The empty flag set */
  val EmptyFlags: CommonFlagSet = opaques.CommonFlagSet(0)

  /** The undefined flag set */
  val UndefinedFlags: FlagSet = FlagSet(~KINDFLAGS)

  /** Three flags with given index between 2 and 63.
   *  The first applies to both terms and types. the second is a term flag, and
   *  the third is a type flag. Installs given name(s) as the name(s) of the flags.
   *  @param name     The name to be used for the term flag
   *  @param typeName The name to be used for the type flag, if it is different from `name`.
   */
  private def newFlags(index: Int, name: String, typeName: String = ""): (CommonFlag, TermFlag, TypeFlag) = {
    flagName(index)(TERMindex) = name
    flagName(index)(TYPEindex) = if (typeName.isEmpty) name else typeName
    val bits = 1L << index
    (opaques.CommonFlag(KINDFLAGS | bits), opaques.TermFlag(TERMS | bits), opaques.TypeFlag(TYPES | bits))
  }

  // ----------------- Available flags -----------------------------------------------------

  /** Labeled with `private` modifier */
  val (Private @ _, PrivateTerm @ _, PrivateType @ _) = newFlags(2, "private")

  /** Labeled with `protected` modifier */
  val (Protected @ _, _, _) = newFlags(3, "protected")

  /** Labeled with `override` modifier */
  val (Override @ _, _, _) = newFlags(4, "override")

  /** A declared, but not defined member */
  val (Deferred @ _, DeferredTerm @ _, DeferredType @ _) = newFlags(5, "<deferred>")

  /** Labeled with `final` modifier */
  val (Final @ _, _, _) = newFlags(6, "final")

  /** A method symbol / a super trait */
  val (_, Method @ _, _) = newFlags(7, "<method>")

  /** A (term or type) parameter to a class or method */
  val (Param @ _, TermParam @ _, TypeParam @ _) = newFlags(8, "<param>")

  /** Labeled with `implicit` modifier (implicit value) */
  val (Implicit @ _, ImplicitVal @ _, _) = newFlags(9, "implicit")

  /** Labeled with `lazy` (a lazy val) / a trait */
  val (LazyOrTrait @ _, Lazy @ _, Trait @ _) = newFlags(10, "lazy", "<trait>")

  /** A value or variable accessor (getter or setter) */
  val (AccessorOrSealed @ _, Accessor @ _, Sealed @ _) = newFlags(11, "<accessor>", "sealed")

  /** A mutable var, an open class */
  val (MutableOrOpen @ _, Mutable @ _, Open @ _) = newFlags(12, "mutable", "open")

  /** Symbol is local to current class (i.e. private[this] or protected[this]
   *  pre: Private or Protected are also set
   */
  val (Local @ _, _, _) = newFlags(13, "<local>")

  /** A field generated for a primary constructor parameter (no matter if it's a 'val' or not),
   *  or an accessor of such a field / An `into` modifier on a class
   */
  val (ParamAccessorOrInto @ _, ParamAccessor @ _, Into @ _) = newFlags(14, "<paramaccessor>", "into")

  /** A value or class implementing a module */
  val (Module @ _, ModuleVal @ _, ModuleClass @ _) = newFlags(15, "module")

   /** A value or class representing a package */
  val (Package @ _, PackageVal @ _, PackageClass @ _) = newFlags(16, "<package>")

  /** A case class or its companion object
   *  Note: Case is also used to indicate that a symbol is bound by a pattern.
   */
  val (Case @ _, CaseVal @ _, CaseClass @ _) = newFlags(17, "case")

  /** A compiler-generated symbol, which is visible for type-checking
   *  (compare with artifact)
   */
  val (Synthetic @ _, _, _) = newFlags(18, "<synthetic>")

  /** Labelled with `inline` modifier */
  val (Inline @ _, _, _) = newFlags(19, "inline")

  /** An outer accessor / a covariant type variable */
  val (OuterOrCovariant @ _, OuterAccessor @ _, Covariant @ _) = newFlags(20, "<outer accessor>", "<covariant>")

  /** The label of a labeled block / a contravariant type variable */
  val (LabelOrContravariant @ _, Label @ _, Contravariant @ _) = newFlags(21, "<label>", "<contravariant>")

  /** Labeled with of abstract & override
   *    /
   *  A trait that has only abstract methods as members
   *  and therefore can be represented by a Java interface.
   *  Warning: PureInterface is set during regular typer pass, should be tested only after typer.
   */
  val (_, AbsOverride @ _, PureInterface @ _) = newFlags(22, "abstract override", "interface")

  /** Labeled with `abstract` modifier (an abstract class)
   *  Note: You should never see Abstract on any symbol except a class.
   *  Note: the flag counts as common, because it can be combined with OVERRIDE in a term.
   */
  val (Abstract @ _, _, _) = newFlags(23, "abstract")

  /** Lazy val or method is known or assumed to be stable and realizable.
   *
   *  For a trait constructor, this is set if and only if owner.is(NoInits),
   *  including for Java interfaces and for Scala 2 traits. It will be used by
   *
   *  - the purity analysis used by the inliner to decide whether it is safe to elide, and
   *  - the TASTy reader of Scala 2.13, to determine whether there is a $init$ method.
   *
   *  StableRealizable is
   *  - asserted for methods
   *  - automatic in conjunction with Module or Enum vals
   *  - cached for other vals
   */
  val (_, StableRealizable @ _, _) = newFlags(24, "<stable>")

  /** A case parameter accessor / an unpickled Scala 2 TASTy (only for Scala 2 stdlib) */
  val (_, CaseAccessor @ _, Scala2Tasty @ _) = newFlags(25, "<caseaccessor>", "<scala-2-tasty>")

  /** A Scala 2x super accessor / an unpickled Scala 2.x class */
  val (SuperParamAliasOrScala2x @ _, SuperParamAlias @ _, Scala2x @ _) = newFlags(26, "<super-param-alias>", "<scala-2.x>")

  /** A parameter with a default value / an impure untpd.FunctionWithMods type */
  val (_, HasDefault @ _, Impure @ _) = newFlags(27, "<hasdefault>", "<impure>")

  /** An extension method, or a collective extension instance */
  val (Extension @ _, ExtensionMethod @ _, _) = newFlags(28, "<extension>")

  /** An inferable (`given`) parameter */
  val (Given @ _, GivenVal @ _,  _) = newFlags(29, "given")

  /** Symbol is defined by a Java class */
  val (JavaDefined @ _, JavaDefinedVal @ _, _) = newFlags(30, "<java>")

  /** Symbol is implemented as a Java static */
  val (JavaStatic @ _, JavaStaticTerm @ _, JavaStaticType @ _) = newFlags(31, "<static>")

  /** Variable is accessed from nested function
   *    /
   *  Trait does not have own fields or initialization code or class does not
   *  have own or inherited initialization code.
   *
   *  Warning: NoInits is set during regular typer pass, should be tested only after typer.
   */
  val (_, Captured @ _, NoInits @ _) = newFlags(32, "<captured>", "<noinits>")

  /** Symbol should be ignored when typechecking; will be marked ACC_SYNTHETIC in bytecode */
  val (Artifact @ _, _, _) = newFlags(33, "<artifact>")

  /** A bridge method. Set by Erasure */
  val (_, Bridge @ _, _) = newFlags(34, "<bridge>")

  /** A proxy for an argument to an inline method */
  val (_, InlineProxy @ _, _) = newFlags(35, "<inline proxy>")

  /** Symbol is a method which should be marked ACC_SYNCHRONIZED */
  val (_, Synchronized @ _, _) = newFlags(36, "<synchronized>")

  /** Symbol is a Java-style varargs method / a Java annotation */
  val (_, JavaVarargs @ _, JavaAnnotation @ _) = newFlags(37, "<varargs>", "<java-annotation>")

  /** Symbol is a Java default method */
  val (_, DefaultMethod @ _, _) = newFlags(38, "<defaultmethod>")

  /** Symbol is a transparent inline method or trait */
  val (Transparent @ _, _, TransparentType @ _) = newFlags(39, "transparent")

  /** Symbol is an enum class or enum case (if used with case) */
  val (Enum @ _, EnumVal @ _, _) = newFlags(40, "enum")

  /** An export forwarder */
  val (Exported @ _, ExportedTerm @ _, ExportedType @ _) = newFlags(41, "exported")

  /** Labeled with `erased` modifier (erased value or class)  */
  val (Erased @ _, _, _) = newFlags(42, "erased")

  /** An opaque type alias or a class containing one */
  val (Opaque @ _, _, _) = newFlags(43, "opaque")

  /** An infix method or type */
  val (Infix @ _, _, _) = newFlags(44, "infix")

  /** Symbol cannot be found as a member during typer */
  val (Invisible @ _, _, _) = newFlags(45, "<invisible>")

  /** Tracked modifier for class parameter / a class with some tracked parameters */
  val (Tracked @ _, _, Dependent @ _) = newFlags(46, "tracked")

  // ------------ Flags following this one are not pickled ----------------------------------

  /** Symbol is not a member of its owner */
  val (NonMember @ _, _, _) = newFlags(49, "<non-member>")

  /** Denotation is in train of being loaded and completed, used to catch cyclic dependencies */
  val (Touched @ _, _, _) = newFlags(50, "<touched>")

  /** Class has been lifted out to package level, local value has been lifted out to class level */
  val (Lifted @ _, _, _) = newFlags(51, "<lifted>") // only used from lambda-lift (could be merged with ConstructorProxy)

  /** Term member has been mixed in */
  val (MixedIn @ _, _, _) = newFlags(52, "<mixedin>")

  /** Symbol is a generated specialized member */
  val (Specialized @ _, _, _) = newFlags(53, "<specialized>")

  /** Symbol is a self name */
  val (_, SelfName @ _, _) = newFlags(54, "<selfname>")

  /** A Scala 2 superaccessor (only needed during Scala2Unpickling) /
   *  an existentially bound symbol (Scala 2.x only) */
  val (Scala2SpecialFlags @ _, Scala2SuperAccessor @ _, Scala2Existential @ _) = newFlags(55, "<existential>")

  /** Children were queried on this class */
  val (_, _, ChildrenQueried @ _) = newFlags(56, "<children-queried>")

  /** A module variable (Scala 2.x only) / a capture-checked class
   *  (Scala2ModuleVar is re-used as a flag for private parameter accessors in Recheck)
   */
  val (_, Scala2ModuleVar @ _, CaptureChecked @ _) = newFlags(57, "<modulevar>/<cc>")

  /** A macro */
  val (Macro @ _, _, _) = newFlags(58, "<macro>")

  /** Translation of Scala2's EXPANDEDNAME flag. This flag is never stored in
   *  symbols, is only used locally when reading the flags of a Scala2 symbol.
   *  It's therefore safe to share the code with `HasDefaultParams` because
   *  the latter is never present in Scala2 unpickle info.
   *    /
   *  A method that is known to have (defined or inherited) default parameters
   */
  val (Scala2ExpandedName @ _, HasDefaultParams @ _, _) = newFlags(59, "<has-default-params>")

  /** A method that is known to have no default parameters
   *    /
   *  A type symbol with provisional empty bounds
   */
  val (_, NoDefaultParams @ _, Provisional @ _) = newFlags(60, "<no-default-params>", "<provisional>")

  /** A denotation that is valid in all run-ids */
  val (Permanent @ _, _, _) = newFlags(61, "<permanent>")

  /** A phantom symbol made up by the compiler to achieve special typing rules.
   *  Phantom symbols cannot be used as regular values, and will be erased after erasure phase.
   *  - For constructor proxies (companion or apply method).
   *  - For dummy capture parameters in capture sets (variables or fields).
   */
  val (PhantomSymbol @ _, _, _) = newFlags(62, "<phantom symbol>") // (could be merged with Lifted)

// --------- Combined Flag Sets and Conjunctions ----------------------

  /** All possible flags */
  val AnyFlags = flagRange(FirstFlag, MaxFlag)

  /** These flags are pickled */
  val PickledFlags = flagRange(FirstFlag, FirstNotPickledFlag)

  /** Flags representing access rights */
  val AccessFlags = Local | Private | Protected

  /** Flags representing source modifiers */
  private val CommonSourceModifierFlags =
    commonFlags(Private, Protected, Final, Case, Implicit, Given, Override, JavaStatic, Transparent, Erased, Synchronized, Inline)

  val TypeSourceModifierFlags =
    CommonSourceModifierFlags.toTypeFlags | Abstract | Sealed | Opaque | Open | Into

  val TermSourceModifierFlags =
    CommonSourceModifierFlags.toTermFlags | AbsOverride | Lazy | Tracked

  /** Flags representing modifiers that can appear in trees */
  val ModifierFlags =
    TypeSourceModifierFlags.toCommonFlags |
    TermSourceModifierFlags.toCommonFlags |
    commonFlags(Module, Param, Synthetic, Package, Local, Mutable, Trait)

  /** Flags that are not (re)set when completing the denotation
   *  TODO: Should check that FromStartFlags do not change in completion
   */
  val FromStartFlags = commonFlags(
    Module, Package, Deferred, Method, Case, Enum, Param, ParamAccessorOrInto,
    Scala2SpecialFlags, MutableOrOpen, Opaque, Touched, JavaStatic,
    OuterOrCovariant, LabelOrContravariant, CaseAccessor, Tracked,
    Extension, NonMember, Implicit, Given, Permanent, Synthetic, Exported,
    SuperParamAliasOrScala2x, Inline, Macro, PhantomSymbol, Invisible)

  /** Flags that are not (re)set when completing the denotation, or, if symbol is
   *  a top-level class or object, when completing the denotation once the class
   *  file defining the symbol is loaded (which is generally before the denotation
   *  is completed)
   */
  val AfterLoadFlags = commonFlags(
    FromStartFlags, AccessFlags, Final, AccessorOrSealed,
    Abstract, LazyOrTrait, SelfName, JavaDefined, JavaAnnotation, Transparent)

  /** A value that's unstable unless complemented with a Stable flag */
  val UnstableValueFlags = Mutable | Method

  /** Flags that express the variance of a type parameter. */
  val VarianceFlags = Covariant | Contravariant

// ----- Creation flag sets ----------------------------------

  /** Modules always have these flags set */
  val ModuleValCreationFlags = ModuleVal | Lazy | Final | StableRealizable

  /** Module classes always have these flags set */
  val ModuleClassCreationFlags = ModuleClass | Final

  /** Accessors always have these flags set */
  val AccessorCreationFlags = Method | Accessor

  /** Pure interfaces always have these flags */
  val PureInterfaceCreationFlags = Trait | NoInits | PureInterface

  /** The flags of the self symbol */
  val SelfSymFlags = Private | Local | Deferred

  /** The flags of a class type parameter */
  val ClassTypeParamCreationFlags =
    TypeParam | Deferred | Private | Local

  /** Packages and package classes always have these flags set */
  val PackageCreationFlags =
    Module | Package | Final | JavaDefined

// ----- Retained flag sets ----------------------------------

  /** Flags that are passed from a type parameter of a class to a refinement symbol
    * that sets the type parameter */
  val RetainedTypeArgFlags = VarianceFlags | Protected | Local

  /** Flags that can apply to both a module val and a module class, except those that
    *  are added at creation anyway
    */
  val RetainedModuleValAndClassFlags =
    AccessFlags | Package | Case |
    Synthetic | JavaDefined | JavaStatic | Artifact |
    Lifted | MixedIn | Specialized | PhantomSymbol | Invisible

  /** Flags that can apply to a module val */
  val RetainedModuleValFlags = RetainedModuleValAndClassFlags |
    Override | Final | Method | Implicit | Given | Lazy | Erased |
    Accessor | AbsOverride | StableRealizable | Captured | Synchronized | Transparent

  /** Flags that can apply to a module class */
  val RetainedModuleClassFlags = RetainedModuleValAndClassFlags | Enum

  /** Flags retained in term export forwarders */
  val RetainedExportTermFlags = Infix | Given | Implicit | Inline | Transparent | HasDefaultParams | NoDefaultParams | ExtensionMethod

  /** Flags retained in parameters of term export forwarders */
  val RetainedExportTermParamFlags = Given | Implicit | Erased | HasDefault | Inline

  val MandatoryExportTermFlags = Exported | Method | Final

  /** Flags retained in type export forwarders */
  val RetainedExportTypeFlags = Infix

  /** Flags that apply only to classes */
  val ClassOnlyFlags = Sealed | Open | Abstract.toTypeFlags

// ------- Other flag sets -------------------------------------

  val NotConcrete                   = AbsOverride | DeferredTerm
  val AbstractFinal                 = Abstract | Final
  val AbstractOverride              = Abstract | Override
  val AbstractSealed                = Abstract | Sealed
  val AbstractOrTrait               = Abstract.toTypeFlags | Trait
  val EffectivelyOpenFlags                   = (Abstract | JavaDefined | Open | Scala2x | Trait).toTypeFlags
  val AccessorOrDeferred            = Accessor | Deferred
  val PrivateAccessor               = Accessor | Private
  val AccessorOrSynthetic           = Accessor | Synthetic
  val JavaOrPrivateOrSynthetic      = Artifact | JavaDefined | Private | Synthetic
  val PrivateOrSynthetic            = Artifact | Private | Synthetic
  val EnumCase                      = Case | Enum
  val CovariantLocal                = Covariant | Local                              // A covariant type parameter
  val ContravariantLocal            = Contravariant | Local                          // A contravariant type parameter
  val ConstructorProxyModule        = PhantomSymbol | Module
  val CaptureParam                  = PhantomSymbol | StableRealizable | Synthetic
  val DefaultParameter              = HasDefault | Param                             // A Scala 2x default parameter
  val DeferredInline                = Deferred | Inline
  val DeferredMethod                = Deferred | Method
  val DeferredOrLazy                = DeferredTerm | Lazy
  val DeferredOrLazyOrMethod        = DeferredTerm | Lazy | Method
  val DeferredOrTermParamOrAccessor = DeferredTerm | ParamAccessor | TermParam           // term symbols without right-hand sides
  val DeferredOrTypeParam           = DeferredType | TypeParam                           // type symbols without right-hand sides
  val DeferredGivenFlags            = Deferred | Given | HasDefault
  val EnumValue                     = Enum | StableRealizable                        // A Scala enum value
  val FinalOrInline                 = Final | Inline
  val FinalOrModuleClass            = Final.toTypeFlags | ModuleClass                            // A module class or a final class
  val EffectivelyFinalFlags         = Final | Private
  val ExcludedForwarder       = Specialized | Lifted | Protected | JavaStatic | Private | Macro | PhantomSymbol
  val FinalOrSealed                 = Final.toTypeFlags | Sealed
  val GivenOrImplicit               = Given | Implicit
  val GivenOrImplicitVal            = GivenOrImplicit.toTermFlags
  val GivenMethod                   = Given | Method
  val LazyGiven                     = Given | Lazy
  val InlineOrProxy                 = Inline.toTermFlags | InlineProxy                           // An inline method or inline argument proxy */
  val InlineMethod                  = Inline | Method
  val InlineImplicitMethod          = Implicit | InlineMethod
  val InlineTrait                   = Inline | Trait
  val InlineParam                   = Inline | Param
  val InlineByNameProxy             = InlineProxy | Method
  val JavaEnum                      = JavaDefined | Enum                             // A Java enum trait
  val JavaEnumValue                 = JavaDefined | EnumValue                        // A Java enum value
  val JavaModule                    = JavaDefined | Module                           // A Java companion object
  val JavaInterface                 = JavaDefined | NoInits | Trait
  val JavaProtected                 = JavaDefined | Protected
  val MethodOrLazy                  = Lazy | Method
  val MethodOrLazyOrMutable         = Lazy | Method | Mutable
  val LiftedMethod                  = Lifted | Method
  val LocalParam                    = Local | Param
  val LocalParamAccessor            = Local | ParamAccessor | Private
  val PrivateLocal                  = Local | Private                                // private[this]
  val ProtectedLocal                = Local | Protected
  val MethodOrModule                = Method | ModuleVal
  val ParamForwarder                = Method | ParamAccessor | StableRealizable      // A parameter forwarder
  val PrivateMethod                 = Method | Private
  val StableMethod                  = Method | StableRealizable
  val NoInitsInterface              = NoInits | PureInterface
  val NoInitsTrait                  = NoInits | Trait                                // A trait that does not need to be initialized
  val ValidForeverFlags             = Package | Permanent | Scala2SpecialFlags
  val TermParamOrAccessor           = TermParam | ParamAccessor
  val PrivateParamAccessor          = ParamAccessor | Private
  val PrivateOrArtifact             = Private | Artifact
  val ClassTypeParam                = Private | TypeParam
  val Scala2Trait                   = Scala2x | Trait
  val SyntheticArtifact             = Synthetic | Artifact
  val SyntheticCase                 = Synthetic | Case
  val SyntheticMethod               = Synthetic | Method
  val SyntheticModule               = Synthetic | Module
  val SyntheticOpaque               = Synthetic | Opaque
  val SyntheticParam                = Synthetic | Param
  val SyntheticTermParam            = Synthetic | TermParam
  val SyntheticTypeParam            = Synthetic | TypeParam
}
