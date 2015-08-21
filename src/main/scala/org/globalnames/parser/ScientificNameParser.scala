package org.globalnames.parser

import org.globalnames.ops.ScientificNameOps
import org.json4s.JValue
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.parboiled2._
import shapeless._

import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

abstract class ScientificNameParser {
  private val parserClean = new ParserClean()

  val version: String

  private final val virusPatterns =
    """\sICTV\s*$""".r :: """[A-Z]?[a-z]+virus\b""".r ::
    """(?ix)\b(virus|viruses|particle|particles|
               phage|phages|viroid|viroids|virophage|
               prion|prions|NPV)\b""".r ::
    """\b[A-Za-z]*(satellite[s]?|NPV)\b""".r :: HNil

  def json(scientificName: ScientificName): JValue = {
    val canonical = scientificName.canonical

    render("scientificName" -> ("id" -> scientificName.id) ~
      ("parsed" -> canonical.isDefined) ~
      ("parser_version" -> version) ~
      ("verbatim" -> scientificName.verbatim) ~
      ("normalized" -> scientificName.normal) ~
      ("canonical" -> canonical) ~
      ("hybrid" -> scientificName.isHybrid) ~
      ("virus" -> scientificName.isVirus) ~
      ("details" -> scientificName.details))
  }

  def renderCompactJson(scientificName: ScientificName): String = compact(json(scientificName))

  def fromString(input: String): ScientificName = {
    val isVirus = checkVirus(input)
    if (isVirus || noParse(input)) ScientificName(input, isVirus = isVirus)
    else {
      val UNESCAPE_HTML4 = new TrackingPositionsUnescapeHtml4Translator
      val parserInput = preprocess(input, UNESCAPE_HTML4)
      parse(input, parserInput)
    }
  }

  private def checkVirus(input: String): Boolean = {
    object PatternMatch extends Poly2 {
      implicit def default =
        at[Boolean, Regex]{ _ && _.findFirstIn(input).isEmpty }
    }
    !virusPatterns.foldLeft(true)(PatternMatch)
  }

  def processParsed(input: String, parser: Parser,
                    result: Try[ScientificName]): ScientificName = {
    result match {
      case Success(res: ScientificName) => res.copy(input)
      case Failure(err: ParseError) => {
        println(err.format(input))
        ScientificName(input)
      }
      case Failure(err) => {
        //println(err)
        ScientificName(input)
      }
      case _ => ScientificName(input)
    }
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

  private def parse(input: String, parserInput: String): ScientificName = {
    val res =  parserClean.sciName.run(parserInput)
    processParsed(input, parserClean, res)
  }

  private def preprocess(input: String,
    translator: TrackingPositionsUnescapeHtml4Translator): String = {

    val unescaped = translator.translate(input)
    val unjunk = removeJunk(unescaped)
    normalizeHybridChar(unjunk)
  }

  private def removeJunk(input: String): String = {
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

  @annotation.tailrec
  private def substitute(input: String, regexes: List[String]): String = {
    if (regexes == List()) input
    else substitute(input.replaceFirst(regexes.head, ""), regexes.tail)
  }

  private def normalizeHybridChar(input: String): String = {
    input.replaceAll(" [Xx] ", " × ")
      .replaceAll("""^\s*[Xx]\s*([\p{Lu}])""", "× $1")
  }
}

object ScientificNameParser extends ScientificNameParser {
  val version = BuildInfo.version
}
