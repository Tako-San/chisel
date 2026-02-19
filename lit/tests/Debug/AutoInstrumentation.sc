// RUN: scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION --scala-option="-Xplugin:%SCALAPLUGINJARS" %s | FileCheck %s
// SPDX-License-Identifier: Apache-2.0

import chisel3._
import circt.stage._

// CHECK-LABEL: circuit AutoInstrument :
class AutoInstrument extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })

  // Wire without explicit debug() call - should get instrumented automatically
  val wire = Wire(UInt(8.W))

  // Register without explicit debug() call - should get instrumented automatically
  val reg = RegInit(0.U(8.W))

  wire := io.in
  reg := wire

  io.out := reg
}

// AutoInstrumentDebugInfo adds circt_dbg_variable intrinsics for all signals:
// CHECK-DAG: intrinsic(circt_dbg_variable
// CHECK-DAG: intrinsic(circt_dbg_variable
// CHECK-DAG: intrinsic(circt_dbg_variable
// CHECK-DAG: intrinsic(circt_dbg_variable

println(circt.stage.ChiselStage.emitCHIRRTL(new AutoInstrument))