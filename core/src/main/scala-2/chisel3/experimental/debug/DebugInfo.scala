package chisel3.experimental.debug

/** Описание одного параметра конструктора или поля класса */
case class FieldInfo(name: String, typeName: String, value: String)

/** Описание всего класса */
case class ClassDebugInfo(className: String, fields: Seq[FieldInfo])
