package bel.api

import bel.domain.*
import bel.repository.SchoolRepository
import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

class SchoolRoutes(repo: SchoolRepository):

  private def error(message: String): Json =
    Json.obj("error" -> Json.fromString(message))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "schools" =>
      repo.findAll.flatMap(schools => Ok(schools.asJson))

    case req @ POST -> Root / "schools" =>
      req.as[CreateSchool].flatMap { cmd =>
        // A new school takes the defaults, so only the fields that identify it
        // are validated here.
        if cmd.name.trim.isEmpty then UnprocessableEntity(error("Nazwa szkoły nie może być pusta"))
        else if cmd.years < 1 || cmd.years > 12 then
          UnprocessableEntity(error("Liczba roczników musi mieścić się w przedziale 1–12"))
        else repo.create(cmd).flatMap(school => Created(school.asJson))
      }

    case GET -> Root / "schools" / UUIDVar(id) =>
      repo.find(id).flatMap {
        case Some(school) => Ok(school.asJson)
        case None         => NotFound(error("Nie ma takiej szkoły"))
      }

    case req @ PUT -> Root / "schools" / UUIDVar(id) =>
      req.as[UpdateSchool].flatMap { cmd =>
        SchoolValidation.validate(cmd.name, cmd.years, cmd.settings) match
          case Left(message) => UnprocessableEntity(error(message))
          case Right(_) =>
            repo.update(id, cmd).flatMap {
              case Some(school) => Ok(school.asJson)
              case None         => NotFound(error("Nie ma takiej szkoły"))
            }
      }

    case GET -> Root / "schools" / UUIDVar(id) / "time-slots" =>
      repo.findTimeSlots(id).flatMap(slots => Ok(slots.asJson))

    case req @ PUT -> Root / "schools" / UUIDVar(id) / "time-slots" =>
      req.as[List[TimeSlotInput]].flatMap { slots =>
        BellSchedule.validate(slots) match
          case Left(message) => UnprocessableEntity(error(message))
          case Right(_) =>
            repo.find(id).flatMap {
              case None    => NotFound(error("Nie ma takiej szkoły"))
              case Some(_) => repo.replaceTimeSlots(id, slots).flatMap(saved => Ok(saved.asJson))
            }
      }
  }
