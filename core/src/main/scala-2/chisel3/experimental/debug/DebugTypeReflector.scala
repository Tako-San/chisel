package chisel3.experimental.debug

import chisel3.{Data, Element, MemBase, Record, Vec}
import chisel3.reflect.DataMirror
import chisel3.reflect.DataMirror.internal
import scala.reflect.runtime.universe._

case class ConstructorParam(name: String, typeName: String, value: String, isComplex: Boolean = false)

object DebugTypeReflector {
  private val mirror = runtimeMirror(getClass.getClassLoader)

  private def findPrimaryConstructor(targetClass: Class[_]): Option[MethodSymbol] = {
    val classSymbol = mirror.classSymbol(targetClass)
    classSymbol.toType.members.collectFirst {
      case m: MethodSymbol if m.isPrimaryConstructor => m
    }
  }

  private def safelyGetValue(instanceMirror: InstanceMirror, termSymbol: TermSymbol): (String, Boolean) = {
    try {
      val fieldMirror = instanceMirror.reflectField(termSymbol)
      val rawValue = fieldMirror.get
      formatValue(rawValue)
    } catch {
      case _: Throwable => ("", false)
    }
  }

  private def safelyGetValue(
    instanceMirror: InstanceMirror,
    classSymbol:    ClassSymbol,
    paramName:      String
  ): Option[(String, String, (String, Boolean))] = {
    val paramTypeSignature =
      try {
        Some(classSymbol.toType.decl(TermName(paramName)).typeSignature)
      } catch {
        case _: Throwable => None
      }

    paramTypeSignature.flatMap { typeSig =>
      val typeName = typeSig.typeSymbol.name.toString
      try {
        val declSymbol = classSymbol.toType.decl(TermName(paramName))
        if (declSymbol == NoSymbol) {
          Some((paramName, typeName, ("", false)))
        } else {
          val termSymbol = declSymbol.asTerm
          val value = safelyGetValue(instanceMirror, termSymbol)
          Some((paramName, typeName, value))
        }
      } catch {
        case _: Throwable => Some((paramName, typeName, ("", false)))
      }
    }
  }

  def getConstructorParams(target: Any): Seq[ConstructorParam] = {
    val result = for {
      targetObject <- Option(target)
      targetClass = targetObject.getClass
      primaryConstructor <- findPrimaryConstructor(targetClass)
      instanceMirror = mirror.reflect(targetObject.asInstanceOf[AnyRef])
      classSymbol = mirror.classSymbol(targetClass)
    } yield {
      primaryConstructor.paramLists.flatten.flatMap { symbol =>
        val paramName = symbol.name.toString
        if (paramName.contains("$outer")) {
          None
        } else {
          safelyGetValue(instanceMirror, classSymbol, paramName).map { case (name, typeName, (value, isComplex)) =>
            ConstructorParam(name, typeName, value, isComplex)
          }
        }
      }
    }

    result.getOrElse(Seq.empty)
  }

  private def formatValue(value: Any): (String, Boolean) = value match {
    case d: Data => (internal.chiselTypeClone(d).getClass.getSimpleName, false)

    case s: Seq[_] =>
      val elements = s.map(e => formatValue(e)._1).mkString(", ")
      (s"Seq($elements)", false)

    case s: String  => (s""""$s"""", false)
    case n: Number  => (n.toString, false)
    case b: Boolean => (b.toString, false)

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

  def shouldReflect(target: Any): Boolean = target match {
    case _: Element    => false
    case _: Record     => true
    case _: Vec[_]     => true
    case _: MemBase[_] => true
    case _ => false
  }
}
