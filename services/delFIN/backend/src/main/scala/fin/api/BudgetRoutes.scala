package fin.api

import cats.effect.IO
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import fin.domain.*
import fin.repository.BudgetRepository
import java.time.YearMonth
import java.util.UUID

object YearMonthParam extends OptionalQueryParamDecoderMatcher[String]("month")

class BudgetRoutes(repo: BudgetRepository):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "budgets" =>
      repo.findAll.flatMap(list => Ok(list.asJson))

    case req @ POST -> Root / "budgets" =>
      req.as[CreateBudget].flatMap { cmd =>
        repo.upsert(cmd).flatMap(b => Created(b.asJson))
      }

    case DELETE -> Root / "budgets" / UUIDVar(id) =>
      repo.delete(id).flatMap(_ => NoContent())

    case GET -> Root / "budgets" / "status" :? YearMonthParam(monthOpt) =>
      val ym = monthOpt
        .flatMap(s => scala.util.Try(YearMonth.parse(s)).toOption)
        .getOrElse(YearMonth.now())
      repo.statusForMonth(ym).flatMap(list => Ok(list.asJson))
  }
