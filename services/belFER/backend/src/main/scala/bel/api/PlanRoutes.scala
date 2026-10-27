package bel.api

import bel.domain.*
import bel.repository.*
import cats.effect.IO
import cats.syntax.all.*
import io.circe.Encoder
import io.circe.generic.semiauto.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import java.util.UUID

/**
 * A plan, in the shape a screen needs it.
 *
 * Every name is resolved here rather than in the browser. A timetable is read
 * far more often than it is built, and the four views the school asked for —
 * by class, by teacher, by room, the whole school at once — are the same
 * lessons grouped four ways. Sending them once, with everything already named,
 * lets the screen do nothing but group and draw.
 *
 * The resolving itself is PlanLayout's, in the domain, because the sheet the
 * solver prints needs exactly the same thing and used to do it again.
 */
object PlanView:

  /** One lesson, at one hour, with everyone it concerns. */
  type Entry = PlanLayout.Lesson

  case class Timetable(
    planId: UUID,
    name: String,
    madeBy: String,
    createdAt: String,
    rulesKept: Option[Int],
    rulesTotal: Option[Int],
    days: List[String],
    slotsPerDay: Int,
    classes: List[String],
    teachers: List[String],
    rooms: List[String],
    entries: List[Entry]
  )

  /**
   * A teacher's idle hours in the finished week.
   *
   * An okienko is an hour with nothing in it between two that have something,
   * on one day. Hours before the first lesson and after the last are not
   * windows — the teacher is simply not in yet, or has gone.
   */
  case class TeacherGaps(
    teacher: String,
    /** Idle hours across the week. */
    gaps: Int,
    /** The longest unbroken run of them, which is what the rule bounds. */
    longestRun: Int,
    /** Days that have at least one. */
    days: Int
  )

  case class Summary(
    id: UUID,
    name: String,
    madeBy: String,
    createdAt: String,
    rulesKept: Option[Int],
    rulesTotal: Option[Int],
    current: Boolean
  )

  given Encoder[Entry]     = deriveEncoder
  given Encoder[Timetable] = deriveEncoder
  given Encoder[Summary]     = deriveEncoder
  given Encoder[TeacherGaps] = deriveEncoder

class PlanRoutes(plans: PlanRepository, loader: SnapshotLoader):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "schools" / UUIDVar(schoolId) / "plans" =>
      for
        all <- plans.list(schoolId)
        current <- plans.current(schoolId)
        body = all.map(p => PlanView.Summary(
          p.id, p.name, p.madeBy, p.createdAt.toString,
          p.rulesKept, p.rulesTotal, current.exists(_.id == p.id)))
        response <- Ok(body.asJson)
      yield response

    // The plan the school is actually running. Everything else is history, so
    // this is the one a screen wants without being told which.
    case GET -> Root / "schools" / UUIDVar(schoolId) / "plans" / "current" =>
      plans.current(schoolId).flatMap {
        case None       => NotFound(ApiError.body("Nie ma jeszcze żadnego planu"))
        case Some(plan) => timetable(schoolId, plan)
      }

    // Only the teachers who have any: a list of sixty names, fifty of them
    // zero, hides the three that are worth looking at.
    case GET -> Root / "schools" / UUIDVar(schoolId) / "plans" / "current" / "gaps" =>
      plans.current(schoolId).flatMap {
        case None => NotFound(ApiError.body("Nie ma jeszcze żadnego planu"))
        case Some(plan) =>
          loader.loadWithRooms(schoolId).flatMap {
            case None => NotFound(ApiError.body("Nie ma takiej szkoły"))
            case Some((s, allRooms)) =>
              for
                placements <- plans.placements(plan.id)
                body = gapsOf(PlanLayout.of(s, placements, allRooms))
                response <- Ok(body.asJson)
              yield response
          }
      }

    case GET -> Root / "schools" / UUIDVar(schoolId) / "plans" / UUIDVar(planId) =>
      plans.find(planId).flatMap {
        case Some(plan) if plan.schoolId == schoolId => timetable(schoolId, plan)
        case _ => NotFound(ApiError.body("Nie ma takiego planu"))
      }

    case POST -> Root / "schools" / UUIDVar(schoolId) / "plans" / UUIDVar(planId) / "current" =>
      plans.find(planId).flatMap {
        case Some(plan) if plan.schoolId == schoolId =>
          plans.makeCurrent(schoolId, planId) *> NoContent()
        case _ => NotFound(ApiError.body("Nie ma takiego planu"))
      }
  }

  private def timetable(schoolId: UUID, plan: Plan): IO[Response[IO]] =
    loader.loadWithRooms(schoolId).flatMap {
      case None => NotFound(ApiError.body("Nie ma takiej szkoły"))
      case Some((s, allRooms)) =>
        for
          placements <- plans.placements(plan.id)
          entries = PlanLayout.of(s, placements, allRooms)
          response <- Ok(PlanView.Timetable(
            planId = plan.id,
            name = plan.name,
            madeBy = plan.madeBy,
            createdAt = plan.createdAt.toString,
            rulesKept = plan.rulesKept,
            rulesTotal = plan.rulesTotal,
            days = SchoolWeek.names(s.teachingDays),
            slotsPerDay = s.slotsPerDay,
            classes = s.classes.map(_.name).sorted,
            teachers = entries.flatMap(_.teachers).distinct.sorted,
            rooms = entries.flatMap(_.room).distinct.sorted,
            entries = entries
          ).asJson)
        yield response
    }

  /** Idle hours per teacher, counted a day at a time, zeroes dropped. */
  private def gapsOf(entries: List[PlanLayout.Lesson]): List[PlanView.TeacherGaps] =
    val busy = entries
      .flatMap(e => e.teachers.map(t => (t, e.day) -> (e.position until e.position + e.length)))
      .groupBy(_._1).view.mapValues(_.flatMap(_._2).toSet).toMap

    busy.toList
      .groupBy((key, _) => key._1)
      .toList
      .map { (teacher, days) =>
        val perDay = days.map((_, hours) => holes(hours))
        PlanView.TeacherGaps(
          teacher = teacher,
          gaps = perDay.map(_._1).sum,
          longestRun = perDay.map(_._2).maxOption.getOrElse(0),
          days = perDay.count(_._1 > 0))
      }
      .filter(_.gaps > 0)
      // Worst first: the point of the list is to be read from the top.
      .sortBy(x => (-x.longestRun, -x.gaps, x.teacher))

  /** Idle hours between the first and last lesson of a day, and the longest run. */
  private def holes(hours: Set[Int]): (Int, Int) =
    if hours.isEmpty then (0, 0)
    else
      val (lo, hi) = (hours.min, hours.max)
      (lo to hi).foldLeft((0, 0, 0)) { case ((total, run, worst), p) =>
        if hours.contains(p) then (total, 0, worst)
        else (total + 1, run + 1, math.max(worst, run + 1))
      } match
        case (total, _, worst) => (total, worst)
