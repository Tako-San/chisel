// SPDX-License-Identifier: Apache-2.0

package chisel3

import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AutoInstrumentSpec extends AnyFlatSpec with Matchers {

  "AutoInstrumentDebugInfo" should "emit circt_dbg_variable for wires" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new Module {
      val io = IO(new Bundle {
        val in  = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val w = Wire(UInt(8.W))
      w := io.in
      io.out := w
    })
    chirrtl should include("circt_dbg_variable")
    chirrtl should include("\"w\"")
  }

  it should "emit circt_dbg_variable for registers" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new Module {
      val io = IO(new Bundle {
        val in  = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val r = RegInit(0.U(8.W))
      r := io.in
      io.out := r
    })
    chirrtl should include("circt_dbg_variable")
    chirrtl should include("\"r\"")
  }

  it should "emit circt_dbg_variable for ports" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new Module {
      val io = IO(new Bundle {
        val in  = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val w = Wire(UInt(8.W))
      w := io.in
      io.out := w
    })
    chirrtl should include("circt_dbg_variable")
    chirrtl should include("\"io\"")
  }

  it should "emit circt_dbg_variable for memories" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new Module {
      val io = IO(new Bundle {
        val addr = Input(UInt(4.W))
        val dout = Output(UInt(8.W))
      })
      val mem = Mem(16, UInt(8.W))
      io.dout := mem(io.addr)
    })
    chirrtl should include("circt_dbg_variable")
    chirrtl should include("mem")
  }

  it should "emit circt_dbg_variable with correct path parameter" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new Module {
      val io = IO(new Bundle {
        val in  = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val w = Wire(UInt(8.W))
      w := io.in
      io.out := w
    })
    chirrtl should include("path =")
    chirrtl should include("name =")
    chirrtl should include("type =")
  }

  it should "emit circt_dbg_variable for SyncReadMem" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new Module {
      val io = IO(new Bundle {
        val addr = Input(UInt(4.W))
        val dout = Output(UInt(8.W))
      })
      val mem = SyncReadMem(16, UInt(8.W))
      io.dout := mem.read(io.addr)
    })
    chirrtl should include("circt_dbg_variable")
    // SyncReadMem generates names like "chisel3.SyncReadMem@hash"
    // so check for the intrinsic and the memory in the smem declaration
    chirrtl should include("smem")
    chirrtl should include("circt_dbg_variable")
  }

  it should "emit circt_dbg_variable for RegInit" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new Module {
      val io = IO(new Bundle {
        val in  = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val r = RegInit(42.U(8.W))
      r := io.in
      io.out := r
    })
    chirrtl should include("circt_dbg_variable")
    chirrtl should include("\"r\"")
  }

  it should "emit circt_dbg_variable for multiple wires" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new Module {
      val io = IO(new Bundle {
        val in  = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val w1 = Wire(UInt(8.W))
      val w2 = Wire(UInt(8.W))
      val w3 = Wire(UInt(8.W))
      w1 := io.in
      w2 := w1
      w3 := w2
      io.out := w3
    })
    chirrtl should include("circt_dbg_variable")
    chirrtl should include("\"w1\"")
    chirrtl should include("\"w2\"")
    chirrtl should include("\"w3\"")
  }

  it should "emit circt_dbg_variable for multiple registers" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new Module {
      val io = IO(new Bundle {
        val in  = Input(UInt(8.W))
        val out = Output(UInt(8.W))
      })
      val r1 = RegInit(0.U(8.W))
      val r2 = RegInit(0.U(8.W))
      r1 := io.in
      r2 := r1
      io.out := r2
    })
    chirrtl should include("circt_dbg_variable")
    chirrtl should include("\"r1\"")
    chirrtl should include("\"r2\"")
  }

  it should "emit circt_dbg_variable for Bundle ports" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new Module {
      val io = IO(new Bundle {
        val in  = Input(new Bundle {
          val a = UInt(4.W)
          val b = UInt(4.W)
        })
        val out = Output(UInt(8.W))
      })
      val w = Wire(UInt(8.W))
      w := util.Cat(io.in.a, io.in.b)
      io.out := w
    })
    chirrtl should include("circt_dbg_variable")
    chirrtl should include("\"io\"")
  }

  it should "emit circt_dbg_variable for Vec ports" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new Module {
      val io = IO(new Bundle {
        val in  = Input(Vec(4, UInt(8.W)))
        val out = Output(UInt(8.W))
      })
      val w = Wire(UInt(8.W))
      w := io.in(0)
      io.out := w
    })
    chirrtl should include("circt_dbg_variable")
    chirrtl should include("\"io\"")
  }

  it should "emit circt_dbg_variable for SInt types" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new Module {
      val io = IO(new Bundle {
        val in  = Input(SInt(8.W))
        val out = Output(SInt(8.W))
      })
      val w = Wire(SInt(8.W))
      w := io.in
      io.out := w
    })
    chirrtl should include("circt_dbg_variable")
    chirrtl should include("\"w\"")
    chirrtl should include("SInt<8>")
  }

  it should "emit circt_dbg_variable for Bool types" in {
    val chirrtl = ChiselStage.emitCHIRRTL(new Module {
      val io = IO(new Bundle {
        val in  = Input(Bool())
        val out = Output(Bool())
      })
      val w = Wire(Bool())
      w := io.in
      io.out := w
    })
    chirrtl should include("circt_dbg_variable")
    chirrtl should include("\"w\"")
    chirrtl should include("UInt<1>")
  }
}