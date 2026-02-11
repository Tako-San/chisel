// SPDX-License-Identifier: Apache-2.0
// RUN: scala-cli compile %s --scala 2.13 --extra-jars %chisel-plugin-jar --scala-option -Xplugin:%chisel-plugin-jar --scala-option -P:chiselplugin:addDebugIntrinsics 2>&1 | FileCheck %s
// CHECK: [DEBUG-PLUGIN-LOADED] ComponentDebugIntrinsics running

import chisel3._

class DummyModule extends Module {
  val dummy = Wire(UInt(8.W))
}

// This is a smoke test - just verify the plugin loads
println("Plugin smoke test passed")
