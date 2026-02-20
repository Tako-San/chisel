// RUN: scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION --scala-option="-Xplugin:%SCALAPLUGINJARS" %s | FileCheck %s
// SPDX-License-Identifier: Apache-2.0

import chisel3._

// CHECK-LABEL: circuit NoDebugEmit :
// CHECK: public module NoDebugEmit :
// CHECK-NEXT: input clock : Clock
// CHECK-NEXT: input reset : UInt<1>
// CHECK-NEXT: output io : { flip in : UInt<8>, out : UInt<8>}
// CHECK-NOT: intrinsic(circt_dbg_variable<
class NoDebugEmit extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })

  val debug_signal = Wire(UInt(8.W))
  debug_signal := io.in
  io.out := debug_signal
}

println(circt.stage.ChiselStage.emitCHIRRTL(new NoDebugEmit))
