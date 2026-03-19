// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import scala.collection.mutable

object DebugMetaTestUtils {

  /** Extracts JSON string from StringParam (for ctorParams/fields/variants/dataType/element).
    *
    * Handles line breaks and proper JSON escaping/unescaping.
    */
  def extractJsonParam(chirrtl: String, intrinsicName: String, paramName: String): Seq[ujson.Value] = {
    val pattern = f"""circt_debug_$intrinsicName.*?$paramName\\s*=\\s*"((?:[^"\\\\]|\\\\.)*)"""".r
    pattern
      .findAllMatchIn(chirrtl)
      .map { m =>
        val jsonStr = m.group(1).replaceAll("\\\\\"", "\"").replaceAll("\\\\\\\\", "\\\\")
        ujson.read(jsonStr)
      }
      .toSeq
  }

  /** Extracts StringParam values.
    *
    * Handles line breaks and properly extracts quoted string values.
    */
  def extractStringParam(chirrtl: String, intrinsicName: String, paramName: String): Seq[String] = {
    val pattern = f"""circt_debug_$intrinsicName.*?$paramName\\s*=\\s*"([^"]*)"""".r
    pattern.findAllMatchIn(chirrtl).map(_.group(1)).toSeq
  }

  /** Extracts all parameters from a single intrinsic line/call.
    * Returns a map of parameter name to string value (for string/JSON params) or number for ints.
    *
    * This handles both single-line and multi-line intrinsic calls.
    * The intrinsic call starts with "intrinsic(circt_debug_X" and ends with ")".
    */
  def extractIntrinsicParams(line: String): Map[String, Either[String, Double]] = {
    val result = mutable.LinkedHashMap[String, Either[String, Double]]()

    // Patterns for different parameter types
    val stringParamPattern = """(\w+)\s*=\s*"([^"\\]*(?:\\.[^"\\]*)*)"""".r
    val intParamPattern = """(\w+)\s*=\s*(-?\d+(?:L)?)""".r

    stringParamPattern.findAllMatchIn(line).foreach { m =>
      result(m.group(1)) = Left(m.group(2))
    }

    intParamPattern.findAllMatchIn(line).foreach { m =>
      val value = m.group(2).replaceAll("L", "").toDouble
      result(m.group(1)) = Right(value)
    }

    result.toMap
  }

  /** Splits CHIRRTL text into individual intrinsic call blocks.
    * Handles multi-line intrinsic calls where the call spans multiple lines.
    */
  def splitIntrinsics(chirrtl: String): Seq[String] = {
    val result = mutable.ArrayBuffer[String]()
    var i = 0
    val chars = chirrtl

    while (i < chars.length) {
      // Look for "intrinsic("
      if (chars.startsWith("intrinsic(", i)) {
        val start = i
        var depth = 1
        var j = i + "intrinsic(".length

        // Find matching closing parenthesis, accounting for nested parens in JSON strings
        var inString = false
        var escapeNext = false

        while (j < chars.length && depth > 0) {
          val c = chars.charAt(j)

          if (escapeNext) {
            escapeNext = false
          } else if (c == '\\') {
            escapeNext = true
          } else if (c == '"') {
            inString = !inString
          } else if (!inString) {
            if (c == '(') depth += 1
            else if (c == ')') depth -= 1
          }

          j += 1
        }

        if (depth == 0) {
          result += chars.substring(start, j)
          i = j
        } else {
          // Couldn't find closing paren, skip this line
          i += 1
        }
      } else {
        i += 1
      }
    }

    result.toSeq
  }
}
