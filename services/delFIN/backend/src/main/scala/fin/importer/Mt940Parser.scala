package fin.importer

import fin.domain.ParsedTransaction
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Mt940Parser:

  // :61: field format: YYMMDD[MMDD][C|D][fundsCode]Amount[NTRF|other][Reference]
  // Amount uses comma as decimal separator (Polish/European format).
  // Some banks (e.g. PKO BP) emit an optional single-letter funds code between
  // the C/D mark and the amount even for domestic PLN entries.
  private val field61Pattern = """(\d{6})(?:\d{4})?([CD])[A-Z]?(\d+,\d+).*""".r
  private val dateFormat     = DateTimeFormatter.ofPattern("yyyyMMdd")

  def parse(content: String): Either[String, List[ParsedTransaction]] =
    scala.util.Try {
      val lines  = content.linesIterator.toVector
      val result = scala.collection.mutable.ListBuffer[ParsedTransaction]()
      var i      = 0

      while i < lines.length do
        val line = lines(i)
        if line.startsWith(":61:") then
          // Field 61 itself is always a single line in practice; some exports
          // insert a free-text transaction-type label line directly after it
          // (e.g. "Transakcja karta debetowa") before the :86: field. That
          // label is not a SWIFT continuation of field 61, so it must not be
          // folded into the amount/date match.
          val field61  = line.stripPrefix(":61:")
          val labelIdx = i + 1
          val hasLabel = labelIdx < lines.length &&
                         !lines(labelIdx).startsWith(":") &&
                         lines(labelIdx).nonEmpty
          val label      = if hasLabel then lines(labelIdx).trim else ""
          val searchFrom = if hasLabel then labelIdx + 1 else labelIdx
          val descIdx    = findNextField(lines, searchFrom, ":86:")
          val desc       = if descIdx >= 0 then collectField(lines, descIdx).stripPrefix(":86:").trim else ""
          parseEntry(field61, label, desc).foreach(result += _)
          i = if descIdx >= 0 then descIdx + 1 else searchFrom
        else
          i += 1

      // Second pass: card-fee lines carry no merchant reference of their own, so
      // borrow the preceding entry's title AND counterparty. Kept as a separate
      // pass (rather than resolved per-entry above) so the exact same logic in
      // DescriptionFormatter can also run retroactively over already-imported
      // rows — see backfillDerivedFields.
      val threaded = DescriptionFormatter.threadFeeInfo(result.map(pt => (pt.title, pt.counterparty)).toSeq)
      result.zip(threaded).map { case (pt, (title, counterparty)) => pt.copy(title = title, counterparty = counterparty) }.toList
    }.toEither.left.map(e => s"MT940 parse error: ${e.getMessage}")

  private def collectField(lines: Vector[String], start: Int): String =
    val sb = StringBuilder(lines(start))
    var j  = start + 1
    while j < lines.length && !lines(j).startsWith(":") && lines(j).nonEmpty do
      sb.append("\n").append(lines(j))
      j += 1
    sb.toString

  private def findNextField(lines: Vector[String], from: Int, prefix: String): Int =
    var j = from
    while j < lines.length && !lines(j).startsWith(prefix) && !lines(j).startsWith(":6") do
      j += 1
    if j < lines.length && lines(j).startsWith(prefix) then j else -1

  private def parseEntry(field61: String, label: String, description: String): Option[ParsedTransaction] =
    field61.trim match
      case field61Pattern(dateStr, indicator, amountStr) =>
        val date      = LocalDate.parse("20" + dateStr, dateFormat)
        val absAmount = BigDecimal(amountStr.replace(",", "."))
        val amount    = if indicator == "D" then -absAmount else absAmount
        // Deliberately not trimmed as a whole: when label is empty this keeps a
        // leading "\n" that tells DescriptionFormatter "no label" rather than
        // misattributing the first line of a multi-line :86: body as the label.
        val raw       = label + "\n" + description
        Some(ParsedTransaction(
          date           = date,
          amount         = amount,
          currency       = "PLN",
          title          = DescriptionFormatter.extractTitle(raw),
          counterparty   = DescriptionFormatter.extractCounterparty(raw),
          rawDescription = raw
        ))
      case _ => None
