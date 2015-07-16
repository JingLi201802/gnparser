package org.globalnames.parser

import org.specs2.mutable.Specification

class ParserRelaxedSpec extends Specification {
  val parser = new ParserRelaxed()

  "ParserClean parses" >> {
    "Still parses Homo sapiens" in {
       val res = parse("Homo sapiens")
       res.canonical === Some("Homo sapiens")
    }
    "Döringina Ihering 1929" in {
      val res = parse("Döringina Ihering 1929")
      res.verbatim === "Döringina Ihering 1929"
      res.normalized === Some("Doringina Ihering 1929")
      res.canonical === Some("Doringina")
      res.isParsed must beTrue
      res.isVirus must beFalse
      res.isHybrid must beFalse
      res.id === "10ee0e2b-507d-519e-82d9-c7f37681d97f"
      res.parserVersion must =~("""^\d+\.\d+\.\d+(-SNAPSHOT)?$""")
    }
  }
  def parse(input: String): SciName = {
    val result = parser.sciName.run(input)
    SciName(input)
    SciName.processParsed(input, parser, result)
  }
}
