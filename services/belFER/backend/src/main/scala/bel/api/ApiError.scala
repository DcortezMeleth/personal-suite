package bel.api

import cats.effect.IO
import io.circe.{DecodingFailure, Json}
import org.http4s.{InvalidMessageBodyFailure, MessageFailure, Response}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.typelevel.log4cats.Logger

object ApiError:

  def body(message: String): Json =
    Json.obj("error" -> Json.fromString(message))

  /**
   * Turns a unique-constraint violation into a 409 with a readable message.
   *
   * Entering a few hundred subjects and rooms by hand, a repeated code is a
   * matter of when rather than whether, and a bare 500 would tell the user
   * nothing about which field to fix.
   */
  def onDuplicate[A](message: String)(action: IO[A])(onSuccess: A => IO[Response[IO]]): IO[Response[IO]] =
    action.attempt.flatMap {
      case Right(value) => onSuccess(value)
      case Left(error) if isUniqueViolation(error) => Conflict(body(message))
      case Left(error) => IO.raiseError(error)
    }

  private def isUniqueViolation(error: Throwable): Boolean =
    error match
      case sql: java.sql.SQLException => sql.getSQLState == "23505"
      case _                          => false

  /**
   * What reaches the person when a request fails somewhere no route handled.
   *
   * Without this, Ember answers any exception with an empty 500 and logs
   * nothing (REVIEW_2026-11-21 A3): a misspelt enum, malformed JSON, a school
   * id that does not exist, a value the database's checks refuse — all of it
   * read "500 Internal Server Error" in the browser and left no trace.
   *
   * What it logs is deliberately thin. The database's own messages quote the
   * offending row, and a request body is the school's data, so neither is
   * written out: an SQL error is logged as its state and constraint name, a
   * body that would not decode as where it failed. Anything else is logged
   * whole, because it is a bug and the stack trace is the point.
   */
  def handler(log: Logger[IO]): PartialFunction[Throwable, IO[Response[IO]]] =
    case InvalidMessageBodyFailure(_, Some(failure: DecodingFailure)) =>
      // The decoders' messages are written to be read ("Nieznany odbiorca
      // lekcji: …") and describe what was sent, so they go back to the sender.
      log.info(s"request body refused at ${failure.pathToRootString.getOrElse("root")}") *>
        UnprocessableContent(body(failure.message))
    case org.http4s.server.middleware.EntityLimiter.EntityTooLarge(limit) =>
      log.info(s"request body over $limit bytes refused") *>
        IO.pure(Response[IO](org.http4s.Status.PayloadTooLarge)
          .withEntity(body(s"Plik jest za duży (najwyżej ${limit / 1024 / 1024} MB)")))
    case failure: MessageFailure
        if Option(failure.getCause).exists(_.isInstanceOf[org.http4s.server.middleware.EntityLimiter.EntityTooLarge]) =>
      log.info("request body over the limit refused") *>
        IO.pure(Response[IO](org.http4s.Status.PayloadTooLarge)
          .withEntity(body("Plik jest za duży")))
    case failure: MessageFailure =>
      log.info(s"request refused: ${failure.getClass.getSimpleName}") *>
        BadRequest(body("Nie udało się odczytać żądania"))
    case sql: java.sql.SQLException =>
      val constraint = sql match
        case p: org.postgresql.util.PSQLException =>
          Option(p.getServerErrorMessage).flatMap(m => Option(m.getConstraint))
        case _ => None
      val where = constraint.fold("")(c => s" on $c")
      sql.getSQLState match
        case "23505" =>
          log.info(s"duplicate$where") *>
            Conflict(body("Taki wpis już istnieje"))
        case "23503" =>
          log.info(s"reference refused$where") *>
            Conflict(body("Odwołanie do czegoś, czego nie ma albo co jest wciąż w użyciu"))
        case state @ ("23502" | "23514" | "22001" | "22003" | "22P02") =>
          log.info(s"value refused ($state)$where") *>
            UnprocessableContent(body("Wartość spoza dozwolonego zakresu"))
        case state =>
          log.error(s"database error $state$where") *>
            InternalServerError(body("Błąd bazy danych — szczegóły są w logu serwera"))
    case other =>
      log.error(other)("unhandled error") *>
        InternalServerError(body("Błąd serwera — szczegóły są w logu serwera"))
