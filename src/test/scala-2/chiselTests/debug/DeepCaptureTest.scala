package chiselTests.debug

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DeepCaptureTest extends AnyFlatSpec with Matchers {

  "DebugCapture" should "capture local wires inside methods" in {
    class TestModule extends Module {
      val io = IO(new Bundle { val out = Output(UInt(8.W)) })

      // Метод создает провода, которые НЕ являются полями класса
      def createLogic(): UInt = {
        val internalWire = Wire(UInt(8.W)) // Локальная переменная
        internalWire := 42.U
        internalWire
      }

      io.out := createLogic()
    }

    // Компиляция с флагом отладки
    val chirrtl = ChiselStage.emitCHIRRTL(
      new TestModule,
      Array("--capture-debug", "true")
    )

    // Проверка
    // Оригинальная версия (Reflection) НЕ найдет "internalWire", так как это не поле класса
    // Новая версия (IR Traversal) найдет его, так как это DefWire в списке команд
    chirrtl should include("intrinsic(chisel.debug.source_info")
    chirrtl should include("field_name = \"internalWire\"")
  }
}
