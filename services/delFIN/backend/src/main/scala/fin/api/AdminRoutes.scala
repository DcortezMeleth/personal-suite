package fin.api

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import fin.repository.TransactionRepository

/** Maintenance operations. No auth — this is a single-user homelab app. */
class AdminRoutes(txRepo: TransactionRepository):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case POST -> Root / "admin" / "backfill-titles" =>
      txRepo.backfillDerivedFields.flatMap(n => Ok(s"""{"updated":$n}"""))
  }
