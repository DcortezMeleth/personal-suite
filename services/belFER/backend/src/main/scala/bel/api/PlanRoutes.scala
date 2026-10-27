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
 */
object PlanView:

  /** One lesson, at one hour, with everyone it concerns. */
  case class Entry(
    day: Int,
    position: Int,
    length: Int,
    subject: String,
    teachers: List[String],
    room: Option[String],
    /** Classes this occupies. Empty for individual tuition. */
    classes: List[String],
    /** 1 or 2 when only one half of a class is in it. */
    half: Option[Int],
    /** One student taught alone, shown on the teacher's plan only. */
    individual: Boolean
  )

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

  val DayNames = List("Poniedziałek", "Wtorek", "Środa", "Czwartek", "Piątek", "Sobota", "Niedziela")

class PlanRoutes(
  plans: PlanRepository,
  rooms: RoomRepository,
  schools: SchoolRepository,
  classes: SchoolClassRepository,
  teachers: TeacherRepository,
  subjects: SubjectRepository,
  lines: LessonLineRepository,
  crossClass: CrossClassUnitRepository,
  individual: IndividualPlanRepository
):

  private val loader =
    SnapshotLoader(schools, classes, teachers, subjects, lines, crossClass, rooms, individual)

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
          loader.load(schoolId).flatMap {
            case None => NotFound(ApiError.body("Nie ma takiej szkoły"))
            case Some(s) =>
              for
                placements <- plans.placements(plan.id)
                allRooms <- rooms.findRooms(schoolId)
                body = gapsOf(resolve(s, placements, allRooms))
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
    loader.load(schoolId).flatMap {
      case None => NotFound(ApiError.body("Nie ma takiej szkoły"))
      case Some(s) =>
        for
          placements <- plans.placements(plan.id)
          allRooms <- rooms.findRooms(schoolId)
          entries = resolve(s, placements, allRooms)
          response <- Ok(PlanView.Timetable(
            planId = plan.id,
            name = plan.name,
            madeBy = plan.madeBy,
            createdAt = plan.createdAt.toString,
            rulesKept = plan.rulesKept,
            rulesTotal = plan.rulesTotal,
            days = PlanView.DayNames.take(s.teachingDays),
            slotsPerDay = s.slotsPerDay,
            classes = s.classes.map(_.name).sorted,
            teachers = entries.flatMap(_.teachers).distinct.sorted,
            rooms = entries.flatMap(_.room).distinct.sorted,
            entries = entries
          ).asJson)
        yield response
    }

  /** Idle hours per teacher, counted a day at a time, zeroes dropped. */
  private def gapsOf(entries: List[PlanView.Entry]): List[PlanView.TeacherGaps] =
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

  /** Placements, with every id turned into the name it stands for. */
  private def resolve(s: SchoolSnapshot, placements: List[Placement],
                      allRooms: List[Room]): List[PlanView.Entry] =
    val subjectNames = s.subjects.map(x => x.id -> x.name).toMap
    val teacherNames = s.teachers.map(t => t.id -> s"${t.lastName} ${t.firstName} (${t.code})").toMap
    val classNames = s.classes.map(c => c.id -> c.name).toMap
    val roomNames = allRooms.map(r => r.id -> r.number).toMap
    val byLine = s.lessonLines.map(l => l.id -> l).toMap
    val byGroup = s.crossClassUnits.flatMap(u => u.groups.map(g => g.id -> (u, g))).toMap
    val byTuition = s.individualPlans.flatMap(p => p.lessons.map(l => l.id -> (p, l))).toMap

    placements.flatMap { p =>
      val room = p.roomId.flatMap(roomNames.get)

      p.lessonLineId.flatMap(byLine.get).map { line =>
        PlanView.Entry(
          day = p.dayOfWeek - 1, position = p.position - 1,
          length = line.blocks.lift(p.blockIndex).getOrElse(1),
          subject = subjectNames.getOrElse(line.subjectId, "?"),
          teachers = (line.teacherId.toList ++ line.supportTeacherId.toList).flatMap(teacherNames.get),
          room = room,
          classes = classNames.get(line.classId).toList,
          half = line.audience match
            case LessonAudience.GROUP_1 => Some(1)
            case LessonAudience.GROUP_2 => Some(2)
            case _                      => None,
          individual = false)
      }.orElse {
        p.crossClassGroupId.flatMap(byGroup.get).map { (unit, group) =>
          PlanView.Entry(
            day = p.dayOfWeek - 1, position = p.position - 1,
            length = unit.blocks.lift(p.blockIndex).getOrElse(1),
            subject = subjectNames.getOrElse(unit.subjectId, "?"),
            teachers = group.teacherId.toList.flatMap(teacherNames.get),
            room = room,
            classes = group.classIds.flatMap(classNames.get).sorted,
            half = None,
            individual = false)
        }
      }.orElse {
        p.individualLessonId.flatMap(byTuition.get).map { (plan, lesson) =>
          PlanView.Entry(
            day = p.dayOfWeek - 1, position = p.position - 1,
            length = lesson.blocks.lift(p.blockIndex).getOrElse(1),
            subject = subjectNames.getOrElse(lesson.subjectId, "?"),
            teachers = lesson.teacherId.toList.flatMap(teacherNames.get),
            room = room,
            // The class is not in school for this, so it names no class: one
            // student is, and which student is not ours to say.
            classes = Nil,
            half = None,
            individual = true)
        }
      }
    }
