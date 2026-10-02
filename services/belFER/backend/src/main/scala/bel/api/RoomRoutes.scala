package bel.api

import bel.domain.*
import bel.repository.RoomRepository
import cats.effect.IO
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

class RoomRoutes(repo: RoomRepository):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "schools" / UUIDVar(schoolId) / "room-kinds" =>
      repo.findKinds(schoolId).flatMap(kinds => Ok(kinds.asJson))

    case req @ POST -> Root / "schools" / UUIDVar(schoolId) / "room-kinds" =>
      req.as[CreateRoomKind].flatMap { cmd =>
        if cmd.name.trim.isEmpty then UnprocessableEntity(ApiError.body("Nazwa typu sali nie może być pusta"))
        else
          ApiError.onDuplicate("Typ sali o tej nazwie już istnieje")(repo.createKind(schoolId, cmd)) { kind =>
            Created(kind.asJson)
          }
      }

    case req @ PUT -> Root / "room-kinds" / UUIDVar(id) =>
      req.as[CreateRoomKind].flatMap { cmd =>
        if cmd.name.trim.isEmpty then UnprocessableEntity(ApiError.body("Nazwa typu sali nie może być pusta"))
        else
          ApiError.onDuplicate("Typ sali o tej nazwie już istnieje")(repo.renameKind(id, cmd)) {
            case Some(kind) => Ok(kind.asJson)
            case None       => NotFound(ApiError.body("Nie ma takiego typu sali"))
          }
      }

    case DELETE -> Root / "room-kinds" / UUIDVar(id) =>
      repo.deleteKind(id).flatMap(n =>
        if n == 0 then NotFound(ApiError.body("Nie ma takiego typu sali")) else NoContent())

    case GET -> Root / "schools" / UUIDVar(schoolId) / "rooms" =>
      repo.findRooms(schoolId).flatMap(rooms => Ok(rooms.asJson))

    case req @ POST -> Root / "schools" / UUIDVar(schoolId) / "rooms" =>
      req.as[RoomInput].flatMap { input =>
        RoomValidation.validate(input) match
          case Left(message) => UnprocessableEntity(ApiError.body(message))
          case Right(_) =>
            ApiError.onDuplicate("Sala o tym numerze już istnieje")(repo.createRoom(schoolId, input)) { room =>
              Created(room.asJson)
            }
      }

    case req @ PUT -> Root / "rooms" / UUIDVar(id) =>
      req.as[RoomInput].flatMap { input =>
        RoomValidation.validate(input) match
          case Left(message) => UnprocessableEntity(ApiError.body(message))
          case Right(_) =>
            ApiError.onDuplicate("Sala o tym numerze już istnieje")(repo.updateRoom(id, input)) {
              case Some(room) => Ok(room.asJson)
              case None       => NotFound(ApiError.body("Nie ma takiej sali"))
            }
      }

    case DELETE -> Root / "rooms" / UUIDVar(id) =>
      repo.deleteRoom(id).flatMap(n =>
        if n == 0 then NotFound(ApiError.body("Nie ma takiej sali")) else NoContent())
  }
