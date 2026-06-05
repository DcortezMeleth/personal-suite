package fin.service

import cats.effect.IO
import cats.syntax.traverse.*
import io.circe.parser.parse
import org.http4s.client.Client
import org.http4s.Uri
import fin.domain.InflationPoint
import fin.repository.InvestmentRepository

class InflationService(client: Client[IO], investRepo: InvestmentRepository):

  private val eurostatUri = Uri.unsafeFromString(
    "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/prc_hicp_midx" +
    "?geo=PL&unit=I15&coicop=CP00&format=JSON"
  )

  def fetchAndStore: IO[Int] =
    client.expect[String](eurostatUri).flatMap { body =>
      parseEurostatJson(body) match
        case Nil    => IO.pure(0)
        case points =>
          points.traverse { case (yearMonth, cpi) =>
            val parts = yearMonth.split("-")
            if parts.length == 2 then
              for
                year  <- IO(parts(0).toInt)
                month <- IO(parts(1).toInt)
                _     <- investRepo.upsertInflation(year, month, cpi)
              yield ()
            else IO.unit
          }.as(points.size)
    }.handleErrorWith(e => IO.raiseError(new RuntimeException(s"Eurostat fetch failed: ${e.getMessage}", e)))

  def getInflationData(months: Int): IO[List[InflationPoint]] =
    investRepo.findInflationData(months)

  private def parseEurostatJson(json: String): List[(String, BigDecimal)] =
    parse(json).toOption.flatMap { doc =>
      val c = doc.hcursor
      for
        indexMap <- c.downField("dimension")
                     .downField("time")
                     .downField("category")
                     .downField("index")
                     .as[Map[String, Int]].toOption
        valueMap <- c.downField("value").as[Map[String, BigDecimal]].toOption
      yield
        indexMap.toList
          .flatMap { case (ym, idx) => valueMap.get(idx.toString).map(ym -> _) }
          .sortBy(_._1)
    }.getOrElse(Nil)
