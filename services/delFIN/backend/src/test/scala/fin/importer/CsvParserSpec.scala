package fin.importer

import fin.domain.InvestmentTxType
import java.time.LocalDate
import org.scalatest.funsuite.AnyFunSuite

/**
 * What the broker exports parse into.
 *
 * Written to pin behaviour that had none written down. Both parsers used
 * `return` from inside a `scala.util.Try` to bail out early — which works
 * only because the control-flow exception it throws is not the kind `Try`
 * catches, and which Scala 3 no longer supports. Rewriting that is a change
 * to how rows are rejected, so this says what rejecting them looks like.
 *
 * Every case here passes against the version that used `return`, which is
 * what makes it a safe rewrite rather than a hopeful one.
 */
class CsvParserSpec extends AnyFunSuite:

  // ── XTB ────────────────────────────────────────────────────────────────

  private val xtbHeader = "ID;Symbol;Komentarz;Typ;Data otwarcia;Cena otwarcia;Ilosc;Prowizja;Zysk"

  private def xtb(rows: String*) = XtbCsvParser.parse((xtbHeader +: rows).mkString("\n"))

  test("an empty XTB export is an empty list, not a failure"):
    assert(XtbCsvParser.parse("") === Right(Nil))
    assert(XtbCsvParser.parse("   \n\n  ") === Right(Nil))

  test("a header with no rows parses to nothing"):
    assert(xtb() === Right(Nil))

  test("a full XTB row keeps every field"):
    val Right(List(r)) = xtb("1;CDR;;Kupno;2026-01-15 10:30:00;123,45;10;-2,50;0"): @unchecked
    assert(r.symbol === "CDR")
    assert(r.name === "CDR")
    assert(r.txType === InvestmentTxType.BUY)
    assert(r.date === LocalDate.of(2026, 1, 15))
    assert(r.quantity === Some(BigDecimal("10")))
    assert(r.openPrice === Some(BigDecimal("123.45")))
    // Commission is stored as a magnitude whichever sign the broker used.
    assert(r.commission === BigDecimal("2.50"))

  test("a row missing the symbol is skipped, and the rest still parse"):
    val Right(rows) = xtb(
      "1;;;Kupno;2026-01-15 10:30:00;123,45;10;0;0",
      "2;PKN;;Kupno;2026-01-16 10:30:00;50,00;5;0;0"): @unchecked
    assert(rows.map(_.symbol) === List("PKN"))

  test("a row missing the type is skipped"):
    val Right(rows) = xtb("1;CDR;;;2026-01-15 10:30:00;123,45;10;0;0"): @unchecked
    assert(rows.isEmpty)

  test("a row whose date cannot be read is skipped"):
    val Right(rows) = xtb("1;CDR;;Kupno;not-a-date;123,45;10;0;0"): @unchecked
    assert(rows.isEmpty)

  test("optional columns may be absent without losing the row"):
    val Right(List(r)) = xtb("1;CDR;;Kupno;2026-01-15 10:30:00;;;;"): @unchecked
    assert(r.openPrice === None && r.quantity === None)
    assert(r.commission === BigDecimal(0) && r.profitPln === BigDecimal(0))

  test("a comma-separated export is read as well as a semicolon one"):
    val csv = "ID,Symbol,Komentarz,Typ,Data otwarcia,Cena otwarcia,Ilosc\n" +
              "1,CDR,,Kupno,2026-01-15 10:30:00,123.45,10"
    val Right(List(r)) = XtbCsvParser.parse(csv): @unchecked
    assert(r.symbol === "CDR" && r.quantity === Some(BigDecimal("10")))

  test("type is inferred from a close price when the column does not say"):
    val Right(List(r)) = XtbCsvParser.parse(
      "Symbol;Typ;Data otwarcia;Cena zamkniecia\nCDR;cokolwiek;2026-01-15 10:30:00;99,00"): @unchecked
    assert(r.txType === InvestmentTxType.SELL)

  // ── Alior ──────────────────────────────────────────────────────────────

  test("an empty Alior export is an empty list, not a failure"):
    assert(AliorCsvParser.parse("") === Right(Nil))

  // Column seven is the amount and column nine the currency — the export has
  // a booking date and an account number before them.
  private val aliorRow =
    """"2026-01-15";"2026-01-16";"Przelew";"Zakupy";"Sklep ABC";"PL123";"-45,00";"1000,00";"PLN""""

  test("Alior rows are read from after the header, wherever it sits"):
    val csv = List(
      "Jakies metadane banku przed naglowkiem",
      "\"Data operacji\";\"Data ksiegowania\";\"Typ\";\"Tytul\";\"Nadawca\";\"Rachunek\";\"Kwota\";\"Saldo\";\"Waluta\"",
      aliorRow).mkString("\n")
    val result = AliorCsvParser.parse(csv)
    assert(result.isRight, result)
    val Right(rows) = result: @unchecked
    assert(rows.sizeIs == 1)
    assert(rows.head.date === LocalDate.of(2026, 1, 15))
    assert(rows.head.amount === BigDecimal("-45.00"))
    assert(rows.head.currency === "PLN")
    assert(rows.head.title === "Przelew | Zakupy")
    assert(rows.head.counterparty === Some("Sklep ABC"))

  test("a row whose date is unreadable is skipped, not fatal"):
    val csv = "\"Data operacji\";h;h;h;h;h;h\n" +
      """"nie-data";"x";"y";"z";"w";"v";"10,00""""
    assert(AliorCsvParser.parse(csv) === Right(Nil))

  test("a short Alior row is skipped rather than failing the file"):
    val csv = "\"Data operacji\";\"x\";\"y\"\n\"2026-01-15\";\"a\";\"b\""
    assert(AliorCsvParser.parse(csv) === Right(Nil))
