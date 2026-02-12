package chisel3.experimental

import chisel3._
import chisel3.Intrinsic // В Chisel 6+ Intrinsic находится здесь
import chisel3.experimental.SourceInfo
import scala.reflect.runtime.universe._
import scala.reflect.ClassTag

package object debug {

  def captureCircuit[M <: RawModule](module: M): Unit = {
    CircuitTraverser.captureCircuit(module)
  }

  /**
   * Прикрепляет отладочную информацию к сигналу Data.
   * Генерирует Intrinsic-вызов в FIRRTL.
   */
  def attachSourceInfo[T <: Data](target: T)(implicit sourceInfo: SourceInfo): Unit = {
    // 1. Извлекаем данные из ReflectionExtractor
    val info = ReflectionExtractor.extract(target)

    // 2. Сериализуем в строку (формат: "key=value,key2=value2")
    val serialized = info.fields.map(f => s"${f.name}=${f.value}").mkString(",")

    // 3. Создаем Intrinsic
    // "chisel.debug.source_info" — уникальное имя интринсика
    Intrinsic("chisel.debug.source_info", "scala_class" -> info.className, "fields" -> serialized)(target)
  }

  /**
   * Проходит по всем полям модуля и автоматически аннотирует те,
   * которые являются наследниками Data.
   *
   * Использует runtime reflection для обхода проблем с инициализацией TypeTag.
   */
  def captureParams[M <: Module](module: M)(implicit ct: ClassTag[M], sourceInfo: SourceInfo): Unit = {
    // Create a new runtime mirror locally
    val mirror = runtimeMirror(getClass.getClassLoader)
    val instanceMirror = mirror.reflect(module)(ct)
    val tpe = instanceMirror.symbol.typeSignature

    // Ищем все публичные геттеры, vals и def-методы без параметров
    // Сначала собираем все кандидатов, а затем фильтруем
    val allCandidates = tpe.members.flatMap { member =>
      val memberName = member.name.toString

      // Пропускаем private/internal/generated members и системные поля
      if (
        memberName.startsWith("_") || memberName.contains("$") ||
        memberName == "impl" || memberName == "_module" ||
        memberName == "implicitClock" || memberName == "implicitReset"
      ) {
        None
      } else {
        member match {
          // Геттер-методы
          case m: MethodSymbol if m.isGetter => Some(m)
          // Def-методы без параметров (без геттеров) - paramLists is empty OR all param lists are empty
          case m: MethodSymbol if !m.isGetter && (m.paramLists.isEmpty || m.paramLists.forall(_.isEmpty)) => Some(m)
          // Val-символы (не методы)
          case t: TermSymbol if t.isVal && !t.isMethod => Some(t)
          case _ => None
        }
      }
    }.toList

    // Фильтруем кандидатов по типу возврата
    val members = allCandidates.filter { member =>
      member match {
        case m: MethodSymbol =>
          m.returnType <:< typeOf[Data] || isDataLike(m.returnType)
        case t: TermSymbol =>
          t.info <:< typeOf[Data] || isDataLike(t.info)
      }
    }

    members.foreach { member =>
      try {
        val term = member match {
          case m: MethodSymbol => m.asTerm
          case t: TermSymbol   => t
          case _ => member.asTerm
        }

        // Сначала пробуем как поле val
        if (member.isTerm && member.isVal && !member.isMethod) {
          try {
            val fieldMirror = instanceMirror.reflectField(term)
            val value = fieldMirror.get
            if (value.isInstanceOf[Data]) {
              val data = value.asInstanceOf[Data]
              Intrinsic("chisel.debug.source_info", "field_name" -> member.name.toString.trim)(data)
            }
          } catch {
            case _: Throwable =>
              // Пробуем через метод/getter
              tryInvokeMethod(member, instanceMirror, term)
          }
        } else if (member.isMethod) {
          // Это метод (getter или def)
          if (member.asMethod.isGetter) {
            // Getter - пробуем через reflectField
            try {
              val value = instanceMirror.reflectField(term).get
              if (value.isInstanceOf[Data]) {
                val data = value.asInstanceOf[Data]
                Intrinsic("chisel.debug.source_info", "field_name" -> member.name.toString.trim)(data)
              }
            } catch {
              case _: Throwable =>
                // Пробуем через reflectMethod как запасной вариант
                tryInvokeMethod(member, instanceMirror, term)
            }
          } else {
            // Def метод без параметров - вызываем через reflectMethod
            tryInvokeMethod(member, instanceMirror, term)
          }
        } else {
          // Обычный геттер или другое
          try {
            val value = instanceMirror.reflectField(term).get
            if (value.isInstanceOf[Data]) {
              val data = value.asInstanceOf[Data]
              Intrinsic("chisel.debug.source_info", "field_name" -> member.name.toString.trim)(data)
            }
          } catch {
            case _: Throwable =>
              // Пробуем через метод
              tryInvokeMethod(member, instanceMirror, term)
          }
        }
      } catch {
        case e: Throwable =>
      }
    }
  }

  private def tryInvokeMethod(member: Symbol, instanceMirror: InstanceMirror, term: TermSymbol)(
    implicit sourceInfo: SourceInfo
  ): Unit = {
    try {
      val methodMirror = instanceMirror.reflectMethod(term.asMethod)
      val value = methodMirror.apply()
      if (value.isInstanceOf[Data]) {
        val data = value.asInstanceOf[Data]
        Intrinsic("chisel.debug.source_info", "field_name" -> member.name.toString.trim)(data)
      }
    } catch {
      case _: Throwable =>
    }
  }

  /**
   * Проверяет, является ли тип Data или похожим на него.
   * Нужна потому что иногда типы могут быть подтипами Data или иметь похожее имя.
   */
  private def isDataLike(tpe: Type): Boolean = {
    tpe.typeSymbol.fullName.startsWith("chisel3.Data") ||
    tpe.typeSymbol.fullName.startsWith("chisel3.") ||
    tpe.toString.contains("Wire") ||
    tpe.toString.contains("UInt") ||
    tpe.toString.contains("Bool")
  }
}
