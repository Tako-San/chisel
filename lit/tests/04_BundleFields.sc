// SPDX-License-Identifier: Apache-2.0
// RUN: scala-cli run %s --scala 2.13 --extra-jars %chisel-plugin-jar --scala-option -Xplugin:%chisel-plugin-jar --scala-option "-P:chiselplugin:addDebugIntrinsics" 2>&1 | FileCheck %s
// CHECK: intrinsic(circt_debug_typeinfo{{.*}}target = "bundle"{{.*}}binding = "WireBinding"

import chisel3._
import circt.stage.ChiselStage

class MyBundle extends Bundle {
  val field_a = UInt(8.W)
  val field_b = SInt(16.W)
}

class BundleTest extends Module {
  val bundle = Wire(new MyBundle)
}

val chirrtl = ChiselStage.emitCHIRRTL(new BundleTest)
println(chirrtl)
