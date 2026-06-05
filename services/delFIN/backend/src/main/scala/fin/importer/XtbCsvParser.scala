package fin.importer

import fin.domain.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

case class XtbRow(
  symbol: String,
  name: String,
  txType: InvestmentTxType,
  date: LocalDate,
  quantity: Option[BigDecimal],
  openPrice: Option[BigDecimal],
  closePrice: Option[BigDecimal],
  commission: BigDecimal,
  profitPln: BigDecimal
)

object XtbCsvParser:

  // XTB xStation5 transaction history export (Historia transakcji).
  // Column order detection is done from the header row.
  // Supported headers (Polish and English variants):
  //   ID; Symbol; Komentarz; Typ; Data otwarcia; Cena otwarcia; Data zamknięcia; Cena zamknięcia; Ilość; Prowizja; Zysk/Strata
  //   ID; Symbol; Comment;   Type; Open time;   Open price;   Close time;       Close price;      Volume; Commission; Profit

  def parse(content: String): Either[String, List[XtbRow]] =
    scala.util.Try {
      val lines = content.linesIterator.toVector.map(_.trim).filter(_.nonEmpty)
      if lines.isEmpty then return Right(Nil)

      val sep     = detectSeparator(lines.head)
      val headers = lines.head.split(sep.toString, -1).map(normalise)
      val dataLines = lines.drop(1)

      dataLines.flatMap(line => parseRow(line, headers, sep)).toList
    }.toEither.left.map(e => s"XTB CSV parse error: ${e.getMessage}")

  private def detectSeparator(header: String): Char =
    if header.count(_ == ';') > header.count(_ == ',') then ';' else ','

  private def normalise(s: String): String =
    s.trim.stripPrefix("\"").stripSuffix("\"").toLowerCase
     .replace("ą","a").replace("ę","e").replace("ó","o").replace("ś","s")
     .replace("ł","l").replace("ź","z").replace("ż","z").replace("ć","c").replace("ń","n")

  private def col(headers: Array[String], row: Array[String], names: String*): Option[String] =
    names.view
      .flatMap(n => headers.zipWithIndex.find(_._1.contains(n)).map(_._2))
      .headOption
      .flatMap(idx => if idx < row.length then Some(row(idx).trim.stripPrefix("\"").stripSuffix("\"")) else None)
      .filter(_.nonEmpty)

  private def parseRow(line: String, headers: Array[String], sep: Char): Option[XtbRow] =
    val cells = line.split(sep.toString, -1)
    scala.util.Try {
      val symbol   = col(headers, cells, "symbol").getOrElse(return None)
      val typStr   = col(headers, cells, "typ", "type").getOrElse(return None)
      val openDate = col(headers, cells, "data otwarcia", "open time", "czas").flatMap(parseDate)
                       .getOrElse(return None)
      val openPrice= col(headers, cells, "cena otwarcia", "open price").flatMap(parseDec)
      val closePrc = col(headers, cells, "cena zamkniecia", "close price").flatMap(parseDec)
      val volume   = col(headers, cells, "ilosc", "volume", "wolumen").flatMap(parseDec)
      val commission = col(headers, cells, "prowizja", "commission")
                        .flatMap(parseDec).getOrElse(BigDecimal(0))
      val profit   = col(headers, cells, "zysk", "profit").flatMap(parseDec).getOrElse(BigDecimal(0))

      val txType = classifyType(typStr, closePrc)
      XtbRow(
        symbol      = symbol,
        name        = symbol,
        txType      = txType,
        date        = openDate,
        quantity    = volume,
        openPrice   = openPrice,
        closePrice  = closePrc,
        commission  = commission.abs,
        profitPln   = profit
      )
    }.toOption

  private def classifyType(raw: String, closePrice: Option[BigDecimal]): InvestmentTxType =
    val lower = raw.toLowerCase
    if lower.contains("kupno") || lower.contains("buy") then InvestmentTxType.BUY
    else if lower.contains("sprzed") || lower.contains("sell") || lower.contains("close") then InvestmentTxType.SELL
    else if lower.contains("dywidend") || lower.contains("dividend") then InvestmentTxType.DIVIDEND
    else if closePrice.isDefined then InvestmentTxType.SELL
    else InvestmentTxType.BUY

  private val dateFmts = List(
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd")
  )

  private def parseDate(s: String): Option[LocalDate] =
    dateFmts.view.flatMap { fmt =>
      scala.util.Try(LocalDate.parse(s.take(19), fmt)).toOption
    }.headOption

  private def parseDec(s: String): Option[BigDecimal] =
    scala.util.Try(BigDecimal(s.replace(",", ".").replace(" ", ""))).toOption
