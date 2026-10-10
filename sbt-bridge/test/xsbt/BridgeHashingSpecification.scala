package xsbt

import xsbt.api.{APIUtil, HashAPI, NameHashing}
import xsbti.{AnalysisCallback5, ClassHashes, TestCallback, TestCallback5, UseScope}
import xsbti.AnalysisCallback5.ApiMode
import xsbti.api.*

import org.junit.Test
import org.junit.Assert.*
import sbt.io.IO
import java.io.File

/** The bridge's hashes (`AnalysisCallback5`) must agree with Zinc's hashing of the full API on
 *  whether a class, or a name in it, changed between two versions. The values differ.
 */
class BridgeHashingSpecification {
  import BridgeHashingSpecification.*

  /** Compiles in `dir`, so that versions of a source have the same path: its `@SourceFile`
   *  annotation is part of each class's own name hash.
   */
  private def compile(srcs: Seq[String], optimizedSealed: Boolean, dir: File = IO.createTemporaryDirectory): Map[String, (Hashes, Hashes)] = {
    IO.delete(new File(dir, "classes"))
    val cb = new TestCallback5(ApiMode.CHECK, optimizedSealed)
    new ScalaCompilerForUnitTesting().compileSrcs(List(srcs.toList), callback = Some(cb), tempDir = Some(dir))
    assertTrue("nothing sent through apiCheck", cb.sent.nonEmpty)
    cb.sent.map { (full, thin, hashes) =>
      val key = s"${full.nn.name}/${full.nn.definitionType}"
      val tree = treeHashes(full.nn, optimizedSealed)
      val bridge = toHashes(hashes)
      assertEquals(s"$key: name-hash keys", tree.names.keySet, bridge.names.keySet)
      assertEquals(s"$key: hasMacro", tree.hasMacro, bridge.hasMacro)
      checkThin(key, full.nn, thin)
      key -> (tree, bridge)
    }.toMap
  }

  /** What changed in each class between `v1` and `v2`, by Zinc's hashing, after checking that the
   *  bridge agrees. Classes are keyed `name/DefinitionType`; changes are `api`, `extra` and
   *  `name/UseScope`.
   */
  private def changes(v1: Seq[String], v2: Seq[String], optimizedSealed: Boolean = false): Map[String, Set[String]] = {
    val dir = IO.createTemporaryDirectory
    val a = compile(v1, optimizedSealed, dir)
    val b = compile(v2, optimizedSealed, dir)
    (a.keySet & b.keySet).toList.map { key =>
      val (treeA, bridgeA) = a(key)
      val (treeB, bridgeB) = b(key)
      val changed = Set.newBuilder[String]
      def agree(what: String, tree: Boolean, bridge: Boolean): Unit = {
        assertEquals(s"$key: did $what change? Zinc says $tree, the bridge $bridge", tree, bridge)
        if (tree) changed += what
      }
      agree("api", treeA.api != treeB.api, bridgeA.api != bridgeB.api)
      agree("extra", treeA.extra != treeB.extra, bridgeA.extra != bridgeB.extra)
      for (k <- treeA.names.keySet ++ treeB.names.keySet)
        agree(s"${k._1}/${k._2}", treeA.names.get(k) != treeB.names.get(k), bridgeA.names.get(k) != bridgeB.names.get(k))
      key -> changed.result()
    }.toMap
  }

  private def changes(v1: String, v2: String): Map[String, Set[String]] = changes(Seq(v1), Seq(v2))

  @Test def unchanged(): Unit = {
    val v1 = "class A { def foo: Int = 1; private def p = 1 }"
    val v2 = "class A { def foo: Int = 2; private def p = \"\" }"
    assertEquals(Map("A/ClassDef" -> Set()), changes(v1, v2))
  }

  @Test def memberAdded(): Unit = {
    val c = changes("class A { def foo: Int = 1 }", "class A { def foo: Int = 1; def bar: String = \"\" }")
    assertEquals(Set("api", "extra", "bar/Default"), c("A/ClassDef"))
    val r = changes("class A { def foo: Int = 1; def bar: String = \"\" }", "class A { def foo: Int = 1 }")
    assertEquals(Set("api", "extra", "bar/Default"), r("A/ClassDef"))
  }

  @Test def signatureChanged(): Unit = {
    val c = changes("object O { def foo(x: Int): Int = 1; def bar = 1 }", "object O { def foo(x: Long): Int = 1; def bar = 1 }")
    assertEquals(Set("api", "extra", "foo/Default"), c("O/Module"))
  }

  @Test def inlineBody(): Unit = {
    val c = changes("object O { inline def f: Int = 1; def g = 1 }", "object O { inline def f: Int = 2; def g = 1 }")
    assertEquals(Set("api", "extra", "f/Default"), c("O/Module"))
  }

  @Test def givensAndImplicits(): Unit = {
    val c = changes("object O { given g: Int = 1; def h = 1 }", "object O { given g: Long = 1L; def h = 1 }")
    assertEquals(Set("api", "extra", "g/Implicit"), c("O/Module"))
    val d = changes("object O { implicit def g: Int = 1 }", "object O { def g: Int = 1 }")
    assertEquals(Set("api", "extra", "g/Implicit", "g/Default"), d("O/Module"))
  }

  @Test def sealedChildren(): Unit = {
    val v1 = Seq("sealed trait S; case class A() extends S")
    val v2 = Seq("sealed trait S; case class A() extends S; case class B() extends S")
    assertTrue(changes(v1, v2, optimizedSealed = false)("S/Trait")("S/Default"))
    val opt = changes(v1, v2, optimizedSealed = true)("S/Trait")
    assertTrue(opt("S/PatMatTarget"))
    assertFalse(opt("S/Default"))
  }

  @Test def nestedSealedChildren(): Unit = {
    val v1 = "object O { sealed trait S; case class A() extends S }"
    val v2 = "object O { sealed trait S; case class A() extends S; case class B() extends S }"
    val c = changes(Seq(v1), Seq(v2), optimizedSealed = true)
    val s = c.collectFirst { case (k, v) if k.endsWith("S/Trait") => v }.get
    assertTrue(s("S/PatMatTarget"))
    assertFalse(s("S/Default"))
  }

  @Test def opaqueTypes(): Unit = {
    val c = changes("object O { opaque type T = Int; def f = 1 }", "object O { opaque type T = Long; def f = 1 }")
    // An object with an opaque type has a self type refined by the type's alias.
    assertEquals(Set("api", "extra", "T/Default", "O/Default"), c("O/Module"))
  }

  @Test def exports(): Unit = {
    val v1 = "class B { def f = 1 }; object O { val b = B(); export b.* }"
    val v2 = "class B { def f = 1; def g = 2 }; object O { val b = B(); export b.* }"
    val c = changes(v1, v2)
    assertEquals(Set("api", "extra", "g/Default"), c("O/Module"))
  }

  @Test def privateTraitMembers(): Unit = {
    val c = changes("trait T { def f = 1 }", "trait T { def f = 1; private val x = 1 }")
    assertEquals(Set("extra"), c("T/Trait"))
    val d = changes("trait T { def f = 1 }", "trait T { def f = 1; private def g = 1 }")
    assertEquals(Set(), d("T/Trait"))
    val e = changes("trait T { def f = 1 }", "trait T { def f = 1; private object X }")
    assertEquals(Set("extra"), e("T/Trait"))
  }

  @Test def valueClasses(): Unit = {
    val c = changes("class V(val x: Int) extends AnyVal", "class V(val x: Long) extends AnyVal")
    assertEquals(Set("api", "extra", "V/Default", "x/Default", "V;init;/Default"), c("V/ClassDef"))
  }

  @Test def refinements(): Unit = {
    val c = changes("object O { def r: { type T = Int } = ??? }", "object O { def r: { type T = Long } = ??? }")
    assertEquals(Set("api", "extra", "r/Default", "T/Default"), c("O/Module"))
  }

  @Test def macros(): Unit = {
    val src = """|import scala.quoted.*
                 |object M { inline def m: Int = ${ mImpl }; def mImpl(using Quotes): Expr[Int] = '{1} }
                 |""".stripMargin
    val (tree, _) = compile(Seq(src), optimizedSealed = false)("M/Module")
    assertTrue(tree.hasMacro)
  }

  @Test def hashesModeMatchesCheck(): Unit = {
    val src = "sealed trait S { def f: Int }; case class A(x: Int) extends S { def f = x }; object O { given Int = 1 }"
    val dir = IO.createTemporaryDirectory
    val check = new TestCallback5(ApiMode.CHECK, optimizedSealed = true)
    new ScalaCompilerForUnitTesting().compileSrcs(List(List(src)), callback = Some(check), tempDir = Some(dir))
    IO.delete(new File(dir, "classes"))
    val hashes = new TestCallback5(ApiMode.HASHES, optimizedSealed = true)
    new ScalaCompilerForUnitTesting().compileSrcs(List(List(src)), callback = Some(hashes), tempDir = Some(dir))
    assertTrue(hashes.sent.forall(_._1 == null))
    assertEquals(check.sent.size, hashes.sent.size)
    assertTrue("HASHES mode must not send full APIs", hashes.apis.values.forall(_.isEmpty))
    def byName(cb: TestCallback5) = cb.sent.map((_, thin, h) => s"${thin.name}/${thin.definitionType}" -> toHashes(h)).toMap
    assertEquals(byName(check), byName(hashes))
  }

  @Test def treeModeSendsFullApis(): Unit = {
    val tree = new TestCallback5(ApiMode.TREE, optimizedSealed = false)
    new ScalaCompilerForUnitTesting().compileSrcs(List(List("class A { def f = 1 }")), callback = Some(tree))
    assertTrue(tree.sent.isEmpty)
    assertEquals(List("A"), tree.apis.values.flatten.map(_.name).toList)
  }

  @Test def libraryMembersAsStubs(): Unit = {
    def inherited(materialise: Boolean) = {
      val cb = new TestCallback5(ApiMode.CHECK, optimizedSealed = false, materialise)
      new ScalaCompilerForUnitTesting().compileSrcs(List(List("class L extends java.util.AbstractList[String] { def get(i: Int) = \"\"; def size = 0 }")), callback = Some(cb))
      cb.sent.collectFirst { case (full, _, _) if full.nn.name == "L" => full.nn.structure.inherited.toList }.get
    }
    val full = inherited(materialise = true)
    val stubs = inherited(materialise = false)
    assertEquals(full.map(_.name).toSet, stubs.map(_.name).toSet)
    assertTrue(full.exists(isTyped))
    // Annotated members stay in full, for test discovery: here `@transient modCount`.
    assertEquals(List("modCount"), stubs.filter(isTyped).map(_.name))
  }
}

object BridgeHashingSpecification {
  case class Hashes(api: Int, extra: Int, names: Map[(String, UseScope), Int], hasMacro: Boolean)

  def toHashes(h: ClassHashes): Hashes =
    Hashes(h.apiHash, h.extraHash, h.nameHashes.map(nh => (nh.name, nh.scope) -> nh.hash).toMap, h.hasMacro)

  /** What `ApiHashCheck.treeHashes` computes in Zinc. */
  def treeHashes(c: ClassLike, optimizedSealed: Boolean): Hashes = {
    val apiHash = HashAPI(c)
    val extraHash =
      if (c.definitionType != DefinitionType.Trait) apiHash
      else HashAPI(_.hashAPI(c), includePrivateDefsInTrait = true)
    toHashes(new ClassHashes(apiHash, extraHash, new NameHashing(optimizedSealed).nameHashes(c), APIUtil.hasMacro(c)))
  }

  def isTyped(d: Definition): Boolean = d match {
    case d: Def => !d.returnType.isInstanceOf[EmptyType] || d.valueParameters.nonEmpty
    case _ => true
  }

  /** The thin class keeps the header, and a stub of each declared and abstract inherited member. */
  def checkThin(key: String, full: ClassLike, thin: ClassLike): Unit = {
    assertEquals(key, full.name, thin.name)
    assertEquals(key, full.definitionType, thin.definitionType)
    assertEquals(key, full.topLevel, thin.topLevel)
    assertEquals(key, full.structure.parents.toList, thin.structure.parents.toList)
    assertEquals(key, full.structure.declared.map(_.name).toList.sorted, thin.structure.declared.map(_.name).toList.sorted)
    assertEquals(key,
      full.structure.inherited.filter(_.modifiers.isAbstract).map(_.name).toList.sorted,
      thin.structure.inherited.map(_.name).toList.sorted)
    val keptInFull = thin.structure.declared.filter(isTyped).map(_.name).toSet
    assertTrue(s"$key: only main methods are kept in full, not $keptInFull", keptInFull.subsetOf(Set("main")))
  }
}
