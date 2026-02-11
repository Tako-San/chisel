// SPDX-License-Identifier: Apache-2.0
// RUN: scala-cli compile %s --scala %SCALAVERSION --extra-jars=%RUNCLASSPATH --scala-option="-Xplugin:%SCALAPLUGINJARS" --scala-option="-P:chiselplugin:addDebugIntrinsics" 2>&1 | FileCheck %s
// CHECK: Phase running on:

import chisel3._

class MyBundle extends Bundle {
  val field_a = UInt(8.W)
  val field_b = SInt(16.W)
}

class BundleTest extends Module {
  val bundle = Wire(new MyBundle)
}
