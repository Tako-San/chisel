// RUN: scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION --scala-option="-Xplugin:%SCALAPLUGINJARS" %s | FileCheck %s
// SPDX-License-Identifier: Apache-2.0

import chisel3._
import circt.stage._

// CHECK-LABEL: circuit BundleParamsTest :
class SimpleBundle extends Bundle {
  val data = Input(UInt(8.W))
  val valid = Input(Bool())
}

class SimpleModule extends Module {
  val io = IO(new Bundle {
    val in = new SimpleBundle
    val out = Output(UInt(8.W))
  })

  // CHECK: intrinsic(circt_dbg_variable<name = "data_bundle", type = "{{.*}}">
  chisel3.debug.debug(io.in, "data_bundle")

  io.out := 0.U
}

class BundleParamsTest extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })

  val simpleMod = Module(new SimpleModule)
  io.out := simpleMod.io.out
}

println(circt.stage.ChiselStage.emitCHIRRTL(new BundleParamsTest))
