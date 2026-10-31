package bel.api

import bel.domain.*
import bel.repository.SnapshotLoader
import cats.effect.IO
import io.circe.Encoder
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import java.util.UUID

/**
 * Nauczanie indywidualne, in the shape a screen needs it.
 *
 * These hours have been imported, planned around and checked since the
 * beginning, and until now there was nowhere to look at them: they reached a
 * screen only as italic entries on a teacher's week, and only once a plan had
 * been generated. A school importing a hundred and twenty-one of them has no
 * way to hold that against the arkusz, which is the one thing it needs to do
 * with them.
 *
 * Read-only, and that is not a shortcut. The hours are the school's business
 * and the student is not: a plan is identified by its class and an ordinal
 * within that class, never by a name, because `IndividualTuition` keeps a
 * named minor and their health provision out of this database on purpose.
 * Editing would mean deciding what it means to add a plan for a student we
 * decline to record, and the arkusz is the right place for that decision.
 */
object TuitionView:

  case class Lesson(
    subject: String,
    /** Absent when the arkusz named no one, which is worth seeing. */
    teacher: Option[String],
    blocks: List[Int],
    hours: Int
  )

  case class Plan(
    id: UUID,
    className: String,
    /** First, second, third such plan in that class — the student is not named. */
    ordinal: Int,
    hours: Int,
    lessons: List[Lesson]
  )

  case class Summary(plans: Int, hours: Int, teachers: Int, byClass: List[Plan])

  /** Everything the school's tuition amounts to, named and ordered. */
  def of(s: SchoolSnapshot): Summary =
    val subjects = s.subjects.map(x => x.id -> x.name).toMap
    val classes = s.classes.map(c => c.id -> c.name).toMap
    val teachers = s.teachers.map(t => t.id -> s"${t.lastName} ${t.firstName} (${t.code})").toMap

    val plans = s.individualPlans
      .map { plan =>
        Plan(
          id = plan.id,
          className = classes.getOrElse(plan.classId, "?"),
          ordinal = plan.ordinal,
          hours = plan.hours,
          lessons = plan.lessons
            .map(l => Lesson(
              subject = subjects.getOrElse(l.subjectId, "?"),
              teacher = l.teacherId.flatMap(teachers.get),
              blocks = l.blocks,
              hours = l.hours))
            .sortBy(_.subject))
      }
      // By class as the arkusz lists them, so the two can be read side by side.
      .sortBy(p => (p.className, p.ordinal))

    Summary(
      plans = plans.size,
      hours = plans.map(_.hours).sum,
      // How many people this falls to, which is the part that competes with
      // everything else they teach.
      teachers = s.individualPlans.flatMap(_.lessons).flatMap(_.teacherId).distinct.size,
      byClass = plans)

  given Encoder[Lesson]  = deriveEncoder
  given Encoder[Plan]    = deriveEncoder
  given Encoder[Summary] = deriveEncoder

class IndividualPlanRoutes(loader: SnapshotLoader):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "schools" / UUIDVar(schoolId) / "individual-plans" =>
      loader.load(schoolId).flatMap {
        case None    => NotFound(ApiError.body("Nie ma takiej szkoły"))
        case Some(s) => Ok(TuitionView.of(s).asJson)
      }
  }
