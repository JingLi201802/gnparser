package org.globalnames.parser

import org.apache.commons.id.uuid.UUID
import org.globalnames.formatters.{Canonizer, Details, Normalizer, Positions}
import org.json4s.JsonAST.{JArray, JValue}
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.parboiled2._
import shapeless._

import scala.util.matching.Regex
import scala.util.{Failure, Success}

import scalaz._
import Scalaz._

abstract class ScientificNameParser {
  import ScientificNameParser.{Input, Result}

  val version: String

  private final val virusPatterns =
    """\sICTV\s*$""".r :: """[A-Z]?[a-z]+virus\b""".r ::
    """(?ix)\b(virus|viruses|particle|particles|
               phage|phages|viroid|viroids|virophage|
               prion|prions|NPV)\b""".r ::
    """\b[A-Za-z]*(satellite[s]?|NPV)\b""".r :: HNil

  def json(parserResult: Result): JValue = {
    val canonical = parserResult.canonized(showRanks = false)
    val positionsJson: JArray =
      parserResult.positioned.map { position =>
        JArray(List(position.nodeName,
                    parserResult.input.verbatimPosAt(position.start),
                    parserResult.input.verbatimPosAt(position.end)))
      }
    val garbage = if (parserResult.scientificName.garbage.isEmpty) None
                  else parserResult.scientificName.garbage.some

    render("scientificName" -> ("id" -> parserResult.input.id) ~
      ("parsed" -> canonical.isDefined) ~
      ("parser_version" -> version) ~
      ("verbatim" -> parserResult.input.verbatim) ~
      ("normalized" -> parserResult.normalized) ~
      ("canonical" -> canonical) ~
      ("canonical_with_ranks" -> parserResult.canonized(showRanks = true)) ~
      ("hybrid" -> parserResult.scientificName.isHybrid) ~
      ("surrogate" -> parserResult.scientificName.surrogate) ~
      ("garbage" -> garbage) ~
      ("virus" -> parserResult.scientificName.isVirus) ~
      ("details" -> parserResult.detailed) ~
      ("positions" -> positionsJson))
  }

  def renderCompactJson(parserResult: Result): String =
    compact(json(parserResult))

  def fromString(input: String, showWarnings: Boolean = false): Result = {
    val isVirus = checkVirus(input)
    val inputString = Input(input)
    if (isVirus || noParse(input)) {
      Result(inputString, ScientificName(isVirus = isVirus))
    } else {
      val ctx = new Parser.Context(new ParserWarnings)
      Parser.sciName.runWithContext(inputString.unescaped, ctx) match {
        case Success(sn: ScientificName) =>
          if (showWarnings) {
            val warningsStr =
              ctx.parserWarnings.warnings.sortBy{_.level}
                .map { w => s"lvl${w.level}: ${w.message}" }.distinct
            if (warningsStr.nonEmpty) {
              println("Parser warnings:")
              warningsStr.foreach { println }
            } else {
              println("Parser has no warnings")
            }
          }
          Result(inputString, sn)
        case Failure(err: ParseError) =>
          println(err.format(inputString.verbatim))
          Result(inputString, ScientificName())
        case Failure(err) =>
          //println(err)
          Result(inputString, ScientificName())
      }
    }
  }

  private def checkVirus(input: String): Boolean = {
    object PatternMatch extends Poly2 {
      implicit def default =
        at[Boolean, Regex]{ _ && _.findFirstIn(input).isEmpty }
    }
    !virusPatterns.foldLeft(true)(PatternMatch)
  }

  private def noParse(input: String): Boolean = {
    val incertaeSedis1 = """(?i).*incertae\s+sedis.*""".r
    val incertaeSedis2 = """(?i)inc\.\s*sed\.""".r
    val rna = """[^A-Z]RNA[^A-Z]*""".r
    if (List(incertaeSedis1.findFirstIn(input),
      incertaeSedis2.findFirstIn(input),
      rna.findFirstIn(input)) == List(None, None, None)) false
    else true
  }
}

object ScientificNameParser {
  final val instance = new ScientificNameParser {
    override final val version: String = BuildInfo.version
  }

  case class Result(input: Input, scientificName: ScientificName)
    extends Details with Positions with Normalizer with Canonizer {

    def stringOf(astNode: AstNode): String =
      input.unescaped.substring(astNode.pos.start, astNode.pos.end)
  }

  case class Input(verbatim: String) {
    private lazy val UNESCAPE_HTML4 = new TrackingPositionsUnescapeHtml4Translator
    lazy val unescaped: String = {
      val unescaped = UNESCAPE_HTML4.translate(verbatim)
      normalizeHybridChar(removeJunk(unescaped))
    }

    val id: String = {
      val gn = UUID.fromString("90181196-fecf-5082-a4c1-411d4f314cda")
      val uuid = UUID.nameUUIDFromString(verbatim, gn, "SHA1").toString
      s"${uuid.substring(0, 14)}5${uuid.substring(15, uuid.length)}"
    }

    def verbatimPosAt(pos: Int): Int = UNESCAPE_HTML4.at(pos)
  }

  @annotation.tailrec
  private def substitute(input: String, regexes: List[String]): String = {
    if (regexes == List()) input
    else substitute(input.replaceFirst(regexes.head, ""), regexes.tail)
  }

  def removeJunk(input: String): String = {
    val notes = """(?ix)\s+(species\s+group|
                   species\s+complex|group|author)\b.*$"""
    val taxonConcepts1 = """(?i)\s+(sensu\.|sensu|auct\.|auct)\b.*$"""
    val taxonConcepts2 = """(?x)\s+
                       (\(?s\.\s?s\.|
                       \(?s\.\s?l\.|
                       \(?s\.\s?str\.|
                       \(?s\.\s?lat\.|
                      sec\.|sec|near)\b.*$"""
    val taxonConcepts3 = """(?i)(,\s*|\s+)(pro parte|p\.\s?p\.)\s*$"""
    val nomenConcepts  = """(?i)(,\s*|\s+)(\(?nomen|\(?nom\.|\(?comb\.).*$"""
    val lastWordJunk  = """(?ix)(,\s*|\s+)
                    (spp\.|spp|var\.|
                     var|von|van|ined\.|
                     ined|sensu|new|non|nec|
                     nudum|cf\.|cf|sp\.|sp|
                     ssp\.|ssp|subsp|subgen|hybrid|hort\.|hort)\??\s*$"""
    substitute(input, List(notes, taxonConcepts1,
      taxonConcepts2, taxonConcepts3, nomenConcepts, lastWordJunk))
  }

  def normalizeHybridChar(input: String): String = {
    input.replaceAll("""(^)[Xx](\p{Lu})|(\b)[Xx](\b)""", "$1×$2")
  }
}