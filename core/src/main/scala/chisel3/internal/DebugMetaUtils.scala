// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

private[chisel3] object DebugMetaUtils {
  private final val MaxCtorArgStringLength = 128

  /** Safely truncates a string to the specified maximum length (in Unicode code points).
    * If the string fits, returns it unchanged. Otherwise, truncates at a safe code point boundary
    * and appends "..." to indicate truncation.
    */
  def truncateString(s: String, maxLen: Int = MaxCtorArgStringLength): String = {
    if (s.length <= maxLen) s
    else {
      val end = s.offsetByCodePoints(0, s.codePointCount(0, maxLen))
      s.substring(0, end) + "..."
    }
  }

  /** Alias for truncateString with the default maximum length.
    * Used for constructor parameter string truncation.
    */
  def truncateCtorArgString(s: String): String = truncateString(s, MaxCtorArgStringLength)
}
