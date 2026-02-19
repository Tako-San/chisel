// RUN: scala-cli --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION --scala-option="-Xplugin:%SCALAPLUGINJARS" %s -- chirrtl | FileCheck %s

import chisel3._

class FooBundle extends Bundle {
  val foo = Input(UInt(3.W))
}

// CHECK-LABEL: circuit FooModule :
// CHECK:         public module FooModule :
// CHECK-NEXT:      input clock : Clock
// CHECK-NEXT:      input reset : UInt<1>
// CHECK-NEXT:      output io : { flip foo : UInt<3>}
// CHECK:           intrinsic(circt_dbg_variable<name = "clock", path = "FooModule.clock", type = "Clock">)
// CHECK-NEXT:      intrinsic(circt_dbg_variable<name = "reset", path = "FooModule.reset", type = "Bool">)
// CHECK-NEXT:      intrinsic(circt_dbg_variable<name = "io", path = "FooModule.io", type = "FooBundle">)

class FooModule extends Module {
  val io = IO(new FooBundle)
}

args.head match {
  case "chirrtl" => {
    println(circt.stage.ChiselStage.emitCHIRRTL(new FooModule))
  }
}
