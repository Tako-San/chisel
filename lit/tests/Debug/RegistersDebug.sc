// RUN: scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION --scala-option="-Xplugin:%SCALAPLUGINJARS" %s | FileCheck %s
// SPDX-License-Identifier: Apache-2.0

import chisel3._

// CHECK-LABEL: circuit RegistersDebug :
// CHECK: public module RegistersDebug :
// CHECK-NEXT: input clock : Clock
// CHECK-NEXT: input reset : UInt<1>
// CHECK-NEXT: output io : { flip in : UInt<8>, out : UInt<8>}
// CHECK-DAG: intrinsic(circt_dbg_variable<name = "my_reg", type = "UInt<8>">
// CHECK-DAG: intrinsic(circt_dbg_variable<name = "my_wire", type = "UInt<8>">
class RegistersDebug extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })

  val my_reg = RegInit(0.U(8.W))
  val my_wire = Wire(UInt(8.W))

  my_wire := io.in
  my_reg := my_wire
  io.out := my_reg
}

println(circt.stage.ChiselStage.emitCHIRRTL(new RegistersDebug, Array("--emit-debug-info")))
