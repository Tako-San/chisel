// SPDX-License-Identifier: Apache-2.0

package chisel3.internal.plugin

import scala.collection.mutable
import scala.reflect.internal.Flags
import scala.reflect.io.AbstractFile
import scala.tools.nsc
import scala.tools.nsc.{Global, Phase}
import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.transform.TypingTransformers

import chisel3.internal.sourceinfo.SourceInfoFileResolver

// The component of the chisel plugin. Not sure exactly what the difference is between
//   a Plugin and a PluginComponent.
class ChiselComponent(val global: Global, arguments: ChiselPluginArguments)
    extends PluginComponent
    with TypingTransformers
    with ChiselOuterUtils {
  import global._
  val runsAfter:              List[String] = List[String]("typer")
  val phaseName:              String = "chiselcomponent"
  def newPhase(_prev: Phase): ChiselComponentPhase = new ChiselComponentPhase(_prev)
  class ChiselComponentPhase(prev: Phase) extends StdPhase(prev) {
    override def name: String = phaseName
    def apply(unit: CompilationUnit): Unit = {
      if (ChiselPlugin.runComponent(global, arguments)(unit)) {
        unit.body = new MyTypingTransformer(unit).transform(unit.body)
      }
    }
  }

  class MyTypingTransformer(unit: CompilationUnit) extends TypingTransformer(unit) with ChiselInnerUtils {

    private val MaxCtorParamLength = 128 // Limits JSON payload size to prevent overflow on cyclic structures

    private final val MaxAstTraversalDepth = 8 // Prevents stack overflow; typical constructor nesting <8 levels

    private def shouldMatchGen(bases: Tree*): Type => Boolean = {
      val cache = mutable.HashMap.empty[Type, Boolean]
      val baseTypes = bases.map(inferType)

      // If subtype of one of the base types, it's a match!
      def terminate(t: Type): Boolean = baseTypes.exists(t <:< _)

      // Recurse through subtype hierarchy finding containers
      // Seen is only updated when we recurse into type parameters, thus it is typically small
      def recShouldMatch(s: Type, seen: Set[Type]): Boolean = {
        def outerMatches(t: Type): Boolean = {
          val str = t.toString
          str.startsWith("Option[") || str.startsWith("Iterable[")
        }
        if (terminate(s)) {
          true
        } else if (seen.contains(s)) {
          false
        } else if (outerMatches(s)) {
          // These are type parameters, loops *are* possible here
          recShouldMatch(s.typeArgs.head, seen + s)
        } else if (definitions.isTupleType(s)) {
          s.typeArgs.exists(t => recShouldMatch(t, seen + s))
        } else {
          // This is the standard inheritance hierarchy, Scalac catches loops here
          s.parents.exists(p => recShouldMatch(p, seen))
        }
      }

      // If doesn't match container pattern, exit early
      def earlyExit(t: Type): Boolean = {
        !(t.matchesPattern(inferType(tq"Iterable[_]")) || t.matchesPattern(inferType(tq"Option[_]")) || definitions
          .isTupleType(t))
      }

      // Return function so that it captures the cache
      { q: Type =>
        cache.getOrElseUpdate(
          q, {
            // First check if a match, then check early exit, then recurse
            if (terminate(q)) {
              true
            } else if (earlyExit(q)) {
              false
            } else {
              recShouldMatch(q, Set.empty)
            }
          }
        )
      }
    }

    private val shouldMatchData: Type => Boolean = shouldMatchGen(tq"chisel3.Data")
    // Checking for all chisel3.internal.NamedComponents, but since it is internal, we instead have
    // to match the public subtypes
    private val shouldMatchNamedComp: Type => Boolean =
      shouldMatchGen(
        tq"chisel3.Data",
        tq"chisel3.MemBase[_]",
        tq"chisel3.VerificationStatement",
        tq"chisel3.properties.DynamicObject",
        tq"chisel3.Disable",
        tq"chisel3.experimental.AffectsChiselName"
      )
    private val shouldMatchModule:   Type => Boolean = shouldMatchGen(tq"chisel3.experimental.BaseModule")
    private val shouldMatchInstance: Type => Boolean = shouldMatchGen(tq"chisel3.experimental.hierarchy.Instance[_]")
    private val matchHasIdData: Type => Boolean = shouldMatchGen(
      tq"chisel3.Data",
      tq"chisel3.MemBase[_]"
    )
    private val shouldMatchChiselPrefixed: Type => Boolean =
      shouldMatchGen(
        tq"chisel3.experimental.AffectsChiselPrefix"
      )

    // Indicates whether a ValDef is properly formed to get name
    private def okVal(dd: ValDef): Boolean = {

      // These were found through trial and error
      def okFlags(mods: Modifiers): Boolean = {
        val badFlags = Set(
          Flag.PARAM,
          Flag.SYNTHETIC,
          Flag.DEFERRED,
          Flags.TRIEDCOOKING,
          Flags.CASEACCESSOR,
          Flags.PARAMACCESSOR
        )
        badFlags.forall { x => !mods.hasFlag(x) }
      }

      // Ensure expression isn't null, as you can't call `null.autoName("myname")`
      val isNull = dd.rhs match {
        case Literal(Constant(null)) => true
        case _                       => false
      }

      okFlags(dd.mods) && !isNull && dd.rhs != EmptyTree
    }
    // TODO Unify with okVal
    private def okUnapply(dd: ValDef): Boolean = {

      // These were found through trial and error
      def okFlags(mods: Modifiers): Boolean = {
        val badFlags = Set(
          Flag.PARAM,
          Flag.DEFERRED,
          Flags.TRIEDCOOKING,
          Flags.CASEACCESSOR,
          Flags.PARAMACCESSOR
        )
        val goodFlags = Set(
          Flag.SYNTHETIC,
          Flag.ARTIFACT
        )
        goodFlags.forall(f => mods.hasFlag(f)) && badFlags.forall(f => !mods.hasFlag(f))
      }

      // Ensure expression isn't null, as you can't call `null.autoName("myname")`
      val isNull = dd.rhs match {
        case Literal(Constant(null)) => true
        case _                       => false
      }
      val tpe = inferType(dd.tpt)
      definitions.isTupleType(tpe) && okFlags(dd.mods) && !isNull && dd.rhs != EmptyTree
    }

    private def findUnapplyNames(tree: Tree): Option[List[String]] = {
      val applyArgs: Option[List[Tree]] = tree match {
        case Match(_, List(CaseDef(_, _, Apply(_, args)))) => Some(args)
        case _                                             => None
      }
      applyArgs.flatMap { args =>
        var ok = true
        val result = mutable.ListBuffer[String]()
        args.foreach {
          case Ident(TermName(name)) => result += name
          // Anything unexpected and we abort
          case _ => ok = false
        }
        if (ok) Some(result.toList) else None
      }
    }

    // Whether this val is directly enclosed by a Bundle type
    private def inBundle(dd: ValDef): Boolean = {
      dd.symbol.logicallyEnclosingMember.thisType <:< inferType(tq"chisel3.Bundle")
    }

    private def stringFromTermName(name: TermName): String =
      name.toString.trim() // Remove trailing space (Scalac implementation detail)

    private def serializeArg(arg: Tree): Option[String] = arg match {
      case Literal(Constant(value)) =>
        value match {
          case null => Some("null")
          case b: Boolean => Some(b.toString)
          case i: Int     => Some(i.toString)
          case l: Long    => Some(l.toString)
          case f: Float   => Some(f.toString)
          case d: Double  => Some(d.toString)
          case s: String  => Some(s)
          case c: Char    => Some(c.toString)
          case _ => None
        }
      case _ =>
        None
    }

    private def extractCtorData(rhs: Tree): Option[List[Tree]] = findNewArgs(rhs)

    private def extractCtorParams(rhs: Tree): String = {
      val args = extractCtorData(rhs).getOrElse(Nil)
      if (args.isEmpty) {
        ""
      } else {
        args.flatMap { arg =>
          serializeArg(arg).map { valueString =>
            if (valueString.length > MaxCtorParamLength) {
              // Trim to code-point boundary.
              val cp = valueString.codePointCount(0, MaxCtorParamLength)
              val safeEnd = valueString.offsetByCodePoints(0, cp)
              valueString.substring(0, safeEnd) + "..."
            } else valueString
          }
        }
          .mkString(",")
      }
    }

    private def findNewArgs(t: Tree, depth: Int = 0): Option[List[Tree]] = {
      if (depth > MaxAstTraversalDepth) {
        return None
      }

      t match {
        case Apply(Select(New(_), nme.CONSTRUCTOR), args) =>
          Some(args)
        case Apply(Select(module, nme.apply), List(Function(_, body))) if isModuleSym(module) =>
          findNewArgs(body, depth + 1)
        case Apply(TypeApply(Select(mod, _), _), List(arg)) if isModuleSym(mod) =>
          findNewArgs(arg, depth + 1)
        case Apply(Select(mod, _), List(arg)) if isModuleSym(mod) =>
          findNewArgs(arg, depth + 1)
        case Apply(Apply(TypeApply(Select(mod, _), _), List(arg)), _) if isModuleSym(mod) =>
          findNewArgs(arg, depth + 1)
        case Apply(innerApply: Apply, _) =>
          findNewArgs(innerApply, depth + 1)
        case Apply(Select(qual, name), _) if Set("suggestName", "do_apply").contains(name.decoded) =>
          findNewArgs(qual, depth + 1)
        case Block(_, expr) =>
          findNewArgs(expr, depth + 1)
        case Typed(expr, _) =>
          findNewArgs(expr, depth + 1)
        case Function(_, body) =>
          findNewArgs(body, depth + 1)
        case _ =>
          None
      }
    }

    private def isModuleSym(tree: Tree): Boolean =
      (tree.symbol != null &&
        tree.symbol != NoSymbol &&
        tree.symbol.fullName == "chisel3.Module") ||
        (tree match {
          case Ident(TermName(name)) => name == "Module"
          case _                     => false
        })

    /** Builds a quasiquote tree for Seq[Any] of literal ctor arg values.
      * Returns q"_root_.scala.None" if args list is empty or all unknown.
      */
    private def ctorArgsToTree(rhs: Tree): Tree = {
      val args = extractCtorData(rhs).getOrElse(Nil)
      if (args.isEmpty) return q"_root_.scala.None"

      val argTrees: List[Tree] = args.map {
        case Literal(Constant(null))       => q"null"
        case Literal(Constant(b: Boolean)) => q"$b"
        case Literal(Constant(i: Int))     => q"$i"
        case Literal(Constant(l: Long))    => Literal(Constant(l))
        case Literal(Constant(f: Float))   => q"$f"
        case Literal(Constant(d: Double))  => q"$d"
        case Literal(Constant(s: String))  => q"$s"
        case Literal(Constant(c: Char))    => q"$c"
        case _                             => q"(null: AnyRef)" // unknown literal
      }

      if (
        argTrees.forall {
          case q"(null: AnyRef)" => true
          case _                 => false
        }
      ) {
        q"_root_.scala.None"
      } else {
        q"_root_.scala.Some(_root_.scala.Seq[Any](..$argTrees))"
      }
    }

    private def extractRhsClassName(dd: ValDef, tpe: Type): String = {
      if (dd.rhs == EmptyTree || dd.rhs == null) {
        return tpe.typeSymbol.name.toString
      }
      // inferType can throw for complex expression trees (type lambdas,
      // implicit conversions, macro-expanded code). Fall back to declared
      // type on failure - this loses precision but never crashes the compiler.
      val rhsTpe = scala.util.Try(inferType(dd.rhs)).getOrElse(tpe)
      val name = rhsTpe.typeSymbol.name.toString
      // "$anon" means anonymous class; "<error>" means failed type inference
      if (name == "$anon" || name == "<error>") "" else name
    }

    private def wrapWithDebugRecording(
      dd:       ValDef,
      tpe:      Type,
      named:    Tree,
      ctorArgs: Tree = q"_root_.scala.None"
    ): Tree = {
      val className = Literal(Constant(extractRhsClassName(dd, tpe)))
      val params = Literal(Constant(extractCtorParams(dd.rhs)))
      val sourceFile = Literal(Constant(dd.pos.source.file.name))
      val sourceLine = Literal(Constant(dd.pos.line))

      ctorArgs match {
        case q"_root_.scala.None" =>
          q"""chisel3.debug.DebugMeta.record(
            $named, $className, $params, $sourceFile, $sourceLine)"""
        case ctorArgsTree =>
          q"""{
        chisel3.debug.DebugMeta.withCtorArgs($ctorArgsTree) {
          chisel3.debug.DebugMeta.record(
            $named, $className, $params, $sourceFile, $sourceLine)
        }
      }"""
      }
    }

    // Method called by the compiler to modify source tree
    override def transform(tree: Tree): Tree = tree match {
      // Check if a subtree is a candidate
      case dd @ ValDef(mods, name, tpt, rhs) if okVal(dd) =>
        val tpe = inferType(tpt)
        val isData = shouldMatchData(tpe)
        val isNamedComp = isData || shouldMatchNamedComp(tpe)
        val isPrefixed = isNamedComp || shouldMatchChiselPrefixed(tpe)
        val isHasIdData = matchHasIdData(tpe)

        // If a Data and in a Bundle, just get the name but not a prefix
        if (isData && inBundle(dd)) {
          val str = stringFromTermName(name)
          val newRHS = transform(rhs) // chisel3.withName
          val named = q"chisel3.withName($str)($newRHS)"
          val wrapped =
            if (isHasIdData && arguments.emitDebugTypeInfo.get) wrapWithDebugRecording(dd, tpe, named) else named
          treeCopy.ValDef(dd, mods, name, tpt, localTyper.typed(wrapped))
        }
        // If a Data or a Memory, get the name and a prefix
        else if (isData || isPrefixed) {
          val str = stringFromTermName(name)
          // Starting with '_' signifies a temporary, we ignore it for prefixing because we don't
          // want double "__" in names when the user is just specifying a temporary
          val prefix = if (str.head == '_') str.tail else str
          val newRHS = transform(rhs)
          val prefixed = q"chisel3.experimental.prefix.apply[$tpt](name=$prefix)(f=$newRHS)"

          val named =
            if (isNamedComp) {
              // Only name named components (not things that are merely prefixed)
              q"chisel3.withName($str)($prefixed)"
            } else {
              prefixed
            }

          val wrapped =
            if (isHasIdData && arguments.emitDebugTypeInfo.get) wrapWithDebugRecording(dd, tpe, named) else named
          treeCopy.ValDef(dd, mods, name, tpt, localTyper.typed(wrapped))
        }
        // If an instance or module, just get a name but no prefix
        else if (shouldMatchModule(tpe)) {
          val str = stringFromTermName(name)
          val newRHS = transform(rhs)
          val named = q"""chisel3.withName($str)($newRHS)"""
          val wrapped =
            if (arguments.emitDebugTypeInfo.get) {
              val ctorArgsTree = ctorArgsToTree(dd.rhs)
              wrapWithDebugRecording(dd, tpe, named, ctorArgsTree)
            } else named
          treeCopy.ValDef(dd, mods, name, tpt, localTyper.typed(wrapped))
        }
        // If an instance, just get a name but no prefix
        else if (shouldMatchInstance(tpe)) {
          val str = stringFromTermName(name)
          val newRHS = transform(rhs)
          val named = q"chisel3.withName($str)($newRHS)"
          treeCopy.ValDef(dd, mods, name, tpt, localTyper.typed(named))
        } else {
          // Otherwise, continue
          super.transform(tree)
        }
      case dd @ ValDef(mods, name, tpt, rhs @ Match(_, _)) if okUnapply(dd) =>
        val tpe = inferType(tpt)
        val fieldsOfInterest: List[Boolean] = tpe.typeArgs.map(shouldMatchNamedComp)
        // Only transform if at least one field is of interest
        if (fieldsOfInterest.reduce(_ || _)) {
          findUnapplyNames(rhs) match {
            case Some(names) =>
              val onames: List[String] =
                fieldsOfInterest.zip(names).map { case (ok, name) => if (ok) name else "" }
              val newRHS = transform(rhs)
              val named = q"chisel3.withNames(..$onames)($newRHS)"
              treeCopy.ValDef(dd, mods, name, tpt, localTyper.typed(named))
            case None => // It's not clear how this could happen but we don't want to crash
              super.transform(tree)
          }
        } else {
          super.transform(tree)
        }
      // Also look for Module class definitions for inserting source locators
      case module: ClassDef
          if isAModule(module.symbol) && !module.mods.hasFlag(
            Flag.ABSTRACT
          ) && !isOverriddenSourceLocator(module.impl) =>
        val path = {
          val file: AbstractFile = module.pos.source.file
          // No file (null) for things like mdoc and macro-generated code.
          if (file.file == null) {
            file.path
          } else {
            SourceInfoFileResolver.resolve(file.file.toPath)
          }
        }
        val info = localTyper.typed(q"chisel3.experimental.SourceLine($path, ${module.pos.line}, ${module.pos.column})")

        val sourceInfoSym =
          module.symbol.newMethod(TermName("_sourceInfo"), module.symbol.pos.focus, Flag.OVERRIDE | Flag.PROTECTED)
        sourceInfoSym.resetFlag(Flags.METHOD)
        sourceInfoSym.setInfo(NullaryMethodType(sourceInfoTpe))
        val sourceInfoImpl = localTyper.typed(
          DefDef(sourceInfoSym, info)
        )

        val moduleWithInfo = deriveClassDef(module) { t =>
          deriveTemplate(t)(sourceInfoImpl :: _)
        }
        super.transform(localTyper.typed(moduleWithInfo))

      // Otherwise, continue
      case _ => super.transform(tree)
    }
  }
}
