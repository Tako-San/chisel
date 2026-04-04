// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
class FirtoolSmokeSpec extends AnyFlatSpec with Matchers {

  class TestModule extends RawModule {
    val in = IO(Input(UInt(8.W)))
    val out = IO(Output(UInt(8.W)))
    out := in
  }

  // Smoke test: ensure firtool runs with `--allow-unrecognized-intrinsic`; full round‑trip is out of scope.
  it should "not crash firtool when debug intrinsics are emitted" in {
    noException should be thrownBy {
      ChiselStage.emitSystemVerilog(
        new TestModule,
        args = Array("--emit-debug-type-info"),
        firtoolOpts = Array("--allow-unrecognized-intrinsic")
      )
    }
  }
}
