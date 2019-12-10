package com.github.ghik.silencer

import java.util.regex.PatternSyntaxException

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.reflect.internal.util.Position
import scala.reflect.io.AbstractFile
import scala.tools.nsc.plugins.{Plugin, PluginComponent}
import scala.tools.nsc.{Global, Phase}
import scala.util.matching.Regex

class SilencerPlugin(val global: Global) extends Plugin { plugin =>

  import global._

  val name = "silencer"
  val description = "Scala compiler plugin for warning suppression"
  val components: List[PluginComponent] = List(extractSuppressions, checkUnusedSuppressions)

  private val globalFilters = ListBuffer.empty[Regex]
  private val pathFilters = ListBuffer.empty[Regex]
  private val sourceRoots = ListBuffer.empty[AbstractFile]
  private var checkUnused = false
  private var experimental = false

  private lazy val reporter =
    new SuppressingReporter(global.reporter, globalFilters.result(), pathFilters.result(), sourceRoots.result())

  object NamedArgExtract extends SilencerNamedArgExtractor(global)

  private def split(s: String): Iterator[String] = s.split(';').iterator

  override def processOptions(options: List[String], error: String => Unit): Unit = {
    options.foreach(_.split("=", 2) match {
      case Array("globalFilters", pattern) =>
        globalFilters ++= split(pattern).map(_.r)
      case Array("pathFilters", pattern) =>
        pathFilters ++= split(pattern).map(_.r)
      case Array("sourceRoots", rootPaths) =>
        sourceRoots ++= split(rootPaths).flatMap { path =>
          val res = Option(AbstractFile.getDirectory(path))
          if (res.isEmpty) {
            reporter.warning(NoPosition, s"Invalid source root: $path is not a directory")
          }
          res
        }
      case Array("checkUnused") =>
        checkUnused = true
      case Array("experimental") =>
        experimental = true
      case _ =>
    })

    global.reporter = reporter
  }

  override val optionsHelp: Option[String] = Some(
    """  -P:silencer:globalFilters=...  Semicolon separated regexes for filtering warning messages globally
      |  -P:silencer:pathFilters=...    Semicolon separated regexes for filtering source paths
      |  -P:silencer:sourceRoots=...    Semicolon separated paths of source root directories to relativize path filters
      |  -P:silencer:checkUnused        Enables reporting of unused @silent annotations
      |  -P:silencer:experimental       Enables usage of @SuppressWarnings annotation instead of @silent
    """.stripMargin)

  private object extractSuppressions extends PluginComponent {
    val global: plugin.global.type = plugin.global
    val runsAfter = List("typer")
    override val runsBefore = List("patmat")
    val phaseName = "silencer"
    override def description: String = "inspect @silent annotations for warning suppression"

    private lazy val silentSym =
      if (experimental) rootMirror.staticClass("java.lang.SuppressWarnings")
      else {
        try rootMirror.staticClass("com.github.ghik.silencer.silent") catch {
          case _: ScalaReflectionException =>
            if (globalFilters.isEmpty && pathFilters.isEmpty) {
              plugin.reporter.error(NoPosition,
                "`silencer-plugin` was enabled but the @silent annotation was not found on classpath" +
                  " - have you added `silencer-lib` as a library dependency?"
              )
            }
            NoSymbol
        }
      }

    def newPhase(prev: Phase): StdPhase = new StdPhase(prev) {
      def apply(unit: CompilationUnit): Unit = applySuppressions(unit)
    }

    def applySuppressions(unit: CompilationUnit): Unit = {
      val suppressions = if (silentSym == NoSymbol) Nil else {
        val silentAnnotType = TypeRef(NoType, silentSym, Nil)
        def isSilentAnnot(tree: Tree) =
          tree.tpe != null && tree.tpe <:< silentAnnotType
        def mkPattern(regex: String, annotPos: Position) =
          try Some(regex.r) catch { case pse: PatternSyntaxException =>
            reporter.error(annotPos, s"invalid message pattern $regex in silencer annotation: ${pse.getMessage}")
            None
          }

        def mkSuppression(tree: Tree, annot: Tree, annotPos: Position, inMacroExpansion: Boolean): Suppression = {
          val range = treeRangePos(tree)

          if (experimental) {
            object SilencerAnnotationValue {
              private val Name = "silencer"
              private val Prefix = s"$Name:"
              private val Empty = ""

              def unapply(tree: Tree): Option[String] =
                tree.collect {
                  case Literal(Constant(Name)) => Some(Empty)
                  case Literal(Constant(str: String)) =>
                    str.stripPrefix(Prefix) match {
                      case `str`    => None
                      case stripped => Some(stripped)
                    }
                  case _ => None
                }
                .headOption
                .flatten
            }

            annot match {
              case Apply(_, List(NamedArgExtract(Ident(TermName("value")), Apply(Ident(_), List(SilencerAnnotationValue(regex)))))) =>
                val msgPattern = if (regex.isEmpty) None else mkPattern(regex, annotPos)

                new Suppression(annotPos, range, msgPattern, inMacroExpansion)
              case _ => null
            }
          } else {
            val msgPattern = annot match {
              case Apply(_, Nil) => None
              case Apply(_, List(Literal(Constant(regex: String)))) => mkPattern(regex, annotPos)
              case _ =>
                reporter.error(annotPos, "expected literal string as @silent annotation argument")
                None
            }

            new Suppression(annotPos, range, msgPattern, inMacroExpansion)
          }
        }

        def treeRangePos(tree: Tree): Position = {
          // compute approximate range
          var start = unit.source.length
          var end = 0
          tree.foreach { child =>
            val pos = child.pos
            if (pos.isDefined) {
              start = start min pos.start
              end = end max pos.end
            }
          }
          end = end max start
          Position.range(unit.source, start, start, end)
        }

        val suppressionsBuf = new ListBuffer[Suppression]

        object FindSuppressions extends Traverser {
          private val suppressionPositionsVisited = new mutable.HashSet[Int]
          private var inMacroExpansion: Boolean = false

          private def addSuppression(tree: Tree, annot: Tree, annotPos: Position): Unit = {
            val actualAnnotPos = if (annotPos != NoPosition) annotPos else tree.pos
            if (isSilentAnnot(annot) && suppressionPositionsVisited.add(actualAnnotPos.point)) {
              val suppression = mkSuppression(tree, annot, actualAnnotPos, inMacroExpansion)
              if (suppression != null) suppressionsBuf += suppression
            }
          }

          override def traverse(t: Tree): Unit = {
            val expandee = analyzer.macroExpandee(t)
            val macroExpansion = expandee != EmptyTree && expandee != t
            if (macroExpansion) {
              traverse(expandee)
            }

            val wasInMacroExpansion = inMacroExpansion
            inMacroExpansion = inMacroExpansion || macroExpansion

            //NOTE: it's important to first traverse the children so that nested suppression ranges come before
            //containing suppression ranges
            super.traverse(t)
            t match {
              case Annotated(annot, arg) =>
                addSuppression(arg, annot, annot.pos)
              case typed@Typed(_, tpt) if tpt.tpe != null =>
                tpt.tpe.annotations.foreach(ai => addSuppression(typed, ai.tree, ai.pos))
              case md: MemberDef =>
                val annots = md.symbol.annotations
                // search for @silent annotations in trees of other annotations
                // you would expect that super.traverse should do that but it doesn't because apparently
                // at typer phase annotations are no longer available in the tree itself and must be fetched from symbol
                annots.foreach(ai => traverse(ai.tree))
                annots.foreach(ai => addSuppression(md, ai.tree, ai.pos))
              case _ =>
            }

            inMacroExpansion = wasInMacroExpansion
          }
        }

        FindSuppressions.traverse(unit.body)
        suppressionsBuf.toList
      }

      plugin.reporter.setSuppressions(unit.source, suppressions)
    }
  }

  private object checkUnusedSuppressions extends PluginComponent {
    val global: plugin.global.type = plugin.global
    val runsAfter = List("jvm")
    override val runsBefore = List("terminal")
    val phaseName = "silencerCheckUnused"
    override def description: String = "report unused @silent annotations"

    def newPhase(prev: Phase): StdPhase = new StdPhase(prev) {
      def apply(unit: CompilationUnit): Unit =
        if (checkUnused) {
          reporter.checkUnused(unit.source)
        }
    }
  }
}
