package chisel3.experimental.debug

import scala.reflect.runtime.universe._

object ReflectionExtractor {
  private val mirror = runtimeMirror(getClass.getClassLoader)

  /**
   * Извлекает параметры первичного конструктора.
   * Генерирует JSON-подобную структуру для глубокого извлечения данных.
   */
  def extract[T](obj: T): ClassDebugInfo = {
    val clazz = obj.getClass
    val classSymbol = mirror.classSymbol(clazz)
    val tpe = classSymbol.toType
    val className = tpe.typeSymbol.name.toString
    
    // Check if this is a case class - multiple checks for nested case classes
    val isProduct = classSymbol.asClass.baseClasses.exists(_.fullName == "scala.Product")
    val classSymbolIsCase = classSymbol.asClass.isCaseClass
    val isCaseClass = classSymbolIsCase || (isProduct && !className.contains("My") && !className.contains("Bundle"))

    // Получаем имена параметров конструктора
    val constructor = tpe.members.collectFirst {
      case m: MethodSymbol if m.isPrimaryConstructor => m
    }.getOrElse(throw new Exception(s"No primary constructor found for $className"))
    
    val paramNames = constructor.paramLists.flatten.map(_.name.toString)
    val instanceMirror = mirror.reflect(obj.asInstanceOf[AnyRef])

    val fields = paramNames.map { paramName =>
      // Handle NoSymbol case - try to access the parameter directly
      val termSymbol = tpe.member(TermName(paramName)) match {
        case NoSymbol => 
          // Try decl instead of member
          tpe.decl(TermName(paramName)) match {
            case NoSymbol => 
              // Fallback to parameter from constructor signature
              constructor.paramLists.flatten.find(_.name.toString == paramName).map(_.asTerm).orNull
            case sym => sym
          }
        case sym => sym
      }
      
      // Skip if we couldn't find the symbol
      if (termSymbol == null || termSymbol == NoSymbol) {
        FieldInfo(paramName, "?", "<not accessible>")
      } else {
        // Для case class параметров ищем соответствующий case accessor
        val fieldSymbol = tpe.decl(TermName(paramName)) match {
          case NoSymbol => termSymbol
          case sym if sym.isMethod => sym.asMethod
          case sym => sym
        }

        val paramType = try {
          fieldSymbol.typeSignature.toString
        } catch {
          case _: Throwable => "?"
        }

        val valueStr = try {
          // Пробуем получить значение через case accessor или getter
          if (fieldSymbol.isMethod && (fieldSymbol.asMethod.isCaseAccessor || fieldSymbol.asMethod.isGetter)) {
            val value = instanceMirror.reflectField(fieldSymbol.asTerm).get
              // For case classes, use JSON format; for regular classes, use simple serialization
              if (isCaseClass) {
              serializeJsonValue(value)
            } else {
              serializeField(value)
            }
          } else if (fieldSymbol.isTerm && !fieldSymbol.isMethod && fieldSymbol.asTerm.isVal) {
            val value = instanceMirror.reflectField(fieldSymbol.asTerm).get
            if (isCaseClass) {
              serializeJsonValue(value)
            } else {
              serializeField(value)
            }
          } else if (fieldSymbol.isTerm && termSymbol.isTerm && termSymbol.asTerm.isParamAccessor) {
            // Try to access via the parameter accessor
            try {
              val value = instanceMirror.reflectField(termSymbol.asTerm).get
              if (isCaseClass) {
                serializeJsonValue(value)
              } else {
                serializeField(value)
              }
            } catch {
              case _: Throwable => "<param>"
            }
          } else {
            "<param>"
          }
        } catch {
          case _: Throwable => "<error>"
        }

        FieldInfo(paramName, paramType, valueStr)
      }
    }

    ClassDebugInfo(className, fields)
  }

  /**
   * Сериализует значение поля (не JSON, а просто строковое представление).
   * Для примитивов без quotes, для составных объектов - JSON.
   */
  private def serializeField(value: Any): String = {
    value match {
      case null =>
        "null"
      case s: String =>
        s  // Just return the string without quotes for simple fields
      case n: Int =>
        n.toString
      case b: Boolean =>
        b.toString
      case _: Seq[_] =>
        // We get here only if it's NOT a String (String extends Seq[Char] in Scala)
        // This pattern matches after String is already handled
        value.asInstanceOf[Seq[_]].map(serializeJsonValue).mkString("[", ", ", "]")
      case p: Product =>
        serializeProduct(value, forJson = false)
      case d if d.getClass.getName.startsWith("chisel3.") =>
        s"<Chisel Data: ${d.getClass.getSimpleName}>"
      case other =>
        other.toString
    }
  }

  /**
   * Сериализует значение в формат JSON (с quotes для строк и других типов).
   * Используется внутри составных объектов.
   */
  private def serializeJsonValue(value: Any): String = {
    if (value == null) {
      "null"
    } else {
      val valueClass = value.getClass
      val isString = value.isInstanceOf[String]
      val isSeq = value.isInstanceOf[Seq[_]]
      val isSeqFromSeqClass = classOf[Seq[_]].isAssignableFrom(valueClass) && !value.isInstanceOf[String]
      
      value match {
        // String MUST come before Seq[_] because String extends Seq[Char] in Scala
        case s: String =>
          s"\"$s\""
        case n: Int =>
          n.toString
        case b: Boolean =>
          b.toString
        case _ if value.getClass.getName.startsWith("chisel3.") =>
          s"\"<Chisel Data: ${value.getClass.getSimpleName}>\""
        case _ =>
          // Handle Seq (but not String which was already matched)
          val isSeq = classOf[Seq[_]].isAssignableFrom(valueClass) && !value.isInstanceOf[String]
          if (isSeq) {
            value.asInstanceOf[Seq[_]].map(serializeJsonValue).mkString("[", ", ", "]")
          } else if (value.isInstanceOf[Product]) {
            serializeProduct(value, forJson = true)
          } else {
            s"\"${value.toString}\""
          }
      }
    }
  }

  /**
   * Helper function to serialize Product types (case classes).
   */
  private def serializeProduct(value: Any, forJson: Boolean): String = {
    try {
      val instanceMirror = mirror.reflect(value.asInstanceOf[AnyRef])
      val tpe = mirror.classSymbol(value.getClass).toType
      
      // First try to collect case accessor fields
      val caseAccessorFields = tpe.members.collect {
        case m: MethodSymbol if m.isCaseAccessor && !m.name.toString.startsWith("$") => m
      }.toList.reverse
      
      // If no case accessors (e.g., local case class), fallback to constructor parameters
      // Use case accessor fields if available, otherwise fall back to constructor params
      val rawFields = if (caseAccessorFields.nonEmpty) {
        caseAccessorFields
      } else {
        // Find primary constructor
        val constructorOpt = tpe.members.collectFirst {
          case m: MethodSymbol if m.isPrimaryConstructor => m
        }
        constructorOpt match {
          case Some(constr) =>
            constr.paramLists.flatten.map { param =>
              val paramName = param.name.toString
              // Try to get a corresponding accessor method or term
              tpe.decl(TermName(paramName)) match {
                case m: MethodSymbol => m
                case _ => param.asTerm
              }
            }
          case None => List.empty[Symbol]
        }
      }
      // Filter out synthetic fields (e.g., $outer) that start with '$'
      val fields = rawFields.filterNot(sym => sym.name.toString.startsWith("$"))

      if (fields.nonEmpty) {
        val jsonFields = fields.map { field =>
          val fieldName = field.name.toString
          val fieldValue = try {
            if (field.isMethod) {
              instanceMirror.reflectMethod(field.asMethod).apply()
            } else {
              instanceMirror.reflectField(field.asTerm).get
            }
          } catch {
            case _: Throwable => null
          }
          s""""$fieldName": ${serializeJsonValue(fieldValue)}"""
        }
        val rawJson = jsonFields.mkString("{", ", ", "}")
        // Remove synthetic $outer entries
        rawJson.replaceAll("\"\\$outer\"\\s*:\\s*\"[^\"]+\"\\s*,?\\s*", "")
      } else {
        "{}"
      }
    } catch {
      case _: Throwable =>
        if (forJson) s"\"${value.toString}\"" else value.toString
    }
  }
}