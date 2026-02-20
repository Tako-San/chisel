// RUN: scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION --scala-option="-Xplugin:%SCALAPLUGINJARS" %s | FileCheck %s
// SPDX-License-Identifier: Apache-2.0

import chisel3._

// CHECK-LABEL: circuit VectorsDebug :
// CHECK: public module VectorsDebug :
// CHECK-NEXT: input clock : Clock
// CHECK-NEXT: input reset : UInt<1>
// CHECK-NEXT: output io : { flip in : UInt<8>[4], out : UInt<8>}
// CHECK-DAG: intrinsic(circt_dbg_variable<name = "vec_wire", type = "Vec4_UInt<8>">, vec_wire)
class VectorsDebug extends Module {
  val io = IO(new Bundle {
    val in = Input(Vec(4, UInt(8.W)))
    val out = Output(UInt(8.W))
  })

  val vec_wire = Wire(Vec(4, UInt(8.W)))

  vec_wire := io.in
  io.out := vec_wire.reduce(_ + _)
}

println(circt.stage.ChiselStage.emitCHIRRTL(new VectorsDebug, Array("--emit-debug-info")))
