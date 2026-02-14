package chisel3.experimental.debug

import chisel3.{Data, Element, Record, Vec}
import scala.reflect.runtime.universe._

case class ConstructorParam(name: String, typeName: String, value: String, isComplex: Boolean = false)

object DebugTypeReflector {
  private val mirror = runtimeMirror(getClass.getClassLoader)

  def getConstructorParams(target: Any): Seq[ConstructorParam] = {
    val targetClass = target.getClass
    val classSymbol = mirror.classSymbol(targetClass)
    val instanceMirror = mirror.reflect(target)

    val primaryConstructor = classSymbol.toType.members.collectFirst {
      case m: MethodSymbol if m.isPrimaryConstructor => m
    }.getOrElse(return Seq.empty)

    primaryConstructor.paramLists.flatten.flatMap { symbol =>
      val paramName = symbol.name.toString

      // Filter synthetic fields like $outer
      if (paramName.contains("$outer")) {
        None
      } else {
        val paramTypeSignature = symbol.typeSignature
        val typeName = paramTypeSignature.typeSymbol.name.toString

        val valueResult =
          try {
            val declSymbol = classSymbol.toType.decl(TermName(paramName))
            if (declSymbol == NoSymbol) {
              ("unknown", false)
            } else {
              val termSymbol = declSymbol.asTerm
              val fieldMirror = instanceMirror.reflectField(termSymbol)
              val rawValue = fieldMirror.get
              formatValue(rawValue)
            }
          } catch {
            case _: Throwable => ("unknown", false)
          }

        Some(ConstructorParam(paramName, typeName, valueResult._1, valueResult._2))
      }
    }
  }

  private def formatValue(value: Any): (String, Boolean) = value match {
    // Chisel Data types
    case d: Data => (d.toString, false)

    // Sequences with recursive handling
    case s: Seq[_] =>
      val elements = s.map(e => formatValue(e)._1).mkString(", ")
      (s"Seq($elements)", false)

    // Basic types
    case s: String  => (s""""$s"""", false)
    case n: Number  => (n.toString, false)
    case b: Boolean => (b.toString, false)

    // Recursive case: nested objects
    case obj if hasConstructorParams(obj) =>
      val params = getConstructorParams(obj)
      val paramsStr = params.map(p => s"${p.name}=${p.value}").mkString(", ")
      (s"${obj.getClass.getSimpleName}($paramsStr)", true)

    case other => (other.toString, false)
  }

  private def hasConstructorParams(target: Any): Boolean = {
    val tpe = mirror.classSymbol(target.getClass).toType
    tpe.members.exists { m =>
      m.isMethod &&
      (m match {
        case msym: MethodSymbol => msym.isPrimaryConstructor && msym.paramLists.flatten.nonEmpty
        case _ => false
      })
    }
  }

  def shouldReflect(target: Any): Boolean = {
    target match {
      case _: Element => false // UInt, SInt, Bool, Clock
      case _: Record  => true // Bundle, Record
      case _ => false
    }
  }
}
