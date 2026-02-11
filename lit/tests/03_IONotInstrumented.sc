// SPDX-License-Identifier: Apache-2.0
// RUN: scala-cli compile %s --scala %SCALAVERSION --extra-jars=%RUNCLASSPATH --scala-option="-Xplugin:%SCALAPLUGINJARS" --scala-option="-P:chiselplugin:addDebugIntrinsics" 2>&1 | FileCheck %s
// CHECK: Phase running on:

import chisel3._

class IOTest extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(8.W))
    val out = Output(UInt(8.W))
  })
  val internal = Wire(UInt(8.W))
  io.out := io.in
}
