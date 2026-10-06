package bel.api

import bel.importer.*
import bel.repository.ImportRepository
import cats.effect.IO
import io.circe.{Encoder, Json}
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.multipart.*
import java.util.UUID

object ImportSummary:
  case class Counts(
    teachers: Int,
    subjects: Int,
    classes: Int,
    lessonLines: Int,
    crossClassUnits: Int,
    crossClassGroups: Int
  )
  case class Note(level: String, message: String)
  case class Preview(schoolName: String, counts: Counts, notes: List[Note], replaces: Counts)

  given Encoder[Counts]  = deriveEncoder
  given Encoder[Note]    = deriveEncoder
  given Encoder[Preview] = deriveEncoder

class ImportRoutes(repo: ImportRepository):

  private given EntityDecoder[IO, Multipart[IO]] = EntityDecoder.multipart[IO]

  // Uploads are capped well above a real arkusz — the committed one is 210 kB —
  // so a mistyped file cannot be read into memory unbounded.
  private val MaxUploadBytes = 20 * 1024 * 1024

  private def counts(plan: ImportPlan) = ImportSummary.Counts(
    teachers = plan.teachers.size,
    subjects = plan.subjects.size,
    classes = plan.classes.size,
    lessonLines = plan.lessonLines.size,
    crossClassUnits = plan.crossClassUnits.size,
    crossClassGroups = plan.crossClassUnits.map(_.groups.size).sum
  )

  private def readUpload(mp: Multipart[IO]): IO[Either[String, (Array[Byte], ArkuszMapper.WeekRange)]] =
    val filePart = mp.parts.find(_.name.contains("file"))
    val field = (name: String) =>
      mp.parts.find(_.name.contains(name))
        .map(_.body.through(fs2.text.utf8.decode).compile.string.map(_.trim.toIntOption))
        .getOrElse(IO.pure(None))

    filePart match
      case None => IO.pure(Left("Nie przesłano pliku"))
      case Some(part) =>
        for
          bytes <- part.body.take(MaxUploadBytes.toLong + 1).compile.to(Array)
          from  <- field("weekFrom")
          to    <- field("weekTo")
        yield
          if bytes.length > MaxUploadBytes then Left("Plik jest za duży")
          else Right((bytes, ArkuszMapper.WeekRange(from.getOrElse(1), to.getOrElse(38))))

  private def planFrom(mp: Multipart[IO]): IO[Either[String, ImportPlan]] =
    readUpload(mp).map(_.flatMap { (bytes, weeks) =>
      ArkuszParser.parse(bytes).map(doc => ArkuszMapper.map(doc, weeks))
    })

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // Says what would happen without doing it. The import replaces a school's
    // whole allocation, so seeing the numbers first is the difference between a
    // decision and a surprise.
    case req @ POST -> Root / "schools" / UUIDVar(schoolId) / "import" / "preview" =>
      req.decode[Multipart[IO]] { mp =>
        planFrom(mp).flatMap {
          case Left(message) => UnprocessableEntity(ApiError.body(message))
          case Right(plan) =>
            repo.existingCounts(schoolId).flatMap { existing =>
              Ok(ImportSummary.Preview(
                schoolName = plan.schoolName,
                counts = counts(plan),
                notes = plan.notes.map(n => ImportSummary.Note(n.level.toString, n.message)),
                replaces = ImportSummary.Counts(
                  existing.teachers, existing.subjects, existing.classes,
                  existing.lessonLines, existing.crossClassUnits, 0)
              ).asJson)
            }
        }
      }

    case req @ POST -> Root / "schools" / UUIDVar(schoolId) / "import" / "apply" =>
      req.decode[Multipart[IO]] { mp =>
        planFrom(mp).flatMap {
          case Left(message) => UnprocessableEntity(ApiError.body(message))
          case Right(plan) =>
            repo.apply(schoolId, plan).flatMap { applied =>
              Ok(Json.obj(
                "teachers" -> Json.fromInt(applied.teachers),
                "subjects" -> Json.fromInt(applied.subjects),
                "classes" -> Json.fromInt(applied.classes),
                "lessonLines" -> Json.fromInt(applied.lessonLines),
                "crossClassUnits" -> Json.fromInt(applied.crossClassUnits)
              ))
            }
        }
      }
  }
