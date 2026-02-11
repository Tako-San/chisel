// SPDX-License-Identifier: Apache-2.0
// RUN: scala-cli compile %s --scala %SCALAVERSION --extra-jars=%RUNCLASSPATH --scala-option="-Xplugin:%SCALAPLUGINJARS" --scala-option="-P:chiselplugin:addDebugIntrinsics" 2>&1 | FileCheck %s
// CHECK: warning: Debug Intrinsics Phase Running

import chisel3._

class DummyModule extends Module {
  val dummy = Wire(UInt(8.W))
}
