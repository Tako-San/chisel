// RUN: scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION --scala-option="-Xplugin:%SCALAPLUGINJARS" %s | FileCheck %s
// SPDX-License-Identifier: Apache-2.0

import chisel3._

class MyBundle extends Bundle {
  val a = UInt(8.W)
  val b = Bool()
}

// CHECK-LABEL: circuit BundlesDebug :
// CHECK: public module BundlesDebug :
// CHECK-NEXT: input clock : Clock
// CHECK-NEXT: input reset : UInt<1>
// CHECK-NEXT: output io : { flip in : { a : UInt<8>, b : UInt<1>}, out : { a : UInt<8>, b : UInt<1>}}
// CHECK-DAG: intrinsic(circt_dbg_variable<name = "bundle_wire", type = "MyBundle">, bundle_wire)
class BundlesDebug extends Module {
  val io = IO(new Bundle {
    val in = Input(new MyBundle)
    val out = Output(new MyBundle)
  })

  val bundle_wire = Wire(new MyBundle)
  bundle_wire.a := io.in.a + 1.U
  bundle_wire.b := ~io.in.b
  io.out := bundle_wire
}

println(circt.stage.ChiselStage.emitCHIRRTL(new BundlesDebug, Array("--emit-debug-info")))
