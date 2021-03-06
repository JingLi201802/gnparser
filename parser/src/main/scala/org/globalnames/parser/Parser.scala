package org.globalnames.parser

import java.util.regex.Pattern

import org.parboiled2.CharPredicate.{Alpha, Digit, LowerAlpha, UpperAlpha}
import org.parboiled2._

import scalaz.syntax.std.boolean._
import scalaz.syntax.std.option._
import scalaz.std.option._
import scalaz.Unzip

import ast._

import shapeless._

class Parser(preprocessorResult: Preprocessor.Result,
             collectErrors: Boolean)
  extends org.parboiled2.Parser() {

  import Parser._

  type RuleNodeMeta[T <: AstNode] = Rule1[NodeMeta[T]]

  override implicit val input: ParserInput = preprocessorResult.unescaped

  override def errorTraceCollectionLimit: Int = 0

  def sciName: Rule2[ScientificName, Vector[Warning]] = rule {
    capturePos(softSpace ~ sciName1) ~ unparsed ~ EOI ~> {
      (ng: NodeMeta[NamesGroup], pos: CapturePosition, unparsedTail: Option[String]) =>
      val name = input.sliceString(pos.start, pos.end)

      val warnings = Set(
        doubleSpacePattern.matcher(name).find().option {
          Warning(2, "Multiple adjacent space characters")
        },
        name.exists { ch => spaceMiscoded.indexOf(ch) >= 0 }.option {
          Warning(3, "Non-standard space characters")
        },
        name.exists { ch => charMiscoded == ch }.option {
          Warning(3, "Incorrect conversion to UTF-8")
        },
        unparsedTail.map {
          case g if g.trim.isEmpty => Warning(2, "Trailing whitespace")
          case _                   => Warning(3, "Unparseable tail")
        }
      ).flatten ++ ng.warnings ++ preprocessorResult.warnings

      val worstLevel = warnings.isEmpty ? 1 | warnings.maxBy { _.level }.level
      val surrogatePreprocessed = preprocessorResult.surrogate
      val sn = ScientificName(namesGroup = ng.node.some, unparsedTail = unparsedTail,
                              quality = worstLevel,
                              surrogatePreprocessed = surrogatePreprocessed)
      val warningsRes = warnings.toVector.distinct.sorted
      sn :: warningsRes :: HNil
    }
  }

  def sciName1: RuleNodeMeta[NamesGroup] = rule {
    hybridFormula | namedHybrid | approxName | sciName2
  }

  def sciName2: RuleNodeMeta[NamesGroup] = rule {
    name ~> { n: NodeMeta[Name] => FactoryAST.namesGroup(n) }
  }

  private type HybridFormula1Type =
    (HybridChar, NodeMeta[Species], Option[NodeMeta[InfraspeciesGroup]])
  private type HybridFormula2Type = (HybridChar, Option[NodeMeta[Name]])

  def hybridFormula: RuleNodeMeta[NamesGroup] = rule {
    name ~ oneOrMore(space ~ (hybridFormula1 | hybridFormula2)) ~> {
      (n1M: NodeMeta[Name], hybsM: Seq[Either[HybridFormula1Type, HybridFormula2Type]]) =>
        val isFormula1 = hybsM.exists { _.isLeft }
        val isFormula2emptyName = hybsM.exists { h => h.isRight && h.right.get._2.isEmpty }
        val isFormula2 = isFormula2emptyName || hybsM.exists { _.isRight }

        val n2M = isFormula1 ? n1M.map { n => n.copy(genusParsed = true) } | n1M
        val hybs1M = hybsM.map {
          case Left((hc, sp, ig)) =>
            val uninomial1M = nodeToMeta(n1M.node.uninomial.copy(implied = true))
            val r = FactoryAST.name(uninomial = uninomial1M,
                                    species = sp.some,
                                    infraspecies = ig)
            (hc, r.some)
          case Right((hc, n)) => (hc, n)
        }
        val r0 = FactoryAST.namesGroup(n2M, hybs1M)
        val r1 = isFormula2 ? r0.warn((2, "Hybrid formula")) | r0
        val r2 = isFormula1 ? r1.warn((3, "Incomplete hybrid formula")) | r1
        isFormula2emptyName ? r2.warn((2, "Probably incomplete hybrid formula")) | r2
    }
  }

  def hybridFormula1: Rule1[Either[HybridFormula1Type, HybridFormula2Type]] = rule {
    hybridChar ~ softSpace ~ species ~ (space ~ infraspeciesGroup).? ~> {
      (hc: HybridChar, sp: NodeMeta[Species], ig: Option[NodeMeta[InfraspeciesGroup]]) =>
        Left((hc, sp, ig))
    }
  }

  def hybridFormula2: Rule1[Either[HybridFormula1Type, HybridFormula2Type]] = rule {
    hybridChar ~ (space ~ name).? ~> {
      (hc: HybridChar, n: Option[NodeMeta[Name]]) => Right((hc, n))
    }
  }

  def namedHybrid: RuleNodeMeta[NamesGroup] = rule {
    hybridChar ~ capturePos(softSpace) ~ name ~> {
      (hc: HybridChar, spacePos: CapturePosition, n: NodeMeta[Name]) =>
        val ng = FactoryAST.namesGroup(n, namedHybrid = hc.some)
        val warns = Vector(
          (spacePos.start == spacePos.end).option { (3, "Hybrid char not separated by space") },
          (2, "Named hybrid").some).flatten
        ng.warn(warns: _*)
    }
  }

  def name: RuleNodeMeta[Name] = rule {
    name2 | name3 | name4 | name1
  }

  def name1: RuleNodeMeta[Name] = rule {
    (uninomialCombo | uninomial) ~> { (u: NodeMeta[Uninomial]) => FactoryAST.name(u) }
  }

  def name4: RuleNodeMeta[Name] = rule {
    uninomialWord ~ space ~ approximation ~ (space ~ species).? ~> {
      (uM: NodeMeta[UninomialWord], apprM: NodeMeta[Approximation],
       spM: Option[NodeMeta[Species]]) =>
        FactoryAST.name(FactoryAST.uninomial(uM), approximation = apprM.some)
                  .warn((3, "Name is approximate"))
    }
  }

  def name2: RuleNodeMeta[Name] = rule {
    uninomialWord ~ space ~ comparison ~ (space ~ species).? ~> {
      (u: NodeMeta[UninomialWord], c: NodeMeta[Comparison], s: Option[NodeMeta[Species]]) =>
        val u1 = FactoryAST.uninomial(u)
        val nm = FactoryAST.name(uninomial = u1, species = s, comparison = c.some)
        nm.warn((3, "Name comparison"))
    }
  }

  def name3: RuleNodeMeta[Name] = rule {
    uninomialWord ~ (softSpace ~
      (subGenus ~> { Left(_: NodeMeta[SubGenus]) } |
       (subGenusOrSuperspecies ~> { Right(_: NodeMeta[SpeciesWord]) }))).? ~
    space ~ species ~ (space ~ comparison).? ~ (space ~ infraspeciesGroup).? ~> {
      (uwM: NodeMeta[UninomialWord],
       eitherGenusSuperspeciesM: Option[Either[NodeMeta[SubGenus], NodeMeta[SpeciesWord]]],
       speciesM: NodeMeta[Species],
       maybeComparisonM: Option[NodeMeta[Comparison]],
       maybeInfraspeciesGroupM: Option[NodeMeta[InfraspeciesGroup]]) =>
         val uM1 = FactoryAST.uninomial(uwM)
         val name = eitherGenusSuperspeciesM match {
           case None => FactoryAST.name(uM1, species = speciesM.some,
                                        comparison = maybeComparisonM,
                                        infraspecies = maybeInfraspeciesGroupM)
           case Some(Left(sgM)) =>
             FactoryAST.name(uM1, sgM.some, species = speciesM.some,
                             comparison = maybeComparisonM,
                             infraspecies = maybeInfraspeciesGroupM)
           case Some(Right(ssM)) =>
             val nm = for { _ <- ssM; u1 <- uM1; species <- speciesM;
                            cmp <- lift(maybeComparisonM);
                              infrOpt <- lift(maybeInfraspeciesGroupM) }
                        yield Name(u1, comparison = cmp,
                                   species = species.some, infraspecies = infrOpt)
             nm
         }
         name
    }
  }

  def infraspeciesGroup: RuleNodeMeta[InfraspeciesGroup] = rule {
    oneOrMore(infraspecies).separatedBy(space) ~> {
      (infs: Seq[NodeMeta[Infraspecies]]) => FactoryAST.infraspeciesGroup(infs)
    }
  }

  def infraspecies: RuleNodeMeta[Infraspecies] = rule {
    (rank ~ softSpace).? ~ !(authorEx) ~ word ~ (space ~ authorship).? ~> {
      (r: Option[NodeMeta[Rank]], sw: NodeMeta[SpeciesWord], a: Option[NodeMeta[Authorship]]) =>
        FactoryAST.infraspecies(sw, r, a)
    }
  }

  def species: RuleNodeMeta[Species] = rule {
     !(authorEx) ~ word ~ (softSpace ~ authorship).? ~ ','.? ~ &(spaceCharsEOI ++ "(") ~> {
      (sw: NodeMeta[SpeciesWord], a: Option[NodeMeta[Authorship]]) => FactoryAST.species(sw, a)
    }
  }

  def comparison: RuleNodeMeta[Comparison] = rule {
    capturePos("cf" ~ '.'.?) ~> { (p: CapturePosition) => FactoryAST.comparison(p) }
  }

  def approximation: RuleNodeMeta[Approximation] = rule {
    capturePos("sp." ~ spaceChars.? ~ "nr." | "sp." ~ spaceChars.? ~ "aff." | "monst." | "?" |
               (("spp" | "nr" | "sp" | "aff" | "species") ~ (&(spaceCharsEOI) | '.'))) ~> {
      (p: CapturePosition) => FactoryAST.approximation(p) }
  }

  def rankUninomial: RuleNodeMeta[Rank] = rule {
    capturePos(("sect" | "subsect" | "trib" | "subtrib" | "subser" | "ser" |
                "subgen" | "fam" | "subfam" | "supertrib") ~ '.'.?) ~ &(spaceCharsEOI) ~> {
      (p: CapturePosition) => FactoryAST.rank(p)
    }
  }

  def rank: RuleNodeMeta[Rank] = rule {
    rankForma | rankVar | rankSsp | rankOther | rankOtherUncommon
  }

  def rankOtherUncommon: RuleNodeMeta[Rank] = rule {
    capturePos("*" | "nat" | "f.sp" | "mut.") ~ &(spaceCharsEOI) ~> {
      (p: CapturePosition) => FactoryAST.rank(p).warn((3, "Uncommon rank"))
    }
  }

  def rankOther: RuleNodeMeta[Rank] = rule {
    capturePos("morph." | "nothosubsp." | "convar." | "pseudovar." | "sect." | "ser." | "subvar." |
               "subf." | "race" | "α" | "ββ" | "β" | "γ" | "δ" | "ε" | "φ" | "θ" | "μ" | "a." |
               "b." | "c." | "d." | "e." | "g." | "k." | "pv." | "pathovar." |
               ("ab." ~ (softSpace ~ "n.").?) | "st.") ~
      &(spaceCharsEOI) ~> { (p: CapturePosition) => FactoryAST.rank(p) }
  }

  def rankVar: RuleNodeMeta[Rank] = rule {
    capturePos("variety" | "[var.]" | "nvar." | ("var" ~ (&(spaceCharsEOI) | '.'))) ~> {
      (pos: CapturePosition) =>
        val varStr = (pos.end - pos.start == "nvar.".length) ? "nvar." | "var."
        FactoryAST.rank(pos, varStr.some)
    }
  }

  def rankForma: RuleNodeMeta[Rank] = rule {
    capturePos(("forma" | "fma" | "form" | "fo" | "f") ~ (&(spaceCharsEOI) | '.')) ~> {
      (p: CapturePosition) => FactoryAST.rank(p, "fm.".some)
    }
  }

  def rankSsp: RuleNodeMeta[Rank] = rule {
    capturePos(("ssp" | "subsp") ~ (&(spaceCharsEOI) | '.')) ~> {
      (p: CapturePosition) => FactoryAST.rank(p, "ssp.".some)
    }
  }

  def subGenusOrSuperspecies: RuleNodeMeta[SpeciesWord] = rule {
    ('(' ~ softSpace ~ word ~ softSpace ~ ')') ~> { (wM: NodeMeta[SpeciesWord]) =>
      wM.warn((2, "Ambiguity: subgenus or superspecies found"))
    }
  }

  def subGenus: RuleNodeMeta[SubGenus] = rule {
    '(' ~ softSpace ~ uninomialWord ~ softSpace ~ ')' ~> {
      (u: NodeMeta[UninomialWord]) => FactoryAST.subGenus(u)
    }
  }

  def uninomialCombo: RuleNodeMeta[Uninomial] = rule {
    (uninomialCombo1 | uninomialCombo2) ~> { (u: NodeMeta[Uninomial]) =>
      u.warn((2, "Combination of two uninomials"))
    }
  }

  def uninomialCombo1: RuleNodeMeta[Uninomial] = rule {
    uninomialWord ~ softSpace ~ subGenus ~ softSpace ~ authorship.? ~> {
      (uw: NodeMeta[UninomialWord], sg: NodeMeta[SubGenus], a: Option[NodeMeta[Authorship]]) =>
        FactoryAST.uninomial(sg.map { _.word }, a,
                             FactoryAST.rank(CapturePosition.empty, typ = "subgen.".some).some,
                             FactoryAST.uninomial(uw).some)
    }
  }

  def uninomialCombo2: RuleNodeMeta[Uninomial] = rule {
    (uninomial ~ softSpace ~ rankUninomial ~ softSpace ~ uninomial) ~> {
      (u1M: NodeMeta[Uninomial], rM: NodeMeta[Rank], u2M: NodeMeta[Uninomial]) =>
        val r = for { u1 <- u1M; r <- rM; u2 <- u2M } yield u2.copy(rank = r.some, parent = u1.some)
        r
      }
  }

  def uninomial: RuleNodeMeta[Uninomial] = rule {
    uninomialWord ~ (space ~ authorship).? ~> {
      (uM: NodeMeta[UninomialWord], aM: Option[NodeMeta[Authorship]]) =>
        val r = for { u <- uM; a <- lift(aM) } yield Uninomial(u, a)
        r
    }
  }

  def uninomialWord: RuleNodeMeta[UninomialWord] = rule {
    abbrGenus | capWord | twoLetterGenera
  }

  def abbrGenus: RuleNodeMeta[UninomialWord] = rule {
    capturePos(upperChar ~ lowerChar.? ~ '.') ~> { (wp: CapturePosition) =>
      FactoryAST.uninomialWord(wp).warn((3, "Abbreviated uninomial word"))
    }
  }

  def capWord: RuleNodeMeta[UninomialWord] = rule {
    (capWord2 | capWord1) ~> { (uw: NodeMeta[UninomialWord]) => {
      val word = input.sliceString(uw.node.pos.start, uw.node.pos.end)
      val hasForbiddenChars = word.exists { ch => sciCharsExtended.indexOf(ch) >= 0 ||
                                                  sciUpperCharExtended.indexOf(ch) >= 0 }
      uw.warn(
        hasForbiddenChars.option { (2, "Non-standard characters in canonical") }.toSeq: _*
      )
    }}
  }

  def capWord1: RuleNodeMeta[UninomialWord] = rule {
    capturePos(upperChar ~ lowerChar ~ oneOrMore(lowerChar) ~ '?'.?) ~> {
      (p: CapturePosition) =>
        val warns = (input.charAt(p.end - 1) == '?').option {
          (3, "Uninomial word with question mark")
        }.toVector
        FactoryAST.uninomialWord(p).warn(warns: _*)
    }
  }

  def capWord2: RuleNodeMeta[UninomialWord] = rule {
    capWord1 ~ dash ~ (capWord1 |
                      word1 ~> { (w: CapturePosition) => FactoryAST.uninomialWord(w) }) ~> {
      (uwM: NodeMeta[UninomialWord], wM: NodeMeta[UninomialWord]) =>
        val uw1M = for { uw <- uwM; w <- wM }
                   yield uw.copy(pos = CapturePosition(uw.pos.start, w.pos.end))
        uw1M
    }
  }

  def twoLetterGenera: RuleNodeMeta[UninomialWord] = rule {
    capturePos("Ca" | "Ea" | "Ge" | "Ia" | "Io" | "Ix" | "Lo" | "Oa" |
      "Ra" | "Ty" | "Ua" | "Aa" | "Ja" | "Zu" | "La" | "Qu" | "As" | "Ba") ~>
    { (p: CapturePosition) => FactoryAST.uninomialWord(p) }
  }

  def word: RuleNodeMeta[SpeciesWord] = rule {
    !(authorPrefix | rankUninomial | approximation | word4) ~
      (word3 | word2StartDigit | word2 | word1) ~ &(spaceCharsEOI ++ "()") ~> {
      (pos: CapturePosition) =>
        val word = input.sliceString(pos.start, pos.end)
        val warns = Vector(
          (word.indexOf(apostr) >= 0).option { (3, "Apostrophe is not allowed in canonical") },
          word(0).isDigit.option { (3, "Numeric prefix") },
          word.exists { ch => sciCharsExtended.indexOf(ch) >= 0 }.option {
            (2, "Non-standard characters in canonical")
          }
        )
        FactoryAST.speciesWord(pos).warn(warns.flatten: _*)
    }
  }

  def word4: Rule0 = rule {
    oneOrMore(lowerChar) ~ '.' ~ lowerChar
  }

  def word1: Rule1[CapturePosition] = rule {
    capturePos((LowerAlpha ~ dash).? ~ lowerChar ~ oneOrMore(lowerChar))
  }

  def word2StartDigit: Rule1[CapturePosition] = rule {
    capturePos(digitNonZero) ~ Digit.? ~ word2sep.? ~
      3.times(lowerChar) ~ capturePos(oneOrMore(lowerChar)) ~> {
      (p1: CapturePosition, p2: CapturePosition) => CapturePosition(p1.start, p2.end)
    }
  }

  def word2: Rule1[CapturePosition] = rule {
    capturePos(oneOrMore(lowerChar) ~ dash.? ~ oneOrMore(lowerChar))
  }

  def word3: Rule1[CapturePosition] = rule {
    capturePos(lowerChar) ~ zeroOrMore(lowerChar) ~ apostr ~ word1 ~> {
      (p1: CapturePosition, p2: CapturePosition) => CapturePosition(p1.start, p2.end)
    }
  }

  def hybridChar: Rule1[HybridChar] = rule {
    capturePos('×') ~> { (pos: CapturePosition) => HybridChar(pos) }
  }

  def unparsed: Rule1[Option[String]] = rule {
    capture(wordBorderChar ~ ANY.*).?
  }

  def approxName: RuleNodeMeta[NamesGroup] = rule {
    uninomial ~ space ~ (approxName1 | approxName2) ~> {
      (n: NodeMeta[Name]) =>
        FactoryAST.namesGroup(n).warn((3, "Name is approximate"))
    }
  }

  def approxNameIgnored: Rule1[Option[String]] = rule {
    (softSpace ~ capture(anyVisible.+ ~ (softSpace ~ anyVisible.+).*)).?
  }

  def approxName1: Rule[NodeMeta[Uninomial] :: HNil, NodeMeta[Name] :: HNil] = rule {
    approximation ~ approxNameIgnored ~> {
      (u: NodeMeta[Uninomial], appr: NodeMeta[Approximation], ign: Option[String]) =>
        FactoryAST.name(uninomial = u, approximation = appr.some, ignored = ign)
      }
  }

  def approxName2: Rule[NodeMeta[Uninomial] :: HNil, NodeMeta[Name] :: HNil] = rule {
    word ~ space ~ approximation ~ approxNameIgnored ~> {
      (u: NodeMeta[Uninomial], sw: NodeMeta[SpeciesWord],
       appr: NodeMeta[Approximation], ign: Option[String]) =>
        val nm = Name(uninomial = u.node, species = Species(sw.node).some,
                      approximation = appr.node.some, ignored = ign)
        NodeMeta(nm, u.warnings ++ sw.warnings ++ appr.warnings)
      }
  }

  def authorship: RuleNodeMeta[Authorship] = rule {
    (combinedAuthorship | basionymYearMisformed |
     basionymAuthorship | authorship1) ~ &(spaceCharsEOI ++ "(,:")
  }

  def combinedAuthorship: RuleNodeMeta[Authorship] = rule {
    combinedAuthorship1 | combinedAuthorship2 | combinedAuthorship3
  }

  def combinedAuthorship1: RuleNodeMeta[Authorship] = rule {
    basionymAuthorship ~ softSpace ~ authorEx ~ authorship1 ~> {
      (bauM: NodeMeta[Authorship], exM: NodeMeta[AuthorWord], exauM: NodeMeta[Authorship]) =>
        val authors1M = for { bau <- bauM; exau <- exauM }
                        yield bau.authors.copy(authorsEx = exau.authors.authors.some)
        val bau1M = for { bau <- bauM; authors1 <- authors1M; _ <- exM }
                    yield bau.copy(authors = authors1)

        bau1M.warn((2, "Ex authors are not required"))
    }
  }

  def combinedAuthorship2: RuleNodeMeta[Authorship] = rule {
    basionymAuthorship ~ softSpace ~ authorEmend ~ authorship1 ~> {
      (bauM: NodeMeta[Authorship], emendM: NodeMeta[AuthorWord], emendauM: NodeMeta[Authorship]) =>
        val authors1M = for { bau <- bauM; emendau <- emendauM }
                        yield bau.authors.copy(authorsEmend = emendau.authors.authors.some)
        val bau1M = for { bau <- bauM; authors1 <- authors1M; _ <- emendM }
                    yield bau.copy(authors = authors1)

        bau1M
    }
  }

  def combinedAuthorship3: RuleNodeMeta[Authorship] = rule {
    basionymAuthorship ~ softSpace ~ authorship1 ~> {
      (bauM: NodeMeta[Authorship], cauM: NodeMeta[Authorship]) =>
        val r = for { bau <- bauM; cau <- cauM }
                yield bau.copy(combination = cau.authors.some, basionymParsed = true)
        r
    }
  }

  def basionymYearMisformed: RuleNodeMeta[Authorship] = rule {
    '(' ~ softSpace ~ authorsGroup ~ softSpace ~ ')' ~ (softSpace ~ ',').? ~ softSpace ~ year ~> {
      (aM: NodeMeta[AuthorsGroup], yM: NodeMeta[Year]) =>
        val authors1 = aM.map { a => a.copy(authors = a.authors.copy(years = Seq(yM.node))) }
        FactoryAST.authorship(authors = authors1, inparenthesis = true, basionymParsed = true)
                  .warn((2, "Misformed basionym year"))
    }
  }

  def basionymAuthorship: RuleNodeMeta[Authorship] = rule {
    basionymAuthorship1 | basionymAuthorship2
  }

  def basionymAuthorship1: RuleNodeMeta[Authorship] = rule {
    '(' ~ softSpace ~ authorship1 ~ softSpace ~ ')' ~> {
      (aM: NodeMeta[Authorship]) =>
        val r = aM.map { a => a.copy(basionymParsed = true, inparenthesis = true) }
        r
    }
  }

  def basionymAuthorship2: RuleNodeMeta[Authorship] = rule {
    '(' ~ softSpace ~ '(' ~ softSpace ~ authorship1 ~ softSpace ~ ')' ~ softSpace ~ ')' ~> {
      (aM: NodeMeta[Authorship]) =>
        val r = aM.map { a => a.copy(basionymParsed = true, inparenthesis = true) }
        r.warn((3, "Authroship in double parentheses"))
    }
  }

  def authorship1: RuleNodeMeta[Authorship] = rule {
    authorsGroup ~> { (a: NodeMeta[AuthorsGroup]) => FactoryAST.authorship(a) }
  }

  def authorsGroup: RuleNodeMeta[AuthorsGroup] = rule {
    authorsTeam ~
      (softSpace ~ authorEmend.? ~ authorEx.? ~ authorsTeam ~> { (x, y, z) => (x, (y, z)) }).? ~> {
      (a: NodeMeta[AuthorsTeam],
       cau: Option[(Option[NodeMeta[AuthorWord]],
                    (Option[NodeMeta[AuthorWord]],
                      NodeMeta[AuthorsTeam]))]) =>
        val (auEmendOOM, auExOOM, auTeamOM) = Unzip[Option].unzip3(cau)
        val auEmendOM = auEmendOOM.flatten
        val auExOM = auExOOM.flatten
        (auEmendOM, auExOM) match {
          case (Some(auEmendM), Some(auExM)) =>
            throw new Exception("Authors ex and emend simultaneously?! Please report the issue!")
          case (None, auExMS) if auExMS.isDefined =>
            val auTeam1OM = for (auTeamM <- auTeamOM)
                            yield auTeamM.warn(auExMS.get.rawWarnings.toSeq: _*)
            val ag = FactoryAST.authorsGroup(a, authorsEx = auTeam1OM)
            ag.warn((2, "Ex authors are not required"))
          case (auEmendMS, None) if auEmendMS.isDefined =>
            val auTeam1OM = for (auTeamM <- auTeamOM)
                            yield auTeamM.warn(auEmendMS.get.rawWarnings.toSeq: _*)
            FactoryAST.authorsGroup(a, authorsEmend = auTeam1OM)
          case (None, None) =>
            FactoryAST.authorsGroup(a)
        }
    }
  }

  def authorsTeam: RuleNodeMeta[AuthorsTeam] = rule {
    author ~> { (aM: NodeMeta[Author]) => FactoryAST.authorsTeam(Seq(aM)) } ~
      zeroOrMore(authorSep ~ author ~> {
      (asM: NodeMeta[AuthorSep], auM: NodeMeta[Author]) =>
        for { au <- auM; as <- asM } yield au.copy(separator = as.some)
      } ~> { (atM: NodeMeta[AuthorsTeam], aM: NodeMeta[Author]) =>
        for (at <- atM; a <- aM) yield at.copy(authors = at.authors :+ a)
      }) ~ (softSpace ~ oneOrMore(','.? ~ softSpace.? ~ year)).? ~> {
      (atM: NodeMeta[AuthorsTeam], ysM: Option[Seq[NodeMeta[Year]]]) =>
        val ys1M = ysM.getOrElse(Seq())
        val at1M = for { at <- atM; ys <- lift(ys1M) } yield at.copy(years = ys)
        at1M
    }
  }

  def authorSep: RuleNodeMeta[AuthorSep] = rule {
    softSpace ~ capturePos((("," ~ softSpace).? ~ ("&" | "and" | "et" | "apud")) | ",") ~
      softSpace ~> { (pos: CapturePosition) => FactoryAST.authorSep(pos) }
  }

  def authorEx: RuleNodeMeta[AuthorWord] = rule {
    capturePos("ex" ~ '.'.? | "in") ~ space ~> { (pos: CapturePosition) =>
      val aw = FactoryAST.authorWord(pos)
      val warnOpt = (input.charAt(pos.end - 1) == '.').option { (3, "`ex` ends with dot") }
      aw.warn(warnOpt.toSeq: _*)
    }
  }

  def authorEmend: RuleNodeMeta[AuthorWord] = rule {
    capturePos("emend" ~ '.'.?) ~ space ~> { (pos: CapturePosition) => FactoryAST.authorWord(pos) }
  }

  def author: RuleNodeMeta[Author] = rule {
    (author1 | author2 | unknownAuthor) ~> { (auM: NodeMeta[Author]) =>
      val warnOpt =
        (auM.node.pos.end - auM.node.pos.start < 2).option { (3, "Author is too short") }
      auM.warn(warnOpt.toSeq: _*)
    }
  }

  def author1: RuleNodeMeta[Author] = rule {
    author2 ~ softSpace ~ filius ~> {
      (auM: NodeMeta[Author], filiusM: NodeMeta[AuthorWord]) =>
        val au1M = for { au <- auM; filius <- filiusM } yield au.copy(filius = filius.some)
        au1M
    }
  }

  def author2: RuleNodeMeta[Author] = rule {
    authorWord ~ zeroOrMore(authorWordSep) ~ !':' ~> {
      (auM: NodeMeta[AuthorWord], ausM: Seq[NodeMeta[AuthorWord]]) =>
        for { au <- auM; aus <- lift(ausM) } yield {
          val auths = aus.foldLeft(List(au)) { (as, a) =>
            if (a.separator == AuthorWordSeparator.Dash) {
              val a1 = as.last.copy(pos = CapturePosition(as.last.pos.start, a.pos.end))
              as.dropRight(1) :+ a1
            } else as :+ a
          }
          Author(auths)
        }
    }
  }

  def authorWordSep: RuleNodeMeta[AuthorWord] = rule {
    (ch(dash) ~ authorWordSoft ~> { (awM: NodeMeta[AuthorWord]) =>
      val aw1M = for (aw <- awM) yield aw.copy(separator = AuthorWordSeparator.Dash)
      aw1M
    }) |
    (softSpace ~ authorWord ~> { (awM: NodeMeta[AuthorWord]) =>
      val aw1M = for (aw <- awM) yield { aw.copy(separator = AuthorWordSeparator.Space) }
      aw1M
    })
  }

  def unknownAuthor: RuleNodeMeta[Author] = rule {
    capturePos("?" | (("auct" | "anon") ~ (&(spaceCharsEOI) | '.'))) ~> {
      (authPos: CapturePosition) =>
        val endsWithQuestion = input.charAt(authPos.end - 1) == '?'
        val warns = Seq((2, "Author is unknown").some,
                        endsWithQuestion.option((3, "Author as a question mark")))
        FactoryAST.author(Seq(FactoryAST.authorWord(authPos)), anon = true)
                  .warn(warns.flatten: _*)
    }
  }

  def authorWord: RuleNodeMeta[AuthorWord] = rule {
    (authorWord1 | authorWord2 | authorPrefix) ~> {
      (awM: NodeMeta[AuthorWord]) =>
        val word = input.sliceString(awM.node.pos.start, awM.node.pos.end)
        val authorIsUpperCase =
          word.length > 2 && word.forall { ch => ch == dash || authCharUpperStr.indexOf(ch) >= 0 }
        val warnOpt = authorIsUpperCase.option { (2, "Author in upper case") }
        awM.warn(warnOpt.toSeq: _*)
    }
  }

  def authorWord1: RuleNodeMeta[AuthorWord] = rule {
    capturePos("arg." | "et al.{?}" | ("et" | "&") ~ " al" ~ '.'.?) ~> {
      (pos: CapturePosition) => FactoryAST.authorWord(pos)
    }
  }

  def authorWord2: RuleNodeMeta[AuthorWord] = rule {
    capturePos("d'".? ~ authCharUpper ~ zeroOrMore(authCharUpper | authCharLower) ~ '.'.?) ~> {
      (pos: CapturePosition) => FactoryAST.authorWord(pos)
    }
  }

  def authorWordSoft: RuleNodeMeta[AuthorWord] = rule {
    capturePos((
      (authCharUpper ~ (oneOrMore(authCharUpper) | oneOrMore(authCharLower))) |
        oneOrMore(authCharLower)
    ) ~ '.'.?) ~> {
      (pos: CapturePosition) => FactoryAST.authorWord(pos)
    }
  }

  def filius: RuleNodeMeta[AuthorWord] = rule {
    capturePos("f." | "fil." | "filius") ~> { (pos: CapturePosition) => FactoryAST.authorWord(pos) }
  }

  def authorPrefix: RuleNodeMeta[AuthorWord] = rule {
    capturePos((("ab" | "af" | "bis" | "da" | "der" | "des" | "den" | "del" | "della" | "dela" |
                 "de" | "di" | "du" | "el" | "la" | "le" | "ter" | "van" | "zur" |
                 ("von" ~ (space ~ "dem").?) |
                 ("v" ~ (space ~ "d").?) | "d'" | "in't") ~ &(spaceCharsEOI)) |
      ("v." ~ (space.? ~ "d.").?) | "'t") ~> {
      (pos: CapturePosition) => FactoryAST.authorWord(pos)
    }
  }

  def year: RuleNodeMeta[Year] = rule {
    yearRange | yearApprox | yearWithParens | yearWithPage | yearWithDot | yearWithChar | yearNumber
  }

  def yearRange: RuleNodeMeta[Year] = rule {
    yearNumber ~ dash ~ capturePos(oneOrMore(Digit)) ~ zeroOrMore(Alpha ++ "?") ~> {
      (yStartM: NodeMeta[Year], yEnd: CapturePosition) =>
        val yrM = yStartM.map { yStart => yStart.copy(approximate = true, rangeEnd = Some(yEnd)) }
        yrM.warn((3, "Years range"))
    }
  }

  def yearWithDot: RuleNodeMeta[Year] = rule {
    yearNumber ~ '.' ~> { (y: NodeMeta[Year]) => y.warn((2, "Year with period")) }
  }

  def yearApprox: RuleNodeMeta[Year] = rule {
    '[' ~ softSpace ~ yearNumber ~ softSpace ~ ']' ~> {
      (yM: NodeMeta[Year]) =>
        val yrM = yM.map { y => y.copy(approximate = true) }
        yrM.warn((3, "Year with square brakets"))
    }
  }

  def yearWithPage: RuleNodeMeta[Year] = rule {
    (yearWithChar | yearNumber) ~ softSpace ~ ':' ~ softSpace ~ oneOrMore(Digit) ~> {
      (yM: NodeMeta[Year]) => yM.warn((3, "Year with page info"))
    }
  }

  def yearWithParens: RuleNodeMeta[Year] = rule {
    '(' ~ softSpace ~ (yearWithChar | yearNumber) ~ softSpace ~ ')' ~> {
      (yM: NodeMeta[Year]) =>
        val y1M = yM.map { y => y.copy(approximate = true) }
        y1M.warn((2, "Year with parentheses"))
      }
  }

  def yearWithChar: RuleNodeMeta[Year] = rule {
    yearNumber ~ capturePos(Alpha) ~> {
      (yM: NodeMeta[Year], pos: CapturePosition) =>
        val y1M = yM.map { y => y.copy(alpha = pos.some) }
        y1M.warn((2, "Year with latin character"))
    }
  }

  def yearNumber: RuleNodeMeta[Year] = rule {
    capturePos(CharPredicate("12") ~ CharPredicate("0789") ~ Digit ~ (Digit|'?') ~ '?'.?) ~> {
      (yPos: CapturePosition) =>
        val yrM = FactoryAST.year(yPos)
        if (input.charAt(yPos.end - 1) == '?') {
          val yr1M = yrM.map { yr => yr.copy(approximate = true) }
          yr1M.warn((2, "Year with question mark"))
        } else yrM
    }
  }

  def softSpace: Rule0 = rule { zeroOrMore(spaceChars) }

  def space: Rule0 = rule { oneOrMore(spaceChars) }
}

object Parser {
  implicit def nodeToMeta[T <: AstNode](node: T): NodeMeta[T] = NodeMeta[T](node)

  trait NodeMetaBase[T] {
    val node: T
    val warnings: Set[Warning]
  }

  def lift[T <: AstNode](nodeOpt: Option[NodeMeta[T]]): NodeMetaOpt[T] = nodeOpt match {
    case None => NodeMetaOpt(None)
    case Some(nodeMeta) => NodeMetaOpt(nodeMeta.node.some, nodeMeta.warnings)
  }

  def lift[T <: AstNode](nodeSeq: Seq[NodeMeta[T]]): NodeMetaSeq[T] = {
    val warns = nodeSeq.flatMap { _.warnings }.toSet
    NodeMetaSeq(nodeSeq.map { _.node }, warns)
  }

  case class NodeMetaSeq[T <: AstNode](node: Seq[T], warnings: Set[Warning] = Set.empty)
    extends NodeMetaBase[Seq[T]] {

    def map[M <: AstNode](f: Seq[T] => M): NodeMeta[M] = {
      val r = f(node)
      NodeMeta(r, warnings)
    }

    def flatMap[M <: AstNode](f: Seq[T] => NodeMeta[M]): NodeMeta[M] = {
      val r = f(node)
      r.copy(warnings = r.warnings ++ warnings)
    }
  }

  case class NodeMetaOpt[T <: AstNode](node: Option[T], warnings: Set[Warning] = Set.empty)
    extends NodeMetaBase[Option[T]] {

    def map[M <: AstNode](f: Option[T] => M): NodeMeta[M] = {
      val r = f(node)
      NodeMeta(r, warnings)
    }

    def flatMap[M <: AstNode](f: Option[T] => NodeMeta[M]): NodeMeta[M] = {
      val r = f(node)
      r.copy(warnings = r.warnings ++ warnings)
    }
  }

  case class NodeMeta[T <: AstNode](node: T, warnings: Set[Warning] = Set.empty)
    extends NodeMetaBase[T] {

    val rawWarnings: Set[(Int, String)] = warnings.map { w => Warning.unapply(w).get }

    def warn(warnings: (Int, String)*): NodeMeta[T] = {
      if (warnings.isEmpty) this
      else {
        val ws = this.warnings ++ warnings.map { Warning.tupled }
        this.copy(warnings = ws)
      }
    }

    def map[M <: AstNode](f: T => M): NodeMeta[M] = {
      val node1 = f(node)
      this.copy(node = node1)
    }

    def flatMap[M <: AstNode](f: T => NodeMeta[M]): NodeMeta[M] = {
      val nodeM = f(node)
      nodeM.copy(warnings = nodeM.warnings ++ warnings)
    }
  }

  private final val digitNonZero = Digit -- "0"
  private final val dash = '-'
  private final val word2sep = CharPredicate("." + dash)
  private final val spaceMiscoded = "　 \t\r\n\f_"
  private final val spaceChars = CharPredicate(" " + spaceMiscoded)
  private final val spaceCharsEOI = spaceChars ++ EOI ++ ";"
  private final val wordBorderChar = spaceChars ++ CharPredicate(";.,:()]")
  private final val sciCharsExtended = "æœſàâåãäáçčéèíìïňññóòôøõöúùüŕřŗššşž"
  private final val sciUpperCharExtended = "ÆŒÖ"
  private final val authCharUpperStr =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖØÙÚÛÜÝĆČĎİĶĹĺĽľŁłŅŌŐŒŘŚŜŞŠŸŹŻŽƒǾȘȚ"
  private final val charMiscoded = '�'
  private final val apostr = '\''
  private final val doubleSpacePattern = Pattern.compile("""[\s_]{2}""")
  private final val authCharLower = LowerAlpha ++
    "àáâãäåæçèéêëìíîïðñòóóôõöøùúûüýÿāăąćĉčďđ'ēĕėęěğīĭİıĺľłńņňŏőœŕřśşšţťũūŭůűźżžſǎǔǧșțȳß"
  private final val authCharUpper = CharPredicate(authCharUpperStr + charMiscoded)
  private final val upperChar = UpperAlpha ++ "Ë" ++ sciUpperCharExtended ++ charMiscoded
  private final val lowerChar = LowerAlpha ++ "ë" ++ sciCharsExtended ++ charMiscoded
  private final val anyVisible = upperChar ++ lowerChar ++ CharPredicate.Visible
}
