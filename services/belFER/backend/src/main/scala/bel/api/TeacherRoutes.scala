package bel.api

import bel.domain.*
import bel.repository.TeacherRepository
import cats.effect.IO
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import java.util.UUID

class TeacherRoutes(repo: TeacherRepository):

  private val duplicate = "Nauczyciel o tym kodzie już istnieje"

  private def validated(schoolId: UUID, input: TeacherInput)(
    onValid: => IO[Response[IO]]
  ): IO[Response[IO]] =
    repo.slotCount(schoolId).flatMap { slots =>
      TeacherValidation.validate(input, slots) match
        case Left(message) => UnprocessableEntity(ApiError.body(message))
        case Right(_)      => onValid
    }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "schools" / UUIDVar(schoolId) / "teachers" =>
      repo.findAll(schoolId).flatMap(teachers => Ok(teachers.asJson))

    case req @ POST -> Root / "schools" / UUIDVar(schoolId) / "teachers" =>
      req.as[TeacherInput].flatMap { input =>
        validated(schoolId, input) {
          ApiError.onDuplicate(duplicate)(repo.create(schoolId, input))(t => Created(t.asJson))
        }
      }

    case req @ PUT -> Root / "schools" / UUIDVar(schoolId) / "teachers" / UUIDVar(id) =>
      req.as[TeacherInput].flatMap { input =>
        validated(schoolId, input) {
          ApiError.onDuplicate(duplicate)(repo.update(id, input)) {
            case Some(teacher) => Ok(teacher.asJson)
            case None          => NotFound(ApiError.body("Nie ma takiego nauczyciela"))
          }
        }
      }

    case DELETE -> Root / "teachers" / UUIDVar(id) =>
      repo.delete(id).flatMap(n =>
        if n == 0 then NotFound(ApiError.body("Nie ma takiego nauczyciela")) else NoContent())
  }
