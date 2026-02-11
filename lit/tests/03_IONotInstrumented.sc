// SPDX-License-Identifier: Apache-2.0
// RUN: scala-cli run %s --scala 2.13 --extra-jars %chisel-plugin-jar --scala-option -Xplugin:%chisel-plugin-jar --scala-option "-P:chiselplugin:addDebugIntrinsics" 2>&1 | FileCheck %s
// CHECK-NOT: target = "io"
// CHECK: intrinsic(circt_debug_typeinfo{{.*}}target = "internal"

import chisel3._
import circt.stage.ChiselStage

class IOTest extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })
  val internal = Wire(UInt(8.W))
  io.out := io.in
}

val chirrtl = ChiselStage.emitCHIRRTL(new IOTest)
println(chirrtl)
