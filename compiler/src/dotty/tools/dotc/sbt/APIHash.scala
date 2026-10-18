package dotty.tools.dotc
package sbt

import scala.util.hashing.MurmurHash3

import xsbti.api

/** A hash of `xsbti.api` values that only depends on their contents.
 *
 *  Their `hashCode` is not suitable: it mixes in the identity hash of
 *  Java enums (`Variance`, `ParameterModifier`, `DefinitionType`), which
 *  differs between JVMs, and `Structure#hashCode` is a constant, so it
 *  ignores the members of a structure.
 *  Modelled on Zinc's `xsbt.api.HashAPI`.
 */
private[sbt] object APIHash:
  def definition(d: api.Definition, initHash: Int): Int =
    val hasher = APIHash(initHash)
    hasher.hashDefinition(d)
    hasher.h

  def tpe(t: api.Type, initHash: Int): Int =
    val hasher = APIHash(initHash)
    hasher.hashType(t)
    hasher.h

  def typeParameter(tp: api.TypeParameter, initHash: Int): Int =
    val hasher = APIHash(initHash)
    hasher.hashTypeParameter(tp)
    hasher.h

private[sbt] final class APIHash(initHash: Int):
  private var h = initHash
  private val visitedStructures = new java.util.IdentityHashMap[api.Structure, Integer]

  private def mix(x: Int): Unit = h = MurmurHash3.mix(h, x)
  private def mix(s: String): Unit = mix(s.hashCode)
  private def mix(b: Boolean): Unit = mix(if b then 1 else 0)

  private inline def mixArray[T](xs: Array[T])(inline f: T => Unit): Unit =
    mix(xs.length)
    var i = 0
    while i < xs.length do
      f(xs(i))
      i += 1

  private def hashDefinition(d: api.Definition): Unit =
    mix(d.name)
    hashAccess(d.access)
    mix(d.modifiers.raw.toInt)
    mixArray(d.annotations)(hashAnnotation)
    d match
      case c: api.ClassLike =>
        mix(1)
        mix(c.definitionType.ordinal)
        mixArray(c.typeParameters)(hashTypeParameter)
        hashType(c.selfType)
        hashType(c.structure)
        mix(c.topLevel)
        mixArray(c.savedAnnotations)(mix)
        mixArray(c.childrenOfSealedClass)(hashType)
      case c: api.ClassLikeDef =>
        mix(2)
        mix(c.definitionType.ordinal)
        mixArray(c.typeParameters)(hashTypeParameter)
      case d: api.Def =>
        mix(3)
        mixArray(d.typeParameters)(hashTypeParameter)
        mixArray(d.valueParameters)(hashParameterList)
        hashType(d.returnType)
      case v: api.Val =>
        mix(4)
        hashType(v.tpe)
      case v: api.Var =>
        mix(5)
        hashType(v.tpe)
      case t: api.TypeAlias =>
        mix(6)
        mixArray(t.typeParameters)(hashTypeParameter)
        hashType(t.tpe)
      case t: api.TypeDeclaration =>
        mix(7)
        mixArray(t.typeParameters)(hashTypeParameter)
        hashType(t.lowerBound)
        hashType(t.upperBound)

  private def hashAccess(a: api.Access): Unit = a match
    case _: api.Public => mix(1)
    case q: api.Qualified =>
      mix(q match
        case _: api.Protected => 2
        case _: api.Private => 3
      )
      q.qualifier match
        case _: api.Unqualified => mix(1)
        case _: api.ThisQualifier => mix(2)
        case id: api.IdQualifier =>
          mix(3)
          mix(id.value)

  private def hashAnnotation(a: api.Annotation): Unit =
    hashType(a.base)
    mixArray(a.arguments) { arg =>
      mix(arg.name)
      mix(arg.value)
    }

  private def hashTypeParameter(tp: api.TypeParameter): Unit =
    mix(tp.id)
    mixArray(tp.annotations)(hashAnnotation)
    mixArray(tp.typeParameters)(hashTypeParameter)
    mix(tp.variance.ordinal)
    hashType(tp.lowerBound)
    hashType(tp.upperBound)

  private def hashParameterList(pl: api.ParameterList): Unit =
    mix(pl.isImplicit)
    mixArray(pl.parameters) { p =>
      mix(p.name)
      hashType(p.tpe)
      mix(p.hasDefault)
      mix(p.modifier.ordinal)
    }

  private def hashPath(p: api.Path): Unit =
    mixArray(p.components) {
      case id: api.Id =>
        mix(1)
        mix(id.id)
      case s: api.Super =>
        mix(2)
        hashPath(s.qualifier)
      case _: api.This =>
        mix(3)
    }

  private def hashType(t: api.Type): Unit = t match
    case p: api.Projection =>
      mix(1)
      hashType(p.prefix)
      mix(p.id)
    case p: api.ParameterRef =>
      mix(2)
      mix(p.id)
    case s: api.Singleton =>
      mix(3)
      hashPath(s.path)
    case e: api.Existential =>
      mix(4)
      hashType(e.baseType)
      mixArray(e.clause)(hashTypeParameter)
    case c: api.Constant =>
      mix(5)
      hashType(c.baseType)
      mix(c.value)
    case a: api.Annotated =>
      mix(6)
      hashType(a.baseType)
      mixArray(a.annotations)(hashAnnotation)
    case s: api.Structure =>
      mix(7)
      val seen = visitedStructures.get(s)
      if seen != null then
        mix(seen.intValue)
      else
        visitedStructures.put(s, visitedStructures.size)
        mixArray(s.parents)(hashType)
        mixArray(s.declared)(hashDefinition)
        mixArray(s.inherited)(hashDefinition)
    case p: api.Polymorphic =>
      mix(8)
      hashType(p.baseType)
      mixArray(p.parameters)(hashTypeParameter)
    case p: api.Parameterized =>
      mix(9)
      hashType(p.baseType)
      mixArray(p.typeArguments)(hashType)
    case _: api.EmptyType =>
      mix(10)
end APIHash
