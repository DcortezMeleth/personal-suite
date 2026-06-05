package fin.api

import cats.effect.IO
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.multipart.*
import fin.domain.*
import fin.importer.*
import fin.repository.AccountRepository
import fin.service.{TransactionService, InvestmentService}
import java.util.UUID

class ImportRoutes(
  accountRepo:   AccountRepository,
  txService:     TransactionService,
  investService: InvestmentService
):

  private given EntityDecoder[IO, Multipart[IO]] = EntityDecoder.multipart[IO]

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ POST -> Root / "import" / "pko" =>
      handleSpendingUpload(req, Mt940Parser.parse)

    case req @ POST -> Root / "import" / "alior" =>
      handleSpendingUpload(req, AliorCsvParser.parse)

    case req @ POST -> Root / "import" / "xtb" =>
      handleXtbUpload(req)
  }

  private def readParts(mp: Multipart[IO]): IO[(Option[String], Option[String])] =
    for
      accountId <- mp.parts.find(_.name.contains("accountId"))
                     .map(_.body.through(fs2.text.utf8.decode).compile.string.map(Option(_)))
                     .getOrElse(IO.pure(None))
      fileContent <- mp.parts.find(_.name.contains("file"))
                     .map(_.body.through(fs2.text.utf8.decode).compile.string.map(Option(_)))
                     .getOrElse(IO.pure(None))
    yield (accountId, fileContent)

  private def handleSpendingUpload(
    req:    Request[IO],
    parser: String => Either[String, List[ParsedTransaction]]
  ): IO[Response[IO]] =
    req.decode[Multipart[IO]] { mp =>
      readParts(mp).flatMap {
        case (None, _)               => BadRequest("""{"error":"Missing accountId part"}""")
        case (_, None)               => BadRequest("""{"error":"Missing file part"}""")
        case (Some(accIdStr), Some(content)) =>
          IO.fromEither(
            scala.util.Try(UUID.fromString(accIdStr.trim))
              .toEither.left.map(e => new IllegalArgumentException(e.getMessage))
          ).flatMap { accountId =>
            accountRepo.findById(accountId).flatMap {
              case None    => BadRequest("""{"error":"Account not found"}""")
              case Some(_) =>
                parser(content) match
                  case Left(err)     => UnprocessableEntity(s"""{"error":"$err"}""")
                  case Right(parsed) =>
                    txService.importTransactions(parsed, accountId).flatMap(r => Ok(r.asJson))
            }
          }
      }
    }

  private def handleXtbUpload(req: Request[IO]): IO[Response[IO]] =
    req.decode[Multipart[IO]] { mp =>
      readParts(mp).flatMap {
        case (None, _)               => BadRequest("""{"error":"Missing accountId part"}""")
        case (_, None)               => BadRequest("""{"error":"Missing file part"}""")
        case (Some(accIdStr), Some(content)) =>
          IO.fromEither(
            scala.util.Try(UUID.fromString(accIdStr.trim))
              .toEither.left.map(e => new IllegalArgumentException(e.getMessage))
          ).flatMap { accountId =>
            accountRepo.findById(accountId).flatMap {
              case None    => BadRequest("""{"error":"Account not found"}""")
              case Some(_) =>
                XtbCsvParser.parse(content) match
                  case Left(err)  => UnprocessableEntity(s"""{"error":"$err"}""")
                  case Right(rows) =>
                    investService.importXtb(rows, accountId).flatMap(r => Ok(r.asJson))
            }
          }
      }
    }
