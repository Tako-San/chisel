// RUN: scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION --scala-option="-Xplugin:%SCALAPLUGINJARS" %s | FileCheck %s
// SPDX-License-Identifier: Apache-2.0

import chisel3._
import chisel3.ChiselEnum

object State extends ChiselEnum {
  val Idle, Busy, Done = Value
}

// CHECK-LABEL: circuit EnumDebug :
// CHECK: public module EnumDebug :
// CHECK-DAG: intrinsic(circt_dbg_variable<name = "state",
// CHECK-SAME: chiselType = "State(Idle=0, Busy=1, Done=2)">, state)
class EnumDebug extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val out = Output(State())
  })

  val state = RegInit(State.Idle)
  when(io.start) {
    state := State.Busy
  }
  io.out := state
}

println(circt.stage.ChiselStage.emitCHIRRTL(new EnumDebug, Array("--emit-debug-info")))
