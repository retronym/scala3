package dotty.tools.dotc
package sbt

import xsbti.UseScope
import xsbti.api.*

import scala.collection.mutable
import scala.util.hashing.MurmurHash3

/** What Zinc does with the API of a class we send it: it hashes it (`HashAPI`, `NameHashing`,
 *  `APIUtil.hasMacro`) and keeps a minimised copy (`APIUtil.minimize`). When Zinc implements
 *  `xsbti.AnalysisCallback5`, we do both here and send only the result.
 *
 *  This is a port of Zinc's code over the same `xsbti.api` tree. The hash values differ from
 *  Zinc's; what must agree is whether two versions of a class hash the same, as a whole
 *  (`apiHash`, `extraHash`) and per name. Values must be stable across JVMs: Zinc stores them.
 */
object APIHashing:

  def hashes(c: ClassLike, optimizedSealed: Boolean): interfaces.ClassHashes =
    val apiHash = HashAPI.hash(_.hashAPI(c))
    val extraHash =
      if c.definitionType != DefinitionType.Trait then apiHash
      else HashAPI.hash(_.hashAPI(c), includeTraitBreakers = true)
    interfaces.ClassHashes(apiHash, extraHash, NameHashing(optimizedSealed).nameHashes(c), hasMacro(c))

  // --- APIUtil.minimize ------------------------------------------------------------------------

  /** The class as Zinc stores it: the header, parents, self type, main methods, a stub (name,
   *  access, modifiers, annotations) of every other declaration, and a stub of each abstract
   *  inherited member.
   */
  def thin(c: ClassLike): ClassLike =
    val savedAnnotations = defAnnotations(c.structure)
    val struct = thinStructure(c.structure, c.definitionType == DefinitionType.Module)
    ClassLike.of(c.name, c.access, c.modifiers, c.annotations, c.definitionType,
      SafeLazy.strict(c.selfType), SafeLazy.strict(struct), savedAnnotations,
      c.childrenOfSealedClass, c.topLevel, c.typeParameters)

  private def thinStructure(s: Structure, isModule: Boolean): Structure =
    val declared = s.declared
    val mains = if isModule then declared.filter(isMainMethod) else noDefs
    val stubs = declared.filterNot(mains.contains).map(stub)
    val inherited = s.inherited
    val inheritedMains = if isModule then inherited.filter(isMainMethod) else noDefs
    val abstractInherited = inherited.filter(d => d.modifiers.isAbstract && !inheritedMains.contains(d))
    Structure.of(SafeLazy.strict(s.parents), SafeLazy.strict(mains ++ stubs),
      SafeLazy.strict(inheritedMains ++ abstractInherited.map(stub)))

  def stub(d: ClassDefinition): ClassDefinition =
    Def.of(d.name, d.access, d.modifiers, d.annotations, noTypeParams, noParamLists, emptyType)

  private val noDefs = Array.empty[ClassDefinition]
  private val noTypeParams = Array.empty[TypeParameter]
  private val noParamLists = Array.empty[ParameterList]
  private val emptyType = EmptyType.of()

  // --- Discovery -------------------------------------------------------------------------------

  /** The annotations of public methods, which test discovery reads (`Discovery.defAnnotations`). */
  private def defAnnotations(s: Structure): Array[String] =
    val result = mutable.LinkedHashSet.empty[String]
    def add(ds: Array[ClassDefinition]) =
      for case d: Def <- ds if d.access.isInstanceOf[Public]; a <- d.annotations; n <- simpleName(a.base) do
        result += n
    add(s.declared)
    add(s.inherited)
    result.toArray

  def isMainMethod(d: Definition): Boolean = d match
    case d: Def =>
      d.name == "main" && d.access.isInstanceOf[Public] && !d.modifiers.isAbstract &&
        simpleName(d.returnType).contains("scala.Unit") && {
          val vps = d.valueParameters
          vps.length == 1 && {
            val ps = vps(0).parameters
            ps.length == 1 && {
              val p = ps(0)
              (p.modifier == ParameterModifier.Plain || p.modifier == ParameterModifier.Repeated) &&
                (p.tpe match
                  case t: Parameterized =>
                    simpleName(t.baseType).contains("scala.Array") && t.typeArguments.length == 1 &&
                      simpleName(t.typeArguments()(0)).contains("java.lang.String")
                  case _ => false)
            }
          }
        }
    case _ => false

  @annotation.tailrec
  def simpleName(t: Type): Option[String] = t match
    case a: Annotated => simpleName(a.baseType)
    case _: Singleton => None
    case p: Projection =>
      p.prefix match
        case s: Singleton =>
          val cs = s.path.components
          cs.last match
            case _: This =>
              val ids = cs.init.collect { case i: Id => i.id }
              if ids.length == cs.length - 1 then Some((ids :+ p.id).mkString(".")) else None
            case _ => None
        case _: EmptyType => Some(p.id)
        case _ => None
    case _ => None

  // --- APIUtil.hasMacro ------------------------------------------------------------------------

  def hasMacro(c: ClassLike): Boolean =
    var result = false
    val visit = new Visit:
      // A class that inherits a macro does not have a macro.
      override def visitStructure0(s: Structure): Unit =
        visitTypes(s.parents)
        visitDefinitions(s.declared)
      override def visitModifiers(m: Modifiers): Unit =
        result ||= m.isMacro
    visit.visitDefinition(c)
    result

  /** `isNonPrivate` in Zinc's `APIUtil`: `private[pkg]` counts as non-private. */
  def isNonPrivate(a: Access): Boolean = a match
    case p: Private => p.qualifier.isInstanceOf[IdQualifier]
    case _ => true

  // --- NameHashing -----------------------------------------------------------------------------

  private final class NameHashing(optimizedSealed: Boolean):
    def nameHashes(c: ClassLike): Array[NameHash] =
      val defs = mutable.ArrayBuffer.empty[Definition]
      new Visit:
        override def visitDefinition(d: Definition): Unit =
          if d.isInstanceOf[ClassLike] || isNonPrivate(d.access) then
            defs += d
            super.visitDefinition(d)
      .visitDefinition(c)
      val (regular, implicits) = defs.partition(!_.modifiers.isImplicit)
      val isTerm = c.definitionType == DefinitionType.Module || c.definitionType == DefinitionType.PackageModule
      val location = MurmurHash3.finalizeHash(MurmurHash3.mix(MurmurHash3.stringHash(c.name), if isTerm then 1 else 2), 2)
      val forPatMat =
        if optimizedSealed then
          group(defs.filter(d => d.isInstanceOf[ClassLike] && d.modifiers.isSealed), location, UseScope.PatMatTarget)
        else Nil
      (group(regular, location, UseScope.Default) ++ group(implicits, location, UseScope.Implicit) ++ forPatMat).toArray

    private def group(defs: collection.Seq[Definition], location: Int, scope: UseScope): Iterable[NameHash] =
      val includeSealedChildren = !optimizedSealed || scope == UseScope.PatMatTarget
      defs.groupBy(d => localName(d.name)).map: (name, ds) =>
        NameHash.of(name, scope, HashAPI.hash(_.hashDefinitionsWithExtraHash(ds, location),
          includeDefinitions = false, includeSealedChildren = includeSealedChildren))

    private def localName(name: String): String = name.substring(name.lastIndexOf('.') + 1)
  end NameHashing

  // --- HashAPI ---------------------------------------------------------------------------------

  private object HashAPI:
    def hash(doHashing: HashAPI => Unit, includeDefinitions: Boolean = true,
        includeSealedChildren: Boolean = true, includeTraitBreakers: Boolean = false): Int =
      val hasher = new HashAPI(includeDefinitions, includeSealedChildren, includeTraitBreakers)
      doHashing(hasher)
      hasher.finalizeHash

    private final val ValHash = 1
    private final val VarHash = 2
    private final val DefHash = 3
    private final val ClassDefHash = 4
    private final val TypeDeclHash = 5
    private final val TypeAliasHash = 6
    private final val PublicHash = 30
    private final val ProtectedHash = 31
    private final val PrivateHash = 32
    private final val UnqualifiedHash = 33
    private final val ThisQualifierHash = 34
    private final val IdQualifierHash = 35
    private final val IdPathHash = 20
    private final val SuperHash = 21
    private final val ThisPathHash = 22
    private final val ValueParamsHash = 40
    private final val EmptyTypeHash = 51
    private final val ParameterRefHash = 52
    private final val SingletonHash = 53
    private final val ProjectionHash = 54
    private final val ParameterizedHash = 55
    private final val AnnotatedHash = 56
    private final val PolymorphicHash = 57
    private final val ConstantHash = 58
    private final val ExistentialHash = 59
    private final val StructureHash = 60
    private final val ClassHash = 70
    private final val TraitHash = 71
    private final val TrueHash = 97
    private final val FalseHash = 98
  end HashAPI

  /** Zinc's `HashAPI`, with `includePrivate = false`. */
  private final class HashAPI(includeDefinitions: Boolean, includeSealedChildren: Boolean, includeTraitBreakers: Boolean):
    import HashAPI.*
    import MurmurHash3.{mix, stringHash}

    private var hash: Int = 0

    private val visitedStructures = new mutable.HashMap[Structure, List[Int]]
    private val visitedClassLike = new mutable.HashMap[ClassLike, List[Int]]

    /** Hashes `t` once; a cycle back to `t` hashes the state on entry to it instead. */
    private def visit[T](map: mutable.HashMap[T, List[Int]], t: T)(hashF: T => Unit): Unit =
      map.put(t, hash :: map.getOrElse(t, Nil)) match
        case Some(x :: _) => extend(x)
        case _ =>
          hashF(t)
          for hs <- map(t) do extend(hs)
          map.put(t, hash :: Nil)

    def finalizeHash: Int = MurmurHash3.finalizeHash(hash, 1)
    def extend(a: Int): Unit = hash = mix(hash, a)
    private def hashString(s: String): Unit = extend(stringHash(s))
    private def hashBoolean(b: Boolean): Unit = extend(if b then TrueHash else FalseHash)
    private inline def hashArray[T <: AnyRef](s: Array[T], inline hashF: T => Unit): Unit =
      extend(s.length)
      var i = 0
      while i < s.length do
        hashF(s(i))
        i += 1

    /** Order-insensitive, as `MurmurHash3.unorderedHash` over the elements' own hashes. */
    private def hashSymmetric[T](ts: Iterable[T], hashF: T => Unit): Unit =
      val current = hash
      var a, b, n = 0
      var c = 1
      for t <- ts do
        hash = 1
        hashF(t)
        val h = finalizeHash
        a += h
        b ^= h
        c *= h | 1
        n += 1
      var h = MurmurHash3.setSeed
      h = mix(h, a)
      h = mix(h, b)
      h = MurmurHash3.mixLast(h, c)
      hash = current
      extend(MurmurHash3.finalizeHash(h, n))

    def hashAPI(c: ClassLike): Unit =
      hash = 1
      hashClass(c)

    private def hashDefinitions(ds: Array[ClassDefinition], isTrait: Boolean): Unit =
      def isTraitBreaker(d: Definition): Boolean = d match
        case _: FieldLike => true
        case cl: ClassLikeDef => cl.definitionType == DefinitionType.Module
        case d: Def => d.modifiers.isSuperAccessor
        case _ => false
      val nonPrivate = ds.filter(d => isNonPrivate(d.access))
      // As in Zinc, a `private[pkg]` trait breaker is hashed twice.
      val defs =
        if isTrait && includeTraitBreakers then
          nonPrivate ++ ds.filter(d => isTraitBreaker(d) && d.access.isInstanceOf[Private])
        else nonPrivate
      hashSymmetric(defs, hashDefinition)

    def hashDefinitionsWithExtraHash(ds: Iterable[Definition], extraHash: Int): Unit =
      hashSymmetric(ds, d => { hashDefinition(d); extend(extraHash) })

    private def hashDefinition(d: Definition): Unit =
      hashString(d.name)
      hashAnnotations(d.annotations)
      extend(d.modifiers.raw.toInt)
      hashAccess(d.access)
      d match
        case c: ClassLikeDef =>
          extend(ClassDefHash)
          hashTypeParameters(c.typeParameters)
        case c: ClassLike => hashClass(c)
        case f: FieldLike =>
          f match
            case _: Var => extend(VarHash)
            case _: Val => extend(ValHash)
          hashType(f.tpe)
        case d: Def =>
          extend(DefHash)
          hashTypeParameters(d.typeParameters)
          hashArray(d.valueParameters, hashValueParameterList)
          hashType(d.returnType)
        case t: TypeDeclaration =>
          extend(TypeDeclHash)
          hashTypeParameters(t.typeParameters)
          hashType(t.lowerBound)
          hashType(t.upperBound)
        case t: TypeAlias =>
          extend(TypeAliasHash)
          hashTypeParameters(t.typeParameters)
          hashType(t.tpe)

    private def hashClass(c: ClassLike): Unit = visit(visitedClassLike, c)(hashClass0)
    private def hashClass0(c: ClassLike): Unit =
      extend(ClassHash)
      hashTypeParameters(c.typeParameters)
      hashType(c.selfType)
      if includeSealedChildren then
        hashSymmetric(c.childrenOfSealedClass, hashType(_, includeDefinitions))
      val isTrait = c.definitionType == DefinitionType.Trait
      if isTrait then extend(TraitHash)
      hashStructure(c.structure, includeDefinitions, isTrait)

    private def hashAccess(a: Access): Unit = a match
      case _: Public => extend(PublicHash)
      case q: Qualified =>
        q match
          case _: Protected => extend(ProtectedHash)
          case _: Private => extend(PrivateHash)
        q.qualifier match
          case _: Unqualified => extend(UnqualifiedHash)
          case _: ThisQualifier => extend(ThisQualifierHash)
          case id: IdQualifier =>
            extend(IdQualifierHash)
            hashString(id.value)

    private def hashValueParameterList(list: ParameterList): Unit =
      extend(ValueParamsHash)
      hashBoolean(list.isImplicit)
      hashArray(list.parameters, p =>
        hashString(p.name)
        hashType(p.tpe)
        extend(p.modifier.ordinal)
        hashBoolean(p.hasDefault))

    private def hashTypeParameters(ps: Array[TypeParameter]): Unit = hashArray(ps, hashTypeParameter)
    private def hashTypeParameter(p: TypeParameter): Unit =
      hashString(p.id)
      extend(p.variance.ordinal)
      hashTypeParameters(p.typeParameters)
      hashType(p.lowerBound)
      hashType(p.upperBound)
      hashAnnotations(p.annotations)

    private def hashAnnotations(as: Array[Annotation]): Unit = hashArray(as, hashAnnotation)
    private def hashAnnotation(a: Annotation): Unit =
      hashType(a.base)
      hashArray(a.arguments, arg =>
        hashString(arg.name)
        hashString(arg.value))

    private def hashTypes(ts: Array[Type], includeDefinitions: Boolean): Unit =
      hashArray(ts, hashType(_, includeDefinitions))

    private def hashType(t: Type, includeDefinitions: Boolean = true): Unit = t match
      case s: Structure => hashStructure(s, includeDefinitions, isTrait = false)
      case e: Existential =>
        extend(ExistentialHash)
        hashTypeParameters(e.clause)
        hashType(e.baseType)
      case c: Constant =>
        extend(ConstantHash)
        hashString(c.value)
        hashType(c.baseType)
      case p: Polymorphic =>
        extend(PolymorphicHash)
        hashTypeParameters(p.parameters)
        hashType(p.baseType)
      case a: Annotated =>
        extend(AnnotatedHash)
        hashType(a.baseType)
        hashAnnotations(a.annotations)
      case p: Parameterized =>
        extend(ParameterizedHash)
        hashType(p.baseType)
        hashTypes(p.typeArguments, includeDefinitions = true)
      case p: Projection =>
        extend(ProjectionHash)
        hashString(p.id)
        hashType(p.prefix)
      case _: EmptyType => extend(EmptyTypeHash)
      case s: Singleton =>
        extend(SingletonHash)
        hashPath(s.path)
      case p: ParameterRef =>
        extend(ParameterRefHash)
        hashString(p.id)

    private def hashPath(path: Path): Unit = hashArray(path.components, {
      case _: This => extend(ThisPathHash)
      case s: Super =>
        extend(SuperHash)
        hashPath(s.qualifier)
      case id: Id =>
        extend(IdPathHash)
        hashString(id.id)
    })

    private def hashStructure(s: Structure, includeDefinitions: Boolean, isTrait: Boolean): Unit =
      val withTraitBreakers = isTrait && includeTraitBreakers
      visit(visitedStructures, s): s =>
        extend(StructureHash)
        hashTypes(s.parents, includeDefinitions)
        if includeDefinitions || withTraitBreakers then
          hashDefinitions(s.declared, withTraitBreakers)
          hashDefinitions(s.inherited, withTraitBreakers)
  end HashAPI

  // --- Visit -----------------------------------------------------------------------------------

  /** Zinc's `Visit`, reduced to what `NameHashing` and `hasMacro` need. */
  private class Visit:
    private val visitedStructures = new mutable.HashSet[Structure]
    private val visitedClassLike = new mutable.HashSet[ClassLike]

    def visitDefinitions(ds: Array[? <: Definition]): Unit = ds.foreach(visitDefinition)
    def visitDefinition(d: Definition): Unit =
      visitAnnotations(d.annotations)
      visitModifiers(d.modifiers)
      d match
        case c: ClassLikeDef => visitTypeParameters(c.typeParameters)
        case c: ClassLike =>
          if visitedClassLike.add(c) then
            visitTypeParameters(c.typeParameters)
            visitType(c.selfType)
            visitStructure(c.structure)
        case f: FieldLike => visitType(f.tpe)
        case d: Def =>
          visitTypeParameters(d.typeParameters)
          d.valueParameters.foreach(_.parameters.foreach(p => visitType(p.tpe)))
          visitType(d.returnType)
        case t: TypeDeclaration =>
          visitTypeParameters(t.typeParameters)
          visitType(t.lowerBound)
          visitType(t.upperBound)
        case t: TypeAlias =>
          visitTypeParameters(t.typeParameters)
          visitType(t.tpe)
    def visitModifiers(m: Modifiers): Unit = ()

    private def visitTypeParameters(ps: Array[TypeParameter]): Unit = ps.foreach: p =>
      visitTypeParameters(p.typeParameters)
      visitType(p.lowerBound)
      visitType(p.upperBound)
      visitAnnotations(p.annotations)
    private def visitAnnotations(as: Array[Annotation]): Unit = as.foreach(a => visitType(a.base))

    def visitTypes(ts: Array[Type]): Unit = ts.foreach(visitType)
    private def visitType(t: Type): Unit = t match
      case s: Structure => if visitedStructures.add(s) then visitStructure0(s)
      case e: Existential =>
        visitTypeParameters(e.clause)
        visitType(e.baseType)
      case c: Constant => visitType(c.baseType)
      case p: Polymorphic =>
        visitTypeParameters(p.parameters)
        visitType(p.baseType)
      case a: Annotated =>
        visitType(a.baseType)
        visitAnnotations(a.annotations)
      case p: Parameterized =>
        visitType(p.baseType)
        visitTypes(p.typeArguments)
      case p: Projection => visitType(p.prefix)
      case _ => ()
    private def visitStructure(s: Structure): Unit = if visitedStructures.add(s) then visitStructure0(s)
    def visitStructure0(s: Structure): Unit =
      visitTypes(s.parents)
      visitDefinitions(s.declared)
      visitDefinitions(s.inherited)
  end Visit
end APIHashing
