package fin.importer

import fin.domain.ParsedTransaction
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Mt940Parser:

  // :61: field format: YYMMDD[MMDD][C|D]Amount[NTRF|other][Reference]
  // Amount uses comma as decimal separator (Polish/European format)
  private val field61Pattern = """(\d{6})(?:\d{4})?([CD])(\d+,\d+).*""".r
  private val dateFormat     = DateTimeFormatter.ofPattern("yyyyMMdd")

  def parse(content: String): Either[String, List[ParsedTransaction]] =
    scala.util.Try {
      val lines  = content.linesIterator.toVector
      val result = scala.collection.mutable.ListBuffer[ParsedTransaction]()
      var i      = 0

      while i < lines.length do
        val line = lines(i)
        if line.startsWith(":61:") then
          val field61 = collectField(lines, i).stripPrefix(":61:")
          val descIdx = findNextField(lines, i + 1, ":86:")
          val desc    = if descIdx >= 0 then collectField(lines, descIdx).stripPrefix(":86:").trim else ""
          parseEntry(field61, desc).foreach(result += _)
          i = if descIdx >= 0 then descIdx + 1 else i + 1
        else
          i += 1

      result.toList
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

  private def parseEntry(field61: String, description: String): Option[ParsedTransaction] =
    field61.trim match
      case field61Pattern(dateStr, indicator, amountStr) =>
        val date      = LocalDate.parse("20" + dateStr, dateFormat)
        val absAmount = BigDecimal(amountStr.replace(",", "."))
        val amount    = if indicator == "D" then -absAmount else absAmount
        Some(ParsedTransaction(
          date           = date,
          amount         = amount,
          currency       = "PLN",
          description    = sanitise(description),
          rawDescription = description.trim
        ))
      case _ => None

  private def sanitise(raw: String): String =
    raw.replaceAll("""~\d{2}""", " ")
       .replaceAll("""\s+""", " ")
       .trim
       .take(500)
