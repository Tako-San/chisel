package chisel3.util.debug

import scala.reflect.runtime.universe._

case class ClassParam(name: String, typeName: String, value: String)

object TypeReflector {
  def getConstructorParams(target: Any): Seq[ClassParam] = {
    val mirror = runtimeMirror(target.getClass.getClassLoader)
    val instanceMirror = mirror.reflect(target)
    val classSymbol = mirror.classSymbol(target.getClass)

    // Find primary constructor
    val primaryConstructor = classSymbol.toType.members.collectFirst {
      case m: MethodSymbol if m.isPrimaryConstructor => m
    }.getOrElse(return Seq.empty)

    // Extract parameters
    primaryConstructor.paramLists.flatten.flatMap { symbol =>
      val paramName = symbol.name.toString
      val paramType = symbol.typeSignature.toString

      // Try to get field value (if val/var)
      val value =
        try {
          val fieldTerm = classSymbol.toType.decl(TermName(paramName)).asTerm
          val fieldValue = instanceMirror.reflectField(fieldTerm).get
          fieldValue.toString
        } catch {
          case _: Exception => "unknown"
        }

      Some(ClassParam(paramName, paramType, value))
    }
  }

  def shouldReflect(target: Any): Boolean = {
    val mirror = runtimeMirror(target.getClass.getClassLoader)
    val classSymbol = mirror.classSymbol(target.getClass)
    classSymbol.baseClasses.exists { sym =>
      sym.fullName == "chisel3.Record" || sym.fullName == "chisel3.Bundle"
    }
  }
}
