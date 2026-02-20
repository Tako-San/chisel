// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import chisel3._
import chisel3.experimental.SourceInfo
import chisel3.internal.Builder.pushCommand
import chisel3.internal.firrtl.ir.{DefIntrinsic, Node}
import chisel3.internal.binding._

/** Emits `circt_debug_typetag` intrinsic statements for Data objects during
  * module elaboration.
  *
  * Called from `RawModule.generateComponent()` after all names are finalized
  * but before the module is closed (`_closed = true`), so `pushCommand` works.
  *
  * Lifecycle position in generateComponent():
  * {{{
  *   1. for (id <- _ids) { nameId(id) }    — all names finalized
  *   2. evaluateAtModuleBodyEnd()           — user callbacks
  *   3. DebugTypeEmitter.emitForModule(...)  — *** WE EMIT HERE ***
  *   4. _closed = true                      — after this, pushCommand throws
  *   5. _body.close()                       — commands finalized
  * }}}
  *
  * The JSON payload merges two sources of information:
  *   - '''Compile-time''' (from the compiler plugin via Builder.debugTypeInfo):
  *     className, constructor params, source location.
  *   - '''Runtime''' (from the Data object during elaboration):
  *     width, binding kind, aggregate structure (Bundle fields / Vec dims).
  *
  * Downstream consumers (Tywaves, HGDB, hw-debug-info.json export pass)
  * read the JSON from the intrinsic's "info" parameter.
  */
private[chisel3] object DebugTypeEmitter {

  /** FIRRTL intrinsic name. A matching CIRCT pass must exist or firtool will
    * error on unknown intrinsic. For MVP testing, pass `--allow-unrecognized-intrinsic`
    * to firtool, or register `circt_debug_typetag` as a no-op intrinsic.
    */
  val IntrinsicName = "circt_debug_typetag"

  // ---------- public entry point ----------------------------------------

  /** Emit debug intrinsics for all interesting Data objects in the module.
    *
    * `ids` is passed directly from `generateComponent()` (the module's `_ids`
    * collection) so that we avoid access-modifier issues — `_ids` may be
    * `private` to `BaseModule` whereas this object lives in `chisel3.internal`.
    *
    * @param ids all HasId objects owned by the module
    * @param si  implicit SourceInfo (typically `UnlocatableSourceInfo`)
    */
  def emitForModule(ids: Iterable[HasId])(implicit si: SourceInfo): Unit = {
    val dataItems = ids.collect {
      case d: Data if d.isSynthesizable && isInteresting(d) => d
    }
    dataItems.foreach(emitForData)
  }

  // ---------- filtering --------------------------------------------------

  /** Only emit debug info for user-visible, named signals.
    * Skip intermediate op results (OpBinding), literals, etc.
    */
  private def isInteresting(data: Data): Boolean =
    data.topBindingOpt match {
      case Some(_: PortBinding)       => true
      case Some(_: RegBinding)        => true
      case Some(_: WireBinding)       => true
      case Some(_: MemoryPortBinding) => true
      case _                          => false
    }

  // ---------- intrinsic emission -----------------------------------------

  /** Emit a single `DefIntrinsic` command for one Data object.
    *
    * Uses `Builder.pushCommand` directly — this is exactly what
    * `chisel3.Intrinsic.apply` does (see Intrinsic.scala).
    *
    * The Data is passed as an intrinsic arg via `Node(data)` so that the
    * emitted FIRRTL carries a direct reference to the signal:
    * {{{
    *   intrinsic(circt_debug_typetag<info = "...">, my_signal)
    * }}}
    *
    * If aggregate references cause issues in firtool, change `Seq(Node(data))`
    * to `Seq.empty` — the signal can still be correlated via JSON payload.
    */
  private def emitForData(data: Data)(implicit si: SourceInfo): Unit = {
    val json = buildJsonPayload(data)

    // ---- Param construction ----
    // chisel3.StringParam(value: String) is the Chisel-level Param.
    // DefIntrinsic expects Seq[(String, chisel3.Param)], NOT firrtl.ir.Param.
    // The Converter maps chisel3.StringParam → firrtl.ir.StringParam(name, StringLit(...))
    //
    // FALLBACK: If StringParam is not in scope or expects different args,
    // try one of these alternatives (uncomment as needed):
    //   import chisel3.{StringParam => SP}  // explicit import
    //   RawParam(json)                      // avoids StringLit escaping
    //   IntParam(BigInt(0))                 // dummy param for testing pipeline
    pushCommand(
      DefIntrinsic(
        si,
        IntrinsicName,
        Seq(Node(data)),
        Seq("info" -> StringParam(json))
      )
    )
  }

  // ---------- JSON payload construction ----------------------------------

  /** Build the JSON payload string for a single Data.
    *
    * Example output for a Bundle port:
    * {{{
    * {
    *   "className": "MyBundle",
    *   "params": "width=8",
    *   "width": "24",
    *   "binding": "port",
    *   "sourceLoc": "MyModule.scala:42",
    *   "fields": {
    *     "a": {"type": "UInt", "width": "8"},
    *     "b": {"type": "SInt", "width": "16"}
    *   }
    * }
    * }}}
    */
  private def buildJsonPayload(data: Data): String = {
    // --- Compile-time info from plugin (may be absent) ---
    val ct = Builder.getDebugType(data)

    val className = ct
      .map(_.className)
      .getOrElse(data.getClass.getSimpleName.stripSuffix("$"))

    val params = ct.map(_.params).getOrElse("")

    val sourceLoc = ct
      .map(r => s"${r.sourceFile}:${r.sourceLine}")
      .getOrElse("unknown")

    // --- Runtime info from Data ---

    // CRITICAL: use widthOption, NOT getWidth.
    // getWidth throws java.lang.IllegalArgumentException on InferredWidth.
    val width = data.widthOption match {
      case Some(w) => w.toString
      case None    => "inferred"
    }

    val binding = data.topBindingOpt match {
      case Some(_: PortBinding)       => "port"
      case Some(_: RegBinding)        => "reg"
      case Some(_: WireBinding)       => "wire"
      case Some(_: MemoryPortBinding) => "memport"
      case _                          => "unknown"
    }

    // --- Assemble JSON ---
    // Using StringBuilder for efficiency — large modules may have
    // thousands of signals, each producing a JSON string.
    val sb = new StringBuilder(256)
    sb.append('{')
    sb.append(s""""className":"${esc(className)}"""")
    if (params.nonEmpty) {
      sb.append(s""","params":"${esc(params)}"""")
    }
    sb.append(s""","width":"$width"""")
    sb.append(s""","binding":"$binding"""")
    sb.append(s""","sourceLoc":"${esc(sourceLoc)}"""")

    val structure = buildStructureJson(data)
    if (structure.nonEmpty) {
      sb.append(',')
      sb.append(structure)
    }

    sb.append('}')
    sb.toString
  }

  // ---------- aggregate structure ----------------------------------------

  /** Build a JSON fragment describing the aggregate structure.
    *
    * Returns empty string for leaf (ground) types — UInt, SInt, Bool, Clock, etc.
    *
    * For Record (Bundle):
    * {{{
    *   "fields":{"a":{"type":"UInt","width":"8"},"b":{"type":"SInt","width":"16"}}
    * }}}
    *
    * For Vec:
    * {{{
    *   "vecLength":4,"element":{"type":"UInt","width":"8"}
    * }}}
    *
    * Recursion handles nested aggregates: `Vec(4, new MyBundle)`, `Bundle`-in-`Bundle`, etc.
    */
  private def buildStructureJson(data: Data): String = data match {
    case r: Record =>
      val fields = r.elements.map { case (fieldName, fieldData) =>
        val w = fieldData.widthOption.map(_.toString).getOrElse("inferred")
        val t = fieldData.getClass.getSimpleName.stripSuffix("$")

        // Recurse into nested aggregates
        val nested = fieldData match {
          case _: Record | _: Vec[_] =>
            val inner = buildStructureJson(fieldData)
            if (inner.nonEmpty) s",$inner" else ""
          case _ => ""
        }

        s""""${esc(fieldName)}":{"type":"${esc(t)}","width":"$w"$nested}"""
      }.mkString(",")

      if (fields.nonEmpty) s""""fields":{$fields}""" else ""

    case v: Vec[_] if v.length > 0 =>
      val elem = v.sample_element
      val et = elem.getClass.getSimpleName.stripSuffix("$")
      val ew = elem.widthOption.map(_.toString).getOrElse("inferred")

      // Recurse into nested aggregates inside Vec element
      val nested = elem match {
        case _: Record | _: Vec[_] =>
          val inner = buildStructureJson(elem)
          if (inner.nonEmpty) s",$inner" else ""
        case _ => ""
      }

      s""""vecLength":${v.length},"element":{"type":"${esc(et)}","width":"$ew"$nested}"""

    // Empty Vec — still record the length (0)
    case v: Vec[_] =>
      s""""vecLength":0"""

    // Leaf type — no structure to record
    case _ => ""
  }

  // ---------- JSON string escaping ---------------------------------------

  /** Escape characters special in JSON string values.
    *
    * Only handles the characters that might appear in Scala class names,
    * file paths, and parameter strings. Full JSON escaping (Unicode \uXXXX)
    * is not needed for our use case.
    */
  private def esc(s: String): String =
    s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
}
