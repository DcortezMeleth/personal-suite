package fin.importer

import org.scalatest.funsuite.AnyFunSuite

class DescriptionFormatterSpec extends AnyFunSuite:

  test("prefers tag 27 (counterparty) over tag 20 (payment title) when both are present") {
    val raw =
      "Przelew na rachunki w innym banku\n" +
      "0200<00p. wychodzacy krajowy/wewnetrzny<10ABC1\n" +
      "<20INV/2024/00001\n" +
      "<27Some Company Sp. z o.o.\n" +
      "<63REFXYZ_ABC1"
    assert(DescriptionFormatter.extractTitle(raw) == "Some Company Sp. z o.o.")
  }

  test("falls back to tag 20 (merchant name) when tag 27 is absent") {
    val raw =
      "Transakcja karta debetowa\n" +
      "8010<00Transakcja karta platnicza<10DEF2\n" +
      "<20Cafe Example\n" +
      "<214000111122223333\n" +
      "<2320200101\n" +
      "<249999\n" +
      "<63REFXYZ_DEF2"
    assert(DescriptionFormatter.extractTitle(raw) == "Cafe Example")
  }

  test("falls back to the type label when no structured subfields are present") {
    val raw = "POBRANIE OPLATY/PROWIZJI\nsome unstructured free text with no tags"
    assert(DescriptionFormatter.extractTitle(raw) == "POBRANIE OPLATY/PROWIZJI")
  }

  test("falls back to a sanitised dump when there is neither a label nor structured subfields") {
    val raw = "just some   raw    text"
    assert(DescriptionFormatter.extractTitle(raw) == "just some raw text")
  }

  test("supports '~NN' subfield markers used by other banks alongside PKO's '<NN'") {
    val raw = "Transfer\n~20Merchant Name~27Counterparty Name"
    assert(DescriptionFormatter.extractTitle(raw) == "Counterparty Name")
  }

  test("ignores an empty tag 27 and falls through to tag 20") {
    val raw = "Wyplata w bankomacie\n0200<00Wyplata<27<20Example ATM Network"
    assert(DescriptionFormatter.extractTitle(raw) == "Example ATM Network")
  }

  test("truncates very long titles to 140 characters") {
    val long = "A" * 300
    val raw  = s"label\n<20$long"
    assert(DescriptionFormatter.extractTitle(raw).length == 140)
  }
