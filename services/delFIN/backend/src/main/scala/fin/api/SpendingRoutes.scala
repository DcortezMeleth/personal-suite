package fin.api

import cats.effect.IO
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import fin.domain.*
import fin.service.SpendingService
import java.time.YearMonth

object SpendingMonthParam extends OptionalQueryParamDecoderMatcher[String]("month")
object TrendMonthsParam   extends OptionalQueryParamDecoderMatcher[Int]("months")
object RollupParam        extends OptionalQueryParamDecoderMatcher[String]("rollup")

class SpendingRoutes(service: SpendingService):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "spending" / "summary" / "all-time" :? RollupParam(rollupOpt) =>
      service.summaryAllTime(rollupOpt.contains("true")).flatMap(s => Ok(s.asJson))

    case GET -> Root / "spending" / "summary" :? SpendingMonthParam(monthOpt) +& RollupParam(rollupOpt) =>
      val ym = monthOpt
        .flatMap(s => scala.util.Try(YearMonth.parse(s)).toOption)
        .getOrElse(YearMonth.now())
      service.summary(ym, rollupOpt.contains("true")).flatMap(s => Ok(s.asJson))

    case GET -> Root / "spending" / "trend" :? TrendMonthsParam(monthsOpt) =>
      service.trend(monthsOpt.getOrElse(12)).flatMap(list => Ok(list.asJson))
  }
