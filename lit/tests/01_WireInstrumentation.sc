// SPDX-License-Identifier: Apache-2.0
// RUN: scala-cli compile %s --scala 2.13 --extra-jars %chisel-plugin-jar --scala-option -Xplugin:%chisel-plugin-jar 2>&1 | FileCheck %s
// CHECK: [CHISEL-DEBUG-INTRINSICS] Phase running

import chisel3._

class WireTest extends Module {
  val w = Wire(UInt(8.W))
}
