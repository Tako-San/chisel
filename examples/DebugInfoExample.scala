// SPDX-License-Identifier: Apache-2.0

package examples

import chisel3._
import chisel3.util.circt.DebugInfo

/**
  * Example Bundle with constructor parameters
  */
class CounterBundle(val width: Int) extends Bundle {
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
  
  // Annotate signals with DebugInfo (automatic enable check)
  DebugInfo.annotateRecursive(io.counter, "io.counter")
  DebugInfo.annotate(io.state, "io.state")
  DebugInfo.annotate(cnt, "counter_reg")
  DebugInfo.annotate(fsm, "fsm_state")
}

/**
  * Main object for running the example
  */
object DebugInfoExample extends App {
  // Use DebugInfo helper to handle debug mode enablement and compilation
  val firrtl = DebugInfo.emitCHIRRTL(
    new DebugInfoExample,
    Array("--enable-debug-intrinsics") // Optional: maintained if downstream tools need it
  )
  
  // Validate debug intrinsics presence
  if (firrtl.contains("circt_debug_typeinfo")) {
    val count = "circt_debug_typeinfo".r.findAllMatchIn(firrtl).length
    println(s"✓ Generated $count debug intrinsics")
  } else {
    println("⚠ No debug intrinsics found")
  }
  
  // Save FIRRTL for inspection
  val outputPath = "generated/DebugInfoExample.fir"
  new java.io.File("generated").mkdirs()
  val writer = new java.io.PrintWriter(outputPath)
  try {
    writer.write(firrtl)
    println(s"✓ FIRRTL saved to $outputPath")
  } finally {
    writer.close()
  }
}
