package chisel3.debuginfo

import chisel3._
import chisel3.{Data, MemBase, Record, Vec, VecLike}
import chisel3.experimental.{BaseModule, Param}
import chisel3.internal.{HasId, NamedComponent}
import chisel3.internal.firrtl.ir._
import chisel3.properties.{DynamicObject, StaticObject}

import scala.collection.mutable

/** Represents a parameter in a class constructor with its name, type, and optional value.
  *
  * This is used to capture high-level Scala type information for debugging purposes.
  *
  * @param name The parameter name
  * @param typeName The Scala type as string
  * @param value Optional string representation of the value
  */
case class TypeParam(name: String, typeName: String, value: Option[String])

/** Debug information metadata that will be emitted via FIRRTL intrinsics.
  *
  * Unlike the old annotation-based approach, this uses Chisel's Intrinsic API
  * to embed metadata directly in FIRRTL, which CIRCT can consume via the debug dialect.
  *
  * @param targetName The name/identifier of the target element
  * @param typeName The Scala source type name
  * @param params Optional constructor parameters with values
  */
private[chisel3] case class DebugTypeInfo(
  targetName: String,
  typeName:   String,
  params:     Option[Seq[TypeParam]]
)

object DebugIntrinsicEmitter {

  // Track which targets have already been instrumented to avoid duplicates
  private val instrumentedTargets = new mutable.HashSet[String]()

  /** Emit a debug intrinsic for a Data element.
    *
    * This creates a FIRRTL intrinsic statement that carries type metadata.
    * CIRCT's firtool can lower this to `dbg.variable` ops.
    *
    * @param target The hardware Data to instrument
    * @param typeInfo Debug metadata to attach
    */
  def emitDebugInfo(target: Data, typeInfo: DebugTypeInfo): Unit = {
    val targetKey = target.toAbsoluteTarget.serialize
    
    if (!instrumentedTargets.contains(targetKey)) {
      instrumentedTargets.add(targetKey)
      
      // Encode params as a string parameter for the intrinsic
      val paramsStr = typeInfo.params match {
        case Some(ps) => ps.map(p => s"${p.name}:${p.typeName}=${p.value.getOrElse("?")}").mkString(",")
        case None => ""
      }
      
      // Emit intrinsic: circt.debug_type_info<"typeName", "params">(target)
      // This will be lowered by firtool to appropriate debug dialect ops
      Intrinsic("circt_debug_type_info",
        "type_name" -> Param(typeInfo.typeName),
        "params" -> Param(paramsStr),
        "target_name" -> Param(typeInfo.targetName)
      )(target)
    }
  }

  /** Generate debug info for a full Circuit.
    *
    * This traverses the circuit IR and emits debug intrinsics for relevant elements.
    */
  def generate(circuit: Circuit): Unit = {
    circuit.components.foreach(c => generate(c))
    instrumentedTargets.clear() // Clear for next elaboration
  }

  /** Generate debug info for a Component. */
  def generate(component: Component): Unit = component match {
    case ctx @ DefModule(id, name, public, layers, ports, cmds) =>
      instrumentModule(id)
      (ports ++ ctx.secretPorts).foreach(p => generate(p))
      (cmds ++ ctx.secretCommands).foreach(c => generate(c))
      
    case ctx @ DefBlackBox(id, name, ports, topDir, params) =>
      instrumentModule(id)
      (ports ++ ctx.secretPorts).foreach(p => generate(p))
      
    case ctx @ DefIntrinsicModule(id, name, ports, topDir, params) =>
      instrumentModule(id)
      (ports ++ ctx.secretPorts).foreach(p => generate(p))
      
    case ctx @ DefClass(id, name, ports, cmds) =>
      instrumentModule(id)
      (ports ++ ctx.secretPorts).foreach(p => generate(p))
      cmds.foreach(c => generate(c))
      
    case _ => // Ignore other component types
  }

  /** Generate debug info for a Port. */
  def generate(port: Port): Unit = {
    // Ports will be instrumented via their Data handle
  }

  /** Generate debug info for a Command. */
  def generate(command: Command): Unit = command match {
    case e: DefPrim[_] => () // Primitives don't need type info
    case e @ DefWire(info, id) => instrumentData(id)
    case e @ DefReg(info, id, clock) => instrumentData(id)
    case e @ DefRegInit(info, id, clock, reset, init) => instrumentData(id)
    case e @ DefMemory(info, id, t, size) => instrumentMemory(id, t, size)
    case e @ DefSeqMemory(info, id, t, size, ruw) => instrumentMemory(id, t, size)
    case e @ When(info, arg, ifRegion, elseRegion) =>
      ifRegion.foreach(generate)
      elseRegion.foreach(generate)
    case _ => () // Other commands don't carry relevant type info
  }

  /** Instrument a Data element with type info. */
  private def instrumentData(id: HasId): Unit = {
    id match {
      case data: Data =>
        val typeInfo = extractTypeInfo(data)
        emitDebugInfo(data, typeInfo)
      case _ => ()
    }
  }

  /** Instrument a Module with type info. */
  private def instrumentModule(id: HasId): Unit = {
    id match {
      case module: BaseModule =>
        val typeInfo = DebugTypeInfo(
          targetName = module.desiredName,
          typeName = module.getClass.getSimpleName,
          params = extractConstructorParams(module)
        )
        // Modules are special - we'd need to attach this differently
        // For now, store for potential later emission
      case _ => ()
    }
  }

  /** Instrument memory with type info. */
  private def instrumentMemory(id: HasId, elemType: Data, size: BigInt): Unit = {
    id match {
      case mem: MemBase[_] =>
        val typeInfo = DebugTypeInfo(
          targetName = mem.toString,
          typeName = s"Mem[${dataToTypeName(elemType)}][$size]",
          params = None
        )
        // Memory instrumentation would go here
      case _ => ()
    }
  }

  /** Extract type information from a Data element. */
  private def extractTypeInfo(data: Data): DebugTypeInfo = {
    val name = dataToTypeName(data)
    val params = data match {
      case _: chisel3.Bits | _: chisel3.Clock | _: chisel3.Reset =>
        None // Skip width parameters for basic types
      case _ => extractConstructorParams(data)
    }
    
    DebugTypeInfo(
      targetName = data.toString,
      typeName = name,
      params = params
    )
  }

  /** Pretty-print type name from Data. */
  private def dataToTypeName(data: Data): String = data match {
    case t: Vec[_] => t.toString.split(" ").last
    case t: Record =>
      t.topBindingOpt match {
        case Some(binding) => s"${t._bindingToString(binding)}[${t.className}]"
        case None => t.className
      }
    case t => t.toString.split(" ").last
  }

  /** Extract constructor parameters via Scala reflection.
    *
    * This uses runtime reflection to introspect the Scala class structure
    * and extract parameter names, types, and values.
    *
    * @param target Any Scala object
    * @return Optional sequence of TypeParam if parameters exist
    */
  def extractConstructorParams(target: Any): Option[Seq[TypeParam]] = {
    import scala.reflect.runtime.universe._
    import scala.reflect.api.{Mirror, TypeCreator, Universe}
    
    def getTypeTag[T](obj: T) = {
      val c = obj.getClass
      val mirror = runtimeMirror(c.getClassLoader)
      val sym = mirror.staticClass(c.getName)
      val tpe = sym.selfType
      TypeTag(
        mirror,
        new TypeCreator {
          def apply[U <: Universe with Singleton](m: Mirror[U]) =
            if (m eq mirror) tpe.asInstanceOf[U#Type]
            else throw new IllegalArgumentException(s"Type tag defined in $mirror cannot be migrated to other mirrors.")
        }
      )
    }
    
    try {
      val tt = getTypeTag(target)
      val im = runtimeMirror(target.getClass.getClassLoader).reflect(target)
      
      val params = tt.tpe.members.collect {
        case m: MethodSymbol if m.isConstructor =>
          m.paramLists.flatten.collect {
            case a if !a.name.toString.contains("$outer") =>
              val typeParts = a.info.toString.split("\\$")
              val typeName = (if (typeParts.length > 1) typeParts(1) else typeParts(0)).split("\\.").last
              val paramName = a.name.toString
              
              val value = try {
                val term = try {
                  tt.tpe.decl(a.name).asTerm.accessed.asTerm
                } catch {
                  case _: Throwable => a.asTerm
                }
                val valueTerm = im.reflectField(term).get
                Some(valueTerm.toString)
              } catch {
                case _: Throwable => None
              }
              
              TypeParam(paramName, typeName, value)
          }
      }.toList.flatten
      
      if (params.nonEmpty) Some(params) else None
    } catch {
      case _: Throwable => None // Silently fail if reflection doesn't work
    }
  }
}
