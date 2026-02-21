// SPDX-License-Identifier: Apache-2.0

package chisel3.internal

import chisel3._
import chisel3.SpecifiedDirection
import chisel3.EnumType
import chisel3.experimental.SourceInfo
import chisel3.internal.Builder
import chisel3.internal.Builder.pushCommand
import chisel3.internal.firrtl.ir.{DefIntrinsic, Node}
import chisel3.internal.binding._
import scala.util.Try
import ujson._

/** Emits `circt_debug_*` intrinsics with JSON metadata for Data objects during
  * module elaboration.
  *
  * Called from `RawModule.generateComponent()` after all names are finalized
  * but before the module is closed (`_closed = true`), so `pushCommand` works.
  *
  * The JSON payload merges two sources of information:
  *   - '''Compile-time''' (from the compiler plugin via Builder.debugTypeInfo):
  *     className, constructor params, source location.
  *   - '''Runtime''' (from the Data object during elaboration):
  *     width, binding kind, aggregate structure (Bundle fields / Vec dims).
  *
  * Downstream consumers (Tywaves, HGDB, hw-debug-info.json export pass)
  * read the JSON from the intrinsic's "info" parameter.
  *
  * JSON payload schema (stable contract for downstream tools):
  *
  *  - For signal typetags (`circt_debug_typetag`), `info` is:
  *    {
  *      "className": String,             // required, Chisel-level type name (Bool, UInt, MyBundle, ...)
  *      "width":     String,             // required, bit width or "inferred"
  *      "binding":   String,             // required, one of: "port" | "reg" | "wire" | "memport" | "unknown"
  *      "direction": String,             // required, one of: "input" | "output" | "flip" | "none"
  *      "sourceLoc": String,             // required, "file.scala:line" or "unknown"
  *      "params":    String,             // optional, human-readable ctor params summary
  *      "fields": {                       // optional, for Bundle/Record
  *        "<fieldName>": {
  *          "type":      String,         // required
  *          "width":     String,         // required
  *          "direction": String,         // required
  *          ... nested "fields"/"vecLength"/"element" recursively ...
  *        },
  *        ...
  *      },
  *      "vecLength": Number,             // optional, for Vec
  *      "element": { ... },              // optional, for Vec element type
  *      "enumDef": {                     // optional, for ChiselEnum-backed Data
  *        "name":     String,            // required
  *        "variants": {                  // required
  *          "<index>": String            // numeric index → variant name
  *        }
  *      }
  *    }
  *
  *  - For module-level info (`circt_debug_moduleinfo`), `info` is:
  *    {
  *      "kind":       "module",          // required, literal
  *      "className":  String,           // required, Scala class name
  *      "name":       String,           // required, elaborated RTL module name
  *      "ctorParams": {                 // optional, best-effort map of primitive ctor params
  *        "<param>": <JSON primitive>   // number, bool, or string
  *      }
  *    }
  *
  * Downstream consumers (Tywaves, HGDB, hw-debug-info.json export) may rely on
  * required fields being present and on optional fields being absent instead
  * of null when not applicable.
  *
  * Future work:
  *  - A CIRCT pass can lower these intrinsics into dbg dialect ops
  *    (e.g., dbg.moduleinfo, dbg.vardecl, dbg.struct) using the JSON payload
  *    as a bridge. This object intentionally does not depend on dbg dialect
  *    so that it can evolve independently of CIRCT.
  */
private[chisel3] object DebugTypeEmitter {

  /** FIRRTL intrinsic names. A matching CIRCT pass must exist or firtool
    * will error on unknown intrinsic unless --allow-unrecognized-intrinsic is used.
    */
  val TypeTagIntrinsic = "circt_debug_typetag"
  val ModuleInfoIntrinsic = "circt_debug_moduleinfo"

  private val MaxParamStringLength = 40

  // --- shared helpers ---

  /** Map Data binding to a short string key, or None if not user-visible. */
  private def bindingKind(data: Data): Option[String] =
    data.topBindingOpt.collect {
      case _: PortBinding       => "port"
      case _: RegBinding        => "reg"
      case _: WireBinding       => "wire"
      case _: MemoryPortBinding => "memport"
    }

  /** Map SpecifiedDirection to a JSON-friendly string. */
  private def directionStr(data: Data): String =
    data.specifiedDirection match {
      case SpecifiedDirection.Input       => "input"
      case SpecifiedDirection.Output      => "output"
      case SpecifiedDirection.Flip        => "flip"
      case SpecifiedDirection.Unspecified => "none"
    }

  /** Width as string: numeric or "inferred". */
  private def widthStr(data: Data): String =
    data.widthOption.map(_.toString).getOrElse("inferred")

  // --- public entry point ---

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
    emitModuleInfo()

    val dataItems = ids.collect {
      case d: Data if d.isSynthesizable && isInteresting(d) => d
    }
    dataItems.foreach(emitForData)
  }

  // --- module metadata ---

  private def emitModuleInfo()(implicit si: SourceInfo): Unit =
    Builder.currentModule.foreach { mod =>
      val modName = mod.name
      val ctOpt = Builder.getDebugType(mod)

      val className = ctOpt.map(_.className).getOrElse(mod.getClass.getSimpleName.stripSuffix("$"))

      val obj = ujson.Obj(
        "kind" -> ujson.Str("module"),
        "className" -> ujson.Str(className),
        "name" -> ujson.Str(modName)
      )

      // Use structured ctorParams from plugin, if available
      ctOpt.flatMap(_.ctorParamJson).foreach { jsonStr =>
        Try(ujson.read(jsonStr)).toOption.foreach {
          case o: ujson.Obj => obj("ctorParams") = o
          case _ => () // ignore non-object payload
        }
      }

      pushCommand(
        DefIntrinsic(si, ModuleInfoIntrinsic, Seq.empty, Seq("info" -> StringParam(ujson.write(obj))))
      )
    }

  // --- filtering ---

  /** Only emit debug info for user-visible, named signals.
    * Skip intermediate op results (OpBinding), literals, etc.
    */
  private def isInteresting(data: Data): Boolean = bindingKind(data).isDefined

  // --- intrinsic emission ---

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
    // --- Param construction ---
    pushCommand(
      DefIntrinsic(
        si,
        TypeTagIntrinsic,
        Seq(Node(data)),
        Seq("info" -> StringParam(json))
      )
    )
  }

  // --- JSON payload construction ---

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
    val ct = Builder.getDebugType(data)

    val className = ct
      .map(_.className)
      .getOrElse(data.getClass.getSimpleName.stripSuffix("$"))

    val params = ct.map(_.params).getOrElse("")
    val sourceLoc = ct
      .map(r => s"${r.sourceFile}:${r.sourceLine}")
      .getOrElse("unknown")

    val obj = ujson.Obj(
      "className" -> ujson.Str(className),
      "width" -> ujson.Str(widthStr(data)),
      "binding" -> ujson.Str(bindingKind(data).getOrElse("unknown")),
      "direction" -> ujson.Str(directionStr(data)),
      "sourceLoc" -> ujson.Str(sourceLoc)
    )

    if (params.nonEmpty) obj("params") = ujson.Str(params)

    for {
      pairs <- buildStructureJson(data)
      (key, value) <- pairs
    } obj.obj.put(key, value)

    buildEnumJson(data).foreach { enumObj => obj("enumDef") = enumObj }

    ujson.write(obj)
  }

  // --- enum variant map ---

  /** Build enum definition object, or None for non-enum types. */
  private def buildEnumJson(data: Data): Option[ujson.Obj] = data match {
    case e: EnumType =>
      Try {
        val factory = e.factory
        val enumName = factory.getClass.getSimpleName.stripSuffix("$")
        val allMethod = factory.getClass.getMethod("all")
        val allValues = allMethod.invoke(factory).asInstanceOf[Seq[EnumType]]

        val variants = ujson.Obj()
        allValues.zipWithIndex.foreach { case (v, i) =>
          val raw = Try(v.toString).getOrElse(s"_$i")
          val vName = raw.indexOf('=') match {
            case -1  => raw
            case idx => raw.substring(idx + 1).stripSuffix(")")
          }
          variants(i.toString) = ujson.Str(vName)
        }

        ujson.Obj("name" -> ujson.Str(enumName), "variants" -> variants)
      }.toOption

    case _ => None
  }

  // --- aggregate structure ---

  /** Build aggregate structure fields, or None for leaf types. */
  private def buildStructureJson(data: Data): Option[Seq[(String, ujson.Value)]] =
    data match {
      case r: Record =>
        val fields = ujson.Obj()
        r.elements.foreach { case (name, fieldData) =>
          val fObj = ujson.Obj(
            "type" -> ujson.Str(fieldData.getClass.getSimpleName.stripSuffix("$")),
            "width" -> ujson.Str(widthStr(fieldData)),
            "direction" -> ujson.Str(directionStr(fieldData))
          )
          // Recurse into nested aggregates
          for {
            pairs <- buildStructureJson(fieldData)
            (key, value) <- pairs
          } fObj.obj.put(key, value)

          fields(name) = fObj
        }
        if (fields.obj.nonEmpty) Some(Seq("fields" -> fields)) else None

      case v: Vec[_] if v.length > 0 =>
        val elem = v.sample_element
        val elemObj = ujson.Obj(
          "type" -> ujson.Str(elem.getClass.getSimpleName.stripSuffix("$")),
          "width" -> ujson.Str(widthStr(elem))
        )
        for {
          pairs <- buildStructureJson(elem)
          (key, value) <- pairs
        } elemObj.obj.put(key, value)
        Some(
          Seq(
            "vecLength" -> ujson.Num(v.length),
            "element" -> elemObj
          )
        )

      case v: Vec[_] =>
        Some(Seq("vecLength" -> ujson.Num(0)))

      case _ => None
    }
}
