// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

object DebugMetaTestUtils {

  /** Extract and parse all JSON info-payloads from intrinsic calls in CHIRRTL output.
    *
    * Robust to CHIRRTL serialization order changes: finds `info="..."` within
    * the intrinsic call block regardless of parameter position.
    */
  def extractPayloads(chirrtl: String, intrinsicName: String): Seq[ujson.Value] = {
    // Match intrinsic lines containing the given name
    val lines = chirrtl.split("\n").filter(_.contains(s"intrinsic($intrinsicName"))

    // Within each line, find info="..." regardless of position
    // Handles escaped quotes inside the JSON string
    val infoPattern = """[<,]\s*info\s*=\s*"((?:[^"\\]|\\.)*)"""".r

    lines.flatMap { line =>
      infoPattern.findFirstMatchIn(line).flatMap { m =>
        val rawPayload = m.group(1)
        // Unescape the CHIRRTL string escaping layer
        val unescaped = unescapeChirrtlString(rawPayload)
        scala.util.Try(ujson.read(unescaped)).toOption.orElse {
          System.err.println(
            s"[DebugMetaTestUtils] Failed to parse JSON from $intrinsicName: $rawPayload"
          )
          None
        }
      }
    }.toSeq
  }

  /** Single-pass unescape for CHIRRTL string literals.
    * Handles: \\, \", \n, \t, \r, and any other \x sequence (preserves backslash).
    */
  private def unescapeChirrtlString(s: String): String = {
    val sb = new StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
      if (s(i) == '\\' && i + 1 < s.length) {
        s(i + 1) match {
          case '\\' => sb.append('\\'); i += 2
          case '"'  => sb.append('"'); i += 2
          case 'n'  => sb.append('\n'); i += 2
          case 't'  => sb.append('\t'); i += 2
          case 'r'  => sb.append('\r'); i += 2
          case c    => sb.append('\\'); sb.append(c); i += 2
        }
      } else {
        sb.append(s(i))
        i += 1
      }
    }
    sb.toString()
  }
}
