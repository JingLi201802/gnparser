package org.globalnames.parser

import org.parboiled2.CharPredicate.{Alpha, Digit, LowerAlpha, UpperAlpha}
import org.parboiled2._

import scalaz.Scalaz._

import shapeless._

class Parser(val input: ParserInput,
             preprocessChanges: Boolean,
             collectErrors: Boolean)
  extends org.parboiled2.Parser(collectErrors = collectErrors) {

  import Parser._

  case class NodeWarned[T <: AstNode](astNode: T,
                                      warns: Vector[Warning] = Vector.empty)

  type RuleWithWarning[T <: AstNode] = Rule1[NodeWarned[T]]

  def sciName: Rule2[ScientificName, Vector[Warning]] = rule {
    capturePos(softSpace ~ sciName1) ~ unparsed ~ EOI ~> {
      (ng: NodeWarned[NamesGroup], pos: CapturePosition,
       unparsedTail: Option[String]) =>
      val name = input.sliceString(pos.start, pos.end)

      val warnings = Vector(
        doubleSpacePattern.findFirstIn(name).map { _ =>
          Warning(2, "Multiple adjacent space characters", ng.astNode.id)
        },
        name.exists { ch => spaceMiscoded.indexOf(ch) >= 0 }.option {
          Warning(3, "Non-standard space characters", ng.astNode.id)
        },
        name.exists { ch => authCharMiscoded == ch }.option {
          Warning(3, "Incorrect conversion to UTF-8", ng.astNode.id)
        },
        unparsedTail.map {
          case g if g.trim.isEmpty =>
            Warning(2, "Trailing whitespace", ng.astNode.id)
          case _ =>
            Warning(3, "Unparseable tail", ng.astNode.id)
        },
        preprocessChanges.option {
          Warning(2, "Name had to be changed by preprocessing", ng.astNode.id)
        }
      ).flatten ++ ng.warns

      val worstLevel = if (warnings.isEmpty) 1
                       else warnings.sortBy { _.level }.last.level

      ScientificName(namesGroup = ng.astNode.some, unparsedTail = unparsedTail,
                     quality = worstLevel) :: warnings :: HNil
    }
  }

  def sciName1: RuleWithWarning[NamesGroup] = rule {
    hybridFormula | namedHybrid | approxName | sciName2
  }

  def sciName2: RuleWithWarning[NamesGroup] = rule {
    name ~> { (n: NodeWarned[Name]) =>
      NodeWarned(NamesGroup(AstNode.id, Vector(n.astNode)), n.warns)
    }
  }

  def hybridFormula: RuleWithWarning[NamesGroup] = rule {
    name ~ space ~ hybridChar ~ (hybridFormula1 | hybridFormula2)
  }

  def hybridFormula1: Rule[NodeWarned[Name] :: HybridChar :: HNil,
                           NodeWarned[NamesGroup] :: HNil] = rule {
    softSpace ~ species ~ (space ~ infraspeciesGroup).? ~> {
      (n: NodeWarned[Name], hc: HybridChar, s: NodeWarned[Species],
       i: Option[NodeWarned[InfraspeciesGroup]]) =>
      val ng = NamesGroup(AstNode.id,
        name = Vector(n.astNode.copy(genusParsed = true),
                      Name(AstNode.id,
                           uninomial = n.astNode.uninomial.copy(implied = true),
                           species = s.astNode.some,
                           infraspecies = i.map{_.astNode})),
        hybrid = hc.some)
      val warns = (Warning(3, "Incomplete hybrid formula", ng.id) +: (n.warns ++ s.warns)) ++
                    i.map { _.warns }.orZero
      NodeWarned(ng, warns)
    }
  }

  def hybridFormula2: Rule[NodeWarned[Name] :: HybridChar :: HNil,
                           NodeWarned[NamesGroup] :: HNil] = rule {
    (space ~ name).? ~> {
      (n1: NodeWarned[Name], hc: HybridChar, n2: Option[NodeWarned[Name]]) =>
        val ng = n2 match {
          case None =>
            NamesGroup(AstNode.id, name = Vector(n1.astNode), hybrid = hc.some)
          case Some(name2) =>
            NamesGroup(AstNode.id, name = Vector(n1.astNode, name2.astNode),
                       hybrid = hc.some)
        }
        val warns = (Warning(2, "Hybrid formula", ng.id) +: n1.warns) ++ n2.map { _.warns }.orZero
        NodeWarned(ng, warns)
    }
  }

  def namedHybrid: RuleWithWarning[NamesGroup] = rule {
    hybridChar ~ softSpace ~ name ~> { (hc: HybridChar, n: NodeWarned[Name]) =>
      val ng = NamesGroup(AstNode.id, Vector(n.astNode), hybrid = hc.some)
      val warns = Vector(
        (n.astNode.uninomial.pos.start == 1).option {
          Warning(3, "Hybrid char not separated by space", ng.id)
        },
        Warning(2, "Named hybrid", ng.id).some).flatten
      NodeWarned(ng, warns)
    }
  }

  def name: RuleWithWarning[Name] = rule {
    name2 | name3 | name1
  }

  def name1: RuleWithWarning[Name] = rule {
    (uninomialCombo | uninomial) ~> { (u: NodeWarned[Uninomial]) =>
      NodeWarned(Name(AstNode.id, u.astNode), u.warns)
    }
  }

  def name2: RuleWithWarning[Name] = rule {
    uninomialWord ~ space ~ comparison ~ (space ~ species).? ~>
    {(u: NodeWarned[UninomialWord], c: NodeWarned[Comparison],
      s: Option[NodeWarned[Species]]) =>
      val nm = Name(AstNode.id, uninomial = Uninomial(AstNode.id, u.astNode.pos),
                    species = s.map{_.astNode}, comparison = c.astNode.some)
      val warns = (Warning(3, "Name comparison", nm.id) +: (u.warns ++ c.warns)) ++
                    s.map { _.warns }.orZero
      NodeWarned(nm, warns)
    }
  }

  def name3: RuleWithWarning[Name] = rule {
    uninomialWord ~ (softSpace ~ subGenus).? ~ softSpace ~
    species ~ (space ~ infraspeciesGroup).? ~>
    {(uw: NodeWarned[UninomialWord],
      maybeSubGenus: Option[NodeWarned[SubGenus]], species: NodeWarned[Species],
      maybeInfraspeciesGroup: Option[NodeWarned[InfraspeciesGroup]]) =>
        val node = Name(AstNode.id,
                        Uninomial(AstNode.id, uw.astNode.pos),
                        maybeSubGenus.map{_.astNode},
                        species = species.astNode.some,
                        infraspecies = maybeInfraspeciesGroup.map{_.astNode})
        val warns = uw.warns ++ maybeSubGenus.map { _.warns }.orZero ++
                    species.warns ++ maybeInfraspeciesGroup.map { _.warns }.orZero
        NodeWarned(node, warns)
    }
  }

  def infraspeciesGroup: RuleWithWarning[InfraspeciesGroup] = rule {
    oneOrMore(infraspecies).separatedBy(space) ~>
    { (inf: Seq[NodeWarned[Infraspecies]]) =>
      NodeWarned(InfraspeciesGroup(AstNode.id, inf.map{_.astNode}),
           inf.flatMap{_.warns}.toVector)
    }
  }

  def infraspecies: RuleWithWarning[Infraspecies] = rule {
    (rank ~ softSpace).? ~ word ~ (space ~ authorship).? ~>
    { (r: Option[NodeWarned[Rank]], sw: NodeWarned[SpeciesWord],
       a: Option[NodeWarned[Authorship]]) =>
      NodeWarned(Infraspecies(AstNode.id, sw.astNode.pos,
                              r.map { _.astNode }, a.map { _.astNode }),
                 r.map { _.warns }.orZero ++ sw.warns ++ a.map { _.warns }.orZero)
    }
  }

  def species: RuleWithWarning[Species] = rule {
    word ~ (softSpace ~ authorship).? ~ &(spaceCharsEOI ++ "(,:.;") ~> {
      (sw: NodeWarned[SpeciesWord], a: Option[NodeWarned[Authorship]]) =>
        NodeWarned(Species(AstNode.id, sw.astNode.pos, a.map{_.astNode}),
                   sw.warns ++ a.map { _.warns }.orZero)
    }
  }

  def comparison: RuleWithWarning[Comparison] = rule {
    capturePos("cf" ~ '.'.?) ~> { (p: CapturePosition) =>
      NodeWarned(Comparison(AstNode.id, p), Vector.empty)
    }
  }

  def approximation: RuleWithWarning[Approximation] = rule {
    capturePos("sp.nr." | "sp. nr." | "sp.aff." | "sp. aff." | "monst." | "?" |
      (("spp" | "nr" | "sp" | "aff" | "species") ~ (&(spaceCharsEOI) | '.'))) ~>
      { (p: CapturePosition) => NodeWarned(Approximation(AstNode.id, p)) }
  }

  def rankUninomial: RuleWithWarning[Rank] = rule {
    capturePos(("sect" | "subsect" | "trib" | "subtrib" | "subser" | "ser" |
      "subgen" | "fam" | "subfam" | "supertrib") ~ '.'.?) ~ &(spaceCharsEOI) ~>
      { (p: CapturePosition) => NodeWarned(Rank(AstNode.id, p)) }
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
         val r = Rank(AstNode.id, p)
         val rank = input.sliceString(p.start, p.end)
         val warns = rank match {
           case "*" | "**" | "***" | "****" | "nat" | "f.sp" | "mut." =>
             Vector(Warning(3, "Uncommon rank", r.id))
           case _ => Vector.empty
         }
         NodeWarned(r, warns)
     }
  }

  def rankVar: RuleWithWarning[Rank] = rule {
    capturePos("[var.]" | ("var" ~ (&(spaceCharsEOI) | '.'))) ~>
      { (p: CapturePosition) => NodeWarned(Rank(AstNode.id, p, "var.".some)) }
  }

  def rankForma: RuleWithWarning[Rank] = rule {
    capturePos(("forma"  | "fma" | "form" | "fo" | "f") ~
    (&(spaceCharsEOI) | '.')) ~> { (p: CapturePosition) =>
      NodeWarned(Rank(AstNode.id, p, "fm.".some))
    }
  }

  def rankSsp: RuleWithWarning[Rank] = rule {
    capturePos(("ssp" | "subsp") ~ (&(spaceCharsEOI) | '.')) ~>
      { (p: CapturePosition) => NodeWarned(Rank(AstNode.id, p, "ssp.".some)) }
  }

  def subGenus: RuleWithWarning[SubGenus] = rule {
    '(' ~ softSpace ~ uninomialWord ~ softSpace ~ ')' ~> {
      (u: NodeWarned[UninomialWord]) =>
        NodeWarned(SubGenus(AstNode.id, u.astNode), u.warns)
    }
  }

  def uninomialCombo: RuleWithWarning[Uninomial] = rule {
    (uninomialCombo1 | uninomialCombo2) ~> { (u: NodeWarned[Uninomial]) =>
      val warns = Warning(2, "Combination of two uninomials", u.astNode.id) +: u.warns
      NodeWarned(u.astNode, warns)
    }
  }

  def uninomialCombo1: RuleWithWarning[Uninomial] = rule {
    uninomialWord ~ softSpace ~ subGenus ~ softSpace ~ authorship.? ~>
    {(uw: NodeWarned[UninomialWord], sg: NodeWarned[SubGenus],
      a: Option[NodeWarned[Authorship]]) =>
      val u = Uninomial(AstNode.id, sg.astNode.pos, a.map { _.astNode },
                Rank(AstNode.id, CapturePosition.empty, typ = "subgen.".some).some,
                Uninomial(AstNode.id, uw.astNode.pos).some)
      val warns = uw.warns ++ sg.warns ++ a.map { _.warns }.orZero
      NodeWarned(u, warns)
    }
  }

  def uninomialCombo2: RuleWithWarning[Uninomial] = rule {
    (uninomial ~ softSpace ~ rankUninomial ~ softSpace ~ uninomial) ~> {
      (u1: NodeWarned[Uninomial], r: NodeWarned[Rank],
       u2: NodeWarned[Uninomial]) =>
        val uw = u2.astNode.copy(rank = r.astNode.some, parent = u1.astNode.some)
        val warns = u1.warns ++ r.warns ++ u2.warns
        NodeWarned(uw, warns)
      }
  }

  def uninomial: RuleWithWarning[Uninomial] = rule {
    uninomialWord ~ (space ~ authorship).? ~>
    { (u: NodeWarned[UninomialWord],
       authorship: Option[NodeWarned[Authorship]]) =>
      val warns = u.warns ++ authorship.map { _.warns }.orZero
      NodeWarned(Uninomial(AstNode.id, u.astNode.pos,
                 authorship.map { _.astNode }), warns)
    }
  }

  def uninomialWord: RuleWithWarning[UninomialWord] = rule {
    abbrGenus | capWord | twoLetterGenera
  }

  def abbrGenus: RuleWithWarning[UninomialWord] = rule {
    capturePos(upperChar ~ lowerChar.? ~ lowerChar.? ~ '.') ~> {
      (wp: CapturePosition) =>
        val uw = UninomialWord(AstNode.id, wp)
        NodeWarned(uw, Vector(Warning(3, "Abbreviated uninomial word", uw.id)))
    }
  }

  def capWord: RuleWithWarning[UninomialWord] = rule {
    (capWord2 | capWord1) ~> { (uw: NodeWarned[UninomialWord]) => {
      val word = input.sliceString(uw.astNode.pos.start,
                                         uw.astNode.pos.end)
      val hasForbiddenChars =
        word.exists { ch => sciCharsExtended.indexOf(ch) >= 0 ||
                            sciUpperCharExtended.indexOf(ch) >= 0 }
      val warns = hasForbiddenChars.option {
        Warning(2, "Non-standard characters in canonical", uw.astNode.id)
      }.toVector ++ uw.warns
      NodeWarned(uw.astNode, warns)
    }}
  }

  def capWord1: RuleWithWarning[UninomialWord] = rule {
    capturePos(upperChar ~ lowerChar ~ oneOrMore(lowerChar) ~ '?'.?) ~> {
      (p: CapturePosition) =>
        val uw = UninomialWord(AstNode.id, p)
        val warns = (input.charAt(p.end - 1) == '?').option {
          Warning(3, "Uninomial word with question mark", uw.id)
        }.toVector
        NodeWarned(uw, warns)
    }
  }

  def capWord2: RuleWithWarning[UninomialWord] = rule {
    capWord1 ~ '-' ~ word1 ~> {
      (uw: NodeWarned[UninomialWord], wPos: CapturePosition) =>
        NodeWarned(uw.astNode.copy(pos = CapturePosition(uw.astNode.pos.start,
                                                         wPos.end)),
                   uw.warns)
    }
  }

  def twoLetterGenera: RuleWithWarning[UninomialWord] = rule {
    capturePos("Ca" | "Ea" | "Ge" | "Ia" | "Io" | "Io" | "Ix" | "Lo" | "Oa" |
      "Ra" | "Ty" | "Ua" | "Aa" | "Ja" | "Zu" | "La" | "Qu" | "As" | "Ba") ~>
    { (p: CapturePosition) => NodeWarned(UninomialWord(AstNode.id, p)) }
  }

  def word: RuleWithWarning[SpeciesWord] = rule {
    !(authorPre | rankUninomial | approximation) ~ (word3 | word2 | word1) ~
      &(spaceCharsEOI ++ "(.,:;") ~> {
      (pos: CapturePosition) =>
        val sw = SpeciesWord(AstNode.id, pos)
        val word = input.sliceString(pos.start, pos.end)
        val warns = Vector(
          (word.indexOf(apostr) >= 0).option {
            Warning(3, "Apostrophe is not allowed in canonical", sw.id)
          },
          word(0).isDigit.option {
            Warning(3, "Numeric prefix", sw.id)
          },
          word.exists { ch => sciCharsExtended.indexOf(ch) >= 0 }.option {
            Warning(2, "Non-standard characters in canonical", sw.id)
          }
        )
        NodeWarned(sw, warns.flatten)
    }
  }

  def word1: Rule1[CapturePosition] = rule {
    capturePos(lowerChar ~ oneOrMore(lowerChar))
  }

  def word2: Rule1[CapturePosition] = rule {
    capturePos(oneOrMore(lowerChar) | (1 to 2).times(CharPredicate(Digit))) ~
      dash ~ word1 ~> {
      (p1: CapturePosition, p2: CapturePosition) =>
        CapturePosition(p1.start, p2.end)
    }
  }

  def word3: Rule1[CapturePosition] = rule {
    capturePos(oneOrMore(lowerChar)) ~ apostr ~ word1 ~> {
      (p1: CapturePosition, p2: CapturePosition) =>
        CapturePosition(p1.start, p2.end)
    }
  }

  def hybridChar: Rule1[HybridChar] = rule {
    capturePos('×') ~> { (pos: CapturePosition) => HybridChar(AstNode.id, pos) }
  }

  def unparsed: Rule1[Option[String]] = rule {
    capture(wordBorderChar ~ ANY.*).?
  }

  def approxName: RuleWithWarning[NamesGroup] = rule {
    uninomial ~ space ~ (approxName1 | approxName2) ~> {
      (n: NodeWarned[Name]) =>
        val ng = NamesGroup(AstNode.id, name = Vector(n.astNode))
        NodeWarned(ng, Warning(3, "Name is approximate", ng.id) +: n.warns)
    }
  }

  def approxNameIgnored: Rule1[Option[String]] = rule {
    (softSpace ~ capture(anyVisible.+ ~ (softSpace ~ anyVisible.+).*)).?
  }

  def approxName1: Rule[NodeWarned[Uninomial] :: HNil,
                        NodeWarned[Name] :: HNil] = rule {
    approximation ~ approxNameIgnored ~>
      { (u: NodeWarned[Uninomial], appr: NodeWarned[Approximation],
         ign: Option[String]) =>
        val nm = Name(AstNode.id, uninomial = u.astNode,
                      approximation = appr.astNode.some, ignored = ign)
        NodeWarned(nm, u.warns ++ appr.warns)
      }
  }

  def approxName2: Rule[NodeWarned[Uninomial] :: HNil,
                        NodeWarned[Name] :: HNil] = rule {
    word ~ space ~ approximation ~ approxNameIgnored ~>
      { (u: NodeWarned[Uninomial], sw: NodeWarned[SpeciesWord],
         appr: NodeWarned[Approximation], ign: Option[String]) =>
        val nm = Name(AstNode.id, uninomial = u.astNode,
                      species = Species(AstNode.id, sw.astNode.pos).some,
                      approximation = appr.astNode.some,
                      ignored = ign)
        NodeWarned(nm, u.warns ++ sw.warns ++ appr.warns)
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
    basionymAuthorship ~ authorEx ~ authorship1 ~>
    { (bau: NodeWarned[Authorship], exau: NodeWarned[Authorship]) =>
      val bau1 = bau.astNode.copy(
        authors = bau.astNode.authors.copy(
          authorsEx = exau.astNode.authors.authors.some))
      NodeWarned(bau1, Warning(2, "Ex authors are not required", bau1.id) +:
                 (bau.warns ++ exau.warns))
    }
  }

  def combinedAuthorship2: RuleWithWarning[Authorship] = rule {
    basionymAuthorship ~ softSpace ~ authorship1 ~>
    {(bau: NodeWarned[Authorship], cau: NodeWarned[Authorship]) =>
      val bau1 = bau.astNode.copy(combination = cau.astNode.authors.some,
                                  basionymParsed = true)
      NodeWarned(bau1, bau.warns ++ cau.warns)
    }
  }

  def basionymYearMisformed: RuleWithWarning[Authorship] = rule {
    '(' ~ softSpace ~ authorsGroup ~ softSpace ~ ')' ~ (softSpace ~ ',').? ~
    softSpace ~ year ~>  {
      (a: NodeWarned[AuthorsGroup], y: NodeWarned[Year]) => {
        val as = Authorship(AstNode.id,
                            authors = a.astNode.copy(year = y.astNode.some),
                            inparenthesis = true, basionymParsed = true)
        NodeWarned(as, (Warning(2, "Misformed basionym year", as.id) +: y.warns) ++ a.warns)
      }
    }
  }

  def basionymAuthorship: RuleWithWarning[Authorship] = rule {
    basionymAuthorship1 | basionymAuthorship2
  }

  def basionymAuthorship1: RuleWithWarning[Authorship] = rule {
    '(' ~ softSpace ~ authorship1 ~ softSpace ~ ')' ~> {
      (a: NodeWarned[Authorship]) =>
        val as = a.astNode.copy(basionymParsed = true, inparenthesis = true)
        NodeWarned(as, a.warns)
    }
  }

  def basionymAuthorship2: RuleWithWarning[Authorship] = rule {
    '(' ~ softSpace ~ '(' ~ softSpace ~ authorship1 ~ softSpace ~ ')' ~
    softSpace ~ ')' ~> { (a: NodeWarned[Authorship]) =>
      val as = a.astNode.copy(basionymParsed = true, inparenthesis = true)
      NodeWarned(as, Warning(3, "Authroship in double parentheses", as.id) +: a.warns)
    }
  }

  def authorship1: RuleWithWarning[Authorship] = rule {
    (authorsYear | authorsGroup) ~> { (a: NodeWarned[AuthorsGroup]) =>
      NodeWarned(Authorship(AstNode.id, a.astNode), a.warns)
    }
  }

  def authorsYear: RuleWithWarning[AuthorsGroup] = rule {
    authorsGroup ~ softSpace ~ (',' ~ softSpace).? ~ year ~>
    { (a: NodeWarned[AuthorsGroup], y: NodeWarned[Year]) =>
      NodeWarned(a.astNode.copy(year = y.astNode.some), a.warns ++ y.warns)
    }
  }

  def authorsGroup: RuleWithWarning[AuthorsGroup] = rule {
    authorsTeam ~ (authorEx ~ authorsTeam).? ~>
    { (a: NodeWarned[AuthorsTeam], exAu: Option[NodeWarned[AuthorsTeam]]) =>
      val ag = AuthorsGroup(AstNode.id, a.astNode, exAu.map{_.astNode})
      val warns =
        exAu.map { _ => Vector(Warning(2, "Ex authors are not required", ag.id)) }.orZero ++
        a.warns ++ exAu.map { _.warns }.orZero
      NodeWarned(ag, warns)
    }
  }

  def authorsTeam: RuleWithWarning[AuthorsTeam] = rule {
    oneOrMore(author).separatedBy(authorSep) ~> {
      (a: Seq[NodeWarned[Author]]) =>
        NodeWarned(AuthorsTeam(AstNode.id,
                               a.map {_.astNode}),
                               a.flatMap{_.warns}.toVector)
    }
  }

  def authorSep = rule { softSpace ~ ("," | "&" | "and" | "et") ~ softSpace }

  def authorEx = rule { space ~ ("ex" | "in") ~ space }

  def author: RuleWithWarning[Author] = rule {
    (author1 | author2 | unknownAuthor) ~> { (au: NodeWarned[Author]) => {
      val warns =
        (au.astNode.pos.end - au.astNode.pos.start < 2).option {
          Warning(3, "Author is too short", au.astNode.id)
        }.toVector ++ au.warns
      NodeWarned(au.astNode, warns)
    }}
  }

  def author1: RuleWithWarning[Author] = rule {
    author2 ~ softSpace ~ filius ~> {
      (au: NodeWarned[Author], filius: NodeWarned[AuthorWord]) =>
        NodeWarned(au.astNode.copy(filius = filius.astNode.some),
                   au.warns ++ filius.warns)
    }
  }

  def author2: RuleWithWarning[Author] = rule {
    authorWord ~ zeroOrMore(
      (softSpace ~ authorWord ~> { aw: NodeWarned[AuthorWord] =>
        aw.copy(aw.astNode.copy(separator = AuthorWordSeparator.Space))
      }) | (ch(dash) ~ authorWord ~> { aw: NodeWarned[AuthorWord] =>
        aw.copy(aw.astNode.copy(separator = AuthorWordSeparator.Dash))
      })) ~ !(':') ~>
    { (au: NodeWarned[AuthorWord], aus: Seq[NodeWarned[AuthorWord]]) =>
      NodeWarned(Author(AstNode.id,
                        au.astNode +: aus.map {_.astNode}),
                        au.warns ++ aus.flatMap {_.warns}.toVector)
    }
  }

  def unknownAuthor: RuleWithWarning[Author] = rule {
    capturePos("?" |
            (("auct" | "anon" | "ht" | "hort") ~ (&(spaceCharsEOI) | '.'))) ~>
    { (authPos: CapturePosition) =>
      val auth = Author(AstNode.id, Seq(AuthorWord(AstNode.id, authPos)),
                        anon = true)
      val endsWithQuestion = input.charAt(authPos.end - 1) == '?'
      val warns = Vector(
        Warning(2, "Author is unknown", auth.id).some,
        endsWithQuestion.option(Warning(3, "Author as a question mark", auth.id)))
      NodeWarned(auth, warns.flatten)
    }
  }

  def authorWord: RuleWithWarning[AuthorWord] = rule {
    (authorWord1 | authorWord2 | authorPre) ~> {
      (aw: NodeWarned[AuthorWord]) => {
        val word = input.sliceString(aw.astNode.pos.start, aw.astNode.pos.end)
        val authorIsUpperCase = word.length > 2 &&
          word.forall { ch => ch == '-' || authCharUpperStr.indexOf(ch) >= 0 }
        val warns = authorIsUpperCase.option {
          Warning(2, "Author in upper case", aw.astNode.id)
        }.toVector ++ aw.warns
        NodeWarned(aw.astNode, warns)
      }
    }
  }

  def authorWord1: RuleWithWarning[AuthorWord] = rule {
    capturePos("arg." | "et al.{?}" | "et al." | "et al") ~> {
      (pos: CapturePosition) =>
        NodeWarned(AuthorWord(AstNode.id, pos), Vector.empty)
    }
  }

  def authorWord2: RuleWithWarning[AuthorWord] = rule {
    capturePos("d'".? ~ authCharUpper ~
    zeroOrMore(authCharUpper | authCharLower) ~ '.'.?) ~> {
      (pos: CapturePosition) =>
        NodeWarned(AuthorWord(AstNode.id, pos), Vector.empty)
    }
  }



  def filius: RuleWithWarning[AuthorWord] = rule {
    capturePos("f." | "fil." | "filius") ~> { (pos: CapturePosition) =>
      NodeWarned(AuthorWord(AstNode.id, pos), Vector.empty)
    }
  }

  def authorPre: RuleWithWarning[AuthorWord] = rule {
    capturePos("ab" | "af" | "bis" | "da" | "der" | "des" |
               "den" | "della" | "dela" | "de" | "di" | "du" |
               "la" | "ter" | "van" | "von" | "d'") ~ &(spaceCharsEOI) ~> {
      (pos: CapturePosition) =>
        NodeWarned(AuthorWord(AstNode.id, pos), Vector.empty)
    }
  }

  def year: RuleWithWarning[Year] = rule {
    yearRange | yearApprox | yearWithParens | yearWithPage |
    yearWithDot | yearWithChar | yearNumber
  }

  def yearRange: RuleWithWarning[Year] = rule {
    yearNumber ~ '-' ~ oneOrMore(Digit) ~ zeroOrMore(Alpha ++ "?") ~>
    { (y: NodeWarned[Year]) => {
      val yr = y.astNode.copy(approximate = true)
      NodeWarned(yr, Warning(3, "Years range", yr.id) +: y.warns)
    }}
  }

  def yearWithDot: RuleWithWarning[Year] = rule {
    yearNumber ~ '.' ~> { (y: NodeWarned[Year]) =>
      NodeWarned(y.astNode, Warning(2, "Year with period", y.astNode.id) +: y.warns)
    }
  }

  def yearApprox: RuleWithWarning[Year] = rule {
    '[' ~ softSpace ~ yearNumber ~ softSpace ~ ']' ~>
      { (y: NodeWarned[Year]) => {
        val yr = y.astNode.copy(approximate = true)
        NodeWarned(yr, Warning(3, "Year with square brakets", yr.id) +: y.warns)
      }
    }
  }

  def yearWithPage: RuleWithWarning[Year] = rule {
    (yearWithChar | yearNumber) ~ softSpace ~ ':' ~ softSpace ~
      oneOrMore(Digit) ~> { (y: NodeWarned[Year]) =>
      NodeWarned(y.astNode, Warning(3, "Year with page info", y.astNode.id) +: y.warns)
    }
  }

  def yearWithParens: RuleWithWarning[Year] = rule {
    '(' ~ softSpace ~ (yearWithChar | yearNumber) ~ softSpace ~ ')' ~>
    { (y: NodeWarned[Year]) => {
      val yr = y.astNode.copy(approximate = true)
      NodeWarned(yr, Warning(2, "Year with parentheses", yr.id) +: y.warns)
    }}
  }

  def yearWithChar: RuleWithWarning[Year] = rule {
    yearNumber ~ capturePos(Alpha) ~> {
      (y: NodeWarned[Year], pos: CapturePosition) =>
        val yr = y.astNode.copy(alpha = pos.some)
        NodeWarned(yr, Vector(Warning(2, "Year with latin character", yr.id)))
    }
  }

  def yearNumber: RuleWithWarning[Year] = rule {
    capturePos(CharPredicate("12") ~ CharPredicate("0789") ~ Digit ~
      (Digit|'?') ~ '?'.?) ~> { (yPos: CapturePosition) => {
        val yr = Year(AstNode.id, yPos)
        if (input.charAt(yPos.end - 1) == '?') {
          NodeWarned(yr.copy(approximate = true),
                     Vector(Warning(2, "Year with question mark", yr.id)))
        } else NodeWarned(yr)
    }}
  }

  def softSpace = rule {
    zeroOrMore(spaceChars)
  }

  def space = rule {
    oneOrMore(spaceChars)
  }
}

object Parser {
  final val dash = '-'
  final val spaceMiscoded = "　 \t\r\n\f_"
  final val spaceChars = CharPredicate(" " + spaceMiscoded)
  final val spaceCharsEOI = spaceChars ++ EOI ++ ";"
  final val wordBorderChar = spaceChars ++ CharPredicate(";.,:()]")
  final val sciCharsExtended = "æœſàâåãäáçčéèíìïňññóòôøõöúùüŕřŗššşž"
  final val sciUpperCharExtended = "ÆŒ"
  final val authCharUpperStr = "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
    "ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖØÙÚÛÜÝĆČĎİĶĹĺĽľŁłŅŌŐŒŘŚŜŞŠŸŹŻŽƒǾȘȚ"
  final val authCharMiscoded = '�'
  final val apostr = '\''
  final val doubleSpacePattern = """[\s_]{2}""".r
  final val authCharLower = CharPredicate(LowerAlpha ++
    ("àáâãäåæçèéêëìíîïðñòóóôõöøùúûüýÿ" +
      "āăąćĉčďđ'-ēĕėęěğīĭİıĺľłńņňŏő" +
      "œŕřśşšţťũūŭůűźżžſǎǔǧșțȳß"))
  final val authCharUpper = CharPredicate(authCharUpperStr + authCharMiscoded)
  final val upperChar = CharPredicate(UpperAlpha ++ "Ë" ++ sciUpperCharExtended)
  final val lowerChar = CharPredicate(LowerAlpha ++ "ë" ++ sciCharsExtended)
  final val anyVisible = upperChar ++ lowerChar ++ CharPredicate.Visible
}
