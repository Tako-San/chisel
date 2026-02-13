package chisel3.experimental.debug

import scala.reflect.runtime.universe._
import java.lang.VirtualMachineError

case class FieldInfo(name: String, typeName: String, value: String)
case class ClassDebugInfo(className: String, fields: Seq[FieldInfo])

object DebugReflection {
  private val mirror = runtimeMirror(getClass.getClassLoader)

  private lazy val anyRefClassSymbol = mirror.classSymbol(classOf[AnyRef])

  def extract[T](obj: T): ClassDebugInfo = {
    val clazz = obj.getClass
    val classSymbol = mirror.classSymbol(clazz)
    val tpe = classSymbol.toType
    val className = tpe.typeSymbol.name.toString

    val constructor = tpe.members.collectFirst {
      case m: MethodSymbol if m.isPrimaryConstructor => m
    }.getOrElse(throw new Exception(s"No constructor for $className"))

    val paramNames = constructor.paramLists.flatten.map(_.name.toString)
    val instanceMirror = mirror.reflect(obj.asInstanceOf[AnyRef])

    val fields = paramNames.flatMap { paramName =>
      val termSymbol = tpe.member(TermName(paramName))
      if (termSymbol == NoSymbol) {
        None
      } else {
        try {
          val value = instanceMirror.reflectField(termSymbol.asTerm).get
          val typeName = termSymbol.typeSignature.toString
          Some(FieldInfo(paramName, typeName, value.toString))
        } catch {
          case e @ scala.util.control.NonFatal(_) => None
        }
      }
    }

    ClassDebugInfo(className, fields)
  }
}
