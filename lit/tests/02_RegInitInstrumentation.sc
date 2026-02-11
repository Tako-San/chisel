// SPDX-License-Identifier: Apache-2.0
// RUN: scala-cli compile %s --scala %SCALAVERSION --extra-jars=%RUNCLASSPATH --scala-option="-Xplugin:%SCALAPLUGINJARS" --scala-option="-P:chiselplugin:addDebugIntrinsics" 2>&1 | FileCheck %s
// CHECK: Phase running on:

import chisel3._

class RegTest extends Module {
  val r = RegInit(0.U(8.W))
}
