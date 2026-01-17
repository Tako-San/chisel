// SPDX-License-Identifier: Apache-2.0

package chiselTests

import scala.util.matching.Regex

/** Centralized test utilities for debug intrinsics validation.
  * 
  * Provides core validation logic for Probe API usage in FIRRTL output.
  * Prevents duplication of validation logic across test suites.
  */
object DebugTestHelpers {
  
  /** Execute a block with debug mode temporarily enabled.
    * 
    * Ensures system property is cleaned up even if block fails.
    * Reduces boilerplate in tests.
    */
  def withDebugMode[T](block: => T): T = {
    chisel3.debuginternal.DebugIntrinsic.withDebugMode(block)
  }

  /** Centralized regex patterns for FIRRTL validation.
    * 
    * Prevents duplication and typos across test files.
    */
  object Patterns {
    /** Probe wire declaration: wire _WIRE : Probe<UInt<8>> */
    val ProbeDeclaration: Regex = """wire\s+(\w+)\s*:\s*Probe<.+>""".r
    
    /** Probe definition: define _WIRE = probe(signal) */
    val ProbeDefine: Regex = """define\s+(\w+)\s*=\s*probe\((.+?)\)""".r
    
    /** Probe read in intrinsic: intrinsic(..., read(_WIRE)) */
    val ProbeRead: Regex = """intrinsic\(circt_debug_typeinfo.*read\((\w+)\)""".r
    
    /** Weak binding anti-pattern: intrinsic with operand but no read() */
    val WeakBinding: Regex = """intrinsic\(circt_debug_typeinfo[^)]*\)\([^r]""".r
    
    /** Intrinsics with read() operands - matches entire intrinsic statement containing read() */
    val IntrinsicsWithRead: Regex = """intrinsic\(circt_debug_typeinfo[^@]*read\(""".r
    
    /** Generic Probe type presence */
    val ProbeType: Regex = """Probe<""".r
    
    /** Intrinsic presence */
    val DebugIntrinsic: Regex = """circt_debug_typeinfo""".r
    
    /** Target parameter extraction */
    val Target: Regex = """target\s*=\s*"([^"]+)"""".r
  }
  
  /** Assert that FIRRTL uses Probe API correctly for debug intrinsics.
    * 
    * Guards against regression to weak binding (direct signal reference).
    * 
    * Validates:
    * 1. Intrinsics are present
    * 2. Probe types declared (wire _probe : Probe<...>)
    * 3. Probe definitions exist (define _probe = probe(signal))
    * 4. Intrinsics read probes (read(_probe) operands)
    * 5. NO weak binding patterns (direct signal reference)
    * 
    * @param firrtl FIRRTL output to validate
    * @param minIntrinsics Minimum expected intrinsic count (default: 1)
    * @throws AssertionError if Probe API not used correctly
    */
  def assertProbeAPIUsed(firrtl: String, minIntrinsics: Int = 1): Unit = {
    val intrinsicCount = Patterns.DebugIntrinsic.findAllIn(firrtl).length
    assert(
      intrinsicCount >= minIntrinsics,
      s"Expected at least $minIntrinsics intrinsics, found $intrinsicCount"
    )
    
    assert(
      Patterns.ProbeType.findFirstIn(firrtl).isDefined,
      "Missing Probe type declarations - weak binding detected!"
    )
    
    val defineCount = Patterns.ProbeDefine.findAllIn(firrtl).length
    assert(
      defineCount >= minIntrinsics,
      s"Only $defineCount define statements for $intrinsicCount intrinsics (expected at least $minIntrinsics)"
    )
    
    val readCount = "read\\(".r.findAllIn(firrtl).length
    assert(
      readCount >= minIntrinsics,
      s"Only $readCount read() calls for $intrinsicCount intrinsics (expected at least $minIntrinsics)"
    )
    
    // Validate intrinsics use read() - more flexible matching
    val intrinsicsWithRead = Patterns.IntrinsicsWithRead.findAllIn(firrtl).length
    
    // Debug output when assertion fails
    if (intrinsicsWithRead < minIntrinsics) {
      val allIntrinsics = """intrinsic\(circt_debug_typeinfo[^@]*\) @""".r.findAllIn(firrtl).toList
      System.err.println(s"\n=== DEBUG: Found $intrinsicsWithRead intrinsics with read() out of $intrinsicCount total ===")
      System.err.println(s"Expected at least: $minIntrinsics")
      System.err.println(s"\nAll intrinsic statements:")
      allIntrinsics.take(5).foreach(i => System.err.println(s"  $i"))
    }
    
    assert(
      intrinsicsWithRead >= minIntrinsics,
      s"Only $intrinsicsWithRead out of $intrinsicCount intrinsics use read() - weak binding detected!"
    )
  }
  
  /** Assert that no weak binding patterns exist in FIRRTL.
    * 
    * Weak binding = intrinsic uses direct signal reference instead of probe:
    * BAD:  intrinsic(circt_debug_typeinfo<...>)(io.signal)
    * GOOD: intrinsic(circt_debug_typeinfo<...>, read(_probe))
    * 
    * @param firrtl FIRRTL output to validate
    * @throws AssertionError if weak binding detected
    */
  def assertWeakBindingAbsent(firrtl: String): Unit = {
    val weakBindingMatch = Patterns.WeakBinding.findFirstIn(firrtl)
    assert(
      weakBindingMatch.isEmpty,
      s"""CRITICAL REGRESSION: Weak binding detected!
         |Found pattern: ${weakBindingMatch.getOrElse("<error>")}
         |Intrinsic uses direct signal reference instead of Probe API.
         |This breaks metadata->RTL binding after FIRRTL optimizations.
         |""".stripMargin
    )
  }
  
  /** Assert exact number of debug intrinsics in FIRRTL.
    * 
    * Use when test expects precise instrumentation count.
    * 
    * @param firrtl FIRRTL output
    * @param expected Expected intrinsic count
    * @throws AssertionError if count doesn't match
    */
  def assertIntrinsicCount(firrtl: String, expected: Int): Unit = {
    val actual = Patterns.DebugIntrinsic.findAllIn(firrtl).length
    assert(
      actual == expected,
      s"Expected exactly $expected intrinsics, found $actual"
    )
  }
  
  /** Extract all debug intrinsic targets from FIRRTL.
    * 
    * @param firrtl FIRRTL output
    * @return Set of target names (e.g., Set("io", "io.ctrl", "io.ctrl.valid"))
    */
  def extractTargets(firrtl: String): Set[String] = {
    Patterns.Target.findAllMatchIn(firrtl).map(_.group(1)).toSet
  }
  
  /** Extract all probe wire names from FIRRTL.
    * 
    * @param firrtl FIRRTL output
    * @return Set of probe wire names (e.g., Set("_WIRE", "_WIRE_1"))
    */
  def extractProbeNames(firrtl: String): Set[String] = {
    Patterns.ProbeDeclaration.findAllMatchIn(firrtl).map(_.group(1)).toSet
  }
}
