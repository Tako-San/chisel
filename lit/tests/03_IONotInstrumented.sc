// SPDX-License-Identifier: Apache-2.0
// RUN: scala-cli compile %s --scala 2.13 --extra-jars %chisel-plugin-jar --scala-option -Xplugin:%chisel-plugin-jar --scala-option "-P:chiselplugin:addDebugIntrinsics" --scala-option "-Xprint:componentDebugIntrinsics" 2>&1 | FileCheck %s
// CHECK: [DEBUG-PLUGIN-LOADED] ComponentDebugIntrinsics running

import chisel3._

class IOTest extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })
  val internal = Wire(UInt(8.W))
  io.out := io.in
}
