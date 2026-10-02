package bel.api

import bel.domain.*
import bel.repository.{SchoolClassRepository, SchoolRepository}
import cats.effect.IO
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import java.util.UUID

class SchoolClassRoutes(repo: SchoolClassRepository, schools: SchoolRepository):

  private val duplicate = "Oddział o tym roczniku i literze już istnieje"

  private def withYears(schoolId: UUID)(f: Int => IO[Response[IO]]): IO[Response[IO]] =
    schools.find(schoolId).flatMap {
      case Some(school) => f(school.years)
      case None         => NotFound(ApiError.body("Nie ma takiej szkoły"))
    }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "schools" / UUIDVar(schoolId) / "classes" =>
      repo.findAll(schoolId).flatMap(classes => Ok(classes.asJson))

    case req @ POST -> Root / "schools" / UUIDVar(schoolId) / "classes" =>
      req.as[SchoolClassInput].flatMap { input =>
        withYears(schoolId) { years =>
          SchoolClassValidation.validate(input, years) match
            case Left(message) => UnprocessableEntity(ApiError.body(message))
            case Right(_) =>
              ApiError.onDuplicate(duplicate)(repo.create(schoolId, input))(c => Created(c.asJson))
        }
      }

    case req @ POST -> Root / "schools" / UUIDVar(schoolId) / "classes" / "generate" =>
      req.as[GenerateClasses].flatMap { cmd =>
        withYears(schoolId) { years =>
          SchoolClassValidation.validateGeneration(cmd, years) match
            case Left(message) => UnprocessableEntity(ApiError.body(message))
            case Right(_)      => repo.generate(schoolId, cmd).flatMap(all => Ok(all.asJson))
        }
      }

    case req @ PUT -> Root / "schools" / UUIDVar(schoolId) / "classes" / UUIDVar(id) =>
      req.as[SchoolClassInput].flatMap { input =>
        withYears(schoolId) { years =>
          SchoolClassValidation.validate(input, years) match
            case Left(message) => UnprocessableEntity(ApiError.body(message))
            case Right(_) =>
              ApiError.onDuplicate(duplicate)(repo.update(id, input)) {
                case Some(cls) => Ok(cls.asJson)
                case None      => NotFound(ApiError.body("Nie ma takiego oddziału"))
              }
        }
      }

    case DELETE -> Root / "classes" / UUIDVar(id) =>
      repo.delete(id).flatMap(n =>
        if n == 0 then NotFound(ApiError.body("Nie ma takiego oddziału")) else NoContent())
  }
