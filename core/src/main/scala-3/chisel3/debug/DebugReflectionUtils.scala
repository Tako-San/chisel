package chisel3.debug

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

  /** Get constructor parameters of the target's class and serialize to JSON.
    *
    * @param target The instance to inspect
    * @return JSON string containing the constructor parameters
    */
  def getParamsJson(target: Any): String = {
    val params = getConstructorParams(target)
    DebugJsonUtils.toJson(params)
  }

  /** Extract constructor parameters from the target's class using Scala reflection.
    *
    * @param target The instance to inspect
    * @return Sequence of ClassParam containing name, type name, and optional value
    */
  def getConstructorParams(target: Any): Seq[ClassParam] = {
    try {
      val clazz = target.getClass
      // Get ONLY constructor parameters via getConstructors
      val ctors = clazz.getConstructors
      if (ctors.isEmpty) return Seq.empty
      // Primary constructor has maximum number of parameters
      val ctor = ctors.maxBy(_.getParameterCount)
      val ctorParamNames = ctor.getParameters.map(_.getName).toSet
      val paramOrder = ctor.getParameters.map(_.getName).zipWithIndex.toMap

      val fields = clazz.getDeclaredFields.filter { f =>
        // Filter synthetic fields Scala/JVM
        !f.isSynthetic &&
        !f.getName.contains("$") &&
        ctorParamNames.contains(f.getName) // Only params
      }
        .sortBy(f => paramOrder.getOrElse(f.getName, Int.MaxValue))

      fields.map { field =>
        field.setAccessible(true)
        val paramName = field.getName
        val rawValue =
          try { Some(field.get(target)) }
          catch { case _: Exception => None }

        val paramValue = rawValue.map {
          case d: chisel3.Data => d.typeName
          case other => other.toString
        }
        val typeName = rawValue.map {
          case d: chisel3.Data => d.getClass.getSimpleName
          case other => other.getClass.getSimpleName
        }.getOrElse("Unknown")

        ClassParam(paramName, typeName, paramValue)
      }.toSeq
    } catch {
      case _: Exception => Seq.empty
    }
  }

  def dataToTypeName(data: Any): String = {
    data match {
      case null => "null"
      case d: chisel3.Data => d.typeName
      case _ => data.getClass.getSimpleName
    }
  }
}
