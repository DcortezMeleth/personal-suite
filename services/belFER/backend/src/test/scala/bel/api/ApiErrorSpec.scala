package bel.api

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.DecodingFailure
import org.http4s.InvalidMessageBodyFailure
import org.http4s.server.middleware.EntityLimiter
import org.postgresql.util.{PSQLException, PSQLState}
import org.scalatest.funsuite.AnyFunSuite
import org.typelevel.log4cats.slf4j.Slf4jLogger

/**
 * What a request that fails outside any route comes back as. It was an empty
 * 500 for all of these (REVIEW_2026-11-21 A3).
 */
class ApiErrorSpec extends AnyFunSuite:

  private val handle = ApiError.handler(Slf4jLogger.getLoggerFromName[IO]("test"))

  private def answer(error: Throwable): (Int, String) =
    val response = handle(error).unsafeRunSync()
    (response.status.code, response.as[String].unsafeRunSync())

  test("a body that does not decode says why, as the decoder put it"):
    val (status, body) = answer(InvalidMessageBodyFailure("Could not decode JSON: {...}",
      Some(DecodingFailure("Nieznany odbiorca lekcji: GROUP_3", Nil))))
    assert(status === 422)
    assert(body.contains("Nieznany odbiorca lekcji: GROUP_3"))
    assert(!body.contains("Could not decode JSON"), "the body sent is not echoed back")

  test("the database refusing a reference is a conflict, not a crash"):
    assert(answer(PSQLException("…", PSQLState.FOREIGN_KEY_VIOLATION))._1 === 409)
    assert(answer(PSQLException("…", PSQLState.UNIQUE_VIOLATION))._1 === 409)

  test("a value the database's checks refuse is unprocessable"):
    assert(answer(PSQLException("…", PSQLState.CHECK_VIOLATION))._1 === 422)
    assert(answer(PSQLException("…", PSQLState.NOT_NULL_VIOLATION))._1 === 422)

  test("an upload over the limit is too large"):
    assert(answer(EntityLimiter.EntityTooLarge(1024))._1 === 413)

  test("anything else is still a 500, but with something to read"):
    val (status, body) = answer(RuntimeException("boom"))
    assert(status === 500)
    assert(body.contains("error"))
