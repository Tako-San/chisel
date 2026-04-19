// SPDX-License-Identifier: Apache-2.0

package chisel3.debug

import chisel3._
import chisel3.experimental.{Analog, IntrinsicModule}
import chisel3.experimental.hierarchy.{instantiable, Definition, Instance}
import chisel3.util.{MixedVec, SRAM}
import circt.stage.ChiselStage
import org.scalatest.AppendedClues.convertToClueful
import org.scalatest.matchers.should.Matchers

// ─── Shared test enums (package-level for clean simple names) ────────────────

object DebugTestEnum extends ChiselEnum { val EA, EB, EC = Value }
object DebugTestEnum2 extends ChiselEnum { val ED, EE, EF = Value }

// ─── TestUtils ────────────────────────────────────────────────────────────────

object DebugTestUtils extends Matchers {

  /** Find a line matching the pattern and return it along with the next one */
  def getSubOccurrence(chirrtl: String, pattern: String): List[String] = {
    val lines = chirrtl.split("\n").toList
    val re = (".*" + pattern + ".*").r
    val idx = lines.indexWhere(re.matches)
    if (idx >= 0) lines.slice(idx, idx + 2) else List()
  }

  def countOccurrences(chirrtl: String, pattern: String): Int =
    pattern.r.findAllMatchIn(chirrtl).length

  def getMissingOccurrences(
    chirrtl:       String,
    pattern:       String,
    expectedLines: Seq[String]
  ): String = {
    val lines = chirrtl.split("\n").toSeq
    val re = (".*" + pattern + ".*").r
    val idxs = lines.indices.filter(i => re.findFirstIn(lines(i)).isDefined)
    val withNext = idxs.flatMap { i =>
      if (i < lines.length - 1) List(lines(i), lines(i + 1))
      else List(lines(i))
    }.distinct
    val expRe = expectedLines.map(_.r)
    withNext
      .filterNot(line => expRe.exists(_.findFirstIn(line).isDefined))
      .mkString("\n")
  }

  // ─── Helpers for intrinsic format ────────────────────────────────────────

  def varPattern(typeName: String, name: String, enumTypeName: Option[String] = None): String = {
    val enumPart = enumTypeName.fold("")(e => s"""[^)]*enumTypeName\\s*=\\s*"${Regex.quote(e)}"""")
    s"""intrinsic\\(circt_debug_var<[^)]*typeName\\s*=\\s*"${Regex.quote(typeName)}"[^)]*name\\s*=\\s*"${Regex.quote(
        name
      )}"$enumPart"""
  }

  def subfieldPattern(typeName: String, name: String, parent: String, enumTypeName: Option[String] = None): String = {
    val enumPart = enumTypeName.fold("")(e => s"""[^)]*enumTypeName\\s*=\\s*"${Regex.quote(e)}"""")
    s"""intrinsic\\(circt_debug_subfield<[^)]*typeName\\s*=\\s*"${Regex.quote(typeName)}"[^)]*name\\s*=\\s*"${Regex
        .quote(name)}"[^)]*parent\\s*=\\s*"${Regex.quote(parent)}"$enumPart"""
  }

  def enumDefPattern(typeName: String): String =
    s"""intrinsic\\(circt_debug_enumdef<[^)]*typeName\\s*=\\s*"${Regex.quote(typeName)}""""

  def moduleInfoPattern(typeName: String): String =
    s"""intrinsic\\(circt_debug_moduleinfo<[^)]*typeName\\s*=\\s*"${Regex.quote(typeName)}""""

  def checkIntrinsics(
    expected: Seq[(String, Int)],
    chirrtl:  String
  ): Unit = {
    val names = expected.map { case (p, _) => p }
    expected.foreach { case (pattern, count) =>
      withClue(
        s"Pattern '$pattern':\n${getMissingOccurrences(chirrtl, pattern, names)}\n"
      ) {
        countOccurrences(chirrtl, pattern) should be(count)
      }
    }
  }

  private object Regex {
    def quote(s: String): String = java.util.regex.Pattern.quote(s)
  }
}

// ─── Test circuits ───────────────────────────────────────────────────────────

object DebugTestCircuits {

  // ── BindingChoice (IO / Wire / Reg) ───────────────────────────────────────

  trait BindingChoice { def apply[T <: Data](data: T): T }

  case object PortBinding extends BindingChoice {
    override def apply[T <: Data](data: T): T = IO(data)
    override def toString = "IO"
  }
  case object WireBinding extends BindingChoice {
    override def apply[T <: Data](data: T): T = Wire(data)
    override def toString = "Wire"
  }
  case object RegBinding extends BindingChoice {
    override def apply[T <: Data](data: T): T = Reg(data)
    override def toString = "Reg"
  }

  abstract class DebugTestModule(bindingChoice: BindingChoice) extends RawModule {
    def body: Unit
    bindingChoice match {
      case RegBinding =>
        val clock = IO(Input(Clock()))
        val reset = IO(Input(Reset()))
        withClockAndReset(clock, reset) { body }
      case _ => body
    }
  }

  // ── ModuleCircuits ────────────────────────────────────────────────────────

  object ModuleCircuits {

    class TopCircuit extends RawModule

    class MyModule extends RawModule

    class TopCircuitSubModule extends RawModule {
      val mod = Module(new MyModule)
    }

    class TopCircuitMultiModule extends RawModule {
      val mod1 = Module(new MyModule)
      val mod2 = Module(new MyModule)
      val mods = Seq.fill(2)(Module(new MyModule))
    }

    class TopCircuitBlackBox extends RawModule {
      class MyBlackBox extends ExtModule(Map("PARAM1" -> "TRUE", "PARAM2" -> "DEFAULT")) {
        val io = IO(new Bundle {})
      }
      val myBlackBox1:  MyBlackBox = Module(new MyBlackBox)
      val myBlackBox2:  MyBlackBox = Module(new MyBlackBox)
      val myBlackBoxes: Seq[MyBlackBox] = Seq.fill(2)(Module(new MyBlackBox))
    }

    class ExampleIntrinsicModule(str: String) extends IntrinsicModule("OtherIntrinsic", Map("STRING" -> str)) {
      val b = IO(Input(Bool()))
      val bout = IO(Output(Bool()))
    }

    class TopCircuitIntrinsic extends RawModule {
      val myIntrinsicModule1 = Module(new ExampleIntrinsicModule("Hello"))
      val myIntrinsicModule2 = Module(new ExampleIntrinsicModule("World"))
      val myIntrinsicModules = Seq.fill(2)(Module(new ExampleIntrinsicModule("Hello")))
    }

    @instantiable
    class CSRDescription extends chisel3.properties.Class

    class CSRModule(csrDescDef: Definition[CSRDescription]) extends RawModule {
      val csrDescription = Instance(csrDescDef)
    }

    class TopCircuitClasses extends RawModule {
      val csrDescDef = Definition(new CSRDescription)
      val csrModule1 = Module(new CSRModule(csrDescDef))
      val csrModule2 = Module(new CSRModule(csrDescDef))
      val csrModules = Seq.fill(2)(Module(new CSRModule(csrDescDef)))
    }
  }

  // ── DataTypesCircuits ─────────────────────────────────────────────────────

  object DataTypesCircuits {

    class TopCircuitClockReset extends RawModule {
      val clock:      Clock = IO(Input(Clock()))
      val syncReset:  Bool = IO(Input(Bool()))
      val reset:      Reset = IO(Input(Reset()))
      val asyncReset: AsyncReset = IO(Input(AsyncReset()))
    }

    class TopCircuitImplicitClockReset extends Module

    class TopCircuitGroundTypes(b: BindingChoice) extends DebugTestModule(b) {
      override def body: Unit = {
        val uint: UInt = b(UInt(8.W))
        val sint: SInt = b(SInt(8.W))
        val bool: Bool = b(Bool())
        if (b != RegBinding) {
          val analog: Analog = b(Analog(1.W))
        }
        val bits: UInt = b(Bits(8.W))
      }
    }

    class MyEmptyBundle extends Bundle

    class MyBundle extends Bundle {
      val a: UInt = UInt(8.W)
      val b: SInt = SInt(8.W)
      val c: Bool = Bool()
    }

    class TopCircuitBundles(b: BindingChoice) extends DebugTestModule(b) {
      override def body: Unit = {
        val a:   Bundle = b(new Bundle {})
        val bnd: MyEmptyBundle = b(new MyEmptyBundle)
        val c:   MyBundle = b(new MyBundle)
      }
    }

    class MyNestedBundle extends Bundle {
      val a: Bool = Bool()
      val b: MyBundle = new MyBundle
      val c: MyBundle = Flipped(new MyBundle)
    }

    class TopCircuitBundlesNested(b: BindingChoice) extends DebugTestModule(b) {
      override def body: Unit = {
        val a: MyNestedBundle = b(new MyNestedBundle)
      }
    }

    class TopCircuitVecs(b: BindingChoice) extends DebugTestModule(b) {
      override def body: Unit = {
        val a:  Vec[SInt] = b(Vec(5, SInt(23.W)))
        val bv: Vec[Vec[SInt]] = b(Vec(5, Vec(3, SInt(23.W))))
        val c = b(Vec(5, new Bundle { val x: UInt = UInt(8.W) }))
        val d = b(MixedVec(UInt(3.W), SInt(10.W)))
      }
    }

    class TopCircuitBundleWithVec(b: BindingChoice) extends DebugTestModule(b) {
      override def body: Unit = {
        val a = b(new Bundle { val vec = Vec(5, UInt(8.W)) })
      }
    }

    class TopCircuitTypeInSubmodule(b: BindingChoice) extends RawModule {
      val mod = Module(new TopCircuitGroundTypes(b))
    }

    class TopCircuitWhenElse extends RawModule {
      val inSeq = IO(Input(Vec(8, UInt(8.W))))
      val out = IO(Output(UInt(8.W)))
      val sel = IO(Input(UInt(math.sqrt(8).ceil.toInt.W)))
      val tmp = sel + 1.U
      when(sel % 2.U === 0.U) {
        val outTmp = inSeq(sel)
        val evenSel = outTmp + 1.U
        out := evenSel
      }.elsewhen(sel === 1.U) {
        val outTmp = inSeq(sel)
        val selIsOne = outTmp + 1.U
        out := selIsOne
      }.otherwise {
        val outTmp = inSeq(sel)
        val oddSel = outTmp + 1.U
        out := oddSel
      }
    }

    class TopCircuitEnumSimple extends RawModule {
      val e = IO(Input(DebugTestEnum()))
      val e2 = IO(Input(DebugTestEnum2()))
      val bnd = IO(Input(new Bundle {
        val en = DebugTestEnum()
        val en2 = DebugTestEnum2()
        val x = UInt(8.W)
      }))
      val v = IO(Input(Vec(3, DebugTestEnum())))
    }

    class TopCircuitChiselEnum extends RawModule {
      object MyEnum extends ChiselEnum { val A, B, C = Value }
      object MyEnum2 extends ChiselEnum { val D, E, F = Value }
      object ScopeEnum {
        object MyEnum2 extends ChiselEnum { val D, E = Value }
      }

      val inputEnum = IO(Input(MyEnum()))
      val io = IO(Input(new Bundle {
        val a = MyEnum()
        val b = MyEnum2()
        val c = Bool()
      }))
      val i = IO(Input(new Bundle {
        val e = MyEnum()
        val b = new Bundle {
          val inner_e = ScopeEnum.MyEnum2()
          val NOENUM = Bool()
          val inner_ee = MyEnum()
          val inner_b = new Bundle {
            val inner_inner_e = MyEnum()
            val inner_NOENUM = Bool()
            val inner_ee = MyEnum2()
          }
          val v = Vec(3, MyEnum())
        }
        val v = Vec(3, MyEnum())
      }))
      val vBundle = VecInit(i)
      val v = IO(Input(Vec(3, MyEnum())))
      val vv = IO(Input(Vec(2, Vec(2, MyEnum()))))
    }
  }

  // ── MemCircuits ───────────────────────────────────────────────────────────

  object MemCircuits {

    class ROMs {
      import chisel3.experimental.BundleLiterals._
      val romFromVec:     Vec[UInt] = Wire(Vec(4, UInt(8.W)))
      val romFromVecInit: Vec[UInt] = VecInit(1.U, 2.U, 4.U, 8.U)
      for (i <- 0 until 4) { romFromVec(i) := romFromVecInit(i) }
      val bundle = new Bundle { val a: UInt = UInt(8.W) }
      val romOfBundles = VecInit(
        bundle.Lit(_.a -> 1.U),
        bundle.Lit(_.a -> 2.U),
        bundle.Lit(_.a -> 4.U),
        bundle.Lit(_.a -> 8.U)
      )
    }

    class TopCircuitROM extends RawModule {
      val roms = new ROMs
    }

    class TopCircuitSyncMem[T <: Data](gen: T, withConnection: Boolean) extends Module {
      val mem = SyncReadMem(4, gen)
      if (withConnection) {
        val idx = IO(Input(UInt(2.W)))
        val in = IO(Input(gen))
        val out = IO(Output(gen))
        mem.write(idx, in)
        out := mem.read(idx)
      }
    }

    class TopCircuitMem[T <: Data](gen: T, withConnection: Boolean) extends Module {
      val mem = Mem(4, gen)
      if (withConnection) {
        val idx = IO(Input(UInt(2.W)))
        val in = IO(Input(gen))
        val out = IO(Output(gen))
        mem(idx) := in
        out := mem(idx)
      }
    }

    class TopCircuitSRAM[T <: Data](
      gen:               T,
      size:              Int,
      numReadPorts:      Int,
      numWritePorts:     Int,
      numReadwritePorts: Int
    ) extends Module {
      val mem = SRAM(size, gen, numReadPorts, numWritePorts, numReadwritePorts)
    }

    class TopCircuitMemWithMask[T <: Data, M <: MemBase[T]](
      _gen:     T,
      _mem:     Class[M],
      maskSize: Int
    ) extends Module {
      val gen = Vec(maskSize, _gen)
      val mem = {
        if (classOf[SyncReadMem[T]].isAssignableFrom(_mem))
          SyncReadMem(4, gen)
        else if (classOf[Mem[T]].isAssignableFrom(_mem))
          Mem(4, gen)
        else
          throw new Exception("Unknown memory type")
      }
      val mask = Wire(Vec(maskSize, Bool()))
      val idx = IO(Input(UInt(2.W)))
      val in = IO(Input(gen))
      val out = IO(Output(gen))
      mem.write(idx, in, mask)
      out := mem.read(idx)
    }

    class TopCircuitSRAMWithMask[T <: Data](_gen: T) extends Module {
      val maskSize = 2
      val gen = Vec(maskSize, _gen)
      val mem = SRAM.masked(4, gen, 1, 1, 0)
    }
  }

  // ── ParamCircuits ─────────────────────────────────────────────────────────

  object ParamCircuits {

    class TopCircuitWithParams(val width1: Int, width2: Int) extends RawModule {
      val width3 = width1 + width2
      val uint: UInt = IO(Input(UInt(width1.W)))
      val sint: SInt = IO(Input(SInt(width2.W)))
      val bool: Bool = IO(Input(Bool()))
      val bits: Bits = IO(Input(Bits(width3.W)))
    }

    class TopCircuitWithParamModules extends RawModule {
      class MyModule(val width: Int) extends RawModule
      val mod1 = Module(new MyModule(8))
      val mod2 = Module(new MyModule(16))
      val mod3 = Module(new MyModule(32))
    }

    class TopCircuitWithParamBundle extends RawModule {
      class BaseBundle(val n: Int) extends Bundle {
        val b: UInt = UInt(n.W)
      }
      class OtherBundle(val a: UInt, val b: BaseBundle) extends Bundle {}
      class TopBundle(
        a:               Bool,
        val b:           String,
        protected val c: Char,
        private val d:   Boolean,
        val o:           OtherBundle
      ) extends Bundle {
        val inner_a = a
      }
      case class CaseClassExample(a: Int, o: OtherBundle) extends Bundle

      val baseBundle = IO(Input(new BaseBundle(1)))
      val otherBundle = IO(Input(new OtherBundle(UInt(baseBundle.n.W), baseBundle.cloneType)))
      val topBundle = IO(Input(new TopBundle(Bool(), "hello", 'c', true, otherBundle.cloneType)))
      val caseClassBundle = IO(Input(CaseClassExample(1, new OtherBundle(UInt(2.W), baseBundle.cloneType))))
      val anonBundle = IO(Input(new Bundle {}))
    }

    class TopCircuitWithParamScalaClasses extends RawModule {
      class MyScalaClass(val a: Int, b: String)
      case class MyScalaCaseClass(a: Int, b: String)
      class MyModule(val a: MyScalaClass, b: MyScalaCaseClass) extends RawModule
      class MyBundle(val a: MyScalaClass, b: MyScalaCaseClass) extends Bundle

      val mod = Module(new MyModule(new MyScalaClass(1, "hello"), MyScalaCaseClass(2, "world")))
      val bundle = IO(Input(new MyBundle(new MyScalaClass(1, "hello"), MyScalaCaseClass(2, "world"))))
    }
  }
}
