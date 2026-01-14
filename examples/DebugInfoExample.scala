// SPDX-License-Identifier: Apache-2.0

package examples

import chisel3._
import chisel3.util.circt.DebugInfo
import circt.stage.ChiselStage

/**
  * Example Bundle with constructor parameters
  */
class Counter Bundle(val width: Int) extends Bundle {
  val value = UInt(width.W)
  val enable = Bool()
}

/**
  * Example ChiselEnum
  */
object State extends ChiselEnum {
  val sIDLE, sRUN, sDONE = Value
}

/**
  * Example module demonstrating DebugInfo API
  */
class DebugInfoExample extends Module {
  val io = IO(new Bundle {
    val counter = Output(new CounterBundle(8))
    val state = Output(State())
  })
  
  // Create internal state
  val cnt = RegInit(0.U.asTypeOf(new CounterBundle(8)))
  val fsm = RegInit(State.sIDLE)
  
  // Simple counter logic
  when(cnt.enable) {
    cnt.value := cnt.value + 1.U
  }
  
  // Simple FSM
  switch(fsm) {
    is(State.sIDLE) {
      fsm := State.sRUN
      cnt.enable := true.B
    }
    is(State.sRUN) {
      when(cnt.value === 255.U) {
        fsm := State.sDONE
        cnt.enable := false.B
      }
    }
    is(State.sDONE) {
      fsm := State.sIDLE
    }
  }
  
  // Connect outputs
  io.counter := cnt
  io.state := fsm
  
  // Annotate signals with DebugInfo (if enabled)
  if (DebugInfo.isEnabled()) {
    println("[DebugInfo] Annotating signals...")
    DebugInfo.annotate(io.counter, "io.counter")
    DebugInfo.annotateRecursive(io.counter, "io.counter")
    DebugInfo.annotate(io.state, "io.state")
    DebugInfo.annotate(cnt, "counter_reg")
    DebugInfo.annotate(fsm, "fsm_state")
  }
}

/**
  * Main object for running the example
  */
object DebugInfoExample extends App {
  println("="*60)
  println("DebugInfo Example - Chisel to FIRRTL")
  println("="*60)
  
  // Set debug mode
  sys.props("chisel.debug") = "true"
  
  // Generate FIRRTL
  val firrtl = ChiselStage.emitCHIRRTL(
    new DebugInfoExample,
    Array("--enable-debug-intrinsics")
  )
  
  println("\n[Output] Generated FIRRTL:")
  println("-" * 60)
  
  // Check for debug intrinsics
  val hasIntrinsics = firrtl.contains("circt_debug_typeinfo")
  println(s"\n[Check] Contains circt_debug_typeinfo: $hasIntrinsics")
  
  if (hasIntrinsics) {
    println("\n[Success] DebugInfo intrinsics generated!")
    
    // Extract intrinsic lines
    val intrinsicLines = firrtl.split("\n").filter(_.contains("circt_debug_typeinfo"))
    println(s"\n[Stats] Found ${intrinsicLines.length} intrinsics:")
    intrinsicLines.take(3).foreach(line => println(s"  - ${line.trim}"))
    if (intrinsicLines.length > 3) {
      println(s"  ... and ${intrinsicLines.length - 3} more")
    }
  } else {
    println("\n[Warning] No intrinsics found. Debug mode may not be active.")
  }
  
  // Save to file
  val outputPath = "generated/DebugInfoExample.fir"
  println(s"\n[Output] Saving to: $outputPath")
  
  val file = new java.io.File("generated")
  file.mkdirs()
  val writer = new java.io.PrintWriter(outputPath)
  try {
    writer.write(firrtl)
    println(s"[Success] FIRRTL written to $outputPath")
  } finally {
    writer.close()
  }
  
  println("\n" + "="*60)
  println("Example complete!")
  println("="*60)
}
