package dotty.tools
package dotc
package core
package tasty

import dotty.tools.tasty.TastyFormat.*
import dotty.tools.tasty.besteffort.BestEffortTastyFormat.ERRORtype
import dotty.tools.tasty.TastyBuffer.*
import dotty.tools.tasty.TastyReader

import ast.Trees.*
import ast.{untpd, tpd}
import Contexts.*, Symbols.*, Types.*, Names.*, Constants.*, Decorators.*, Annotations.*, Flags.*
import Comments.{Comment, docCtx}
import NameKinds.*
import StdNames.nme
import config.Config
import config.Feature.sourceVersion
import collection.mutable
import reporting.{Profile, NoProfile}
import dotty.tools.tasty.TastyFormat.ASTsSection
import scala.util.control.NonFatal

class TreePickler(pickler: TastyPickler, attributes: Attributes) {
  val buf: TreeBuffer = new TreeBuffer
  pickler.newSection(ASTsSection, buf)
  import buf.*
  import pickler.nameBuffer.nameIndex
  import tpd.*

  private val symRefs = Symbols.MutableSymbolMap[Addr](256)
  private val forwardSymRefs = Symbols.MutableSymbolMap[List[Addr]]()
  private val pickledTypes = util.EqHashMap[Type, Addr]()

  /** Addresses of pickled types, keyed by their encoding (see `encodingKey`).
   *  Distinct `Type` objects can pickle to the same bytes, e.g. `TypeRef(pre, name)`
   *  (as read by the unpickler) and `TypeRef(pre, sym)` (as created by typer) for
   *  an external `sym`, or the `ThisType` and `TermRef` of a package. Sharing on
   *  the encoding rather than on identity makes the pickle independent of whether
   *  referenced definitions came from source or from TASTy.
   */
  private val pickledTypeEncodings = util.HashMap[AnyRef, Addr]()

  /** Under -Ytest-pickler-sharing, the types that were found by encoding rather
   *  than by identity, with the address of the type they share. Checked by
   *  `checkSharedByEncoding`.
   */
  private var sharedByEncoding: mutable.ArrayBuffer[(Type, Addr)] | Null = null

  /** The shapes of types that were looked up by encoding but are not pickled
   *  yet, so that the shape of a type, which can involve a member lookup (see
   *  `isExternalRefIn`), is computed only once.
   */
  private val pendingShapes = util.EqHashMap[Type, TypeShape]()

  /** A list of annotation trees for every member definition, so that later
   *  parallel position pickling does not need to access and force symbols.
   */
  private val annotTrees = util.EqHashMap[untpd.MemberDef, mutable.ListBuffer[Tree]]()

  /** A set of annotation trees appearing in annotated types.
   */
  private val annotatedTypeTrees = mutable.ListBuffer[Tree]()

  /** A map from member definitions to their doc comments, so that later
   *  parallel comment pickling does not need to access symbols of trees (which
   *  would involve accessing symbols of named types and possibly changing phases
   *  in doing so).
   */
  private val docStrings = util.EqHashMap[untpd.MemberDef, Comment]()

  private var profile: Profile = NoProfile

  private val isOutlinePickle: Boolean = attributes.isOutline
  private val isJavaPickle: Boolean = attributes.isJava

  def treeAnnots(tree: untpd.MemberDef): List[Tree] =
    val ts = annotTrees.lookup(tree)
    if ts == null then Nil else ts.toList

  def typeAnnots: List[Tree] = annotatedTypeTrees.toList

  def docString(tree: untpd.MemberDef): Option[Comment] =
    Option(docStrings.lookup(tree))

  private inline def withLength(inline op: Unit) = {
    val lengthAddr = reserveRef(relative = true)
    op
    fillRef(lengthAddr, currentAddr, relative = true)
  }

  /** There are certain expectations with code which is naturally able to reach
   *  pickling phase as opposed to one that uses best-effort compilation features.
   *  When pickling betasty files, we do some custom checks, in case those
   *  expectations cannot be fulfilled, and if so, then we can try to do something
   *  else (usually pickle an ERRORtype).
   *  For regular non best-effort compilation (without -Ybest-effort with thrown errors
   *  and without using .betasty on classpath), this will always return true.
   */
  private inline def passesConditionForErroringBestEffortCode(condition: => Boolean)(using Context): Boolean =
    !((ctx.isBestEffort && ctx.reporter.errorsReported) || ctx.usedBestEffortTasty) || condition

  def addrOfSym(sym: Symbol): Option[Addr] =
    symRefs.get(sym)

  def preRegister(tree: Tree)(using Context): Unit = tree match
    case tree: MemberDef => symRefs.getOrElseUpdate(tree.symbol, NoAddr)
    case _ => ()

  def registerDef(sym: Symbol): Unit =
    symRefs(sym) = currentAddr
    val refs = forwardSymRefs.lookup(sym)
    if refs != null then
      refs.foreach(fillRef(_, currentAddr, relative = false))
      forwardSymRefs -= sym

  def pickleName(name: Name): Unit = writeNat(nameIndex(name).index)

  private def nameAndSig(name: Name, sig: Signature, target: Name): Name =
    if (sig eq Signature.NotAMethod) name
    else SignedName(name.toTermName, sig, target.asTermName)

  private def pickleNameAndSig(name: Name, sig: Signature, target: Name): Unit =
    pickleName(nameAndSig(name, sig, target))

  private def pickleSymRef(sym: Symbol)(using Context) =
    val label: Addr | Null = symRefs.lookup(sym)
    if label == null then
      // See pos/t1957.scala for an example where this can happen.
      // I believe it's a bug in typer: the type of an implicit argument refers
      // to a closure parameter outside the closure itself. TODO: track this down, so that we
      // can eliminate this case.
      report.log(i"pickling reference to as yet undefined $sym in ${sym.owner}", sym.srcPos)
      pickleForwardSymRef(sym)
    else if label == NoAddr then
      pickleForwardSymRef(sym)
    else
      writeRef(label)

  private def pickleForwardSymRef(sym: Symbol)(using Context) = {
    val ref = reserveRef(relative = false)
    assert(!sym.is(Flags.Package), sym)
    forwardSymRefs(sym) = ref :: forwardSymRefs.getOrElse(sym, Nil)
  }

  private def isLocallyDefined(sym: Symbol)(using Context) =
    sym.topLevelClass.isLinkedWith(pickler.rootCls)

  /** The tag with which constant `c` is pickled */
  private def constantTag(c: Constant): Int = c.tag match {
    case UnitTag => UNITconst
    case BooleanTag => if (c.booleanValue) TRUEconst else FALSEconst
    case ByteTag => BYTEconst
    case ShortTag => SHORTconst
    case CharTag => CHARconst
    case IntTag => INTconst
    case LongTag => LONGconst
    case FloatTag => FLOATconst
    case DoubleTag => DOUBLEconst
    case StringTag => STRINGconst
    case NullTag => NULLconst
    case ClazzTag => CLASSconst
  }

  def pickleConstant(c: Constant)(using Context): Unit =
    writeByte(constantTag(c))
    pickleConstantValue(c)

  /** Pickle what follows the tag of constant `c` */
  private def pickleConstantValue(c: Constant)(using Context): Unit = c.tag match {
    case ByteTag => writeInt(c.byteValue)
    case ShortTag => writeInt(c.shortValue)
    case CharTag => writeNat(c.charValue)
    case IntTag => writeInt(c.intValue)
    case LongTag => writeLongInt(c.longValue)
    case FloatTag => writeInt(java.lang.Float.floatToRawIntBits(c.floatValue))
    case DoubleTag => writeLongInt(java.lang.Double.doubleToRawLongBits(c.doubleValue))
    case StringTag => pickleName(c.stringValue.toTermName)
    case ClazzTag => pickleType(c.typeValue)
    case UnitTag | BooleanTag | NullTag =>
  }

  /** The variance tags pickled after type bounds with upper bound `hi`.
   *  A `LazyRef` to a lambda is not dereferenced, so its variances are not
   *  pickled; changing this would change existing output.
   */
  private def varianceTags(hi: Type)(using Context): List[Int] = hi match
    case hi: HKTypeLambda if hi.isDeclaredVarianceLambda =>
      hi.declaredVariances.map: v =>
        if v.is(Covariant) then COVARIANT
        else if v.is(Contravariant) then CONTRAVARIANT
        else STABLE
    case _ =>
      Nil

  def pickleType(tpe0: Type, richTypes: Boolean = false)(using Context): Unit = {
    val tpe = tpe0.stripTypeVar
    printOnAssertionError(i"error when pickling type $tpe") {
      val prev: Addr | Null = pickledTypes.lookup(tpe)
      if prev != null then pickleSharedType(prev)
      else
        val pending = pendingShapes.remove(tpe)
        val shape = if pending != null then pending else typeShape(tpe)
        if shape == null then
          pickledTypes(tpe) = currentAddr
          pickleNewType(tpe, richTypes)
        else
          val prev = encodedAddr(tpe, shape)
          if prev != null then pickleSharedType(prev)
          else
            val addr = currentAddr
            pickledTypes(tpe) = addr
            pickleShape(shape, richTypes)
            val key = encodingKey(shape) // components are pickled now
            if key != null then pickledTypeEncodings(key) = addr
    }
  }

  private def pickleSharedType(addr: Addr): Unit =
    writeByte(SHAREDtype)
    writeRef(addr)

  /** The address of an already pickled type with the same encoding as `tpe`, or null */
  private def pickledAddr(tpe: Type)(using Context): Addr | Null =
    val addr = pickledTypes.lookup(tpe)
    if addr != null then addr
    else
      var shape = pendingShapes.lookup(tpe)
      if shape == null then
        shape = typeShape(tpe)
        if shape == null then return null
        pendingShapes(tpe) = shape
      val addr = encodedAddr(tpe, shape)
      if addr != null then pendingShapes.remove(tpe)
      addr

  /** The address of an already pickled type with the same encoding as `tpe`, which
   *  has shape `shape`, or null if there is none.
   */
  private def encodedAddr(tpe: Type, shape: TypeShape)(using Context): Addr | Null =
    if shape.missedAt == pickledTypeEncodings.size then null
    else
      val key = encodingKey(shape)
      val addr = if key == null then null else pickledTypeEncodings.lookup(key)
      if addr == null then shape.missedAt = pickledTypeEncodings.size
      else
        pickledTypes(tpe) = addr
        if sharedByEncoding != null then sharedByEncoding.nn += ((tpe, addr))
      addr

  /** What is written for a type that is shared by encoding: its tag, the name,
   *  symbol, constant or other data that follows the tag, and its component
   *  types, in the order they are written. Names are term names, as in the name
   *  table. Both `pickleShape`, which writes it, and `encodingKey`, which keys
   *  it, are derived from the shape, so the bytes written for a type are
   *  determined by its key.
   */
  private class TypeShape(val tag: Int, val payload: Any, val components: List[Type]):
    /** The size of `pickledTypeEncodings` when this shape was last looked up
     *  without success, or -1. The lookup is bound to fail again while the size
     *  is unchanged: a hit needs an entry whose key contains the address of a
     *  component that was not pickled at the time, or an entry for an
     *  identical key, and either would have been added since. This makes the
     *  lookups of the components of a type that was just missed O(1).
     */
    var missedAt: Int = -1

  /** The shape of `tpe`, or null if `tpe` is shared by identity only and is
   *  pickled by `pickleNewType`.
   */
  private def typeShape(tpe: Type)(using Context): TypeShape | Null = tpe match
    case FlexibleType(hi) =>
      TypeShape(FLEXIBLEtype, (), hi :: Nil)
    case AppliedType(tycon, args) =>
      if tycon.typeSymbol == defn.MatchCaseClass then TypeShape(MATCHCASEtype, (), args)
      else TypeShape(APPLIEDtype, (), tycon :: args)
    case ConstantType(value) =>
      if value.tag == ClazzTag then null
      else TypeShape(constantTag(value), value, Nil)
    case tpe: NamedType =>
      val sym = tpe.symbol
      if sym.is(Flags.Package) then
        TypeShape(if tpe.isType then TYPEREFpkg else TERMREFpkg, sym.fullName.toTermName, Nil)
      else if tpe.prefix == NoPrefix then
        TypeShape(if tpe.isType then TYPEREFdirect else TERMREFdirect, sym, Nil)
      else tpe.designator match
        case name: Name =>
          TypeShape(if tpe.isType then TYPEREF else TERMREF, name.toTermName, tpe.prefix :: Nil)
        case sym: Symbol =>
          if isLocallyDefined(sym) then
            TypeShape(if tpe.isType then TYPEREFsymbol else TERMREFsymbol, sym, tpe.prefix :: Nil)
          else if isExternalRefIn(tpe, sym) then
            TypeShape(if tpe.isType then TYPEREFin else TERMREFin,
              nameAndSig(sym.name, sym.signature, sym.targetName).toTermName,
              tpe.prefix :: sym.owner.typeRef :: Nil)
          else if isJavaPickle && sym == defn.FromJavaObjectSymbol then
            null
          else
            TypeShape(if tpe.isType then TYPEREF else TERMREF,
              nameAndSig(sym.name, tpe.signature, sym.targetName).toTermName,
              tpe.prefix :: Nil)
    case tpe: ThisType =>
      if tpe.cls.is(Flags.Package) && !tpe.cls.isEffectiveRoot then
        TypeShape(TERMREFpkg, tpe.cls.fullName.toTermName, Nil)
      else TypeShape(THIS, (), tpe.tref :: Nil)
    case tpe: RefinedType =>
      TypeShape(REFINEDtype, tpe.refinedName.toTermName, tpe.parent :: tpe.refinedInfo :: Nil)
    case tpe: TypeBounds =>
      // The variances depend on `hi` itself, not only on its address: a
      // `LazyRef` shares the address of the type it refers to.
      val variances = varianceTags(tpe.hi)
      if tpe.isInstanceOf[AliasingBounds] then TypeShape(TYPEBOUNDS, variances, tpe.lo :: Nil)
      else TypeShape(TYPEBOUNDS, variances, tpe.lo :: tpe.hi :: Nil)
    case tpe: AndType =>
      TypeShape(ANDtype, (), tpe.tp1 :: tpe.tp2 :: Nil)
    case tpe: OrType =>
      TypeShape(ORtype, (), tpe.tp1 :: tpe.tp2 :: Nil)
    case tpe: ExprType =>
      TypeShape(BYNAMEtype, (), tpe.underlying :: Nil)
    case _ =>
      null

  /** Is a reference to external `sym` pickled as TYPEREFin or TERMREFin? */
  private def isExternalRefIn(tpe: NamedType, sym: Symbol)(using Context): Boolean =
    def isShadowedRef = sym.isClass && tpe.prefix.member(sym.name).symbol != sym
    sym.is(Flags.Private) || isShadowedRef

  /** A key that determines the encoding of a type with shape `shape`: its tag,
   *  payload and the addresses of its component types. Component addresses
   *  are found by identity or, recursively, by encoding. Null if a component
   *  type has not been pickled yet.
   *
   *  Types that bind parameters (lambdas, `RecType`) and those that contain
   *  trees (annotations) have no shape and are only shared by identity.
   */
  private def encodingKey(shape: TypeShape)(using Context): AnyRef | Null =
    var addrs: List[Addr] = Nil
    var cs = shape.components
    while cs.nonEmpty do
      val addr = pickledAddr(cs.head.stripTypeVar)
      if addr == null then return null
      addrs = addr :: addrs
      cs = cs.tail
    (shape.tag, shape.payload, addrs)

  private def pickleShape(shape: TypeShape, richTypes: Boolean)(using Context): Unit =
    val components = shape.components
    writeByte(shape.tag)
    shape.tag match
      case TYPEREFpkg | TERMREFpkg =>
        pickleName(shape.payload.asInstanceOf[Name])
      case TYPEREFdirect | TERMREFdirect =>
        val sym = shape.payload.asInstanceOf[Symbol]
        if Config.checkLevelsOnConstraints && !symRefs.contains(sym) && !sym.isPatternBound && !sym.hasAnnotation(defn.QuotedRuntimePatterns_patternTypeAnnot) then
          report.error(em"pickling reference to as yet undefined $sym", sym.srcPos)
        pickleSymRef(sym)
      case TYPEREF | TERMREF =>
        pickleName(shape.payload.asInstanceOf[Name])
        pickleType(components.head)
      case TYPEREFsymbol | TERMREFsymbol =>
        pickleSymRef(shape.payload.asInstanceOf[Symbol])
        pickleType(components.head)
      case TYPEREFin | TERMREFin =>
        withLength {
          pickleName(shape.payload.asInstanceOf[Name])
          components.foreach(pickleType(_))
        }
      case REFINEDtype =>
        val parent :: refinedInfo :: Nil = components: @unchecked
        withLength {
          pickleName(shape.payload.asInstanceOf[Name])
          pickleType(parent)
          pickleType(refinedInfo, richTypes = true)
        }
      case THIS | BYNAMEtype =>
        pickleType(components.head)
      case APPLIEDtype | MATCHCASEtype =>
        withLength { components.foreach(pickleType(_)) }
      case FLEXIBLEtype | ANDtype | ORtype =>
        withLength { components.foreach(pickleType(_, richTypes)) }
      case TYPEBOUNDS =>
        withLength {
          components.foreach(pickleType(_, richTypes))
          shape.payload.asInstanceOf[List[Int]].foreach(writeByte)
        }
      case _ =>
        pickleConstantValue(shape.payload.asInstanceOf[Constant])

  /** Pickle a type that has no shape */
  private def pickleNewType(tpe: Type, richTypes: Boolean)(using Context): Unit = tpe match {
    case ConstantType(value) =>
      pickleConstant(value)
    case tpe: NamedType =>
      // `FromJavaObject` in a Java pickle; it is replaced back when unpickling Java TASTy
      pickleType(defn.ObjectType)
    case tpe: SuperType =>
      writeByte(SUPERtype)
      withLength { pickleType(tpe.thistpe); pickleType(tpe.supertpe) }
    case tpe: RecThis =>
      writeByte(RECthis)
      val binderAddr: Addr | Null = pickledTypes.lookup(tpe.binder)
      assert(binderAddr != null, tpe.binder)
      writeRef(binderAddr)
    case tpe: SkolemType =>
      pickleType(tpe.info)
    case tpe: RecType =>
      writeByte(RECtype)
      pickleType(tpe.parent)
    case tpe: AnnotatedType =>
      writeByte(ANNOTATEDtype)
      withLength:
        pickleType(tpe.parent, richTypes)
        tpe.annot match
          case ann: CompactAnnotation =>
            if sourceVersion.enablesCompactAnnotation then
              pickleType(ann.tpe)
            else
              val atree = ann.oldTree
              pickleTree(atree)
              annotatedTypeTrees += atree
          case ann =>
            pickleTree(ann.tree)
            annotatedTypeTrees += ann.tree
    case tpe: HKTypeLambda =>
      pickleMethodic(TYPELAMBDAtype, tpe, EmptyFlags)
    case tpe: MatchType =>
      writeByte(MATCHtype)
      withLength {
        pickleType(tpe.bound)
        pickleType(tpe.scrutinee)
        tpe.cases.foreach(pickleType(_))
      }
    case tpe: PolyType if richTypes =>
      pickleMethodic(POLYtype, tpe, EmptyFlags)
    case tpe: MethodType if richTypes =>
      var mods = EmptyFlags
      if tpe.isContextualMethod then mods |= Given
      else if tpe.isImplicitMethod then mods |= Implicit
      pickleMethodic(METHODtype, tpe, mods)
    case tpe: ParamRef =>
      val pickled = pickleParamRef(tpe)
      if !ctx.isBestEffort then assert(pickled, s"orphan parameter reference: $tpe")
      else if !pickled then pickleErrorType()
    case tpe: LazyRef =>
      pickleType(tpe.ref)
    case _ if ctx.isBestEffort =>
      pickleErrorType()
  }

  def pickleMethodic(tag: Int, tpe: LambdaType, mods: FlagSet)(using Context): Unit = {
    writeByte(tag)
    withLength {
      pickleType(tpe.resultType, richTypes = true)
      tpe.paramNames.lazyZip(tpe.paramInfos).foreach { (name, tpe) =>
        pickleType(tpe); pickleName(name)
      }
      if (mods != EmptyFlags) pickleFlags(mods, tpe.isTermLambda)
    }
  }

  def pickleParamRef(tpe: ParamRef)(using Context): Boolean = {
    val binder: Addr | Null = pickledTypes.lookup(tpe.binder)
    if (binder != null) {
      writeByte(PARAMtype)
      withLength { writeRef(binder); writeNat(tpe.paramNum) }
      true
    } else {
      false
    }
  }

  def pickleErrorType(): Unit = {
    writeByte(ERRORtype)
  }

  def pickleTpt(tpt: Tree)(using Context): Unit =
    if passesConditionForErroringBestEffortCode(tpt.isType) then pickleTree(tpt)
    else pickleErrorType()

  def pickleTreeUnlessEmpty(tree: Tree)(using Context): Unit = {
    if (!tree.isEmpty) pickleTree(tree)
  }

  def pickleElidedUnlessEmpty(tree: Tree, tp: Type)(using Context): Unit =
    if !tree.isEmpty then
      writeByte(ELIDED)
      pickleType(tp)

  def pickleDef(tag: Int, mdef: MemberDef, tpt: Tree, rhs: Tree = EmptyTree, pickleParams: => Unit = ())(using Context): Unit = {
    val sym = mdef.symbol

    def isDefSymPreRegisteredAndTreeHasCorrectStructure() =
      symRefs.get(sym) == Some(NoAddr) && // check if symbol id preregistered (with the preRegister method)
      !(tag == TYPEDEF && tpt.isInstanceOf[Template] && !tpt.symbol.exists) // in case this is a TEMPLATE, check if we are able to pickle it

    if passesConditionForErroringBestEffortCode(isDefSymPreRegisteredAndTreeHasCorrectStructure()) then
      assert(symRefs(sym) == NoAddr, sym)
      registerDef(sym)
      writeByte(tag)
      val addr = currentAddr
      ctx.handleRecursive("tree pickling", mdef, mdef):
        withLength {
          pickleName(sym.name)
          pickleParams
          tpt match {
            case _: Template | _: Hole => pickleTree(tpt)
            case _ if tpt.isType => pickleTpt(tpt)
            case _ if ctx.isBestEffort => pickleErrorType()
          }
          if isOutlinePickle && sym.isTerm && isJavaPickle then
            // TODO: if we introduce outline typing for Scala definitions
            // then we will need to update the check here
            pickleElidedUnlessEmpty(rhs, tpt.tpe)
          else
            pickleTreeUnlessEmpty(rhs)
          pickleModifiers(sym, mdef)
        }
      if sym.is(Method) && sym.owner.isClass then
        profile.recordMethodSize(sym, (currentAddr.index - addr.index) max 1, mdef.span)
      for docCtx <- ctx.docCtx do
        val comment = docCtx.docstrings.lookup(sym)
        if comment != null then
          docStrings(mdef) = comment
  }

  def pickleParam(tree: Tree)(using Context): Unit = {
    registerTreeAddr(tree)
    tree match {
      case tree: ValDef  => pickleDef(PARAM, tree, tree.tpt)
      case tree: TypeDef => pickleDef(TYPEPARAM, tree, tree.rhs)
    }
  }

  def pickleParams(trees: List[Tree])(using Context): Unit = {
    trees.foreach(preRegister)
    trees.foreach(pickleParam)
  }

  def pickleStats(stats: List[Tree])(using Context): Unit = {
    stats.foreach(preRegister)
    stats.foreach(stat => if (!stat.isEmpty) pickleTree(stat))
  }

  def pickleTree(tree: Tree)(using Context): Unit = {
    val addr = registerTreeAddr(tree)
    if (addr != currentAddr) {
      writeByte(SHAREDterm)
      writeRef(addr)
    }
    else
      try tree match {
        case Ident(name) =>
          if passesConditionForErroringBestEffortCode(tree.hasType) then
            tree.tpe match {
              case tp: TermRef if name != nme.WILDCARD =>
                // wildcards are pattern bound, need to be preserved as ids.
                pickleType(tp)
              case tp =>
                writeByte(if (tree.isType) IDENTtpt else IDENT)
                pickleName(name)
                pickleType(tp)
            }
          else pickleErrorType()
        case This(qual) =>
          // This may be needed when pickling a `This` inside a capture set. See #19662 and #19859.
          // In this case, we pickle the tree as null.asInstanceOf[tree.tpe].
          // Since the pickled tree is not the same as the input, special handling is needed
          // in the tree printer when testing the pickler. See [[PlainPrinter#homogenize]].
          inline def pickleCapturedThis =
            pickleTree(Literal(Constant(null)).cast(tree.tpe).withSpan(tree.span))
          if (qual.isEmpty)
            if tree.tpe.isSingleton then pickleType(tree.tpe)
            else pickleCapturedThis
          else
            tree.tpe match
              case ThisType(tref) =>
                writeByte(QUALTHIS)
                pickleTree(qual.withType(tref))
              case _: ErrorType if ctx.isBestEffort =>
                if qual.hasType then
                  pickleTree(qual.asInstanceOf[Tree]) // it has a type, so it's a valid tpd.Tree
                else
                  pickleErrorType()
              case _ => pickleCapturedThis
        case Select(qual, name) =>
          name match {
            case OuterSelectName(_, levels) =>
              writeByte(SELECTouter)
              withLength {
                writeNat(levels)
                pickleTree(qual)
                val SkolemType(tp) = tree.tpe: @unchecked
                pickleType(tp)
              }
            case _ =>
              val sym = tree.symbol
              val ename = sym.targetName
              if passesConditionForErroringBestEffortCode(tree.hasType) then
                // #19951 The signature of a constructor of a Java annotation is irrelevant
                val sig =
                  if name == nme.CONSTRUCTOR && sym.exists && sym.owner.is(JavaAnnotation) then Signature.NotAMethod
                  else tree.tpe.signature
                val selectFromQualifier =
                  name.isTypeName
                  || qual.isInstanceOf[Hole] // holes have no symbol
                  || sig == Signature.NotAMethod // no overload resolution necessary
                  || !sym.exists // polymorphic function type
                  || tree.denot.asSingleDenotation.isRefinedMethod // refined methods have no defining class symbol
                if selectFromQualifier then
                  writeByte(if name.isTypeName then SELECTtpt else SELECT)
                  pickleNameAndSig(name, sig, ename)
                  pickleTree(qual)
                else // select from owner
                  writeByte(SELECTin)
                  withLength {
                    pickleNameAndSig(name, sym.signature, ename)
                    pickleTree(qual)
                    pickleType(sym.owner.typeRef)
                  }
              else
                writeByte(if name.isTypeName then SELECTtpt else SELECT)
                pickleNameAndSig(name, Signature.NotAMethod, ename)
                pickleTree(qual)
          }
        case Apply(fun, args) =>
          val funSym = fun.symbol
          if (funSym eq defn.throwMethod) {
            writeByte(THROW)
            pickleTree(args.head)
          }
          else if funSym.originalSignaturePolymorphic.exists then
            writeByte(APPLYsigpoly)
            withLength {
              pickleTree(fun)
              pickleType(fun.tpe.widenTermRefExpr, richTypes = true) // this widens to a MethodType, so need richTypes
              args.foreach(pickleTree)
            }
          else {
            writeByte(APPLY)
            withLength {
              pickleTree(fun)
              // #19951 Do not pickle default arguments to Java annotation constructors
              if funSym.isClassConstructor && funSym.owner.is(JavaAnnotation) then
                for arg <- args do
                  arg match
                    case NamedArg(_, Ident(nme.WILDCARD)) => ()
                    case _                                => pickleTree(arg)
              else
                args.foreach(pickleTree)
            }
          }
        case TypeApply(fun, args) =>
          writeByte(TYPEAPPLY)
          withLength {
            pickleTree(fun)
            args.foreach(pickleTpt)
          }
        case Literal(const1) =>
          if passesConditionForErroringBestEffortCode(tree.hasType) then
            pickleConstant {
              tree.tpe match {
                case ConstantType(const2) => const2
                case _ => const1
              }
            }
          else pickleConstant(const1)
        case Super(qual, mix) =>
          writeByte(SUPER)
          withLength {
            pickleTree(qual);
            if (!mix.isEmpty) {
              // mixinType being a TypeRef when mix is non-empty is enforced by TreeChecker#checkSuper
              val SuperType(_, mixinType: TypeRef) = tree.tpe: @unchecked
              pickleTree(mix.withType(mixinType))
            }
          }
        case New(tpt) =>
          writeByte(NEW)
          pickleTpt(tpt)
        case Typed(expr, tpt) =>
          writeByte(TYPED)
          withLength { pickleTree(expr); pickleTpt(tpt) }
        case NamedArg(name, arg) =>
          writeByte(NAMEDARG)
          pickleName(name)
          pickleTree(arg)
        case Assign(lhs, rhs) =>
          writeByte(ASSIGN)
          withLength { pickleTree(lhs); pickleTree(rhs) }
        case Block(stats, expr) =>
          writeByte(BLOCK)
          stats.foreach(preRegister)
          withLength { pickleTree(expr); stats.foreach(pickleTree) }
        case tree @ If(cond, thenp, elsep) =>
          writeByte(IF)
          withLength {
            if (tree.isInline) writeByte(INLINE)
            pickleTree(cond)
            pickleTree(thenp)
            pickleTree(elsep)
          }
        case Closure(env, meth, tpt) =>
          writeByte(LAMBDA)
          assert(env.isEmpty)
          withLength {
            pickleTree(meth)
            if (tpt.tpe.exists) pickleTpt(tpt)
          }
        case tree @ Match(selector, cases) =>
          writeByte(MATCH)
          withLength {
            if (tree.isInline)
              if (selector.isEmpty) writeByte(IMPLICIT)
              else { writeByte(INLINE); pickleTree(selector) }
            else if tree.isSubMatch then { writeByte(SUBMATCH); pickleTree(selector) }
            else pickleTree(selector)
            tree.cases.foreach(pickleTree)
          }
        case CaseDef(pat, guard, rhs) =>
          writeByte(CASEDEF)
          withLength { pickleTree(pat); pickleTree(rhs); pickleTreeUnlessEmpty(guard) }
        case Return(expr, from) =>
          writeByte(RETURN)
          withLength { pickleSymRef(from.symbol); pickleTreeUnlessEmpty(expr) }
        case WhileDo(cond, body) =>
          writeByte(WHILE)
          withLength { pickleTree(cond); pickleTree(body) }
        case Try(block, cases, finalizer) =>
          writeByte(TRY)
          withLength { pickleTree(block); cases.foreach(pickleTree); pickleTreeUnlessEmpty(finalizer) }
        case SeqLiteral(elems, elemtpt) =>
          writeByte(REPEATED)
          withLength { pickleTree(elemtpt); elems.foreach(pickleTree) }
        case tree @ Inlined(call, bindings, expansion) =>
          writeByte(INLINED)
          bindings.foreach(preRegister)
          withLength {
            pickleTree(expansion)
            if (!tree.inlinedFromOuterScope) pickleTree(call)
            bindings.foreach { b =>
              assert(b.isInstanceOf[DefDef] || b.isInstanceOf[ValDef])
              pickleTree(b)
            }
          }
        case Bind(name, body) =>
          val sym = tree.symbol
          registerDef(sym)
          writeByte(BIND)
          withLength {
            pickleName(name)
            pickleType(sym.info)
            pickleTree(body)
            pickleFlags(sym.flags &~ Case, sym.isTerm)
          }
        case Alternative(alts) =>
          writeByte(ALTERNATIVE)
          withLength { alts.foreach(pickleTree) }
        case UnApply(fun, implicits, patterns) =>
          writeByte(UNAPPLY)
          withLength {
            pickleTree(fun)
            for (implicitArg <- implicits) {
              writeByte(IMPLICITarg)
              pickleTree(implicitArg)
            }
            pickleType(tree.tpe)
            patterns.foreach(pickleTree)
          }
        case tree: ValDef =>
          pickleDef(VALDEF, tree, tree.tpt, tree.rhs)
        case tree: DefDef =>
          def pickleParamss(paramss: List[ParamClause]): Unit = paramss match
            case Nil =>
            case Nil :: rest =>
              writeByte(EMPTYCLAUSE)
              pickleParamss(rest)
            case (params @ (param1 :: _)) :: rest =>
              pickleParams(params)
              rest match
                case (param2 :: _) :: _
                if param1.isInstanceOf[untpd.TypeDef] == param2.isInstanceOf[untpd.TypeDef] =>
                  writeByte(SPLITCLAUSE)
                case _ =>
              pickleParamss(rest)
          pickleDef(DEFDEF, tree, tree.tpt, tree.rhs, pickleParamss(tree.paramss))
        case tree: TypeDef =>
          pickleDef(TYPEDEF, tree, tree.rhs)
        case tree: Template =>
          registerDef(tree.symbol)
          writeByte(TEMPLATE)
          val (params, rest) = decomposeTemplateBody(tree.body)
          withLength {
            pickleParams(params)
            tree.parents.foreach(pickleTree)
            val cinfo @ ClassInfo(_, _, _, _, selfInfo) = tree.symbol.owner.info: @unchecked
            if (!tree.self.isEmpty) {
              writeByte(SELFDEF)
              pickleName(tree.self.name)

              if (!tree.self.tpt.isEmpty) pickleTree(tree.self.tpt)
              else {
                if (!tree.self.isEmpty) registerTreeAddr(tree.self)
                pickleType {
                  selfInfo match {
                    case sym: Symbol => sym.info
                    case tp: Type => tp
                  }
                }
              }
            }
            if isJavaPickle then
              val rest0 = rest.dropWhile:
                case stat: ValOrDefDef => stat.symbol.is(Flags.Invisible)
                case _ => false
              if tree.constr.symbol.is(Flags.Invisible) then
                writeByte(SPLITCLAUSE)
                pickleStats(rest0)
              else
                pickleStats(tree.constr :: rest0)
            else
              pickleStats(tree.constr :: rest)
          }
        case Import(expr, selectors) =>
          writeByte(IMPORT)
          withLength {
            pickleTree(expr)
            pickleSelectors(selectors)
          }
        case Export(expr, selectors) =>
          writeByte(EXPORT)
          withLength {
            pickleTree(expr)
            pickleSelectors(selectors)
          }
        case PackageDef(pid, stats) =>
          writeByte(PACKAGE)
          withLength { pickleType(pid.tpe); pickleStats(stats) }
        case tree: TypeTree =>
          if passesConditionForErroringBestEffortCode(tree.hasType) then pickleType(tree.tpe)
          else pickleErrorType()
        case SingletonTypeTree(ref) =>
          val tp = ref.tpe
          val tp1 = tp.deskolemized
          if tp1 ne tp then
            pickleType(tp1)
          else
            writeByte(SINGLETONtpt)
            pickleTree(ref)
        case RefinedTypeTree(parent, refinements) =>
          if (refinements.isEmpty) pickleTree(parent)
          else {
            if passesConditionForErroringBestEffortCode(refinements.head.symbol.exists) then
              val refineCls = refinements.head.symbol.owner.asClass
              registerDef(refineCls)
              pickledTypes(refineCls.typeRef) = currentAddr
              writeByte(REFINEDtpt)
              refinements.foreach(preRegister)
              withLength { pickleTree(parent); refinements.foreach(pickleTree) }
            else pickleErrorType()
          }
        case AppliedTypeTree(tycon, args) =>
          writeByte(APPLIEDtpt)
          withLength { pickleTree(tycon); args.foreach(pickleTree) }
        case MatchTypeTree(bound, selector, cases) =>
          writeByte(MATCHtpt)
          withLength {
            if (!bound.isEmpty) pickleTree(bound)
            pickleTree(selector)
            cases.foreach(pickleTree)
          }
        case ByNameTypeTree(tp) =>
          writeByte(BYNAMEtpt)
          pickleTree(tp)
        case Annotated(tree, annot) =>
          writeByte(ANNOTATEDtpt)
          withLength { pickleTree(tree); pickleTree(annot) }
        case LambdaTypeTree(tparams, body) =>
          writeByte(LAMBDAtpt)
          withLength { pickleParams(tparams); pickleTree(body) }
        case TypeBoundsTree(lo, hi, alias) =>
          writeByte(TYPEBOUNDStpt)
          withLength {
            pickleTree(lo);
            if alias.isEmpty then
              if hi ne lo then pickleTree(hi)
            else
              pickleTree(hi)
              pickleTree(alias)
          }
        case tree @ Quote(body, Nil) =>
          assert(body.isTerm,
            """Quote with type should not be pickled.
              |Quote with type should only exists after staging phase at staging level 0.""".stripMargin)
          writeByte(QUOTE)
          withLength {
            pickleTree(body)
            pickleType(tree.bodyType)
          }
        case Splice(expr) =>
          writeByte(SPLICE)
          withLength {
            pickleTree(expr)
            pickleType(tree.tpe)
          }
        case QuotePattern(bindings, body, quotes)  =>
          writeByte(QUOTEPATTERN)
          withLength {
            if body.isType then writeByte(EXPLICITtpt)
            pickleTree(body)
            pickleTree(quotes)
            pickleType(tree.tpe)
            bindings.foreach(pickleTree)
          }
        case SplicePattern(pat, targs, args) =>
          writeByte(SPLICEPATTERN)
          withLength {
            pickleTree(pat)
            pickleType(tree.tpe)
            for targ <- targs do
              writeByte(EXPLICITtpt)
              pickleTree(targ)
            args.foreach(pickleTree)
          }
        case Hole(_, idx, args, _) =>
          writeByte(HOLE)
          withLength {
            writeNat(idx)
            pickleType(tree.tpe, richTypes = true)
            args.foreach { arg =>
              arg.tpe match
                case _: TermRef if arg.isType => writeByte(EXPLICITtpt)
                case _ =>
              pickleTree(arg)
            }
          }
        case other if ctx.isBestEffort =>
          pickleErrorType()
      }
      catch {
        case ex: TypeError =>
          report.error(ex.toMessage, tree.srcPos.focus)
          pickleErrorType()
        case NonFatal(t) =>
          println(i"error when pickling tree $tree of class ${tree.getClass}")
          throw t
      }
  }

  def pickleSelectors(selectors: List[untpd.ImportSelector])(using Context): Unit =
    for sel <- selectors do
      pickleSelector(IMPORTED, sel.imported)
      sel.renamed match
        case to @ Ident(_) => pickleSelector(RENAMED, to)
        case _ =>
      sel.bound match
        case bound @ untpd.TypedSplice(tpt) =>
          registerTreeAddr(bound)
          writeByte(BOUNDED)
          pickleTree(tpt)
        case _ =>

  def pickleSelector(tag: Int, id: untpd.Ident)(using Context): Unit = {
    registerTreeAddr(id)
    writeByte(tag)
    pickleName(id.name)
  }

  def pickleModifiers(sym: Symbol, mdef: MemberDef)(using Context): Unit = {
    import Flags.*
    var flags = sym.flags
    val privateWithin = sym.privateWithin
    if (privateWithin.exists) {
      writeByte(if (flags.is(Protected)) PROTECTEDqualified else PRIVATEqualified)
      pickleType(privateWithin.typeRef)
      flags = flags &~ Protected
    }
    if (flags.is(ParamAccessor) && sym.isTerm && !sym.isSetter)
      flags = flags &~ ParamAccessor // we only generate a tag for parameter setters
    pickleFlags(flags, sym.isTerm)
    val annots = sym.annotations.foreach(pickleAnnotation(sym, mdef, _))
  }

  def pickleFlags(flags: FlagSet, isTerm: Boolean)(using Context): Unit =
    import Flags.*
    def writeModTag(tag: Int) =
      assert(isModifierTag(tag))
      writeByte(tag)

    if flags.is(Scala2x) then assert(attributes.scala2StandardLibrary)
    if flags.is(Private) then writeModTag(PRIVATE)
    if flags.is(Protected) then writeModTag(PROTECTED)
    if flags.is(Final, butNot = Module) then writeModTag(FINAL)
    if flags.is(Case) then writeModTag(CASE)
    if flags.is(Override) then writeModTag(OVERRIDE)
    if flags.is(Inline) then writeModTag(INLINE)
    if flags.is(InlineProxy) then writeModTag(INLINEPROXY)
    if flags.is(Macro) then writeModTag(MACRO)
    if flags.is(JavaStatic) then writeModTag(STATIC)
    if flags.is(Module) then writeModTag(OBJECT)
    if flags.is(Enum) then writeModTag(ENUM)
    if flags.is(Local) then writeModTag(LOCAL)
    if flags.is(Synthetic) then writeModTag(SYNTHETIC)
    if flags.is(Artifact) then writeModTag(ARTIFACT)
    if flags.is(Transparent) then writeModTag(TRANSPARENT)
    if flags.is(Infix) then writeModTag(INFIX)
    if flags.is(Invisible) then writeModTag(INVISIBLE)
    if flags.is(Erased) then writeModTag(ERASED)
    if flags.is(Exported) then writeModTag(EXPORTED)
    if flags.is(Given) then writeModTag(GIVEN)
    if flags.is(Implicit) then writeModTag(IMPLICIT)
    if flags.is(Tracked) then writeModTag(TRACKED)
    if isTerm then
      if flags.is(Lazy, butNot = Module) then writeModTag(LAZY)
      if flags.is(AbsOverride) then { writeModTag(ABSTRACT); writeModTag(OVERRIDE) }
      if flags.is(Mutable) then writeModTag(MUTABLE)
      if flags.is(Accessor) then writeModTag(FIELDaccessor)
      if flags.is(CaseAccessor) then writeModTag(CASEaccessor)
      if flags.is(HasDefault) then writeModTag(HASDEFAULT)
      if flags.isAllOf(StableMethod) then writeModTag(STABLE) // other StableRealizable flag occurrences are either implied or can be recomputed
      if flags.is(Extension) then writeModTag(EXTENSION)
      if flags.is(ParamAccessor) then writeModTag(PARAMsetter)
      if flags.is(SuperParamAlias) then writeModTag(PARAMalias)
      assert(!flags.is(Label))
    else
      if flags.is(Sealed) then writeModTag(SEALED)
      if flags.is(Abstract) then writeModTag(ABSTRACT)
      if flags.is(Trait) then writeModTag(TRAIT)
      if flags.is(Covariant) then writeModTag(COVARIANT)
      if flags.is(Contravariant) then writeModTag(CONTRAVARIANT)
      if flags.is(Opaque) then writeModTag(OPAQUE)
      if flags.is(Open) then writeModTag(OPEN)
      if flags.is(Into) then writeModTag(INTO)
  end pickleFlags

  private def isUnpicklable(owner: Symbol, ann: Annotation)(using Context) = ann match {
    case Annotation.Child(sym) => sym.isInaccessibleChildOf(owner)
      // If child annotation refers to a local class or enum value under
      // a different toplevel class, it is impossible to pickle a reference to it.
      // Such annotations will be reconstituted when unpickling the child class.
      // See tests/pickling/i3149.scala
    case _ if ctx.isBestEffort && !ann.symbol.denot.isError => true
    case _ => defn.unpicklableAnnotations.contains(ann.symbol)
  }

  def pickleAnnotation(owner: Symbol, mdef: MemberDef, ann: Annotation)(using Context): Unit =
    if !isUnpicklable(owner, ann) then
      writeByte(ANNOTATION)
      val annotTree = ann match
        case ann: CompactAnnotation =>
          if sourceVersion.enablesCompactAnnotation then ann.tree else ann.oldTree
        case _ =>
          ann.tree
      withLength { pickleType(ann.symbol.typeRef); pickleTree(annotTree) }
      var treeBuf = annotTrees.lookup(mdef)
      if treeBuf == null then
        treeBuf = new mutable.ListBuffer[Tree]
        annotTrees(mdef) = treeBuf
      treeBuf += annotTree

// ---- main entry points ---------------------------------------

  def pickle(trees: List[Tree])(using Context): Unit = {
    profile = Profile.current
    if ctx.settings.YtestPicklerSharing.value then sharedByEncoding = mutable.ArrayBuffer()
    for tree <- trees do
      if !tree.isEmpty then pickleTree(tree)

    def missing = forwardSymRefs.keysIterator
      .map(sym => i"${sym.showLocated} (line ${sym.srcPos.line}) #${sym.id}")
      .toList
    assert(forwardSymRefs.isEmpty, i"unresolved symbols: $missing%, % when pickling ${ctx.source}")
    if sharedByEncoding != null then checkSharedByEncoding()
  }

  /** Check that each type in `sharedByEncoding` pickles to the same bytes as the
   *  type it shares. Each is pickled afresh at the end of the buffer, which is
   *  truncated again afterwards. This runs once all forward symbol references
   *  are patched, so that symbol references can be compared.
   */
  private def checkSharedByEncoding()(using Context): Unit =
    val toCheck = sharedByEncoding.nn.toList
    sharedByEncoding = null
    val start = currentAddr
    for (tpe, addr) <- toCheck do
      val fresh = currentAddr
      pickleShape(typeShape(tpe).nn, richTypes = false)
      assert(sameEncoding(addr, fresh),
        i"$tpe is pickled differently from the type at $addr that it shares, when pickling ${ctx.source}")
    buf.truncate(start)

  /** Are the type trees at `a` and `b` the same, after following SHAREDtype references? */
  private def sameEncoding(a: Addr, b: Addr): Boolean =
    def reader(addr: Addr) =
      val r = TastyReader(bytes, 0, length)
      r.goto(addr)
      r
    def deref(addr: Addr): Addr =
      val r = reader(addr)
      if r.readByte() == SHAREDtype then deref(r.readAddr()) else addr
    def skipTree(r: TastyReader): Unit =
      val tag = r.readByte()
      if tag >= firstLengthTreeTag then r.goto(r.readEnd())
      else if tag >= firstNatASTTreeTag then { r.readLongNat(); skipTree(r) }
      else if tag >= firstASTTreeTag then skipTree(r)
      else if tag >= firstNatTreeTag then r.readLongNat()
    def subtrees(r: TastyReader, end: Addr): List[Addr] =
      val addrs = mutable.ListBuffer[Addr]()
      while r.currentAddr != end do
        addrs += r.currentAddr
        skipTree(r)
      addrs.toList
    def same(a0: Addr, b0: Addr): Boolean =
      val a = deref(a0)
      val b = deref(b0)
      a == b || {
        val ra = reader(a)
        val rb = reader(b)
        def sameNat = ra.readLongNat() == rb.readLongNat()
        def sameSubtree = same(ra.currentAddr, rb.currentAddr)
        val tag = ra.readByte()
        tag == rb.readByte() && {
          if tag < firstNatTreeTag then true
          else if tag < firstASTTreeTag then sameNat
          else if tag < firstNatASTTreeTag then sameSubtree
          else if tag < firstLengthTreeTag then sameNat && sameSubtree
          else
            val endA = ra.readEnd()
            val endB = rb.readEnd()
            tag match
              case TYPEREFin | TERMREFin | REFINEDtype | APPLIEDtype | MATCHCASEtype
                 | FLEXIBLEtype | ANDtype | ORtype | TYPEBOUNDS =>
                val hasName = tag == TYPEREFin || tag == TERMREFin || tag == REFINEDtype
                (!hasName || sameNat)
                && subtrees(ra, endA).corresponds(subtrees(rb, endB))(same)
              case _ => // not shared by encoding, compare bytes
                java.util.Arrays.equals(bytes, a.index, endA.index, bytes, b.index, endB.index)
        }
      }
    same(a, b)

  def compactify(scratch: ScratchData = new ScratchData): Unit = {
    buf.compactify(scratch)

    def updateMapWithDeltas(mp: MutableSymbolMap[Addr]) =
      val keys = new Array[Symbol](mp.size)
      val it = mp.keysIterator
      var i = 0
      while i < keys.length do
        keys(i) = it.next()
        i += 1
      assert(!it.hasNext)
      i = 0
      while i < keys.length do
        val key = keys(i)
        mp(key) = adjusted(mp(key), scratch)
        i += 1

    updateMapWithDeltas(symRefs)
  }
}
