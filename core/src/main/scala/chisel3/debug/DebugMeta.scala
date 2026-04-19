// SPDX-License-Identifier: Apache-2.0

package chisel3.debug

import logger.LazyLogging

import chisel3._
import chisel3.internal.binding._

import scala.reflect.runtime.universe._

import upickle.{default => json}

/** Constructor parameter descriptor.
  *
  * `value` is a native JSON value (`Bool`, `Num`, `Str`, …) so that bool
  * params round-trip as `true`/`false`, not `"true"`/`"false"`. Downstream
  * consumers (e.g. the CIRCT debug-info sink) can dispatch on the JSON type
  * to build correctly-typed MLIR attributes.
  */
private[debug] case class ClassParam(
  name:     String,
  typeName: String,
  value:    Option[ujson.Value] = None
)

private[debug] object ClassParam {
  implicit val rw: json.ReadWriter[ClassParam] = json
    .readwriter[ujson.Value]
    .bimap(
      p =>
        ujson.Obj(
          "name" -> p.name,
          "typeName" -> p.typeName,
          "value" -> p.value.getOrElse(ujson.Null)
        ),
      j =>
        ClassParam(
          j("name").str,
          j("typeName").str,
          j.obj.get("value").filterNot(_ == ujson.Null)
        )
    )
}

private[debug] object CtorParamExtractor extends LazyLogging {

  private[debug] def getCtorParams(target: Any): Seq[ClassParam] = {
    val tpe = typeOf(target)
    ctorSymbols(tpe).map { a =>
      ClassParam(a.name.toString, paramTypeName(a), paramValue(target, a))
    }
  }

  /** Reflectively read the constructor argument, returning its value as a
    * native JSON type when the Scala type maps directly (Bool, numeric
    * primitives, String). Complex types (Data, nested case classes) are
    * rendered to a descriptive string.
    */
  private def paramValue(obj: Any, a: Symbol): Option[ujson.Value] = {
    val fieldName = a.name.toString.trim
    val methodOpt =
      try {
        val m = obj.getClass.getDeclaredMethod(fieldName)
        m.setAccessible(true)
        Some(m)
      } catch { case _: NoSuchMethodException => None }

    methodOpt.flatMap { method =>
      try {
        val v = method.invoke(obj.asInstanceOf[AnyRef])
        v match {
          case s: scala.collection.Seq[_] if s.exists(_.isInstanceOf[Data]) =>
            Some(ujson.Str(
              s.collect { case d: Data => dataToTypeName(d) }.mkString("[", ", ", "]")
            ))
          case _ =>
            val nested = getCtorParams(v)
            if (nested.exists(_.value.isDefined)) {
              val nestedStr = nested.map { p =>
                p.value.fold(p.name)(vv => s"${p.name}: ${renderJson(vv)}")
              }
              Some(ujson.Str(s"${paramTypeName(a)}(${nestedStr.mkString(", ")})"))
            } else
              v match {
                case d: Data     => Some(ujson.Str(dataToTypeName(d)))
                case b: Boolean  => Some(ujson.Bool(b))
                case _           => Some(ujson.Str(v.toString))
              }
        }
      } catch {
        case e: Throwable =>
          logger.debug(s"paramValue: cannot reflect $fieldName: ${e.getMessage}")
          None
      }
    }
  }

  /** Stringify a JSON value without quoting — used when flattening nested
    * ClassParams into a human-readable string. */
  private def renderJson(v: ujson.Value): String = v match {
    case ujson.Str(s)  => s
    case ujson.Bool(b) => b.toString
    case other         => other.toString
  }

  private[debug] def dataToTypeName(data: Data): String = data match {
    case t: Record =>
      t.topBindingOpt match {
        case Some(binding) => s"${t._bindingToString(binding)}[${t.className}]"
        case None          => t.className
      }
    case t => t.toString.split(" ").last
  }

  private val mirrorCache =
    new java.util.concurrent.ConcurrentHashMap[ClassLoader, scala.reflect.runtime.universe.Mirror]()

  private def typeOf(obj: Any): Type = {
    val cl = obj.getClass.getClassLoader
    val rm =
      if (cl != null) mirrorCache.computeIfAbsent(cl, runtimeMirror(_))
      else runtimeMirror(cl)
    rm.classSymbol(obj.getClass).toType
  }

  private def ctorSymbols(tpe: Type): Seq[Symbol] = {
    val ctor = tpe.typeSymbol.asClass.primaryConstructor
    if (ctor == NoSymbol) Seq.empty
    else ctor.asMethod.paramLists.flatten.filter(!_.name.toString.contains("$outer"))
  }

  private def paramTypeName(a: Symbol): String = {
    val parts = a.info.toString.split("\\$")
    (if (parts.length > 1) parts(1) else parts(0)).split("\\.").last
  }
}
