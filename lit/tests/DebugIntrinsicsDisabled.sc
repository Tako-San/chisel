// SPDX-License-Identifier: Apache-2.0

// Test that plugin generates NO intrinsics when debug mode disabled
// RUN: scala-cli --server=false \
// RUN:   --java-home=%JAVAHOME \
// RUN:   --extra-jars=%RUNCLASSPATH \
// RUN:   --scala-version=%SCALAVERSION \
// RUN:   --scala-option="-Xplugin:%SCALAPLUGINJARS" \
// RUN:   --scala-option="-P:chiselplugin:addDebugIntrinsics" \
// RUN:   %s | FileCheck %s

import chisel3._
import circt.stage.ChiselStage

// CHECK-LABEL: module DisabledTest :
class DisabledTest extends Module {
  val state = RegInit(0.U(8.W))
  val temp = Wire(UInt(8.W))
  val io = IO(new Bundle { val out = Output(UInt(8.W)) })
  
  temp := state
  io.out := temp
}

// CHECK-NOT: circt_debug_typeinfo
// CHECK-NOT: Probe<
// CHECK-NOT: define({{.*}}, probe(

println(ChiselStage.emitCHIRRTL(new DisabledTest))
