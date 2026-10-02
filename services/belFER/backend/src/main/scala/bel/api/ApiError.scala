package bel.api

import cats.effect.IO
import io.circe.Json
import org.http4s.Response
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

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
