package chisel3.experimental.debug

object DebugTypeReflector {
  def getConstructorParams(target: Any): Seq[ConstructorParam] = Seq.empty
  def shouldReflect(target:        Any): Boolean = false
}

case class ConstructorParam(name: String, typeName: String, value: String, isComplex: Boolean = false)
