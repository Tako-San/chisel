// See LICENSE for license details.

package chisel3

import chisel3.internal.firrtl.ir.Command
import scala.reflect.runtime.universe._

import chisel3.internal.Builder

/**
 * IR Traverser - provides access to module commands for debug purposes.
 * This allows the debug capture mechanism to traverse the IR and capture wire/reg/node definitions.
 */
object IRTraverser {

  /**
   * Get all commands from a RawModule's command list.
   * This must be called after the module is closed.
   * Uses reflection to access the protected getCommands method.
   */
  def getCommands(module: RawModule): Seq[Command] = {
    val mirror = runtimeMirror(module.getClass.getClassLoader)
    val instanceMirror = mirror.reflect(module)

    // Get the _body field (a Block) to access getAllCommands which includes secret commands
    val bodyFieldSym = instanceMirror.symbol.typeSignature.member(TermName("_body"))
    if (bodyFieldSym == NoSymbol || !bodyFieldSym.isTerm) {
      return Seq.empty
    }
    val bodyFieldSymbol = bodyFieldSym.asTerm
    val bodyValue = instanceMirror.reflectField(bodyFieldSymbol).get

    // If body is null, try to get from component
    val targetBlock = if (bodyValue == null) {
      // Try to get the component which contains the module's commands
      try {
        val componentField = instanceMirror.symbol.typeSignature.member(TermName("_component"))
        if (componentField.isTerm) {
          val component = instanceMirror.reflectField(componentField.asTerm).get
          if (component != null) {
            // This is a DefModule or similar, get its body if it exists
            val bodyMethod = component.getClass.getMethod("body")
            bodyMethod.invoke(component).asInstanceOf[chisel3.internal.firrtl.ir.Block]
          } else null
        } else null
      } catch {
        case _: Exception => null
      }
    } else bodyValue

    if (targetBlock == null) return Seq.empty

    // Call getAllCommands on the Block to get both regular and secret commands
    val bodyMirror = mirror.reflect(targetBlock)
    val getAllCommandsMethodSym = bodyMirror.symbol.typeSignature.member(TermName("getAllCommands"))
    if (getAllCommandsMethodSym == NoSymbol || !getAllCommandsMethodSym.isMethod) {
      return Seq.empty
    }
    val result = bodyMirror.reflectMethod(getAllCommandsMethodSym.asMethod)()
    result.asInstanceOf[Seq[Command]]
  }

  /**
   * Add an intrinsic command to a RawModule's secret commands.
   * Secret commands can be added after the module block is closed.
   * Uses reflection to access the protected _body field and its addSecretCommand method.
   * For Module instances (which don't have their own body), adds to the parent container.
   */
  def addIntrinsicCommand(module: RawModule, cmd: Command): Unit = {
    val mirror = runtimeMirror(module.getClass.getClassLoader)
    val instanceMirror = mirror.reflect(module)

    // First check if this Module has a _body field (only RawModules do, Modules don't)
    val bodyFieldSym = instanceMirror.symbol.typeSignature.member(TermName("_body"))
    val bodyValue = if (bodyFieldSym.isTerm) {
      val bodyField = bodyFieldSym.asTerm
      try {
        instanceMirror.reflectField(bodyField).get
      } catch {
        case e: Exception =>
          null
      }
    } else null

    // For Modules (which have _body=null), we need to add to the parent container's body
    val targetBlock = if (bodyValue != null) {
      // This is a RawModule with a body
      bodyValue
    } else {
      // This is a Module - get the parent container (which is the elaborating module)
      Builder.currentModule match {
        case Some(parent: RawModule) =>
          // Get parent's _body
          val parentMirror = mirror.reflect(parent.asInstanceOf[AnyRef])
          val parentBodySym = parentMirror.symbol.typeSignature.member(TermName("_body"))
          if (parentBodySym.isTerm) {
            val parentBody = parentMirror.reflectField(parentBodySym.asTerm).get
            parentBody
          } else {
            return
          }
        case _ =>
          return
      }
    }

    if (targetBlock == null) {
      return
    }

    // Call addSecretCommand on the Block
    val bodyMirror = mirror.reflect(targetBlock)
    val addSecretMethodSym = bodyMirror.symbol.typeSignature.member(TermName("addSecretCommand"))
    if (addSecretMethodSym == NoSymbol) {
      return
    }
    if (!addSecretMethodSym.isMethod) {
      return
    }
    val addSecretMethodSymbol = addSecretMethodSym.asMethod

    bodyMirror.reflectMethod(addSecretMethodSymbol).apply(cmd)
  }
}
