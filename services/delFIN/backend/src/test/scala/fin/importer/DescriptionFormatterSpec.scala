package fin.importer

import org.scalatest.funsuite.AnyFunSuite

class DescriptionFormatterSpec extends AnyFunSuite:

  test("prefers tag 20 (payment title) over tag 27 (counterparty) when both are present") {
    val raw =
      "Przelew na rachunki w innym banku\n" +
      "0200<00p. wychodzacy krajowy/wewnetrzny<10ABC1\n" +
      "<20INV/2024/00001\n" +
      "<27Some Company Sp. z o.o.\n" +
      "<63REFXYZ_ABC1"
    assert(DescriptionFormatter.extractTitle(raw) == "INV/2024/00001")
  }

  test("falls back to the type label when tag 20 looks like a structured reference code, not a title") {
    val raw =
      "Przelew na rachunek organu podatko\n" +
      "0310<00Przelew organ podatkowy<10ABC2\n" +
      "<20/TI/P92071402674\n" +
      "<21/OKR/26M04\n" +
      "<27Pierwszy Urzad Skarbowy - Kielce\n" +
      "<63REFXYZ_ABC2"
    assert(DescriptionFormatter.extractTitle(raw) == "Przelew na rachunek organu podatko")
  }

  test("falls back to tag 27 as a last resort when neither tag 20 nor a label is usable") {
    // Leading "\n" marks "no label" — matches how Mt940Parser joins label+body
    // (label empty) so a multi-line :86: body isn't misread as having a label.
    val raw = "\n0300<00x<10ABC3\n<27ZUS\n<63REFXYZ_ABC3"
    assert(DescriptionFormatter.extractTitle(raw) == "ZUS")
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
    assert(DescriptionFormatter.extractTitle(raw) == "Merchant Name")
    assert(DescriptionFormatter.extractCounterparty(raw) == Some("Counterparty Name"))
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

  test("extractCounterparty returns the tag 27 name for a transfer") {
    val raw =
      "Przelew na rachunki w innym banku\n" +
      "0200<00p. wychodzacy krajowy/wewnetrzny<10ABC1\n" +
      "<20INV/2024/00001\n" +
      "<27Some Company Sp. z o.o.\n" +
      "<63REFXYZ_ABC1"
    assert(DescriptionFormatter.extractCounterparty(raw) == Some("Some Company Sp. z o.o."))
  }

  test("extractCounterparty falls back to tag 20 (the merchant) for a card purchase") {
    // A purchase has no separate counterparty field — the merchant fills that
    // role too, so "who did I pay" is never misleadingly empty.
    val raw =
      "Transakcja karta debetowa\n" +
      "8010<00Transakcja karta platnicza<10DEF2\n" +
      "<20Cafe Example\n" +
      "<63REFXYZ_DEF2"
    assert(DescriptionFormatter.extractCounterparty(raw) == Some("Cafe Example"))
  }

  test("extractCounterparty returns None for a card-fee line (boilerplate tag 20, no real entity)") {
    val raw = s"POBRANIE OPLATY/PROWIZJI\n8090<00Pobranie oplaty<10DEF2\n<20${DescriptionFormatter.GenericCardFeeTitle}\n<63REFXYZ_DEF2"
    assert(DescriptionFormatter.extractCounterparty(raw) == None)
  }

  test("extractCounterparty falls through to tag 20 when tag 27 is present but empty") {
    val raw = "Wyplata w bankomacie\n0200<00Wyplata<27<20Example ATM Network"
    assert(DescriptionFormatter.extractCounterparty(raw) == Some("Example ATM Network"))
  }

  test("extractCounterparty and extractTitle surface different information from the same raw text") {
    val raw =
      "Przelew ELIXIR - ZUS\n" +
      "0300<00Przelew ELIXIR - ZUS<103115\n" +
      "<20Przelew do ZUS\n" +
      "<27ZUS\n" +
      "<63REFXYZ_3115"
    assert(DescriptionFormatter.extractTitle(raw) == "Przelew do ZUS")
    assert(DescriptionFormatter.extractCounterparty(raw) == Some("ZUS"))
  }

  test("threadFeeInfo borrows the preceding meaningful title AND counterparty for each generic fee entry") {
    val entries = Seq(
      ("Cafe Example", Some("Cafe Example")),
      (DescriptionFormatter.GenericCardFeeTitle, None),
      ("SIXT RAC", Some("SIXT RAC")),
      (DescriptionFormatter.GenericCardFeeTitle, None)
    )
    assert(DescriptionFormatter.threadFeeInfo(entries) == Seq(
      ("Cafe Example", Some("Cafe Example")),
      ("Card fee — Cafe Example", Some("Cafe Example")),
      ("SIXT RAC", Some("SIXT RAC")),
      ("Card fee — SIXT RAC", Some("SIXT RAC"))
    ))
  }

  test("threadFeeInfo leaves a leading generic entry (no preceding title) unchanged") {
    val entries = Seq((DescriptionFormatter.GenericCardFeeTitle, None), ("Cafe Example", Some("Cafe Example")))
    assert(DescriptionFormatter.threadFeeInfo(entries) == entries)
  }

  test("threadFeeInfo doesn't nest when consecutive fees follow the same purchase") {
    val entries = Seq(
      ("Cafe Example", Some("Cafe Example")),
      (DescriptionFormatter.GenericCardFeeTitle, None),
      (DescriptionFormatter.GenericCardFeeTitle, None)
    )
    assert(DescriptionFormatter.threadFeeInfo(entries) == Seq(
      ("Cafe Example", Some("Cafe Example")),
      ("Card fee — Cafe Example", Some("Cafe Example")),
      ("Card fee — Cafe Example", Some("Cafe Example"))
    ))
  }

  test("threadFeeInfo threads a transfer's counterparty too, not just card purchases") {
    val entries = Seq(("Przelew do ZUS", Some("ZUS")), (DescriptionFormatter.GenericCardFeeTitle, None))
    assert(DescriptionFormatter.threadFeeInfo(entries) == Seq(
      ("Przelew do ZUS", Some("ZUS")),
      ("Card fee — Przelew do ZUS", Some("ZUS"))
    ))
  }
