// RUN: scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION --scala-option="-Xplugin:%SCALAPLUGINJARS" %s | FileCheck %s
// SPDX-License-Identifier: Apache-2.0

import chisel3._

// CHECK-LABEL: circuit BasicDebug :
// CHECK: public module BasicDebug :
// CHECK-NEXT: input clock : Clock
// CHECK-NEXT: input reset : UInt<1>
// CHECK-NEXT: output io : { flip a : UInt<8>, flip b : UInt<8>, c : UInt<8>}
// CHECK-DAG: intrinsic(circt_dbg_variable<name = "debug_signal", type = "UInt<8>">, debug_signal)
class BasicDebug extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(8.W))
    val b = Input(UInt(8.W))
    val c = Output(UInt(8.W))
  })

  val debug_signal = Wire(UInt(8.W))
  debug_signal := io.a + io.b
  io.c := debug_signal
}

println(circt.stage.ChiselStage.emitCHIRRTL(new BasicDebug, Array("--emit-debug-info")))
