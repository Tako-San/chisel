// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DebugMetaTestUtilsSpec extends AnyFlatSpec with Matchers {

  "extractPayloads" should "match info when it is the FIRST param (after `<`)" in {
    val chirrtl = """intrinsic(circt_debug_moduleinfo<info = "{\"kind\":\"module\"}">)"""
    val result = DebugMetaTestUtils.extractPayloads(chirrtl, "circt_debug_moduleinfo")
    result should have size 1
    result.head("kind").str shouldBe "module"
  }

  it should "match info when it is NOT the first param (after `,`)" in {
    val chirrtl = """intrinsic(circt_debug_meminfo<memName = "MEM", info = "{\"kind\":\"mem\"}">)"""
    val result = DebugMetaTestUtils.extractPayloads(chirrtl, "circt_debug_meminfo")
    result should have size 1
    result.head("kind").str shouldBe "mem"
  }

  it should "unescape backslash-quote correctly" in {
    // JSON payload with escaped quote: {\"msg\":\"say \\\"hello\\\"\"}
    val escaped = """{\"msg\":\"say \\\"hello\\\"\"}"""
    val chirrtl = s"""intrinsic(circt_debug_moduleinfo<info = "$escaped">)"""
    val result = DebugMetaTestUtils.extractPayloads(chirrtl, "circt_debug_moduleinfo")
    result should have size 1
    result.head("msg").str shouldBe """say "hello""""
  }
}
