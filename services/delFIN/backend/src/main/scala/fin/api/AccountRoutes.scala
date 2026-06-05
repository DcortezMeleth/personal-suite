package fin.api

import cats.effect.IO
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import fin.domain.*
import fin.repository.AccountRepository

class AccountRoutes(repo: AccountRepository):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "accounts" =>
      repo.findAll.flatMap(list => Ok(list.asJson))

    case req @ POST -> Root / "accounts" =>
      req.as[CreateAccount].flatMap { cmd =>
        repo.create(cmd).flatMap(acc => Created(acc.asJson))
      }
  }
