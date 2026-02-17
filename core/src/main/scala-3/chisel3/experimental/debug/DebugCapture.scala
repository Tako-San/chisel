// SPDX-License-Identifier: Apache-2.0

package chisel3.experimental.debug

import chisel3.RawModule
import logger.LazyLogging

object DebugCapture extends LazyLogging {
  def capture(module: RawModule): Unit = {
    logger.warn("Debug capture is not yet supported in Scala 3")
  }
}
