// SPDX-License-Identifier: Apache-2.0

// RUN: CHISEL_DEBUG=true scala-cli --server=false \
// RUN:   --java-home=%JAVAHOME \
// RUN:   --extra-jars=%RUNCLASSPATH \
// RUN:   --scala-version=%SCALAVERSION \
// RUN:   --scala-option="-Xplugin:%SCALAPLUGINJARS" \
// RUN:   --scala-option="-P:chiselplugin:addDebugIntrinsics" \
// RUN:   --scala-option "-Xprint:componentDebugIntrinsics" \
// RUN:   %s | FileCheck %s

import chisel3._
import circt.stage.ChiselStage

// ============================================================================
// Test 1: RegInit instrumentation
// ============================================================================

// CHECK-LABEL: module RegInitTest :
class RegInitTest extends Module {
  val state = RegInit(0.U(8.W))

  // CHECK: wire [[PROBE_STATE:_.*]] : Probe<UInt<8>>
  // CHECK: define([[PROBE_STATE]], probe(state))
  // CHECK: intrinsic(circt_debug_typeinfo<
  // CHECK-SAME: target = "state"
  // CHECK-SAME: binding = "Reg"
  // CHECK-SAME: typeName = "UInt"
  // CHECK-SAME: parameters = "width=8"
  // CHECK-SAME: >, read([[PROBE_STATE]]))
}

println("// ===== RegInitTest =====")
println(ChiselStage.emitCHIRRTL(new RegInitTest))

// ============================================================================
// Test 2: Wire instrumentation
// ============================================================================

// CHECK-LABEL: module WireTest :
class WireTest extends Module {
  val temp = Wire(UInt(16.W))
  temp := 0.U

  // CHECK: wire [[PROBE_TEMP:_.*]] : Probe<UInt<16>>
  // CHECK: define([[PROBE_TEMP]], probe(temp))
  // CHECK: intrinsic(circt_debug_typeinfo<
  // CHECK-SAME: target = "temp"
  // CHECK-SAME: binding = "Wire"
  // CHECK-SAME: typeName = "UInt"
  // CHECK-SAME: >, read([[PROBE_TEMP]]))
}

println("// ===== WireTest =====")
println(ChiselStage.emitCHIRRTL(new WireTest))

// ============================================================================
// Test 3: IO Bundle instrumentation
// ============================================================================

// CHECK-LABEL: module IOTest :
class IOTest extends Module {
  val io = IO(new Bundle {
    val in = Input(UInt(32.W))
    val out = Output(UInt(32.W))
  })
  io.out := io.in

  // CHECK: wire [[PROBE_IO:_.*]] : Probe<
  // CHECK: define([[PROBE_IO]], probe(io))
  // CHECK: intrinsic(circt_debug_typeinfo<
  // CHECK-SAME: target = "io"
  // CHECK-SAME: binding = "IO"
  // CHECK-SAME: >, read([[PROBE_IO]]))
}

println("// ===== IOTest =====")
println(ChiselStage.emitCHIRRTL(new IOTest))

// ============================================================================
// Test 4: Multiple signals instrumentation
// ============================================================================

// CHECK-LABEL: module MultiSignalTest :
class MultiSignalTest extends Module {
  val io = IO(new Bundle { val in = Input(UInt(8.W)) })
  val reg1 = RegInit(0.U(8.W))
  val reg2 = RegInit(0.U(8.W))
  val wire1 = Wire(UInt(8.W))

  wire1 := io.in
  reg1 := wire1
  reg2 := reg1

  // CHECK-DAG: target = "io"
  // CHECK-DAG: target = "reg1"
  // CHECK-DAG: target = "reg2"
  // CHECK-DAG: target = "wire1"

  // CHECK-COUNT-4: intrinsic(circt_debug_typeinfo
  // CHECK-COUNT-4: read(
}

println("// ===== MultiSignalTest =====")
println(ChiselStage.emitCHIRRTL(new MultiSignalTest))

// ============================================================================
// Test 5: ValDef in when-block (closure)
// ============================================================================

// CHECK-LABEL: module ClosureTest :
class ClosureTest extends Module {
  val cond = IO(Input(Bool()))
  val out = IO(Output(UInt(8.W)))

  out := 0.U

  when(cond) {
    val r = RegInit(0.U(8.W))
    r := 1.U
    out := r
  }

  // CHECK: wire [[PROBE_R:_.*]] : Probe<UInt<8>>
  // CHECK: define([[PROBE_R]], probe({{r|_.*}}))
  // CHECK: intrinsic(circt_debug_typeinfo<
  // CHECK-SAME: target = "r"
  // CHECK-SAME: binding = "Reg"
  // CHECK-SAME: >, read([[PROBE_R]]))
}

println("// ===== ClosureTest =====")
println(ChiselStage.emitCHIRRTL(new ClosureTest))

// ============================================================================
// Test 6: ChiselEnum instrumentation
// ============================================================================

object CpuState extends ChiselEnum {
  val sIDLE, sFETCH, sDECODE = Value
}

// CHECK-LABEL: module EnumTest :
class EnumTest extends Module {
  val state = RegInit(CpuState.sIDLE)
  state := CpuState.sFETCH

  // CHECK: wire [[PROBE_STATE:_.*]] : Probe<
  // CHECK: define([[PROBE_STATE]], probe(state))
  // CHECK: intrinsic(circt_debug_typeinfo<
  // CHECK-SAME: target = "state"
  // CHECK-SAME: typeName = "CpuState"
  // CHECK-SAME: enumDef = {
  // CHECK-SAME: >, read([[PROBE_STATE]]))
}

println("// ===== EnumTest =====")
println(ChiselStage.emitCHIRRTL(new EnumTest))
