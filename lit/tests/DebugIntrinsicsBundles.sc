// SPDX-License-Identifier: Apache-2.0

// RUN: CHISEL_DEBUG=true scala-cli --server=false \
// RUN:   --java-home=%JAVAHOME \
// RUN:   --extra-jars=%RUNCLASSPATH \
// RUN:   --scala-version=%SCALAVERSION \
// RUN:   --scala-option="-Xplugin:%SCALAPLUGINJARS" \
// RUN:   --scala-option="-P:chiselplugin:addDebugIntrinsics" \
// RUN:   %s | FileCheck %s

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

  // CHECK: wire [[PROBE_IO:_.*]] : Probe<
  // CHECK: define([[PROBE_IO]], probe(io))
  // CHECK: intrinsic(circt_debug_typeinfo<
  // CHECK-SAME: target = "io"
  // CHECK-SAME: typeName = "MyBundle"
  // CHECK-SAME: parameters = {{.*}}dataWidth=16{{.*}}
  // CHECK-SAME: >, read([[PROBE_IO]]))

  val reg = RegInit(0.U.asTypeOf(new MyBundle(16)))
  reg := io

  // CHECK: wire [[PROBE_REG:_.*]] : Probe<
  // CHECK: define([[PROBE_REG]], probe(reg))
  // CHECK: intrinsic(circt_debug_typeinfo<
  // CHECK-SAME: target = "reg"
  // CHECK-SAME: typeName = "MyBundle"
  // CHECK-SAME: >, read([[PROBE_REG]]))
}

println(ChiselStage.emitCHIRRTL(new BundleTest))

// ============================================================================
// Test: Vec instrumentation
// ============================================================================

// CHECK-LABEL: module VecTest :
class VecTest extends Module {
  val regs = RegInit(VecInit(Seq.fill(4)(0.U(8.W))))

  // CHECK: wire [[PROBE_REGS:_.*]] : Probe<
  // CHECK: define([[PROBE_REGS]], probe(regs))
  // CHECK: intrinsic(circt_debug_typeinfo<
  // CHECK-SAME: target = "regs"
  // CHECK-SAME: typeName = "Vec"
  // CHECK-SAME: parameters = {{.*}}length=4{{.*}}
  // CHECK-SAME: >, read([[PROBE_REGS]]))
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

  // CHECK: wire [[PROBE_IO:_.*]] : Probe<
  // CHECK: define([[PROBE_IO]], probe(io))
  // CHECK: intrinsic(circt_debug_typeinfo<
  // CHECK-SAME: target = "io"
  // CHECK-SAME: typeName = "OuterBundle"
  // CHECK-SAME: >, read([[PROBE_IO]]))
}

println(ChiselStage.emitCHIRRTL(new NestedBundleTest))

// ============================================================================
// Test: WireInit with complex type
// ============================================================================

// CHECK-LABEL: module WireInitTest :
class WireInitTest extends Module {
  val data = WireInit(0.U.asTypeOf(new MyBundle(32)))

  // CHECK: wire [[PROBE_DATA:_.*]] : Probe<
  // CHECK: define([[PROBE_DATA]], probe(data))
  // CHECK: intrinsic(circt_debug_typeinfo<
  // CHECK-SAME: target = "data"
  // CHECK-SAME: binding = "Wire"
  // CHECK-SAME: typeName = "MyBundle"
  // CHECK-SAME: >, read([[PROBE_DATA]]))
}

println(ChiselStage.emitCHIRRTL(new WireInitTest))
