// SPDX-License-Identifier: Apache-2.0
// RUN: scala-cli compile %s --scala 2.13 --extra-jars %chisel-plugin-jar --scala-option -Xplugin:%chisel-plugin-jar --scala-option "-P:chiselplugin:addDebugIntrinsics" --scala-option "-Xprint:componentDebugIntrinsics" 2>&1 | FileCheck %s
// CHECK: [DEBUG-PLUGIN-LOADED] ComponentDebugIntrinsics running

import chisel3._

class MyBundle extends Bundle {
  val field_a = UInt(8.W)
  val field_b = SInt(16.W)
}

class BundleTest extends Module {
  val bundle = Wire(new MyBundle)
}
