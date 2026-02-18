package chisel3.stage.phases

import chisel3.debug.ClassParam
import play.api.libs.json._

/** Utilities for reflecting on Chisel types to extract constructor parameters.
  */
object DebugReflectionUtils {

  implicit val classParamWrites: OWrites[ClassParam] = Json.writes[ClassParam]

  /** Get constructor parameters of the target's class and serialize to JSON.
    *
    * @param target The instance to inspect
    * @return JSON string containing the constructor parameters
    */
  def getParamsJson(target: Any): String =
    Json.toJson(getConstructorParams(target)).toString

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

      // Helper to normalize type names for Scala 3
      // Handles both Java primitive names (lowercase) and boxed types
      def normalizeTypeName(name: String): String = {
        // Handle Scala 3's boxed type names like "Int[eger]" and Java primitives
        val cleaned = name.split("\\[").head
        // Map Java primitive names and boxed types to Scala type names
        cleaned match {
          case "int"           => "Int"
          case "Integer"       => "Int"
          case "boolean"       => "Boolean"
          case "Boolean"       => "Boolean"
          case "char"          => "Char"
          case "Character"     => "Char"
          case "long"          => "Long"
          case "Long"          => "Long"
          case "float"         => "Float"
          case "Float"         => "Float"
          case "double"        => "Double"
          case "Double"        => "Double"
          case "byte"          => "Byte"
          case "Byte"          => "Byte"
          case "short"         => "Short"
          case "Short"         => "Short"
          case "void" | "Void" => "Unit"
          case other           => other
        }
      }

      // Use constructor parameter types to get the actual parameter type names
      val paramsWithTypes = ctor.getParameters()

      val fields = clazz.getDeclaredFields.filter { f =>
        // Filter synthetic fields Scala/JVM
        !f.isSynthetic &&
        !f.getName.contains("$") &&
        ctorParamNames.contains(f.getName) // Only params
      }
        .sortBy(f => paramOrder.getOrElse(f.getName, Int.MaxValue))
        .map { field =>
          // Get the parameter type from the constructor, not the field type
          val paramType = paramsWithTypes
            .find(_.getName == field.getName)
            .map(_.getType.getSimpleName)
            .getOrElse(field.getType.getSimpleName)

          (field, paramType)
        }

      fields.map { case (field, paramType) =>
        field.setAccessible(true)
        val paramName = field.getName
        val rawValue =
          try { Some(field.get(target)) }
          catch { case _: Exception => None }

        val (typeName, paramValue) = rawValue match {
          case Some(d: chisel3.Data) => (normalizeTypeName(d.getClass.getSimpleName), Some(d.typeName))
          case Some(other)           => (normalizeTypeName(paramType), Some(other.toString))
          case None                  => ("Unknown", None)
        }

        ClassParam(paramName, typeName, paramValue)
      }.toSeq
    } catch {
      case _: Exception => Seq.empty
    }
  }

  def dataToTypeName(data: Any): String = data match {
    case null => "null"
    case d: chisel3.Data => d.typeName
    case _ => data.getClass.getSimpleName
  }
}