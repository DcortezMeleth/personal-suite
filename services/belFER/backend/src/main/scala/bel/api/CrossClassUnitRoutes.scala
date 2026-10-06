package bel.api

import bel.domain.*
import bel.repository.{CrossClassUnitRepository, TeacherRepository}
import cats.effect.IO
import cats.syntax.traverse.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import java.util.UUID

class CrossClassUnitRoutes(repo: CrossClassUnitRepository, teachers: TeacherRepository):

  private def validated(schoolId: UUID, input: CrossClassUnitInput)(
    onValid: => IO[Response[IO]]
  ): IO[Response[IO]] =
    for
      slots <- teachers.slotCount(schoolId)
      rooms <- input.requiredRoomKindId.traverse(repo.roomsOfKind)
      result <- CrossClassUnitValidation.validate(input, slots, rooms) match
                  case Left(message) => UnprocessableEntity(ApiError.body(message))
                  case Right(_)      => onValid
    yield result

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "schools" / UUIDVar(schoolId) / "cross-class-units" =>
      repo.findAll(schoolId).flatMap(units => Ok(units.asJson))

    case req @ POST -> Root / "schools" / UUIDVar(schoolId) / "cross-class-units" =>
      req.as[CrossClassUnitInput].flatMap { input =>
        validated(schoolId, input)(repo.create(schoolId, input).flatMap(u => Created(u.asJson)))
      }

    case req @ PUT -> Root / "schools" / UUIDVar(schoolId) / "cross-class-units" / UUIDVar(id) =>
      req.as[CrossClassUnitInput].flatMap { input =>
        validated(schoolId, input) {
          repo.update(schoolId, id, input).flatMap {
            case Some(unit) => Ok(unit.asJson)
            case None       => NotFound(ApiError.body("Nie ma takiego zespołu"))
          }
        }
      }

    case DELETE -> Root / "cross-class-units" / UUIDVar(id) =>
      repo.delete(id).flatMap(n =>
        if n == 0 then NotFound(ApiError.body("Nie ma takiego zespołu")) else NoContent())
  }
