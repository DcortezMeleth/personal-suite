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
import fin.service.TransactionService
import java.util.UUID

class ImportRoutes(
  accountRepo:  AccountRepository,
  txService:    TransactionService
):

  private given EntityDecoder[IO, Multipart[IO]] = EntityDecoder.multipart[IO]

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ POST -> Root / "import" / "pko" =>
      handleUpload(req, Mt940Parser.parse)

    case req @ POST -> Root / "import" / "alior" =>
      handleUpload(req, AliorCsvParser.parse)
  }

  private def handleUpload(
    req:    Request[IO],
    parser: String => Either[String, List[ParsedTransaction]]
  ): IO[Response[IO]] =
    req.decode[Multipart[IO]] { mp =>
      val filePart   = mp.parts.find(p => p.name.contains("file"))
      val accountPart = mp.parts.find(p => p.name.contains("accountId"))

      (filePart, accountPart) match
        case (None, _) => BadRequest("""{"error":"Missing file part"}""")
        case (_, None) => BadRequest("""{"error":"Missing accountId part"}""")
        case (Some(file), Some(accPart)) =>
          for
            accountIdStr <- accPart.body.through(fs2.text.utf8.decode).compile.string
            accountId    <- IO.fromEither(
                              scala.util.Try(UUID.fromString(accountIdStr.trim))
                                .toEither.left.map(e => new IllegalArgumentException(e.getMessage))
                            )
            account      <- accountRepo.findById(accountId)
            result       <- account match
                              case None =>
                                BadRequest("""{"error":"Account not found"}""")
                              case Some(_) =>
                                file.body
                                  .through(fs2.text.utf8.decode)
                                  .compile.string
                                  .flatMap { content =>
                                    parser(content) match
                                      case Left(err)     => UnprocessableEntity(s"""{"error":"$err"}""")
                                      case Right(parsed) =>
                                        txService
                                          .importTransactions(parsed, accountId)
                                          .flatMap(r => Ok(r.asJson))
                                  }
          yield result
    }
