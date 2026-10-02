package bel.api

import bel.domain.*
import bel.repository.{PeUnitRepository, TeacherRepository}
import cats.effect.IO
import cats.syntax.traverse.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import java.util.UUID

class PeUnitRoutes(repo: PeUnitRepository, teachers: TeacherRepository):

  private def validated(schoolId: UUID, input: PeUnitInput)(
    onValid: => IO[Response[IO]]
  ): IO[Response[IO]] =
    for
      slots <- teachers.slotCount(schoolId)
      rooms <- input.requiredRoomKindId.traverse(repo.roomsOfKind)
      result <- PeUnitValidation.validate(input, slots, rooms) match
                  case Left(message) => UnprocessableEntity(ApiError.body(message))
                  case Right(_)      => onValid
    yield result

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "schools" / UUIDVar(schoolId) / "pe-units" =>
      repo.findAll(schoolId).flatMap(units => Ok(units.asJson))

    case req @ POST -> Root / "schools" / UUIDVar(schoolId) / "pe-units" =>
      req.as[PeUnitInput].flatMap { input =>
        validated(schoolId, input)(repo.create(schoolId, input).flatMap(u => Created(u.asJson)))
      }

    case req @ PUT -> Root / "schools" / UUIDVar(schoolId) / "pe-units" / UUIDVar(id) =>
      req.as[PeUnitInput].flatMap { input =>
        validated(schoolId, input) {
          repo.update(schoolId, id, input).flatMap {
            case Some(unit) => Ok(unit.asJson)
            case None       => NotFound(ApiError.body("Nie ma takiego zespołu"))
          }
        }
      }

    case DELETE -> Root / "pe-units" / UUIDVar(id) =>
      repo.delete(id).flatMap(n =>
        if n == 0 then NotFound(ApiError.body("Nie ma takiego zespołu")) else NoContent())
  }
