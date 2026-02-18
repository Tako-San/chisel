package chisel3.debug

import chisel3.Data
import scala.reflect.runtime.universe._
import scala.util.control.NonFatal

/** Represents the parameters of a class constructor.
  *
  * @param name The name of the parameter
  * @param typeName The type of the parameter
  * @param value The value of the parameter. It is `None` when the annotator is not able to retrieve the value
  */
case class ClassParam(name: String, typeName: String, value: Option[String])

/** Utilities for reflecting on Chisel types to extract constructor parameters.
  */
object DebugReflectionUtils {
  // Cache for TypeTags by class
  private val typeTagCache = new java.util.concurrent.ConcurrentHashMap[Class[_], TypeTag[_]]()

  /** Get constructor parameters of the target's class and serialize to JSON.
    *
    * @param target The instance to inspect
    * @return JSON string containing the constructor parameters
    */
  def getParamsJson(target: Any): String =
    DebugJsonUtils.toJson(getConstructorParams(target))

  /** Extract constructor parameters from the target's class using Scala reflection.
    *
    * @param target The instance to inspect
    * @return Sequence of ClassParam containing name, type name, and optional value
    */
  def getConstructorParams(target: Any): Seq[ClassParam] = {
    import scala.reflect.api.{Mirror, TypeCreator, Universe}

    def getTypeTag[T](target: T): TypeTag[_] = {
      val c = target.getClass
      typeTagCache.computeIfAbsent(
        c,
        { clazz =>
          // FIX: safe fallback for null classloader
          val cl = Option(clazz.getClassLoader)
            .getOrElse(ClassLoader.getSystemClassLoader)
          val mirror = runtimeMirror(cl)
          val sym = mirror.staticClass(clazz.getName)
          val tpe = sym.selfType
          TypeTag(
            mirror,
            new TypeCreator {
              def apply[U <: Universe with Singleton](m: Mirror[U]) =
                if (m eq mirror) tpe.asInstanceOf[U#Type]
                else
                  throw new IllegalArgumentException(
                    s"Type tag defined in $mirror cannot be migrated to other mirrors."
                  )
            }
          )
        }
      )
    }

    val tt = getTypeTag(target)
    // FIX: safe fallback for null classloader
    val cl = Option(target.getClass.getClassLoader)
      .getOrElse(ClassLoader.getSystemClassLoader)
    val im = runtimeMirror(cl).reflect(target)

    tt.tpe.members.collect {
      case m: MethodSymbol if m.isConstructor =>
        m.paramLists.flatten.collect {
          case a if !a.name.toString.contains("$outer") =>
            val t = a.info.toString.split("\\$")
            val typeName = (if (t.length > 1) t(1) else t(0)).split("\\.").last
            val paramName = a.name.toString
            val value =
              try {
                val term =
                  try { tt.tpe.decl(a.name).asTerm.accessed.asTerm }
                  catch { case NonFatal(_) => a.asTerm }
                val valueTerm = im.reflectField(term).get

                val finalValueTerm =
                  if (valueTerm.isInstanceOf[Data]) {
                    dataToTypeName(valueTerm)
                  } else {
                    valueTerm.toString
                  }
                Some(finalValueTerm)
              } catch {
                case NonFatal(_) => None
              }
            ClassParam(paramName, typeName, value)
        }
    }.toList.flatten
  }

  /** Convert a type to a human-readable type name.
    *
    * Uses `typeName` for Chisel Data instances, otherwise falls back to class name.
    *
    * @param data The object whose type to convert
    * @return String representation of the type
    */
  def dataToTypeName(data: Any): String = {
    data match {
      case null => "null"
      case d: Data => d.typeName
      case _ => data.getClass.getSimpleName
    }
  }
}
