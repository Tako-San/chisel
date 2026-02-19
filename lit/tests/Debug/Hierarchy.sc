// RUN: scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION --scala-option="-Xplugin:%SCALAPLUGINJARS" %s | FileCheck %s
// SPDX-License-Identifier: Apache-2.0

import chisel3._
import circt.stage._

// CHECK-LABEL: circuit HierarchyTest :
class InnerModule extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })

  // CHECK: intrinsic(circt_dbg_variable<name = "inner_data", type = "UInt<8>">
  val innerData = Wire(UInt(8.W)).suggestName("innerData")
  chisel3.debug.debug(innerData, "inner_data")

  innerData := io.in + 1.U
  io.out := innerData
}

class HierarchyTest extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })

  // CHECK: intrinsic(circt_dbg_variable<name = "top_port", type = "UInt<8>">
  chisel3.debug.debug(io.in, "top_port")

  val inner = Module(new InnerModule)
  inner.io.in := io.in
  io.out := inner.io.out + 1.U
}

println(circt.stage.ChiselStage.emitCHIRRTL(new HierarchyTest))
