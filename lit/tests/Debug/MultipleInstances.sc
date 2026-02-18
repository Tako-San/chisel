// RUN: scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION --scala-option="-Xplugin:%SCALAPLUGINJARS" %s | FileCheck %s
// SPDX-License-Identifier: Apache-2.0

import chisel3._
import chisel3.experimental.SourceInfo
import circt.stage._

// CHECK-LABEL: circuit MultipleInstancesTest :
class CounterModule extends Module {
  val io = IO(new Bundle {
    val en = Input(Bool())
    val count = Output(UInt(8.W))
  })

  val countReg = RegInit(0.U(8.W))

  io.count := countReg
  when(io.en) {
    countReg := countReg + 1.U
  }
}

class MultipleInstancesTest extends Module {
  val io = IO(new Bundle {
    val en0 = Input(Bool())
    val en1 = Input(Bool())
    val count0 = Output(UInt(8.W))
    val count1 = Output(UInt(8.W))
  })

  val counter0 = Module(new CounterModule)
  val counter1 = Module(new CounterModule)

  // CHECK: intrinsic(circt_dbg_variable
  // CHECK-SAME: path = "MultipleInstancesTest.counter0.io.count"
  chisel3.debug.debug(counter0.io.count, "counter0_count")

  // CHECK: intrinsic(circt_dbg_variable
  // CHECK-SAME: path = "MultipleInstancesTest.counter1.io.count"
  chisel3.debug.debug(counter1.io.count, "counter1_count")

  counter0.io.en := io.en0
  counter1.io.en := io.en1

  io.count0 := counter0.io.count
  io.count1 := counter1.io.count
}

println(circt.stage.ChiselStage.emitCHIRRTL(new MultipleInstancesTest))
