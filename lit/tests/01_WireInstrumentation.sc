// SPDX-License-Identifier: Apache-2.0
// RUN: scala-cli compile %s --scala 2.13 --extra-jars %chisel-plugin-jar --scala-option -Xplugin:%chisel-plugin-jar --scala-option "-P:chiselplugin:addDebugIntrinsics" --scala-option "-Xprint:componentDebugIntrinsics" 2>&1 | FileCheck %s
// CHECK: [DEBUG-PLUGIN-LOADED] ComponentDebugIntrinsics running

import chisel3._

class WireTest extends Module {
  val w = Wire(UInt(8.W))
}
