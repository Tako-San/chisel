// SPDX-License-Identifier: Apache-2.0
package chisel3.debug

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import chisel3._

// All test classes are defined at object scope.
// Scala reflection cannot locate classes defined inside method bodies
// (they get anonymous names like $anon$1 and staticClass lookup fails).
object DebugCtorParamExtractorSpec {
  class Foo(val width: Int)
  class Bar(val n: Int, val label: String, val flag: Boolean)
  class Baz(x: Int)
  class Mixed(val a: Int, b: String)
  case class MyCaseClass(n: Int, name: String)
  class Inner(val x: Int)
  class Outer(val inner: Inner)
  class A(val v: Int)
  class B(val a: A)
  class C(val b: B)
  class Empty
  class WithProtected(protected val n: Int)
  class WithPrivate(private val n: Int)
  class WithData(val gen: UInt)
}

class DebugCtorParamExtractorSpec extends AnyFunSpec with Matchers {
  import DebugCtorParamExtractorSpec._

  // Helpers matching the new ClassParam.value: Option[ujson.Value] schema:
  // - `s(x)` wraps a string value, matching the default `.toString` path in
  //   CtorParamExtractor.
  // - `b(x)` wraps a native boolean, matching the type-preserving branch.
  private def s(v: String): Option[ujson.Value] = Some(ujson.Str(v))
  private def b(v: Boolean): Option[ujson.Value] = Some(ujson.Bool(v))

  describe("val parameters") {
    it("extracts name, typeName and value for a single val param") {
      CtorParamExtractor.getCtorParams(new Foo(8)) shouldEqual Seq(
        ClassParam("width", "Int", s("8"))
      )
    }

    it("extracts multiple val params of different types") {
      CtorParamExtractor.getCtorParams(new Bar(4, "hello", true)) shouldEqual Seq(
        ClassParam("n", "Int", s("4")),
        ClassParam("label", "String", s("hello")),
        ClassParam("flag", "Boolean", b(true))
      )
    }
  }

  describe("non-val parameters") {
    it("returns None for value when param has no val") {
      CtorParamExtractor.getCtorParams(new Baz(42)) shouldEqual Seq(
        ClassParam("x", "Int", None)
      )
    }

    it("mixes val and non-val in same constructor") {
      CtorParamExtractor.getCtorParams(new Mixed(1, "ignored")) shouldEqual Seq(
        ClassParam("a", "Int", s("1")),
        ClassParam("b", "String", None)
      )
    }
  }

  describe("case class") {
    it("extracts all fields with values") {
      CtorParamExtractor.getCtorParams(MyCaseClass(7, "world")) shouldEqual Seq(
        ClassParam("n", "Int", s("7")),
        ClassParam("name", "String", s("world"))
      )
    }
  }

  describe("nested class parameters") {
    it("recursively serializes one level of nesting") {
      CtorParamExtractor.getCtorParams(new Outer(new Inner(3))) shouldEqual Seq(
        ClassParam("inner", "Inner", s("Inner(x: 3)"))
      )
    }

    it("handles two levels of nesting") {
      val params = CtorParamExtractor.getCtorParams(new C(new B(new A(5))))
      params.head.value shouldEqual s("B(a: A(v: 5))")
    }
  }

  describe("empty constructor") {
    it("returns empty Seq") {
      CtorParamExtractor.getCtorParams(new Empty) shouldBe empty
    }
  }

  describe("protected and private val parameters") {
    it("extracts protected val") {
      CtorParamExtractor.getCtorParams(new WithProtected(9)) shouldEqual Seq(
        ClassParam("n", "Int", s("9"))
      )
    }

    it("extracts private val") {
      CtorParamExtractor.getCtorParams(new WithPrivate(9)) shouldEqual Seq(
        ClassParam("n", "Int", s("9"))
      )
    }
  }

  describe("Data parameters") {
    // Value is simplified via dataToTypeName rather than full .toString,
    // so "UInt<8>" is returned instead of e.g. "IO[UInt<8>]".
    it("does not crash and returns non-empty value for unbound Data param") {
      val params = CtorParamExtractor.getCtorParams(new WithData(UInt(8.W)))
      (params should have).length(1)
      params.head.name shouldEqual "gen"
      params.head.typeName shouldEqual "UInt"
      params.head.value should not be empty
    }
  }
}
