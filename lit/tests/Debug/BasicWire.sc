// RUN: scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION --scala-option="-Xplugin:%SCALAPLUGINJARS" %s | FileCheck %s
// SPDX-License-Identifier: Apache-2.0

import chisel3._
import chisel3.experimental.SourceInfo
import circt.stage._

// CHECK-LABEL: circuit BasicWire :
class BasicWire extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })

  // CHECK: intrinsic(circt_dbg_variable
  // CHECK-SAME: path = "BasicWire.io.in"
  chisel3.debug.debug(io.in, "in")

  io.out := io.in + 1.U
}

println(circt.stage.ChiselStage.emitCHIRRTL(new BasicWire))
