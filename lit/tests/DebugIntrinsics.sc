// REQUIRES: scala-2
// RUN: scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION --scala-option="-Xplugin:%SCALAPLUGINJARS" --scala-option="-P:chiselplugin:emitDebugTypeInfo" %s | FileCheck %s
// SPDX-License-Identifier: Apache-2.0

import chisel3._

class DebugIntrinsicsModule extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })
  val reg = RegNext(io.in)
  io.out := reg
}

println(circt.stage.ChiselStage.emitCHIRRTL(new DebugIntrinsicsModule, args = Array("--emit-debug-type-info")))

// CHECK-LABEL: circuit DebugIntrinsicsModule :
// CHECK: circt_debug_moduleinfo
// CHECK: circt_debug_typetag
// CHECK-NOT: "sourceLoc":"unknown"
