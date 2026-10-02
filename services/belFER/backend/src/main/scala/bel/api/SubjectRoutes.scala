package bel.api

import bel.domain.*
import bel.repository.SubjectRepository
import cats.effect.IO
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*

class SubjectRoutes(repo: SubjectRepository):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "schools" / UUIDVar(schoolId) / "subjects" =>
      repo.findAll(schoolId).flatMap(subjects => Ok(subjects.asJson))

    case req @ POST -> Root / "schools" / UUIDVar(schoolId) / "subjects" =>
      req.as[SubjectInput].flatMap { input =>
        SubjectValidation.validate(input) match
          case Left(message) => UnprocessableEntity(ApiError.body(message))
          case Right(_) =>
            ApiError.onDuplicate("Przedmiot o tym kodzie już istnieje")(repo.create(schoolId, input)) { subject =>
              Created(subject.asJson)
            }
      }

    case req @ PUT -> Root / "subjects" / UUIDVar(id) =>
      req.as[SubjectInput].flatMap { input =>
        SubjectValidation.validate(input) match
          case Left(message) => UnprocessableEntity(ApiError.body(message))
          case Right(_) =>
            ApiError.onDuplicate("Przedmiot o tym kodzie już istnieje")(repo.update(id, input)) {
              case Some(subject) => Ok(subject.asJson)
              case None          => NotFound(ApiError.body("Nie ma takiego przedmiotu"))
            }
      }

    case DELETE -> Root / "subjects" / UUIDVar(id) =>
      repo.delete(id).flatMap(n =>
        if n == 0 then NotFound(ApiError.body("Nie ma takiego przedmiotu")) else NoContent())
  }
