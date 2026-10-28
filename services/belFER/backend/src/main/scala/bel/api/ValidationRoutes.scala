package bel.api

import bel.domain.*
import bel.repository.*
import cats.effect.IO
import io.circe.Encoder
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import java.util.UUID

object ValidationReport:
  // The ids are what let a screen put an overload beside the row it belongs
  // to. Pensum overruns are reported on the Kontrola page as one aggregate
  // line — 37 near-identical warnings buried the three that mattered — so
  // the per-teacher detail has to travel with the load table instead.
  case class ClassLoad(id: UUID, name: String, occupied: Int, capacity: Int)
  case class TeacherLoad(id: UUID, name: String, allocated: Int, pensum: Option[Int])
  case class Report(
    blocking: List[Finding],
    warnings: List[Finding],
    classes: List[ClassLoad],
    teachers: List[TeacherLoad]
  )

  given Encoder[ClassLoad]   = deriveEncoder
  given Encoder[TeacherLoad] = deriveEncoder
  given Encoder[Report]      = deriveEncoder

class ValidationRoutes(loader: SnapshotLoader):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "schools" / UUIDVar(schoolId) / "validation" =>
      loader.load(schoolId).flatMap {
        case None => NotFound(ApiError.body("Nie ma takiej szkoły"))
        case Some(s) =>
          val findings = Validation.run(s)
          val capacity = s.slotsPerDay * s.teachingDays
          Ok(ValidationReport.Report(
            blocking = findings.filter(_.severity == Severity.Blocking),
            warnings = findings.filter(_.severity == Severity.Warning),
            classes = s.classes.map { cls =>
              val own = ClassAllocation.occupiedHours(s.lessonLines.filter(_.classId == cls.id))
              val cross = s.crossClassUnits.filter(_.classIds.contains(cls.id)).map(_.hours).sum
              ValidationReport.ClassLoad(cls.id, cls.name, own + cross, capacity)
            },
            teachers = s.teachers.map { teacher =>
              val own = s.lessonLines.filter(_.teacherId.contains(teacher.id)).map(_.hours).sum
              val cross = s.crossClassUnits
                .flatMap(u => u.groups.filter(_.teacherId.contains(teacher.id)).map(_ => u.hours)).sum
              ValidationReport.TeacherLoad(
                teacher.id, s"${teacher.lastName} ${teacher.firstName}", own + cross, teacher.pensum)
            }
          ).asJson)
      }
  }
