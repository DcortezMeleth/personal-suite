package bel.api

import bel.domain.*
import bel.repository.{LessonLineRepository, SchoolRepository, SubjectRepository, TeacherRepository}
import cats.effect.IO
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import java.util.UUID

class LessonLineRoutes(
  repo: LessonLineRepository,
  schools: SchoolRepository,
  teachers: TeacherRepository,
  subjects: SubjectRepository
):

  private val duplicate =
    "Ten oddział ma już przydział tego przedmiotu dla tej grupy i rodzaju godzin"

  private def validated(schoolId: UUID, input: LessonLineInput)(
    onValid: => IO[Response[IO]]
  ): IO[Response[IO]] =
    teachers.slotCount(schoolId).flatMap { slots =>
      LessonLineValidation.validate(input, slots) match
        case Left(message) => UnprocessableEntity(ApiError.body(message))
        case Right(_)      => onValid
    }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "schools" / UUIDVar(schoolId) / "lesson-lines" =>
      repo.findForSchool(schoolId).flatMap(lines => Ok(lines.asJson))

    case GET -> Root / "classes" / UUIDVar(classId) / "lesson-lines" =>
      repo.findForClass(classId).flatMap(lines => Ok(lines.asJson))

    // The consistency rules live here rather than in the browser, so the screen
    // and whatever checks the plan before generation cannot drift apart.
    case GET -> Root / "schools" / UUIDVar(schoolId) / "classes" / UUIDVar(classId) / "allocation-summary" =>
      for
        lines <- repo.findForClass(classId)
        subs  <- subjects.findAll(schoolId)
        names  = subs.map(s => s.id -> s.name).toMap
        result <- Ok(ClassAllocation.summary(lines, names).asJson)
      yield result

    case req @ POST -> Root / "schools" / UUIDVar(schoolId) / "lesson-lines" =>
      req.as[LessonLineInput].flatMap { input =>
        validated(schoolId, input) {
          ApiError.onDuplicate(duplicate)(repo.create(schoolId, input))(l => Created(l.asJson))
        }
      }

    case req @ PUT -> Root / "schools" / UUIDVar(schoolId) / "lesson-lines" / UUIDVar(id) =>
      req.as[LessonLineInput].flatMap { input =>
        validated(schoolId, input) {
          ApiError.onDuplicate(duplicate)(repo.update(id, input)) {
            case Some(line) => Ok(line.asJson)
            case None       => NotFound(ApiError.body("Nie ma takiego przydziału"))
          }
        }
      }

    case DELETE -> Root / "lesson-lines" / UUIDVar(id) =>
      repo.delete(id).flatMap(n =>
        if n == 0 then NotFound(ApiError.body("Nie ma takiego przydziału")) else NoContent())
  }
