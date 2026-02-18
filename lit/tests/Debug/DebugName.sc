// RUN: scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION --scala-option="-Xplugin:%SCALAPLUGINJARS" %s | FileCheck %s
// SPDX-License-Identifier: Apache-2.0

import chisel3._
import circt.stage._

// CHECK-LABEL: circuit DebugNameTest :
class DebugNameTest extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })

  // CHECK: intrinsic(circt_dbg_variable
  // CHECK-SAME: name = "my_custom_name"
  // CHECK-SAME: path = "DebugNameTest.io.in"
  chisel3.debug.debug(io.in, "my_custom_name")

  io.out := io.in
}

println(circt.stage.ChiselStage.emitCHIRRTL(new DebugNameTest))
