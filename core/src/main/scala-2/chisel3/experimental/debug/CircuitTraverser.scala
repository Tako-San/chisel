package chisel3.experimental.debug

import chisel3._
import chisel3.internal.Builder
import chisel3.internal.HasId
import chisel3.internal.firrtl.ir._
import chisel3.experimental.{fromStringToStringParam, BaseModule, SourceInfo}
import chisel3.reflect.DataMirror
import chisel3.MemBase
import scala.reflect.runtime.universe._
import scala.annotation.nowarn
import scala.collection.mutable

object CircuitTraverser {

  private val annotatedTargets = new mutable.HashSet[Any]()

  private def shouldAnnotate(target: Any): Boolean = {
    if (annotatedTargets.contains(target)) {
      false
    } else {
      annotatedTargets.add(target)
      true
    }
  }

  def captureCircuit(root: RawModule): Unit = {
    // Do NOT reset annotatedTargets - this prevents duplicates across multiple calls
    // Process the module at Chisel level
    // This is called when the module construction is complete
    root match {
      case m: RawModule =>
        processModule(m)
        // Recursively process all submodules
        processSubmodules(m)
      case _ =>
    }
  }

  // Process a single module
  private def processModule(module: RawModule): Unit = {
    // Process IO ports
    processIOPorts(module)
    // Process internal data (registers, wires, memories)
    processInternalData(module)
  }

  // Process IO ports using the IO bundle approach
  private def processIOPorts(module: RawModule): Unit = {
    val mirror = scala.reflect.runtime.universe.runtimeMirror(getClass.getClassLoader)
    val instanceMirror = mirror.reflect(module.asInstanceOf[AnyRef])
    val tpe = mirror.classSymbol(module.getClass).toType

    // Process the IO bundle if it exists
    val ioSymbol = tpe.member(TermName("io"))
    if (ioSymbol != NoSymbol) {
      try {
        val ioValue = instanceMirror.reflectField(ioSymbol.asTerm).get
        if (ioValue.isInstanceOf[Data]) {
          val io = ioValue.asInstanceOf[Data]
          // Process the IO bundle to get port info
          processIOPortData(io, "io", parentDirection = None)
        }
      } catch {
        case _: Throwable =>
        // Skip IO processing errors
      }
    }

    // Also process individual IO ports that are not in an io bundle
    tpe.members.foreach { member =>
      val memberName = member.name.toString
      if (
        !memberName.startsWith("_") &&
        !memberName.contains("$") &&
        memberName != "impl" &&
        memberName != "_module" &&
        memberName != "implicitClock" &&
        memberName != "implicitReset" &&
        memberName != "io"
      ) {
        member match {
          case m: MethodSymbol @unchecked if m.isGetter =>
            try {
              val value = instanceMirror.reflectMethod(m.asMethod).apply()
              if (value.isInstanceOf[Data]) {
                val data = value.asInstanceOf[Data]
                if (DataMirror.isIO(data)) {
                  val direction = data.specifiedDirection match {
                    case SpecifiedDirection.Input  => "INPUT"
                    case SpecifiedDirection.Output => "OUTPUT"
                    case _                         => "UNKNOWN"
                  }
                  generatePortInfoIntrinsic(data, memberName, direction)
                  // Process bundle elements recursively
                  if (data.isInstanceOf[Record]) {
                    processRecordFieldsForIO(data.asInstanceOf[Record], memberName, direction)
                  }
                }
              }
            } catch {
              case _: Throwable =>
              // Skip errors
            }
          case t: TermSymbol @unchecked if t.isVal && !t.isMethod =>
            try {
              val value = instanceMirror.reflectField(t.asTerm).get
              if (value.isInstanceOf[Data]) {
                val data = value.asInstanceOf[Data]
                if (DataMirror.isIO(data)) {
                  val direction = data.specifiedDirection match {
                    case SpecifiedDirection.Input  => "INPUT"
                    case SpecifiedDirection.Output => "OUTPUT"
                    case _                         => "UNKNOWN"
                  }
                  generatePortInfoIntrinsic(data, memberName, direction)
                  // Process bundle elements recursively
                  if (data.isInstanceOf[Record]) {
                    processRecordFieldsForIO(data.asInstanceOf[Record], memberName, direction)
                  }
                }
              }
            } catch {
              case _: Throwable =>
              // Skip errors
            }
          case _ =>
        }
      }
    }
  }

  // Process IO port Data (bundle or scalar)
  private def processIOPortData(data: Data, prefix: String, parentDirection: Option[String]): Unit = {
    val direction = parentDirection.getOrElse {
      data.specifiedDirection match {
        case SpecifiedDirection.Input  => "INPUT"
        case SpecifiedDirection.Output => "OUTPUT"
        case _                         => "UNKNOWN"
      }
    }

    if (data.isInstanceOf[Record]) {
      // Recursively process bundle fields
      processRecordFieldsForIO(data.asInstanceOf[Record], prefix, direction)
    } else if (data.isInstanceOf[Vec[_]]) {
      // Process Vec elements
      val vec = data.asInstanceOf[Vec[_]]
      if (vec.nonEmpty) {
        val headElem = vec.sample_element.asInstanceOf[Data]
        vec.indices.foreach { idx =>
          try {
            val elem = vec.apply(idx).asInstanceOf[Data]
            generatePortInfoIntrinsic(elem, s"$prefix[$idx]", direction)
          } catch {
            case _: Throwable =>
              // Use head element type if idx access fails
              generatePortInfoIntrinsic(headElem, s"$prefix[$idx]", direction)
          }
        }
      } else {
        generatePortInfoIntrinsic(data, prefix, direction)
      }
    } else {
      // Scalar port
      generatePortInfoIntrinsic(data, prefix, direction)
    }
  }

  // Process Record fields for IO and generate port_info intrinsics
  private def processRecordFieldsForIO(record: Record, prefix: String, parentDirection: String): Unit = {
    record.elements.foreach { case (name, element) =>
      val fullName = if (prefix.isEmpty) name else s"$prefix.$name"

      // Determine direction for this element
      // In Chisel, Flip changes direction: Flip(Input) = OUTPUT from module's perspective
      // But for IO ports, we read the specifiedDirection directly
      val direction = element.specifiedDirection match {
        case SpecifiedDirection.Input  => "INPUT"
        case SpecifiedDirection.Output => "OUTPUT"
        case _                         => parentDirection // Default to parent direction
      }

      element match {
        case nestedRecord: Record =>
          processRecordFieldsForIO(nestedRecord, fullName, direction)
        case vec: Vec[_] =>
          if (vec.nonEmpty) {
            vec.indices.foreach { idx =>
              try {
                val elem = vec.apply(idx).asInstanceOf[Data]
                generatePortInfoIntrinsic(elem, s"$fullName[$idx]", direction)
              } catch {
                case _: Throwable =>
                  val headElem = vec.sample_element.asInstanceOf[Data]
                  generatePortInfoIntrinsic(headElem, s"$fullName[$idx]", direction)
              }
            }
          } else {
            generatePortInfoIntrinsic(element, fullName, direction)
          }
        case _ =>
          generatePortInfoIntrinsic(element, fullName, direction)
      }
    }
  }

  // Generate a single port_info intrinsic for a data element
  private def generatePortInfoIntrinsic(data: Data, name: String, direction: String): Unit = {
    if (!shouldAnnotate(data)) {
      return
    }
    val typeName = dataTypeName(data)
    Intrinsic("chisel.debug.port_info", "name" -> name, "direction" -> direction, "type" -> typeName)(data)
  }

  // Process internal data (registers, wires, etc.)
  private def processInternalData(module: RawModule): Unit = {
    val mirror = scala.reflect.runtime.universe.runtimeMirror(getClass.getClassLoader)
    val instanceMirror = mirror.reflect(module.asInstanceOf[AnyRef])
    val tpe = mirror.classSymbol(module.getClass).toType

    System.err.println(s"[CircuitTraverser DEBUG] ===== Processing module: ${module.getClass.getName} =====")

    // Process all module fields (registers, wires, etc.)
    tpe.members.foreach { member =>
      val memberName = member.name.toString
      if (
        !memberName.startsWith("_") &&
        !memberName.contains("$") &&
        memberName != "impl" &&
        memberName != "_module" &&
        memberName != "implicitClock" &&
        memberName != "implicitReset" &&
        memberName != "io"
      ) {
        System.err.println(s"[CircuitTraverser DEBUG] Found member: $memberName (${member.getClass.getSimpleName})")
        member match {
          case m: MethodSymbol @unchecked if m.isGetter =>
            try {
              val value = instanceMirror.reflectMethod(m.asMethod).apply()
              System.err.println(s"[CircuitTraverser DEBUG]   - Getter value: ${value.getClass.getName}")
              if (value.isInstanceOf[Data]) {
                val data = value.asInstanceOf[Data]
                // Skip IO ports (already processed)
                if (!DataMirror.isIO(data)) {
                  processDataWithName(data, memberName, isPort = false)
                }
              }
            } catch {
              case e: Throwable =>
                System.err.println(s"[CircuitTraverser DEBUG]   - Getter error: ${e.getMessage}")
              // Skip errors
            }
          case t: TermSymbol @unchecked if t.isVal && !t.isMethod =>
            try {
              val value = instanceMirror.reflectField(t.asTerm).get
              System.err.println(s"[CircuitTraverser DEBUG]   - Val value: ${value.getClass.getName}")
              // Check if this is a Data or a Memory (MemBase)
              if (value.isInstanceOf[Data]) {
                val data = value.asInstanceOf[Data]
                // Skip IO ports (already processed)
                if (!DataMirror.isIO(data)) {
                  processDataWithName(data, memberName, isPort = false)
                }
              } else if (value.getClass.getSimpleName.contains("Mem")) {
                // This is a memory, process it directly
                if (value.isInstanceOf[HasId]) {
                  val className = value.getClass.getSimpleName
                  val fullName = value.getClass.getName

                  val memKind = className match {
                    case name if name.contains("SyncReadMem") || name.contains("SMem") => "SyncReadMem"
                    case name if name.contains("SRAM")                                 => "SRAM"
                    case name if name.contains("Mem")                                  => "Mem"
                    case _                                                             => "Mem"
                  }

                  // Try to get the memory size (length) using reflection
                  val size =
                    try {
                      val mirror = scala.reflect.runtime.universe.runtimeMirror(getClass.getClassLoader)
                      val instanceMirror2 = mirror.reflect(value)
                      val lengthMethod = instanceMirror2.symbol.typeSignature.member(TermName("length"))
                      if (lengthMethod != NoSymbol) {
                        val length = instanceMirror2.reflectMethod(lengthMethod.asMethod).apply()
                        Some(BigInt(length.toString))
                      } else {
                        None
                      }
                    } catch {
                      case _: Throwable => None
                    }

                  System.err.println(
                    s"[CircuitTraverser DEBUG]   - Processed memory: $memberName, kind=$memKind, size=$size"
                  )

                  // Get the memory's HasId to extract its target
                  val memId = value.asInstanceOf[HasId]
                  val memTarget =
                    try {
                      memId.toTarget.serialize
                    } catch {
                      case _: Throwable => memberName // Fallback to field name
                    }

                  // Try to get memory type using cloneType
                  val innerType: Option[String] = try {
                    val mirror = scala.reflect.runtime.universe.runtimeMirror(getClass.getClassLoader)
                    val instanceMirror2 = mirror.reflect(value)
                    val cloneTypeMethod = instanceMirror2.symbol.typeSignature.member(TermName("cloneType"))
                    if (cloneTypeMethod != NoSymbol) {
                      val cloneTypeResult = instanceMirror2.reflectMethod(cloneTypeMethod.asMethod).apply()
                      if (cloneTypeResult.isInstanceOf[Data]) {
                        Some(dataTypeName(cloneTypeResult.asInstanceOf[Data]))
                      } else {
                        None
                      }
                    } else {
                      None
                    }
                  } catch {
                    case _: Throwable => None
                  }

                  // Try to extract depth from memory size option
                  val depth = size.map(_.toString).getOrElse("unknown")

                  // Push intrinsic directly using the memory's HasId (Node is an Arg type)
                  // Memories are MemBase which extends HasId, so Node(memId) is valid
                  val params = Seq.newBuilder[(String, Param)]
                  params += "kind" -> memKind
                  innerType.foreach { t => params += "inner_type" -> t }
                  params += "depth" -> depth

                  System.err.println(
                    s"[CircuitTraverser DEBUG]   - Annotating memory $memberName with intrinsic: chisel.debug.memory"
                  )
                  if (!shouldAnnotate(memId)) {
                    return
                  }
                  implicit val info: SourceInfo = SourceInfo.materializeFromStacktrace
                  Builder.pushCommand(
                    DefIntrinsic(
                      info,
                      "chisel.debug.memory",
                      Seq(Node(memId)),
                      params.result()
                    )
                  )

                  // Now annotate memory fields (for Bundle types)
                  innerType.foreach { _ =>
                    // Try to get the Data type to extract fields
                    try {
                      val mirror = scala.reflect.runtime.universe.runtimeMirror(getClass.getClassLoader)
                      val instanceMirror2 = mirror.reflect(value)
                      val cloneTypeMethod = instanceMirror2.symbol.typeSignature.member(TermName("cloneType"))
                      if (cloneTypeMethod != NoSymbol) {
                        val cloneTypeResult = instanceMirror2.reflectMethod(cloneTypeMethod.asMethod).apply()
                        if (cloneTypeResult.isInstanceOf[Data]) {
                          val innerData = cloneTypeResult.asInstanceOf[Data].cloneType
                          innerData match {
                            case record: Record =>
                              record.elements.foreach { case (fieldName, elem) =>
                                if (!shouldAnnotate(s"${memTarget}_field_$fieldName")) {
                                  return
                                }
                                implicit val info: SourceInfo = SourceInfo.materializeFromStacktrace
                                Builder.pushCommand(
                                  DefIntrinsic(
                                    info,
                                    "chisel.debug.memory_field",
                                    Seq(Node(memId)),
                                    Seq("parent" -> memTarget, "field" -> fieldName, "type" -> dataTypeName(elem))
                                  )
                                )
                              }
                            case _ =>
                            // Not a bundle, skip field annotations
                          }
                        }
                      }
                    } catch {
                      case _: Throwable =>
                      // Skip field annotation errors
                    }
                  }
                } else {
                  System.err.println(s"[CircuitTraverser DEBUG]   - Memory is not HasId: $memberName")
                }
              }
            } catch {
              case e: Throwable =>
                System.err.println(s"[CircuitTraverser DEBUG]   - Val error: ${e.getMessage}")
              // Skip errors
            }
          case _ =>
            System.err.println(s"[CircuitTraverser DEBUG]   - Other type: ${member.getClass.getName}")
        }
      }
    }
  }

  // Recursively process all submodules to capture hierarchy
  private def processSubmodules(module: RawModule): Unit = {
    val mirror = scala.reflect.runtime.universe.runtimeMirror(getClass.getClassLoader)
    val instanceMirror = mirror.reflect(module.asInstanceOf[AnyRef])
    val tpe = mirror.classSymbol(module.getClass).toType

    tpe.members.foreach { member =>
      val memberName = member.name.toString
      if (
        !memberName.startsWith("_") &&
        !memberName.contains("$") &&
        memberName != "impl" &&
        memberName != "_module" &&
        memberName != "implicitClock" &&
        memberName != "implicitReset" &&
        memberName != "io"
      ) {
        member match {
          case m: MethodSymbol @unchecked if m.isGetter =>
            try {
              val value = instanceMirror.reflectMethod(m.asMethod).apply()
              // Check for BaseModule or RawModule
              if (value.isInstanceOf[RawModule] || value.isInstanceOf[BaseModule]) {
                val submodule = value.asInstanceOf[RawModule]
                // Process this submodule and its children
                processModule(submodule)
                processSubmodules(submodule)
              }
            } catch {
              case _: Throwable =>
              // Skip errors
            }
          case t: TermSymbol @unchecked if t.isVal && !t.isMethod =>
            try {
              val value = instanceMirror.reflectField(t.asTerm).get
              // Check for BaseModule or RawModule
              if (value.isInstanceOf[RawModule] || value.isInstanceOf[BaseModule]) {
                val submodule = value.asInstanceOf[RawModule]
                // Process this submodule and its children
                processModule(submodule)
                processSubmodules(submodule)
              }
            } catch {
              case _: Throwable =>
              // Skip errors
            }
          case _ =>
        }
      }
    }
  }

  // Helper function to recursively collect commands
  private def collect[A](commands: Seq[Command])(f: PartialFunction[Command, A]): Seq[A] = {
    commands.flatMap {
      case cmd @ When(_, _, ifRegion, elseRegion) =>
        val head =
          if (f.isDefinedAt(cmd))
            Seq(f(cmd))
          else
            Seq.empty
        head ++ collect(ifRegion)(f) ++ collect(elseRegion)(f)
      case cmd @ LayerBlock(_, _, region) =>
        val head = f.lift(cmd).toSeq
        head ++ collect(region)(f)
      case cmd @ Placeholder(_, block) =>
        collect(block)(f)
      case cmd if f.isDefinedAt(cmd) => Some(f(cmd))
      case _                         => None
    }
  }

  // Process memories by examining the current block's commands
  private def processMemoriesFromCIRCT(module: RawModule): Unit = {
    try {
      Builder.currentBlock match {
        case Some(block) =>
          val commands = block.getCommands()
          System.err.println(s"[CircuitTraverser DEBUG] processMemoriesFromCIRCT: Found ${commands.length} commands")

          // Process all memories found in the current block
          val allMemories = collect(commands) {
            case r: DefSeqMemory => (r, "SyncReadMem")
            case r: DefMemory    => (r, "Mem")
          }

          System.err.println(s"[CircuitTraverser DEBUG] processMemoriesFromCIRCT: Found ${allMemories.length} memories")

          // Annotate each memory
          allMemories.foreach { case (defMem, kind) =>
            System.err.println(s"[CircuitTraverser DEBUG] processMemoriesFromCIRCT: Processing memory of kind $kind")
            // defMem contains both: id: HasId and t: Data
            // id is the MemBase (which is HasId), t is the Data type
            val memId = defMem.id // This is HasId (MemBase)

            // Get size and type from DefSeqMemory/DefMemory
            val (size, memData) = defMem match {
              case d: DefSeqMemory => (d.size, d.t)
              case d: DefMemory    => (d.size, d.t)
              case _ => return
            }

            // Get type name from the data field
            val innerType = dataTypeName(memData)
            System.err.println(s"[CircuitTraverser DEBUG] processMemoriesFromCIRCT: innerType=$innerType, size=$size")

            // Push intrinsic directly using the memory's HasId (Node is an Arg type)
            val params = Seq.newBuilder[(String, Param)]
            params += "kind" -> kind
            params += "inner_type" -> innerType
            params += "depth" -> size.toString

            if (!shouldAnnotate(memId)) {
              return
            }
            implicit val info: SourceInfo = SourceInfo.materializeFromStacktrace
            System.err.println(s"[CircuitTraverser DEBUG] processMemoriesFromCIRCT: Pushing DefIntrinsic for memory")
            Builder.pushCommand(
              DefIntrinsic(
                info,
                "chisel.debug.memory",
                Seq(Node(memId)),
                params.result()
              )
            )

            // Annotate memory fields (for Bundle types)
            memData match {
              case record: Record =>
                val memTarget =
                  try {
                    memId.toTarget.serialize
                  } catch {
                    case _: Throwable => kind
                  }
                record.elements.foreach { case (fieldName, elem) =>
                  if (!shouldAnnotate(s"${memTarget}_$fieldName")) {
                    return
                  }
                  implicit val info: SourceInfo = SourceInfo.materializeFromStacktrace
                  Builder.pushCommand(
                    DefIntrinsic(
                      info,
                      "chisel.debug.memory_field",
                      Seq(Node(memId)),
                      Seq("field" -> fieldName, "type" -> dataTypeName(elem))
                    )
                  )
                }
              case _ =>
              // Not a bundle, skip field annotations
            }
          }

        case None =>
          System.err.println(s"[CircuitTraverser DEBUG] processMemoriesFromCIRCT: No current block available")
      }
    } catch {
      case e: Throwable =>
        System.err.println(s"[CircuitTraverser DEBUG] processMemoriesFromCIRCT: Error - ${e.getMessage}")
        e.printStackTrace()
    }
  }

  private def processDataWithName(data: Data, fieldName: String, isPort: Boolean): Unit = {
    // Skip if this is an already processed port
    if (isPort) {
      return
    }

    // Check if this is a memory (SyncReadMem, Mem, or SRAM)
    // Use class name to detect since MemBase is not a subtype of Data
    val className = data.getClass.getSimpleName
    val fullName = data.getClass.getName

    // Debug logging
    System.err.println(
      s"[CircuitTraverser DEBUG] processDataWithName: fieldName=$fieldName, className=$className, fullName=$fullName"
    )

    val isMemory = className.contains("SyncReadMem") ||
      className.contains("SMem") ||
      fullName.contains("chisel3.Mem")

    if (isMemory) {
      System.err.println(s"[CircuitTraverser DEBUG] *** FOUND MEMORY: $fieldName of type $className")
      // Determine the exact memory type
      val memKind = className match {
        case name if name.contains("SyncReadMem") || name.contains("SMem") => "SyncReadMem"
        case name if name.contains("SRAM")                                 => "SRAM"
        case name if name.contains("Mem")                                  => "Mem"
        case _                                                             => "Mem"
      }

      // Try to get the memory size (length) using reflection
      val size =
        try {
          val mirror = scala.reflect.runtime.universe.runtimeMirror(getClass.getClassLoader)
          val instanceMirror = mirror.reflect(data.asInstanceOf[AnyRef])
          val lengthMethod = instanceMirror.symbol.typeSignature.member(TermName("length"))
          if (lengthMethod != NoSymbol) {
            val length = instanceMirror.reflectMethod(lengthMethod.asMethod).apply()
            Some(BigInt(length.toString))
          } else {
            None
          }
        } catch {
          case _: Throwable => None
        }

      // Process memory annotation
      processMemory(data, memKind, size)
    } else {
      // For non-memory data (regs, wires), create source_info intrinsic
      val info = ReflectionExtractor.extract(data)
      if (info.fields.nonEmpty) {
        info.fields.foreach { field =>
          if (!shouldAnnotate(s"${data}_${field.name}")) {
            return
          }
          Intrinsic(
            "chisel.debug.source_info",
            "field_name" -> field.name,
            "field_type" -> field.typeName,
            "field_value" -> field.value
          )(data)
        }
      }
    }
  }

  private def processMemory(data: Data, memKind: String, memorySize: Option[BigInt]): Unit = {
    // Get the actual target name for the memory
    val target =
      try {
        data.toTarget.serialize
      } catch {
        case _: Throwable => "unknown"
      }
    processMemoryWithTarget(data, memKind, memorySize, target)
  }

  private def processMemoryWithTarget(data: Data, memKind: String, memorySize: Option[BigInt], target: String): Unit = {
    // Try to extract depth from memory size option
    val depth =
      try {
        memorySize match {
          case Some(size) => size.toString
          case None       => inferMemoryDepth(data)
        }
      } catch {
        case _: Throwable => "unknown"
      }

    // Get the inner type from cloneType
    val innerType =
      try {
        dataTypeName(data.cloneType)
      } catch {
        case _: Throwable => data.getClass.getSimpleName
      }

    // Main memory annotation
    if (!shouldAnnotate(data)) {
      return
    }
    Intrinsic(
      "chisel.debug.memory",
      "kind" -> memKind,
      "inner_type" -> innerType,
      "depth" -> depth
    )(data)

    // Annotate inner type structure (for Bundle/Vec elements)
    val innerData = data.cloneType
    innerData match {
      case record: Record =>
        record.elements.foreach { case (name, elem) =>
          if (!shouldAnnotate(s"${target}_$name")) {
            return
          }
          Intrinsic(
            "chisel.debug.memory_field",
            "parent" -> target,
            "field" -> name,
            "type" -> dataTypeName(elem)
          )(data)
        }
      case vec: Vec[_] if vec.nonEmpty =>
        val headElem = vec.head
        vec.indices.foreach { idx =>
          if (!shouldAnnotate(s"${target}_$idx")) {
            return
          }
          Intrinsic(
            "chisel.debug.memory_field",
            "parent" -> target,
            "field" -> s"_$idx",
            "type" -> dataTypeName(headElem)
          )(data)
        }
      case _ =>
      // Not a bundle or vec, skip field annotations
    }
  }

  // Get simplified data type name similar to FIRRTL's representation
  private def dataTypeName(data: Data): String = data match {
    case _: Bool => "Bool"
    case u: UInt => s"UInt<${u.getWidth}>"
    case s: SInt => s"SInt<${s.getWidth}>"
    case r: Record =>
      r.className match {
        case null                 => r.getClass.getSimpleName
        case name if name.isEmpty => r.getClass.getSimpleName
        case name                 => name
      }
    case v: Vec[_] =>
      if (v.nonEmpty) s"Vec[${dataTypeName(v.head)}]"
      else "Vec"
    case _ => data.getClass.getSimpleName
  }

  // Infer memory depth from data type using reflection
  private def inferMemoryDepth(data: Data): String = {
    try {
      val mirror = scala.reflect.runtime.universe.runtimeMirror(getClass.getClassLoader)
      val instanceMirror = mirror.reflect(data.asInstanceOf[AnyRef])
      val sizeMethod = instanceMirror.symbol.info.member(TermName("size"))
      if (sizeMethod != NoSymbol) {
        val size = instanceMirror.reflectMethod(sizeMethod.asMethod).apply()
        size.toString
      } else {
        "unknown"
      }
    } catch {
      case _: Throwable => "unknown"
    }
  }
}
