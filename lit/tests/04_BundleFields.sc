// SPDX-License-Identifier: Apache-2.0
// RUN: scala-cli compile %s --scala 2.13 --extra-jars %chisel-plugin-jar --scala-option -Xplugin:%chisel-plugin-jar 2>&1 | FileCheck %s
// CHECK: [CHISEL-DEBUG-INTRINSICS] Phase running

import chisel3._

class MyBundle extends Bundle {
  val field_a = UInt(8.W)
  val field_b = SInt(16.W)
}

class BundleTest extends Module {
  val bundle = Wire(new MyBundle)
}
