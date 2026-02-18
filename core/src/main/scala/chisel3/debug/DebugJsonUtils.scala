package chisel3.debug

private[debug] object DebugJsonUtils {

  def toJson(params: Seq[ClassParam]): String = {
    val entries = params.map { param =>
      val valueStr = param.value match {
        case Some(v) => s""""value": "${escapeJson(v)}""""
        case None    => """"value": null"""
      }
      s"""    "${escapeJson(param.name)}": {
         |      "typeName": "${escapeJson(param.typeName)}",
         |      $valueStr
         |    }""".stripMargin
    }
    if (entries.isEmpty) "{}"
    else entries.mkString("{\n", ",\n", "\n}")
  }

  def escapeJson(s: String): String = {
    val sb = new StringBuilder(s.length)
    s.foreach {
      case '\\'          => sb.append("\\\\")
      case '"'           => sb.append("\\\"")
      case '\n'          => sb.append("\\n")
      case '\r'          => sb.append("\\r")
      case '\t'          => sb.append("\\t")
      case '\b'          => sb.append("\\b")
      case '\f'          => sb.append("\\f")
      case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
      case c             => sb.append(c)
    }
    sb.toString()
  }
}
