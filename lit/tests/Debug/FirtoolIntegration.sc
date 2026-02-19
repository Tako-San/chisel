// RUN: scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION --scala-option="-Xplugin:%SCALAPLUGINJARS" %s | FileCheck %s
// SPDX-License-Identifier: Apache-2.0

import chisel3._
import circt.stage._

// CHECK-LABEL: circuit FirtoolIntegration :
class FirtoolIntegration extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })

  // Auto-instrumented signals - should get circt_dbg_variable intrinsics
  val wire = Wire(UInt(8.W))
  val reg = RegInit(0.U(8.W))

  wire := io.in
  reg := wire
  io.out := reg
}

// Verify circt_dbg_variable intrinsics are generated for auto-instrumented signals
// CHECK-LABEL: module FirtoolIntegration :
// CHECK-DAG: intrinsic(circt_dbg_variable<name = "wire", path = "FirtoolIntegration.wire", type = "UInt<8>"
// CHECK-DAG: intrinsic(circt_dbg_variable<name = "reg", path = "FirtoolIntegration.reg", type = "UInt<8>"
// CHECK-DAG: intrinsic(circt_dbg_variable<name = "io", path = "FirtoolIntegration.io", type = "FirtoolIntegration_Anon"

println(circt.stage.ChiselStage.emitCHIRRTL(new FirtoolIntegration))
