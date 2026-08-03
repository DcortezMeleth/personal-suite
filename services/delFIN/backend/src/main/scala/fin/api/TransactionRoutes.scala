package fin.api

import cats.effect.IO
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import fin.domain.*
import fin.repository.TransactionRepository
import java.util.UUID

object MonthParam  extends QueryParamDecoderMatcher[Int]("month")
object YearParam   extends QueryParamDecoderMatcher[Int]("year")
object LimitParam  extends OptionalQueryParamDecoderMatcher[Int]("limit")

class TransactionRoutes(repo: TransactionRepository):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "transactions" / "top" / "all-time" :? LimitParam(limitOpt) =>
      repo.findTopAll(limitOpt.getOrElse(10)).flatMap(list => Ok(list.asJson))

    case GET -> Root / "transactions" :? YearParam(year) +& MonthParam(month) =>
      repo.findByMonth(year, month).flatMap(list => Ok(list.asJson))

    case GET -> Root / "transactions" / "top" :? YearParam(year) +& MonthParam(month) +& LimitParam(limitOpt) =>
      repo.findTopByMonth(year, month, limitOpt.getOrElse(10))
        .flatMap(list => Ok(list.asJson))

    case req @ PATCH -> Root / "transactions" / UUIDVar(id) / "category" =>
      req.as[SetCategory].flatMap { cmd =>
        repo.updateCategory(id, cmd.categoryId)
          .flatMap(_ => Ok("""{"ok":true}"""))
      }
  }
