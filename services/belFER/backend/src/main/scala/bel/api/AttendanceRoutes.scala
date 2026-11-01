package bel.api

import bel.repository.OptionalAttendanceRepository
import cats.effect.IO
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import java.util.UUID

/**
 * How many of each class attend each optional subject.
 *
 * A route of its own rather than fields on the class, because this is edited
 * one figure at a time against a grid of classes and subjects, and because
 * sending it with the rest of a class would mean every screen that saves a
 * class had to carry numbers it has no opinion about — which is how the
 * school's optimisation settings were silently wiped once already.
 */
object AttendanceView:
  /** One figure. `attending = null` means nobody has said, which is not zero. */
  case class Entry(classId: UUID, subjectId: UUID, attending: Option[Int])
  given Encoder[Entry] = deriveEncoder
  given Decoder[Entry] = deriveDecoder

class AttendanceRoutes(repo: OptionalAttendanceRepository):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "schools" / UUIDVar(schoolId) / "attendance" =>
      repo.forSchool(schoolId).flatMap(all =>
        Ok(all.toList.map((key, n) =>
          AttendanceView.Entry(key._1, key._2, Some(n))).asJson))

    case req @ PUT -> Root / "schools" / UUIDVar(_) / "attendance" =>
      req.as[AttendanceView.Entry].flatMap { entry =>
        // Negative is a typo rather than an instruction, and zero attenders is
        // a real answer — the subject is simply not taught in that class this
        // year — so only a missing value clears the figure.
        repo.set(entry.classId, entry.subjectId, entry.attending.map(_.max(0))) *> NoContent()
      }
  }
