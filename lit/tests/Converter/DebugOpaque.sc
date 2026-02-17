// RUN: scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION --scala-option="-Xplugin:%SCALAPLUGINJARS" %s | FileCheck %s
// SPDX-License-Identifier: Apache-2.0

import chisel3._

// CHECK-LABEL: circuit DebugOpaqueTest :
class DebugOpaqueTest extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })
  // CHECK: intrinsic(chisel.debug.port_info<name = "io.out", direction = "OUTPUT", type = "UInt<8>"{{.*}}>, io.out)
  // CHECK: intrinsic(chisel.debug.port_info<name = "io.in", direction = "INPUT", type = "UInt<8>"{{.*}}>, io.in)

  // Use a simple wire to test basic functionality
  val opaqueWire = Wire(UInt(8.W))
  // CHECK: intrinsic(chisel.debug.source_info<field_name = "opaqueWire", type = "UInt<8>"{{.*}}>, opaqueWire)

  opaqueWire := io.in
  io.out := opaqueWire + 1.U
}

println(circt.stage.ChiselStage.emitCHIRRTL(new DebugOpaqueTest, Array("--capture-debug", "true")))
