// SPDX-License-Identifier: Apache-2.0
// RUN: scala-cli compile %s --scala 2.13 --extra-jars %chisel-plugin-jar --scala-option -Xplugin:%chisel-plugin-jar 2>&1 | FileCheck %s
// CHECK: [CHISEL-DEBUG-INTRINSICS] Phase running

import chisel3._

class RegTest extends Module {
  val r = RegInit(0.U(8.W))
}
