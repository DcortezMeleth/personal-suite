package fin.importer

import fin.domain.ParsedTransaction
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object AliorCsvParser:

  // Expected Alior export columns (semicolon-delimited):
  // 0: Data operacji  1: Data księgowania  2: Opis operacji  3: Tytuł
  // 4: Nadawca/Odbiorca  5: Nr rachunku  6: Kwota  7: Saldo  8: Waluta
  private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

  def parse(content: String): Either[String, List[ParsedTransaction]] =
    scala.util.Try {
      val lines = content.linesIterator.toVector
      if lines.isEmpty then return Right(Nil)

      // Detect if file has a BOM or leading metadata rows before the header
      val headerIdx = lines.indexWhere(l =>
        l.contains("Data operacji") || l.contains("Data transakcji")
      )
      val dataLines =
        if headerIdx >= 0 then lines.drop(headerIdx + 1)
        else lines.drop(1)  // assume first line is header

      dataLines
        .filter(_.trim.nonEmpty)
        .flatMap(parseRow)
        .toList
    }.toEither.left.map(e => s"Alior CSV parse error: ${e.getMessage}")

  private def parseRow(line: String): Option[ParsedTransaction] =
    val cols = line
      .split(";", -1)
      .map(_.trim.stripPrefix("\"").stripSuffix("\""))

    if cols.length < 7 then None
    else
      scala.util.Try {
        val date        = LocalDate.parse(cols(0), dateFormat)
        val opType      = if cols.length > 2 then cols(2) else ""
        val title       = if cols.length > 3 then cols(3) else ""
        val counterpart = if cols.length > 4 then cols(4) else ""
        val description = List(opType, title, counterpart).filter(_.nonEmpty).mkString(" | ")
        val rawDesc     = cols.take(6).mkString(";")
        val amount      = BigDecimal(cols(6).replace(" ", "").replace(",", "."))
        val currency    = if cols.length > 8 then cols(8) else "PLN"
        ParsedTransaction(
          date           = date,
          amount         = amount,
          currency       = currency,
          description    = description.take(500),
          rawDescription = rawDesc
        )
      }.toOption
