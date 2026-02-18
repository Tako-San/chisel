// See LICENSE for license details.

package chisel3.debug

import chisel3.Data
import chisel3.experimental.SourceInfo
import scala.collection.concurrent.TrieMap

case class DebugEntry(
  data:         Data,
  src:          SourceInfo,
  debugName:    Option[String] = None,
  instanceName: Option[String] = None,
  pathName:     Option[String] = None,
  typeName:     Option[String] = None,
  paramsJson:   Option[String] = None
)

object DebugRegistry {
  private val _current = new scala.util.DynamicVariable[Option[TrieMap[String, DebugEntry]]](None)

  // NOTE: IDs are stable within a single elaboration run but not across JVM restarts.
  // For cross-run stable IDs, use explicit name= parameter in debug().
  private val globalCallCounter = new java.util.concurrent.atomic.AtomicLong(0)

  /** Returns the next global call sequence number */
  def nextCallId(): Long = globalCallCounter.incrementAndGet()

  def withFreshRegistry[A](body: => A): (A, Map[String, DebugEntry]) = {
    val map = TrieMap.empty[String, DebugEntry]
    val result = _current.withValue(Some(map))(body)
    (result, map.toMap)
  }

  def register(id: String, data: Data, debugName: Option[String])(implicit src: SourceInfo): Unit = {
    _current.value match {
      case Some(registry) =>
        // Capture instanceName safely during elaboration when Builder context is available
        val capturedInstanceName =
          try Some(data.instanceName)
          catch { case _: Exception => None }
        registry(id) = DebugEntry(data, src, debugName, capturedInstanceName)
      case None => () // Silently ignore calls outside context (mods without debug pipeline)
    }
  }

  def get(id: String): Option[DebugEntry] =
    _current.value.flatMap(_.get(id))

  def update(id: String, entry: DebugEntry): Unit = {
    _current.value match {
      case Some(registry) => registry(id) = entry
      case None           => () // Silently ignore calls outside context
    }
  }

  def entries: Seq[(String, DebugEntry)] =
    _current.value.map(_.toSeq.sortBy(_._1)).getOrElse(Seq.empty)
}
