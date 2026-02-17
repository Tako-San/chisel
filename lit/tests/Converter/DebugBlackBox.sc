// RUN: scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION --scala-option="-Xplugin:%SCALAPLUGINJARS" %s | FileCheck %s
// SPDX-License-Identifier: Apache-2.0

import chisel3._

// Create a BlackBox module
class MyBlackBox extends BlackBox {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })
}

// CHECK-LABEL: circuit DebugBlackBoxTest :
class DebugBlackBoxTest extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })
  // CHECK: intrinsic(chisel.debug.port_info<name = "io.out", direction = "OUTPUT", type = "UInt<8>"{{.*}}>, io.out)
  // CHECK: intrinsic(chisel.debug.port_info<name = "io.in", direction = "INPUT", type = "UInt<8>"{{.*}}>, io.in)

  // Instantiate BlackBox
  val bb = Module(new MyBlackBox)
  bb.io.in := io.in

  // BlackBox modules should NOT have port_info intrinsics
  // CHECK-NOT: intrinsic(chisel.debug.port_info<name = "bb.io"

  io.out := bb.io.out
}

println(circt.stage.ChiselStage.emitCHIRRTL(new DebugBlackBoxTest, Array("--capture-debug", "true")))
