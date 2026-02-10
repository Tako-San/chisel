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

  // CHECK-DAG: wire [[PROBE_STATE:_.*]] : Probe<UInt<8>>
  // CHECK-DAG: define [[PROBE_STATE]] = probe(state)
  // CHECK-DAG: intrinsic(circt_debug_typeinfo<{{.*}}target = "state"{{.*}}typeName = "UInt"{{.*}}>, read([[PROBE_STATE]]))
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

  // CHECK-DAG: wire [[PROBE_TEMP:_.*]] : Probe<UInt<16>>
  // CHECK-DAG: define [[PROBE_TEMP]] = probe(temp)
  // CHECK-DAG: intrinsic(circt_debug_typeinfo<{{.*}}target = "temp"{{.*}}binding = "Wire"{{.*}}>, read([[PROBE_TEMP]]))
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

  // IO excluded by plugin filter (!isIO check)
  // CHECK-NOT: target = "io"
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

  // CHECK-DAG: target = "reg1"
  // CHECK-DAG: target = "reg2"
  // CHECK-DAG: target = "wire1"

  // CHECK-COUNT-3: intrinsic(circt_debug_typeinfo
  // CHECK-COUNT-3: read(
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

  // CHECK-DAG: wire [[PROBE_R:_.*]] : Probe<UInt<8>>
  // CHECK-DAG: define [[PROBE_R]] = probe({{r|_.*}})
  // CHECK-DAG: intrinsic(circt_debug_typeinfo<{{.*}}target = "r"{{.*}}binding = "Reg"{{.*}}>, read([[PROBE_R]]))
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

  // CHECK-DAG: wire [[PROBE_STATE:_.*]] : Probe<
  // CHECK-DAG: define [[PROBE_STATE]] = probe(state)
  // CHECK-DAG: intrinsic(circt_debug_typeinfo<{{.*}}target = "state"{{.*}}typeName = "CpuState"{{.*}}enumDef{{.*}}>, read([[PROBE_STATE]]))
}

println("// ===== EnumTest =====")
println(ChiselStage.emitCHIRRTL(new EnumTest))
