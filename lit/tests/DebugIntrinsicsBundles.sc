// SPDX-License-Identifier: Apache-2.0

// RUN: CHISEL_DEBUG=true scala-cli --server=false \
// RUN:   --java-home=%JAVAHOME \
// RUN:   --extra-jars=%RUNCLASSPATH \
// RUN:   --scala-version=%SCALAVERSION \
// RUN:   --scala-option="-Xplugin:%SCALAPLUGINJARS" \
// RUN:   --scala-option="-P:chiselplugin:addDebugIntrinsics" \
// RUN:   --scala-option "-Xprint:componentDebugIntrinsics" \
// RUN:   --java-opt="-Dchisel.debug=true" \
// RUN:   %s | FileCheck %s

// Force recompile v2

import chisel3._
import circt.stage.ChiselStage

// ============================================================================
// Test: Bundle with constructor parameters
// ============================================================================

class MyBundle(val dataWidth: Int) extends Bundle {
  val valid = Bool()
  val data = UInt(dataWidth.W)
}

// CHECK-LABEL: module BundleTest :
class BundleTest extends Module {
  val io = IO(new MyBundle(16))

  // IO excluded by plugin (!isIO filter)
  // CHECK-NOT: target = "io"

  val reg = RegInit(0.U.asTypeOf(new MyBundle(16)))
  reg := io

  // CHECK-DAG: wire [[PROBE_REG:_.*]] : Probe<
  // CHECK-DAG: define [[PROBE_REG]] = probe(reg)
  // CHECK-DAG: intrinsic(circt_debug_typeinfo<{{.*}}target = "reg"{{.*}}typeName = "MyBundle"{{.*}}>, read([[PROBE_REG]]))
}

println(ChiselStage.emitCHIRRTL(new BundleTest))

// ============================================================================
// Test: Vec instrumentation
// ============================================================================

// CHECK-LABEL: module VecTest :
class VecTest extends Module {
  val regs = RegInit(VecInit(Seq.fill(4)(0.U(8.W))))

  // CHECK-DAG: wire [[PROBE_REGS:_.*]] : Probe<
  // CHECK-DAG: define [[PROBE_REGS]] = probe(regs)
  // CHECK-DAG: intrinsic(circt_debug_typeinfo<{{.*}}target = "regs"{{.*}}typeName = "Vec"{{.*}}>, read([[PROBE_REGS]]))
}

println(ChiselStage.emitCHIRRTL(new VecTest))

// ============================================================================
// Test: Nested Bundle
// ============================================================================

class InnerBundle extends Bundle {
  val x = UInt(8.W)
  val y = UInt(8.W)
}

class OuterBundle extends Bundle {
  val inner = new InnerBundle
  val flag = Bool()
}

// CHECK-LABEL: module NestedBundleTest :
class NestedBundleTest extends Module {
  val io = IO(new OuterBundle)

  // IO excluded
  // CHECK-NOT: target = "io"
}

println(ChiselStage.emitCHIRRTL(new NestedBundleTest))

// ============================================================================
// Test: WireInit with complex type
// ============================================================================

// CHECK-LABEL: module WireInitTest :
class WireInitTest extends Module {
  val data = WireInit(0.U.asTypeOf(new MyBundle(32)))

  // CHECK-DAG: wire [[PROBE_DATA:_.*]] : Probe<
  // CHECK-DAG: define [[PROBE_DATA]] = probe(data)
  // CHECK-DAG: intrinsic(circt_debug_typeinfo<{{.*}}target = "data"{{.*}}binding = "Wire"{{.*}}typeName = "MyBundle"{{.*}}>, read([[PROBE_DATA]]))
}

println(ChiselStage.emitCHIRRTL(new WireInitTest))
