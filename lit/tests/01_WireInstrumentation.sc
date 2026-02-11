// SPDX-License-Identifier: Apache-2.0
// RUN: scala-cli run %s --scala 2.13 --extra-jars %chisel-plugin-jar --scala-option -Xplugin:%chisel-plugin-jar --scala-option "-P:chiselplugin:addDebugIntrinsics" 2>&1 | FileCheck %s
// CHECK: intrinsic(circt_debug_typeinfo{{.*}}target = "w"{{.*}}binding = "WireBinding"

import chisel3._
import circt.stage.ChiselStage

class WireTest extends Module {
  val w = Wire(UInt(8.W))
}

val chirrtl = ChiselStage.emitCHIRRTL(new WireTest)
println(chirrtl)
