package org.globalnames.parser

import java.util.UUID

import com.fasterxml.uuid.{Generators, StringArgGenerator}
import org.globalnames.formatters._
import org.parboiled2._
import shapeless._

import scala.util.matching.Regex
import scala.util.{Failure, Success}

abstract class ScientificNameParser {
  import ScientificNameParser.{Input, Result}

  val version: String

  private final val virusPatterns =
    """\sICTV\s*$""".r :: """[A-Z]?[a-z]+virus\b""".r ::
    """(?ix)\b(virus|viruses|particle|particles|
               phage|phages|viroid|viroids|virophage|
               prion|prions|NPV)\b""".r ::
    """\b[A-Za-z]*(satellite[s]?|NPV)\b""".r :: HNil

  def fromString(input: String): Result = {
    val isVirus = checkVirus(input)
    val inputString = Input(input)
    if (isVirus || noParse(input)) {
      Result(inputString, ScientificName(isVirus = isVirus), version)
    } else {
      val input = inputString.unescaped
      val ctx = new Parser.Context(inputString.preprocessed)
      Parser.sciName.runWithContext(input, ctx) match {
        case Success(scientificName :: warnings :: HNil) =>
          Result(inputString, scientificName, version, warnings)
        case Failure(err: ParseError) =>
          Console.err.println(err.format(inputString.verbatim))
          Result(inputString, ScientificName(), version)
        case Failure(err) =>
          Result(inputString, ScientificName(), version)
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
  private final val uuidGenerator: StringArgGenerator = {
    val namespace = UUID.fromString("90181196-fecf-5082-a4c1-411d4f314cda")
    Generators.nameBasedGenerator(namespace)
  }

  final val instance = new ScientificNameParser {
    override final val version: String = BuildInfo.version
  }

  case class Result(input: Input, scientificName: ScientificName,
                    version: String, warnings: Vector[Warning] = Vector.empty)
    extends JsonRenderer with Details with Positions
       with Normalizer with Canonizer {

    def stringOf(astNode: AstNode): String =
      input.unescaped.substring(astNode.pos.start, astNode.pos.end)
  }

  case class Input(verbatim: String) {
    private lazy val UNESCAPE_HTML4 =
      new TrackingPositionsUnescapeHtml4Translator
    lazy val (unescaped, preprocessed): (String, Boolean) = {
      val unescaped = UNESCAPE_HTML4.translate(verbatim)
      val preprocessed = normalizeHybridChar(removeJunk(unescaped))

      val isPreprocessed =
        !UNESCAPE_HTML4.identity || unescaped.length != preprocessed.length
      (preprocessed, isPreprocessed)
    }

    val id: String = uuidGenerator.generate(verbatim).toString

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
    val taxonConcepts1 = """(?i)\s+(sensu|auct|sec|near)\.?\b.*$"""
    val taxonConcepts2 = """(?x)(,\s*|\s+)
                       (\(?s\.\s?s\.|
                       \(?s\.\s?l\.|
                       \(?s\.\s?str\.|
                       \(?s\.\s?lat\.).*$"""
    val taxonConcepts3 = """(?i)(,\s*|\s+)(pro parte|p\.\s?p\.)\s*$"""
    val nomenConcepts  = """(?i)(,\s*|\s+)(\(?nomen|\(?nom\.|\(?comb\.).*$"""
    val lastWordJunk  = """(?ix)(,\s*|\s+)
                    (var\.|var|von|van|ined\.|
                     ined|sensu|new|non|nec|nudum|
                     ssp\.|ssp|subsp|subgen|hybrid|hort\.|hort)\??\s*$"""
    substitute(input, List(notes, taxonConcepts1,
      taxonConcepts2, taxonConcepts3, nomenConcepts, lastWordJunk))
  }

  def normalizeHybridChar(input: String): String = {
    input.replaceAll("""(^)[Xx](\p{Lu})|(\b)[Xx](\b)""", "$1×$2")
  }
}
