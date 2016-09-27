package org.globalnames.parser

import java.util.regex.Pattern

import org.parboiled2.CharPredicate.{Alpha, Digit, LowerAlpha, UpperAlpha}
import org.parboiled2._

import scalaz.Scalaz._

import shapeless._

class Parser(val input: ParserInput,
             preprocessChanges: Boolean,
             collectErrors: Boolean)
  extends org.parboiled2.Parser(collectErrors = collectErrors) {

  import Parser._

  type RuleWithWarning[T <: AstNode] = Rule1[NodeMeta[T]]

  def sciName: Rule2[ScientificName, Vector[Warning]] = rule {
    capturePos(softSpace ~ sciName1) ~ unparsed ~ EOI ~> {
      (ng: NodeMeta[NamesGroup], pos: CapturePosition, unparsedTail: Option[String]) =>
      val name = input.sliceString(pos.start, pos.end)

      val warnings = Vector(
        doubleSpacePattern.matcher(name).find().option {
          Warning(2, "Multiple adjacent space characters", ng.node)
        },
        name.exists { ch => spaceMiscoded.indexOf(ch) >= 0 }.option {
          Warning(3, "Non-standard space characters", ng.node)
        },
        name.exists { ch => authCharMiscoded == ch }.option {
          Warning(3, "Incorrect conversion to UTF-8", ng.node)
        },
        unparsedTail.map {
          case g if g.trim.isEmpty =>
            Warning(2, "Trailing whitespace", ng.node)
          case _ =>
            Warning(3, "Unparseable tail", ng.node)
        },
        preprocessChanges.option {
          Warning(2, "Name had to be changed by preprocessing", ng.node)
        }
      ).flatten ++ ng.warnings

      val worstLevel = if (warnings.isEmpty) 1
                       else warnings.maxBy { _.level }.level

      ScientificName(namesGroup = ng.node.some, unparsedTail = unparsedTail,
                     quality = worstLevel) :: warnings :: HNil
    }
  }

  def sciName1: RuleWithWarning[NamesGroup] = rule {
    hybridFormula | namedHybrid | approxName | sciName2
  }

  def sciName2: RuleWithWarning[NamesGroup] = rule {
    name ~> { (n: NodeMeta[Name]) => FactoryAST.namesGroup(Vector(n)) }
  }

  def hybridFormula: RuleWithWarning[NamesGroup] = rule {
    name ~ space ~ hybridChar ~ (hybridFormula1 | hybridFormula2)
  }

  def hybridFormula1: Rule[NodeMeta[Name] :: HybridChar :: HNil,
                           NodeMeta[NamesGroup] :: HNil] = rule {
    softSpace ~ species ~ (space ~ infraspeciesGroup).? ~> {
      (n: NodeMeta[Name], hc: HybridChar, s: NodeMeta[Species],
       i: Option[NodeMeta[InfraspeciesGroup]]) =>
        val uninomial1 = n.node.uninomial.copy(implied = true)
        val n1 = n.node.copy(genusParsed = true)
        val n2 = FactoryAST.name(uninomial = uninomial1,
                                 species = s.some,
                                 infraspecies = i)
        FactoryAST.namesGroup(Seq(n1, n2), hybrid = hc.some)
          .add(warnings = Seq((3, "Incomplete hybrid formula")))
          .changeWarningsRef((n.node, n1.node), (n.node.uninomial, uninomial1.node))
    }
  }

  def hybridFormula2: Rule[NodeMeta[Name] :: HybridChar :: HNil,
                           NodeMeta[NamesGroup] :: HNil] = rule {
    (space ~ name).? ~> {
      (n1: NodeMeta[Name], hc: HybridChar, n2: Option[NodeMeta[Name]]) =>
        val ng = n2 match {
          case None => FactoryAST.namesGroup(Seq(n1), hybrid = hc.some)
          case Some(name2) =>
            FactoryAST.namesGroup(name = Vector(n1, name2), hybrid = hc.some)
        }
        ng.add(warnings = Seq((2, "Hybrid formula")))
    }
  }

  def namedHybrid: RuleWithWarning[NamesGroup] = rule {
    hybridChar ~ capturePos(softSpace) ~ name ~> {
      (hc: HybridChar, spacePos: CapturePosition, n: NodeMeta[Name]) =>
        val ng = FactoryAST.namesGroup(Vector(n), hybrid = hc.some)
        val warns = Vector(
          (spacePos.start == spacePos.end).option { (3, "Hybrid char not separated by space") },
          (2, "Named hybrid").some).flatten
        ng.add(warnings = warns)
    }
  }

  def name: RuleWithWarning[Name] = rule {
    name2 | name3 | name1
  }

  def name1: RuleWithWarning[Name] = rule {
    (uninomialCombo | uninomial) ~> { (u: NodeMeta[Uninomial]) => FactoryAST.name(u) }
  }

  def name2: RuleWithWarning[Name] = rule {
    uninomialWord ~ space ~ comparison ~ (space ~ species).? ~> {
      (u: NodeMeta[UninomialWord], c: NodeMeta[Comparison], s: Option[NodeMeta[Species]]) =>
        val u1 = FactoryAST.uninomial(u)
        val nm = FactoryAST.name(uninomial = u1, species = s, comparison = c.some)
        nm.add(warnings = Seq((3, "Name comparison")))
          .changeWarningsRef((u.node, u1.node))
    }
  }

  def name3: RuleWithWarning[Name] = rule {
    uninomialWord ~ (softSpace ~ subGenus).? ~ softSpace ~
    species ~ (space ~ infraspeciesGroup).? ~> {
      (uw: NodeMeta[UninomialWord], maybeSubGenus: Option[NodeMeta[SubGenus]],
       species: NodeMeta[Species], maybeInfraspeciesGroup: Option[NodeMeta[InfraspeciesGroup]]) =>
         val u1 = FactoryAST.uninomial(uw)
         val name = FactoryAST.name(u1,
                                    maybeSubGenus,
                                    species = species.some,
                                    infraspecies = maybeInfraspeciesGroup)
         name.changeWarningsRef((uw.node, u1.node))
    }
  }

  def infraspeciesGroup: RuleWithWarning[InfraspeciesGroup] = rule {
    oneOrMore(infraspecies).separatedBy(space) ~> {
      (infs: Seq[NodeMeta[Infraspecies]]) => FactoryAST.infraspeciesGroup(infs)
    }
  }

  def infraspecies: RuleWithWarning[Infraspecies] = rule {
    (rank ~ softSpace).? ~ word ~ (space ~ authorship).? ~> {
      (r: Option[NodeMeta[Rank]], sw: NodeMeta[SpeciesWord], a: Option[NodeMeta[Authorship]]) =>
        FactoryAST.infraspecies(sw, r, a)
    }
  }

  def species: RuleWithWarning[Species] = rule {
    word ~ (softSpace ~ authorship).? ~ &(spaceCharsEOI ++ "(,:.;") ~> {
      (sw: NodeMeta[SpeciesWord], a: Option[NodeMeta[Authorship]]) => FactoryAST.species(sw, a)
    }
  }

  def comparison: RuleWithWarning[Comparison] = rule {
    capturePos("cf" ~ '.'.?) ~> { (p: CapturePosition) => FactoryAST.comparison(p) }
  }

  def approximation: RuleWithWarning[Approximation] = rule {
    capturePos("sp.nr." | "sp. nr." | "sp.aff." | "sp. aff." | "monst." | "?" |
               (("spp" | "nr" | "sp" | "aff" | "species") ~ (&(spaceCharsEOI) | '.'))) ~> {
      (p: CapturePosition) => FactoryAST.approximation(p) }
  }

  def rankUninomial: RuleWithWarning[Rank] = rule {
    capturePos(("sect" | "subsect" | "trib" | "subtrib" | "subser" | "ser" |
                "subgen" | "fam" | "subfam" | "supertrib") ~ '.'.?) ~ &(spaceCharsEOI) ~> {
      (p: CapturePosition) => FactoryAST.rank(p)
    }
  }

  def rank: RuleWithWarning[Rank] = rule {
    rankForma | rankVar | rankSsp | rankOther
  }

  def rankOther: RuleWithWarning[Rank] = rule {
    capturePos("morph." | "f.sp." | "mut." | "nat" |
               "nothosubsp." | "convar." | "pseudovar." | "sect." | "ser." |
               "subvar." | "subf." | "race" | "α" | "ββ" | "β" | "γ" | "δ" |
               "ε" | "φ" | "θ" | "μ" | "a." | "b." | "c." | "d." | "e." | "g." |
               "k." | "****" | "**" | "*") ~ &(spaceCharsEOI) ~> {
      (p: CapturePosition) =>
        val rank = input.sliceString(p.start, p.end)
        val warns = rank match {
          case "*" | "**" | "***" | "****" | "nat" | "f.sp" | "mut." => Vector((3, "Uncommon rank"))
          case _ => Vector.empty
        }
        FactoryAST.rank(p).add(warnings = warns)
    }
  }

  def rankVar: RuleWithWarning[Rank] = rule {
    capturePos("[var.]" | ("var" ~ (&(spaceCharsEOI) | '.'))) ~> {
      (p: CapturePosition) => FactoryAST.rank(p, "var.".some)
    }
  }

  def rankForma: RuleWithWarning[Rank] = rule {
    capturePos(("forma" | "fma" | "form" | "fo" | "f") ~ (&(spaceCharsEOI) | '.')) ~> {
      (p: CapturePosition) => FactoryAST.rank(p, "fm.".some)
    }
  }

  def rankSsp: RuleWithWarning[Rank] = rule {
    capturePos(("ssp" | "subsp") ~ (&(spaceCharsEOI) | '.')) ~> {
      (p: CapturePosition) => FactoryAST.rank(p, "ssp.".some)
    }
  }

  def subGenus: RuleWithWarning[SubGenus] = rule {
    '(' ~ softSpace ~ uninomialWord ~ softSpace ~ ')' ~> {
      (u: NodeMeta[UninomialWord]) => FactoryAST.subGenus(u)
    }
  }

  def uninomialCombo: RuleWithWarning[Uninomial] = rule {
    (uninomialCombo1 | uninomialCombo2) ~> { (u: NodeMeta[Uninomial]) =>
      u.add(warnings = Seq((2, "Combination of two uninomials")))
    }
  }

  def uninomialCombo1: RuleWithWarning[Uninomial] = rule {
    uninomialWord ~ softSpace ~ subGenus ~ softSpace ~ authorship.? ~> {
      (uw: NodeMeta[UninomialWord], sg: NodeMeta[SubGenus], a: Option[NodeMeta[Authorship]]) =>
        FactoryAST.uninomial(sg.map { _.word }, a,
                             FactoryAST.rank(CapturePosition.empty, typ = "subgen.".some).some,
                             FactoryAST.uninomial(uw).some)
    }
  }

  def uninomialCombo2: RuleWithWarning[Uninomial] = rule {
    (uninomial ~ softSpace ~ rankUninomial ~ softSpace ~ uninomial) ~> {
      (u1M: NodeMeta[Uninomial], rM: NodeMeta[Rank], u2M: NodeMeta[Uninomial]) =>
        val r = for { u1 <- u1M; r <- rM; u2 <- u2M } yield u2.copy(rank = r.some, parent = u1.some)
        r.changeWarningsRef((u2M.node, r.node))
      }
  }

  def uninomial: RuleWithWarning[Uninomial] = rule {
    uninomialWord ~ (space ~ authorship).? ~> {
      (uM: NodeMeta[UninomialWord], aM: Option[NodeMeta[Authorship]]) =>
        val r = for { u <- uM; a <- lift(aM) } yield Uninomial(u, a)
        r.changeWarningsRef((uM.node, r.node))
    }
  }

  def uninomialWord: RuleWithWarning[UninomialWord] = rule {
    abbrGenus | capWord | twoLetterGenera
  }

  def abbrGenus: RuleWithWarning[UninomialWord] = rule {
    capturePos(upperChar ~ lowerChar.? ~ lowerChar.? ~ '.') ~> { (wp: CapturePosition) =>
      FactoryAST.uninomialWord(wp).add(warnings = Seq((3, "Abbreviated uninomial word")))
    }
  }

  def capWord: RuleWithWarning[UninomialWord] = rule {
    (capWord2 | capWord1) ~> { (uw: NodeMeta[UninomialWord]) => {
      val word = input.sliceString(uw.node.pos.start, uw.node.pos.end)
      val hasForbiddenChars = word.exists { ch => sciCharsExtended.indexOf(ch) >= 0 ||
                                                  sciUpperCharExtended.indexOf(ch) >= 0 }
      uw.add(warnings =
        hasForbiddenChars.option { (2, "Non-standard characters in canonical") }.toVector)
    }}
  }

  def capWord1: RuleWithWarning[UninomialWord] = rule {
    capturePos(upperChar ~ lowerChar ~ oneOrMore(lowerChar) ~ '?'.?) ~> {
      (p: CapturePosition) =>
        val warns = (input.charAt(p.end - 1) == '?').option {
          (3, "Uninomial word with question mark")
        }.toVector
        FactoryAST.uninomialWord(p).add(warnings = warns)
    }
  }

  def capWord2: RuleWithWarning[UninomialWord] = rule {
    capWord1 ~ '-' ~ word1 ~> {
      (uwM: NodeMeta[UninomialWord], wPos: CapturePosition) =>
        val uw1M = uwM.map { uw => uw.copy(pos = CapturePosition(uwM.node.pos.start, wPos.end)) }
        uw1M.changeWarningsRef((uwM.node, uw1M.node))
    }
  }

  def twoLetterGenera: RuleWithWarning[UninomialWord] = rule {
    capturePos("Ca" | "Ea" | "Ge" | "Ia" | "Io" | "Io" | "Ix" | "Lo" | "Oa" |
      "Ra" | "Ty" | "Ua" | "Aa" | "Ja" | "Zu" | "La" | "Qu" | "As" | "Ba") ~>
    { (p: CapturePosition) => FactoryAST.uninomialWord(p) }
  }

  def word: RuleWithWarning[SpeciesWord] = rule {
    !(authorPre | rankUninomial | approximation) ~ (word3 | word2 | word1) ~
    &(spaceCharsEOI ++ "(.,:;") ~> {
      (pos: CapturePosition) =>
        val word = input.sliceString(pos.start, pos.end)
        val warns = Vector(
          (word.indexOf(apostr) >= 0).option { (3, "Apostrophe is not allowed in canonical") },
          word(0).isDigit.option { (3, "Numeric prefix") },
          word.exists { ch => sciCharsExtended.indexOf(ch) >= 0 }.option {
            (2, "Non-standard characters in canonical")
          }
        )
        FactoryAST.speciesWord(pos).add(warnings = warns.flatten)
    }
  }

  def word1: Rule1[CapturePosition] = rule {
    capturePos(lowerChar ~ oneOrMore(lowerChar))
  }

  def word2: Rule1[CapturePosition] = rule {
    capturePos(oneOrMore(lowerChar) | (1 to 2).times(CharPredicate(Digit))) ~ dash ~ word1 ~> {
      (p1: CapturePosition, p2: CapturePosition) => CapturePosition(p1.start, p2.end)
    }
  }

  def word3: Rule1[CapturePosition] = rule {
    capturePos(oneOrMore(lowerChar)) ~ apostr ~ word1 ~> {
      (p1: CapturePosition, p2: CapturePosition) => CapturePosition(p1.start, p2.end)
    }
  }

  def hybridChar: Rule1[HybridChar] = rule {
    capturePos('×') ~> { (pos: CapturePosition) => HybridChar(pos) }
  }

  def unparsed: Rule1[Option[String]] = rule {
    capture(wordBorderChar ~ ANY.*).?
  }

  def approxName: RuleWithWarning[NamesGroup] = rule {
    uninomial ~ space ~ (approxName1 | approxName2) ~> {
      (n: NodeMeta[Name]) =>
        FactoryAST.namesGroup(Seq(n)).add(warnings = Seq((3, "Name is approximate")))
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

  def approxName2: Rule[NodeMeta[Uninomial] :: HNil,
                        NodeMeta[Name] :: HNil] = rule {
    word ~ space ~ approximation ~ approxNameIgnored ~> {
      (u: NodeMeta[Uninomial], sw: NodeMeta[SpeciesWord],
       appr: NodeMeta[Approximation], ign: Option[String]) =>
        val nm = Name(uninomial = u.node, species = Species(sw.node).some,
                      approximation = appr.node.some, ignored = ign)
        NodeMeta(nm, u.warnings ++ sw.warnings ++ appr.warnings)
      }
  }

  def authorship: RuleWithWarning[Authorship] = rule {
    (combinedAuthorship | basionymYearMisformed |
     basionymAuthorship | authorship1) ~ &(spaceCharsEOI ++ "(,:")
  }

  def combinedAuthorship: RuleWithWarning[Authorship] = rule {
    combinedAuthorship1 | combinedAuthorship2
  }

  def combinedAuthorship1: RuleWithWarning[Authorship] = rule {
    basionymAuthorship ~ authorEx ~ authorship1 ~> {
      (bauM: NodeMeta[Authorship], exauM: NodeMeta[Authorship]) =>
        val authors1M =
          bauM.map { bau => bau.authors.copy(authorsEx = exauM.node.authors.authors.some) }
        val bau1M = bauM.map { bau => bau.copy(authors = authors1M.node) }
        bau1M.add(warnings = Seq((2, "Ex authors are not required")))
             .changeWarningsRef((bauM.node.authors, authors1M.node), (bauM.node, bau1M.node))
    }
  }

  def combinedAuthorship2: RuleWithWarning[Authorship] = rule {
    basionymAuthorship ~ softSpace ~ authorship1 ~> {
      (bauM: NodeMeta[Authorship], cauM: NodeMeta[Authorship]) =>
        val bau1 = bauM.node.copy(combination = cauM.node.authors.some, basionymParsed = true)
        val warns = bauM.warnings ++ cauM.warnings
        NodeMeta(bau1, warns).changeWarningsRef((bauM.node, bau1))

        val r = for { bau <- bauM; cau <- cauM }
          yield bau.copy(combination = cau.authors.some, basionymParsed = true)
        r.changeWarningsRef((bauM.node, r.node))
    }
  }

  def basionymYearMisformed: RuleWithWarning[Authorship] = rule {
    '(' ~ softSpace ~ authorsGroup ~ softSpace ~ ')' ~ (softSpace ~ ',').? ~ softSpace ~ year ~> {
      (aM: NodeMeta[AuthorsGroup], yM: NodeMeta[Year]) =>
        val authors1 = aM.map { a => a.copy(year = yM.node.some) }
        FactoryAST.authorship(authors = authors1, inparenthesis = true, basionymParsed = true)
          .add(warnings = Seq((2, "Misformed basionym year")))
          .changeWarningsRef((aM.node, authors1.node))
    }
  }

  def basionymAuthorship: RuleWithWarning[Authorship] = rule {
    basionymAuthorship1 | basionymAuthorship2
  }

  def basionymAuthorship1: RuleWithWarning[Authorship] = rule {
    '(' ~ softSpace ~ authorship1 ~ softSpace ~ ')' ~> {
      (aM: NodeMeta[Authorship]) =>
        val r = aM.map { a => a.copy(basionymParsed = true, inparenthesis = true) }
        r.changeWarningsRef((aM.node, r.node))
    }
  }

  def basionymAuthorship2: RuleWithWarning[Authorship] = rule {
    '(' ~ softSpace ~ '(' ~ softSpace ~ authorship1 ~ softSpace ~ ')' ~ softSpace ~ ')' ~> {
      (aM: NodeMeta[Authorship]) =>
        val r = aM.map { a => a.copy(basionymParsed = true, inparenthesis = true) }
        r.add(warnings = Seq((3, "Authroship in double parentheses")))
         .changeWarningsRef((aM.node, r.node))
    }
  }

  def authorship1: RuleWithWarning[Authorship] = rule {
    (authorsYear | authorsGroup) ~> { (a: NodeMeta[AuthorsGroup]) => FactoryAST.authorship(a) }
  }

  def authorsYear: RuleWithWarning[AuthorsGroup] = rule {
    authorsGroup ~ softSpace ~ (',' ~ softSpace).? ~ year ~> {
      (aM: NodeMeta[AuthorsGroup], yM: NodeMeta[Year]) =>
        val a1 = for { a <- aM; y <- yM } yield a.copy(year = y.some)
        a1.changeWarningsRef((aM.node, a1.node))
    }
  }

  def authorsGroup: RuleWithWarning[AuthorsGroup] = rule {
    authorsTeam ~ (authorEx ~ authorsTeam).? ~> {
      (a: NodeMeta[AuthorsTeam], exAu: Option[NodeMeta[AuthorsTeam]]) =>
        val ag = FactoryAST.authorsGroup(a, exAu)
        val warns = exAu.map { _ => ((2, "Ex authors are not required")) }.toVector
        ag.add(warnings = warns)
    }
  }

  def authorsTeam: RuleWithWarning[AuthorsTeam] = rule {
    oneOrMore(author).separatedBy(authorSep) ~> {
      (asM: Seq[NodeMeta[Author]]) => FactoryAST.authorsTeam(asM)
    }
  }

  def authorSep = rule { softSpace ~ ("," | "&" | "and" | "et") ~ softSpace }

  def authorEx = rule { space ~ ("ex" | "in") ~ space }

  def author: RuleWithWarning[Author] = rule {
    (author1 | author2 | unknownAuthor) ~> { (auM: NodeMeta[Author]) =>
      val warns = (auM.node.pos.end - auM.node.pos.start < 2).option { (3, "Author is too short") }
      auM.add(warnings = warns.toVector)
    }
  }

  def author1: RuleWithWarning[Author] = rule {
    author2 ~ softSpace ~ filius ~> {
      (auM: NodeMeta[Author], filiusM: NodeMeta[AuthorWord]) =>
        val au1M = for { au <- auM; filius <- filiusM } yield au.copy(filius = filius.some)
        au1M.changeWarningsRef((auM.node, au1M.node))
    }
  }

  def author2: RuleWithWarning[Author] = rule {
    authorWord ~ zeroOrMore(authorWordSep) ~ !(':') ~> {
      (auM: NodeMeta[AuthorWord], ausM: Seq[NodeMeta[AuthorWord]]) =>
        for { au <- auM; aus <- lift(ausM) } yield Author(au +: aus)
    }
  }

  def authorWordSep: RuleWithWarning[AuthorWord] = rule {
    capture(ch(dash) | softSpace) ~ authorWord ~> {
      (sep: String, awM: NodeMeta[AuthorWord]) =>
        val aw1M = awM.map { aw => sep match {
          case d if d.length == 1 && d(0) == dash =>
            aw.copy(separator = AuthorWordSeparator.Dash)
          case _ =>
            awM.node.copy(separator = AuthorWordSeparator.Space)
        }}
        aw1M.changeWarningsRef((awM.node, aw1M.node))
    }
  }

  def unknownAuthor: RuleWithWarning[Author] = rule {
    capturePos("?" | (("auct" | "anon" | "ht" | "hort") ~ (&(spaceCharsEOI) | '.'))) ~> {
      (authPos: CapturePosition) =>
        val endsWithQuestion = input.charAt(authPos.end - 1) == '?'
        val warns = Vector((2, "Author is unknown").some,
                           endsWithQuestion.option((3, "Author as a question mark")))
        FactoryAST.author(Seq(FactoryAST.authorWord(authPos)), anon = true)
                  .add(warnings = warns.flatten)
    }
  }

  def authorWord: RuleWithWarning[AuthorWord] = rule {
    (authorWord1 | authorWord2 | authorPre) ~> {
      (awM: NodeMeta[AuthorWord]) =>
        val word = input.sliceString(awM.node.pos.start, awM.node.pos.end)
        val authorIsUpperCase =
          word.length > 2 && word.forall { ch => ch == '-' || authCharUpperStr.indexOf(ch) >= 0 }
        val warns = authorIsUpperCase.option { (2, "Author in upper case") }.toVector
        awM.add(warnings = warns)
    }
  }

  def authorWord1: RuleWithWarning[AuthorWord] = rule {
    capturePos("arg." | "et al.{?}" | "et al." | "et al") ~> {
      (pos: CapturePosition) => FactoryAST.authorWord(pos)
    }
  }

  def authorWord2: RuleWithWarning[AuthorWord] = rule {
    capturePos("d'".? ~ authCharUpper ~ zeroOrMore(authCharUpper | authCharLower) ~ '.'.?) ~> {
      (pos: CapturePosition) => FactoryAST.authorWord(pos)
    }
  }

  def filius: RuleWithWarning[AuthorWord] = rule {
    capturePos("f." | "fil." | "filius") ~> { (pos: CapturePosition) => FactoryAST.authorWord(pos) }
  }

  def authorPre: RuleWithWarning[AuthorWord] = rule {
    capturePos("ab" | "af" | "bis" | "da" | "der" | "des" |
               "den" | "della" | "dela" | "de" | "di" | "du" |
               "la" | "ter" | "van" | "von" | "d'") ~ &(spaceCharsEOI) ~> {
      (pos: CapturePosition) => FactoryAST.authorWord(pos)
    }
  }

  def year: RuleWithWarning[Year] = rule {
    yearRange | yearApprox | yearWithParens | yearWithPage | yearWithDot | yearWithChar | yearNumber
  }

  def yearRange: RuleWithWarning[Year] = rule {
    yearNumber ~ '-' ~ capturePos(oneOrMore(Digit)) ~ zeroOrMore(Alpha ++ "?") ~> {
      (yStartM: NodeMeta[Year], yEnd: CapturePosition) =>
        val yrM = yStartM.map { yStart => yStart.copy(approximate = true, rangeEnd = Some(yEnd)) }
        yrM.add(warnings = Seq((3, "Years range")))
           .changeWarningsRef((yStartM.node, yrM.node))
    }
  }

  def yearWithDot: RuleWithWarning[Year] = rule {
    yearNumber ~ '.' ~> { (y: NodeMeta[Year]) => y.add(warnings = Seq((2, "Year with period"))) }
  }

  def yearApprox: RuleWithWarning[Year] = rule {
    '[' ~ softSpace ~ yearNumber ~ softSpace ~ ']' ~> {
      (yM: NodeMeta[Year]) =>
        val yrM = yM.map { y => y.copy(approximate = true) }
        yrM.add(warnings = Seq((3, "Year with square brakets")))
           .changeWarningsRef((yM.node, yrM.node))
    }
  }

  def yearWithPage: RuleWithWarning[Year] = rule {
    (yearWithChar | yearNumber) ~ softSpace ~ ':' ~ softSpace ~ oneOrMore(Digit) ~> {
      (yM: NodeMeta[Year]) => yM.add(warnings = Seq((3, "Year with page info")))
    }
  }

  def yearWithParens: RuleWithWarning[Year] = rule {
    '(' ~ softSpace ~ (yearWithChar | yearNumber) ~ softSpace ~ ')' ~> {
      (yM: NodeMeta[Year]) =>
        val y1M = yM.map { y => y.copy(approximate = true) }
        y1M.add(warnings = Seq((2, "Year with parentheses")))
           .changeWarningsRef((yM.node, y1M.node))
      }
  }

  def yearWithChar: RuleWithWarning[Year] = rule {
    yearNumber ~ capturePos(Alpha) ~> {
      (yM: NodeMeta[Year], pos: CapturePosition) =>
        val y1M = yM.map { y => y.copy(alpha = pos.some) }
        y1M.add(warnings = Seq((2, "Year with latin character")))
           .changeWarningsRef((yM.node, y1M.node))
    }
  }

  def yearNumber: RuleWithWarning[Year] = rule {
    capturePos(CharPredicate("12") ~ CharPredicate("0789") ~ Digit ~ (Digit|'?') ~ '?'.?) ~> {
      (yPos: CapturePosition) =>
        val yrM = FactoryAST.year(yPos)
        if (input.charAt(yPos.end - 1) == '?') {
          val yr1M = yrM.map { yr => yr.copy(approximate = true) }
          yr1M.add(warnings = Seq((2, "Year with question mark")))
        } else yrM
    }
  }

  def softSpace = rule {
    zeroOrMore(spaceChars)
  }

  def space = rule {
    oneOrMore(spaceChars)
  }
}

object Parser {
  implicit def nodeToMeta[T <: AstNode](node: T): NodeMeta[T] = NodeMeta[T](node)

  trait NodeMetaBase[T] {
    val node: T
    val warnings: Vector[Warning]
  }

  def lift[T <: AstNode](nodeOpt: Option[NodeMeta[T]]): NodeMetaOpt[T] = nodeOpt match {
    case None => NodeMetaOpt(None)
    case Some(nodeMeta) => NodeMetaOpt(nodeMeta.node.some, nodeMeta.warnings)
  }

  def lift[T <: AstNode](nodeSeq: Seq[NodeMeta[T]]): NodeMetaSeq[T] = {
    val warns = nodeSeq.flatMap { _.warnings }.toVector
    NodeMetaSeq(nodeSeq.map { _.node }, warns)
  }

  case class NodeMetaSeq[T <: AstNode](node: Seq[T], warnings: Vector[Warning] = Vector.empty)
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

  case class NodeMetaOpt[T <: AstNode](node: Option[T], warnings: Vector[Warning] = Vector.empty)
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

  case class NodeMeta[T <: AstNode](node: T, warnings: Vector[Warning] = Vector.empty)
    extends NodeMetaBase[T] {

    def changeWarningsRef(substitutions: (AstNode, AstNode)*): NodeMeta[T] = {
      val substWarnsMap = substitutions.toMap
      val ws = warnings.map { w =>
        substWarnsMap.get(w.node).map { subst => w.copy(node = subst) }.getOrElse(w)
      }
      this.copy(warnings = ws)
    }

    def add(warnings: Seq[(Int, String)] = Seq.empty): NodeMeta[T] = {
      if (warnings.isEmpty) this
      else {
        val ws =
          this.warnings ++ warnings.map { case (level, message) => Warning(level, message, node) }
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

  final val dash = '-'
  final val spaceMiscoded = "　 \t\r\n\f_"
  final val spaceChars = CharPredicate(" " + spaceMiscoded)
  final val spaceCharsEOI = spaceChars ++ EOI ++ ";"
  final val wordBorderChar = spaceChars ++ CharPredicate(";.,:()]")
  final val sciCharsExtended = "æœſàâåãäáçčéèíìïňññóòôøõöúùüŕřŗššşž"
  final val sciUpperCharExtended = "ÆŒ"
  final val authCharUpperStr =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖØÙÚÛÜÝĆČĎİĶĹĺĽľŁłŅŌŐŒŘŚŜŞŠŸŹŻŽƒǾȘȚ"
  final val authCharMiscoded = '�'
  final val apostr = '\''
  final val doubleSpacePattern = Pattern.compile("""[\s_]{2}""")
  final val authCharLower = LowerAlpha ++
    "àáâãäåæçèéêëìíîïðñòóóôõöøùúûüýÿāăąćĉčďđ'-ēĕėęěğīĭİıĺľłńņňŏőœŕřśşšţťũūŭůűźżžſǎǔǧșțȳß"
  final val authCharUpper = CharPredicate(authCharUpperStr + authCharMiscoded)
  final val upperChar = UpperAlpha ++ "Ë" ++ sciUpperCharExtended
  final val lowerChar = LowerAlpha ++ "ë" ++ sciCharsExtended
  final val anyVisible = upperChar ++ lowerChar ++ CharPredicate.Visible
}
