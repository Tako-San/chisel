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

    private val SkippedMethodNames = Set("suggestName", "do_apply")

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

    private def rhsIsValid(dd: ValDef): Boolean = dd.rhs match {
      case EmptyTree | Literal(Constant(null)) => false
      case _                                   => true
    }

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

      okFlags(dd.mods) && rhsIsValid(dd)
    }
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
      val tpe = inferType(dd.tpt)
      definitions.isTupleType(tpe) && okFlags(dd.mods) && rhsIsValid(dd)
    }

    private def findUnapplyNames(tree: Tree): Option[List[String]] = tree match {
      case Match(_, List(CaseDef(_, _, Apply(_, args)))) =>
        args.foldLeft(Option(List.empty[String])) {
          case (Some(acc), Ident(TermName(n))) => Some(acc :+ n)
          case _                               => None
        }
      case _ => None
    }

    // Whether this val is directly enclosed by a Bundle type
    private def inBundle(dd: ValDef): Boolean = {
      dd.symbol.logicallyEnclosingMember.thisType <:< inferType(tq"chisel3.Bundle")
    }

    private def stringFromTermName(name: TermName): String =
      name.toString.trim() // Remove trailing space (Scalac implementation detail)

    private def mkTransformed(dd: ValDef): (String, Tree) = {
      val str = stringFromTermName(dd.name)
      val newRHS = transform(dd.rhs)
      (str, newRHS)
    }

    private def mkNamed(dd: ValDef): Tree = {
      val (str, newRHS) = mkTransformed(dd)
      q"chisel3.withName($str)($newRHS)"
    }

    private sealed trait CtorArg
    private case class KnownArg(tree: Tree, str: String) extends CtorArg
    private case object UnknownArg extends CtorArg

    private def classifyLiteral(arg: Tree): CtorArg = arg match {
      case Literal(Constant(null))       => KnownArg(q"null", "null")
      case Literal(Constant(b: Boolean)) => KnownArg(q"$b", b.toString)
      case Literal(Constant(i: Int))     => KnownArg(q"$i", i.toString)
      case Literal(Constant(l: Long))    => KnownArg(Literal(Constant(l)), l.toString)
      case Literal(Constant(f: Float))   => KnownArg(q"$f", f.toString)
      case Literal(Constant(d: Double))  => KnownArg(q"$d", d.toString)
      case Literal(Constant(s: String))  => KnownArg(q"$s", s)
      case Literal(Constant(c: Char))    => KnownArg(q"$c", c.toString)
      case _                             => UnknownArg
    }

    private def safeTruncate(s: String, maxLen: Int = MaxCtorParamLength): String = {
      val end = s.offsetByCodePoints(0, s.codePointCount(0, maxLen))
      s.substring(0, end) + "..."
    }

    private def truncate(s: String): String =
      if (s.length > MaxCtorParamLength) safeTruncate(s) else s

    private def extractCtorParams(classified: List[CtorArg]): String =
      classified.collect { case KnownArg(_, s) => truncate(s) }.mkString(",")

    private def moduleWrappedArg(t: Tree): Option[Tree] = t match {
      case Apply(Select(m, nme.apply), List(Function(_, body))) if isModuleSym(m)   => Some(body)
      case Apply(TypeApply(Select(m, _), _), List(arg)) if isModuleSym(m)           => Some(arg)
      case Apply(Select(m, _), List(arg)) if isModuleSym(m)                         => Some(arg)
      case Apply(Apply(TypeApply(Select(m, _), _), List(arg)), _) if isModuleSym(m) => Some(arg)
      case _                                                                        => None
    }

    private def findNewArgs(t: Tree, depth: Int = 0): Option[List[Tree]] = {
      if (depth > MaxAstTraversalDepth) {
        return None
      }

      t match {
        case Apply(Select(New(_), nme.CONSTRUCTOR), args) =>
          Some(args)
        case Apply(innerApply: Apply, _) =>
          findNewArgs(innerApply, depth + 1)
        case Apply(Select(qual, name), _) if SkippedMethodNames.contains(name.decoded) =>
          findNewArgs(qual, depth + 1)
        case Block(_, expr) =>
          findNewArgs(expr, depth + 1)
        case Typed(expr, _) =>
          findNewArgs(expr, depth + 1)
        case Function(_, body) =>
          findNewArgs(body, depth + 1)
        case _ =>
          moduleWrappedArg(t).flatMap(findNewArgs(_, depth + 1))
      }
    }

    private def isModuleSym(tree: Tree): Boolean = tree match {
      case t if t.symbol != null && t.symbol != NoSymbol =>
        t.symbol.fullName == "chisel3.Module"
      case Ident(TermName("Module")) => true
      case _                         => false
    }

    private def ctorArgsToTree(classified: List[CtorArg]): Tree = {
      val trees = classified.map {
        case KnownArg(t, _) => t
        case UnknownArg     => q"(null: AnyRef)"
      }
      // Use `_root_.scala.Seq.empty` instead of `Nil` to avoid issues with name shadowing
      if (trees.isEmpty || classified.forall(_ == UnknownArg))
        q"_root_.scala.Seq.empty"
      else
        q"_root_.scala.Seq[Any](..$trees)"
    }

    private def extractRhsClassName(dd: ValDef, tpe: Type): String =
      if (dd.rhs == null || dd.rhs == EmptyTree) tpe.typeSymbol.name.toString
      else {
        val rhsTpe = scala.util.Try(inferType(dd.rhs)).getOrElse(tpe)
        val n = rhsTpe.typeSymbol.name.toString
        if (n == "$anon" || n == "<error>") "" else n
      }

    private def maybeWrapDebug(
      dd:         ValDef,
      tpe:        Type,
      named:      Tree,
      guardCheck: Boolean = true
    ): Tree =
      if (guardCheck && arguments.emitDebugTypeInfo.get) {
        val classified = findNewArgs(dd.rhs).getOrElse(Nil).map(classifyLiteral)
        wrapWithDebugRecording(dd, tpe, named, classified)
      } else named

    private def wrapWithDebugRecording(
      dd:         ValDef,
      tpe:        Type,
      named:      Tree,
      classified: List[CtorArg] = Nil
    ): Tree = {
      val className = Literal(Constant(extractRhsClassName(dd, tpe)))
      val params = Literal(Constant(extractCtorParams(classified)))
      val paramTree = ctorArgsToTree(classified)
      val sourceFile = {
        val file = dd.pos.source.file
        val path =
          if (file.file == null) file.path
          else SourceInfoFileResolver.resolve(file.file.toPath)
        Literal(Constant(path))
      }
      val sourceLine = Literal(Constant(dd.pos.line))

      q"""{
        chisel3.debug.DebugMeta.withCtorArgs($paramTree) {
          chisel3.debug.DebugMeta.record(
            $named, $className, $params, $sourceFile, $sourceLine)
        }
      }"""
    }

    // Method called by the compiler to modify source tree
    override def transform(tree: Tree): Tree = tree match {
      // Check if a subtree is a candidate
      case dd @ ValDef(mods, name, tpt, rhs) if okVal(dd) =>
        val tpe = inferType(tpt)
        val isNamedComp = shouldMatchNamedComp(tpe)
        val isPrefixed = isNamedComp || shouldMatchChiselPrefixed(tpe)
        lazy val isHasIdData = matchHasIdData(tpe)

        // If a Data and in a Bundle, just get the name but not a prefix
        if (inBundle(dd)) {
          val named = mkNamed(dd)
          val wrapped = maybeWrapDebug(dd, tpe, named, isHasIdData)
          treeCopy.ValDef(dd, mods, name, tpt, localTyper.typed(wrapped))
        }
        // If a NamedComponent or Prefixed, get the name and a prefix
        else if (isPrefixed) {
          val (str, newRHS) = mkTransformed(dd)
          // Starting with '_' signifies a temporary, we ignore it for prefixing because we don't
          // want double "__" in names when the user is just specifying a temporary
          val prefix = if (str.head == '_') str.tail else str
          val prefixed = q"chisel3.experimental.prefix.apply[$tpt](name=$prefix)(f=$newRHS)"

          val named =
            if (isNamedComp) {
              // Only name named components (not things that are merely prefixed)
              q"chisel3.withName($str)($prefixed)"
            } else {
              prefixed
            }

          val wrapped = maybeWrapDebug(dd, tpe, named, isHasIdData)
          treeCopy.ValDef(dd, mods, name, tpt, localTyper.typed(wrapped))
        }
        // If an instance or module, just get a name but no prefix
        else if (shouldMatchModule(tpe)) {
          val named = mkNamed(dd)
          val wrapped = maybeWrapDebug(dd, tpe, named, guardCheck = true)
          treeCopy.ValDef(dd, mods, name, tpt, localTyper.typed(wrapped))
        }
        // If an instance, just get a name but no prefix
        else if (shouldMatchInstance(tpe)) {
          val named = mkNamed(dd)
          treeCopy.ValDef(dd, mods, name, tpt, localTyper.typed(named))
        } else {
          // Otherwise, continue
          super.transform(tree)
        }
      case dd @ ValDef(mods, name, tpt, rhs @ Match(_, _)) if okUnapply(dd) =>
        val tpe = inferType(tpt)
        val fieldsOfInterest: List[Boolean] = tpe.typeArgs.map(shouldMatchNamedComp)
        // Only transform if at least one field is of interest
        if (fieldsOfInterest.exists(identity)) {
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
