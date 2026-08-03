package fin.importer

import org.scalatest.funsuite.AnyFunSuite
import java.time.LocalDate
import fin.domain.ParsedTransaction

class Mt940ParserSpec extends AnyFunSuite:

  private def parseOk(content: String): List[ParsedTransaction] =
    Mt940Parser.parse(content).fold(err => fail(s"expected a successful parse, got error: $err"), identity)

  // Minimal but structurally real MT940 statement, modeled on a PKO BP export:
  // - every :61: line carries an extra single-letter funds code ("N") between
  //   the C/D mark and the amount
  // - a free-text transaction-type label line sits between :61: and :86:
  private val sample =
    """:20:STARTUMKM
      |:25:PL61109010140000071219812874
      |:28C:1/1
      |:60F:C260101PLN1000,00
      |:61:2601050105DN123,45NMSCNONREF//0001
      |Transakcja karta debetowa
      |:86:8010<00Transakcja karta platnicza<100001
      |<20Cafe Example
      |<63REF0001
      |:61:2601060106CN5000,00NTRFNONREF//0002
      |Przelew
      |:86:0510<00p. przychodzacy<100002
      |<27ACME Sp. z o.o.
      |<63REF0002
      |:62F:C260107PLN5876,55
      |""".stripMargin

  test("parses every entry despite the extra funds-code letter and the free-text label line") {
    val result = Mt940Parser.parse(sample)
    assert(result.isRight, result)
    assert(result.map(_.length) == Right(2))
  }

  test("reads the debit/credit sign and amount correctly") {
    val txs = parseOk(sample)
    assert(txs(0).amount == BigDecimal("-123.45"))
    assert(txs(1).amount == BigDecimal("5000.00"))
  }

  test("reads the transaction date correctly (YYMMDD -> 20YY-MM-DD)") {
    val txs = parseOk(sample)
    assert(txs(0).date == LocalDate.of(2026, 1, 5))
    assert(txs(1).date == LocalDate.of(2026, 1, 6))
  }

  test("produces a clean, short description instead of the raw tag dump") {
    val txs = parseOk(sample)
    assert(txs(0).description == "Cafe Example")
    assert(txs(1).description == "ACME Sp. z o.o.")
  }

  test("keeps the full raw content (label + field 86) in rawDescription for dedup/category matching") {
    val txs = parseOk(sample)
    assert(txs(0).rawDescription.contains("Transakcja karta debetowa"))
    assert(txs(0).rawDescription.contains("Cafe Example"))
  }

  test("a :61: entry with no funds-code letter still parses (regression guard)") {
    val noFundsCode =
      """:61:2601050105D123,45NMSCNONREF//0001
        |Card purchase
        |:86:<20Some Merchant
        |""".stripMargin
    val txs = parseOk(noFundsCode)
    assert(txs.length == 1)
    assert(txs.head.amount == BigDecimal("-123.45"))
  }

  test("an entry with no free-text label line before :86: still parses (regression guard)") {
    val noLabel =
      """:61:2601050105DN123,45NMSCNONREF//0001
        |:86:<20Some Merchant
        |""".stripMargin
    val txs = parseOk(noLabel)
    assert(txs.length == 1)
    assert(txs.head.description == "Some Merchant")
  }

  test("an empty statement parses to an empty list, not an error") {
    assert(Mt940Parser.parse("") == Right(Nil))
  }
