package bel.solver

import bel.domain.*
import bel.solver.TimetableProblem.Audience
import java.util.UUID
import scala.jdk.CollectionConverters.*

/**
 * Everything the solver needs that the snapshot does not already carry.
 *
 * Rooms are here rather than in SchoolSnapshot because validation only ever
 * needed to count them by kind, while the solver has to assign one.
 */
case class SolverRooms(rooms: List[Room])

/**
 * Turns the school's data into the solver's problem.
 *
 * The one real piece of work is expansion. A lesson line is four hours shaped
 * 1+2+1; the solver places *blocks*, so that line becomes three things to
 * place. A cross-class unit is two hours across four groups drawn from six
 * classes; that becomes, per hour, one lesson per class, tagged with the group
 * it belongs to so the solver knows the group is taught once and the unit runs
 * as one.
 *
 * Ids are carried out and back as strings so no solver type reaches the rest
 * of the backend — see ADR 0001. They are reversible: `LessonRef` parses them
 * back into what the placement refers to.
 */
object SolverInput:

  /** What a placement in the solution refers to. */
  enum LessonRef:
    case Line(lineId: UUID, block: Int)
    case CrossClass(unitId: UUID, groupId: UUID, classId: UUID, block: Int)
    /** The taught group, as distinct from the classes it draws from. */
    case CrossClassGroupRef(unitId: UUID, groupId: UUID, block: Int)

  object LessonRef:
    def parse(id: String): Option[LessonRef] =
      id.split('|').toList match
        case "line" :: line :: block :: Nil =>
          uuid(line).map(LessonRef.Line(_, block.toInt))
        case "group" :: unit :: group :: block :: Nil =>
          for
            u <- uuid(unit)
            g <- uuid(group)
          yield LessonRef.CrossClassGroupRef(u, g, block.toInt)
        case "cross" :: unit :: group :: cls :: block :: Nil =>
          for
            u <- uuid(unit)
            g <- uuid(group)
            c <- uuid(cls)
          yield LessonRef.CrossClass(u, g, c, block.toInt)
        case _ => None

    private def uuid(s: String): Option[UUID] = scala.util.Try(UUID.fromString(s)).toOption

  private def lineId(line: LessonLine, block: Int): String =
    s"line|${line.id}|$block"

  private def crossId(unit: CrossClassUnit, group: CrossClassGroup, classId: UUID, block: Int): String =
    s"cross|${unit.id}|${group.id}|$classId|$block"

  /** The group itself — who teaches it, and in which room. */
  private def groupId(unit: CrossClassUnit, group: CrossClassGroup, block: Int): String =
    s"group|${unit.id}|${group.id}|$block"

  /**
   * @param timeLimitSeconds the planner's budget. ADR 0001: at school size the
   *   work is about half an hour, so a budget set *at* that is the one setting
   *   that turns a proven optimum into a merely good answer.
   * @param workers 0 leaves it to the machine. Worth setting: ten cores
   *   produced a timetable where two produced nothing.
   */
  def build(
    snapshot: SchoolSnapshot,
    rooms: List[Room],
    timeLimitSeconds: Long,
    workers: Int = 0,
    relaxed: Set[TimetableProblem.Rule] = Set.empty,
    targetDayLength: Int = 0,
    dayLengthWeight: Int = 1
  ): TimetableProblem =
    val subjects = snapshot.subjects.map(s => s.id -> s).toMap
    val lessons = List.newBuilder[TimetableProblem.Lesson]

    for
      line  <- snapshot.lessonLines
      subject = subjects.get(line.subjectId)
      (length, index) <- line.blocks.zipWithIndex
    do
      lessons += TimetableProblem.Lesson(
        lineId(line, index),
        line.classId.toString,
        line.subjectId.toString,
        audienceOf(line.audience),
        line.teacherId.map(_.toString).orNull,
        line.supportTeacherId.map(_.toString).orNull,
        length,
        subject.exists(_.optional),
        null,
        null,
        // A soft room requirement is left to the weights rather than forced
        // here; only a hard one narrows what the solver may choose.
        subject.filter(_.roomRequirementHard).flatMap(_.requiredRoomKind).map(_.id.toString).orNull
      )

    // A unit is expanded in two halves, because a class may contribute
    // students to several of its groups — 14 of the school's 17 WF units do,
    // one class spread across three. Emitting a lesson per (group, class)
    // books that class into its own slot three times over, which is a
    // contradiction no timetable can satisfy.
    //
    //   per group  — who teaches it and where. No class attached.
    //   per class  — that the class is occupied, once, whatever it sends.
    //
    // Both halves share the unit, so they are pinned to the same slot anyway.
    for
      unit <- snapshot.crossClassUnits
      (length, index) <- unit.blocks.zipWithIndex
    do
      for group <- unit.groups do
        lessons += TimetableProblem.Lesson(
          groupId(unit, group, index),
          null,
          unit.subjectId.toString,
          Audience.WHOLE_CLASS,
          group.teacherId.map(_.toString).orNull,
          null,
          length,
          false,
          s"${unit.id}|$index",
          s"${group.id}|$index",
          unit.requiredRoomKindId.map(_.toString).orNull
        )
      // One per class, tagged with a group that has already claimed its
      // teacher and room, so this adds occupancy and nothing else.
      for
        classId <- unit.classIds
        group   <- unit.groups.find(_.classIds.contains(classId))
      do
        lessons += TimetableProblem.Lesson(
          crossId(unit, group, classId, index),
          classId.toString,
          unit.subjectId.toString,
          Audience.WHOLE_CLASS,
          null,
          null,
          length,
          false,
          s"${unit.id}|$index",
          s"${group.id}|$index",
          null
        )

    val teachers = snapshot.teachers.map { t =>
      TimetableProblem.Teacher(
        t.id.toString,
        t.maxWorkingDays.getOrElse(snapshot.settings.defaultTeacherMaxWorkingDays),
        t.maxLessonsPerDay.getOrElse(snapshot.settings.defaultTeacherMaxLessonsPerDay),
        // The bell schedule is 1-based for people and 0-based for the solver.
        t.unavailability
          .map(b => TimetableProblem.Blocked(b.dayOfWeek - 1, b.fromPosition - 1, b.toPosition - 1))
          .asJava
      )
    }

    val solverRooms = rooms.flatMap { room =>
      room.kinds match
        // A room of no particular kind can still hold an ordinary lesson, so
        // it is offered under a null kind rather than dropped.
        case Nil   => List(TimetableProblem.Room(room.id.toString, null))
        case kinds => kinds.map(k => TimetableProblem.Room(room.id.toString, k.id.toString))
    }

    val target =
      if targetDayLength > 0 then targetDayLength
      else defaultTarget(snapshot)

    TimetableProblem(
      snapshot.teachingDays,
      snapshot.slotsPerDay,
      lessons.result().asJava,
      teachers.asJava,
      solverRooms.asJava,
      TimetableProblem.Settings(
        snapshot.settings.maxConsecutiveTeacherGaps,
        snapshot.settings.allowClassGaps,
        target,
        dayLengthWeight,
        timeLimitSeconds,
        workers,
        relaxed.asJava
      )
    )

  /**
   * How long a day should be when nobody has said. The busiest class spread
   * evenly over the teaching week, rounded up — asking for less than that is
   * asking for a penalty no arrangement can avoid, which wastes the solver's
   * time proving the obvious.
   */
  private def defaultTarget(snapshot: SchoolSnapshot): Int =
    val busiest = snapshot.classes.map { cls =>
      val own = ClassAllocation.occupiedHours(snapshot.lessonLines.filter(_.classId == cls.id))
      val cross = snapshot.crossClassUnits.filter(_.classIds.contains(cls.id)).map(_.hours).sum
      own + cross
    }
    if busiest.isEmpty then snapshot.slotsPerDay
    else math.min(snapshot.slotsPerDay,
      math.ceil(busiest.max.toDouble / snapshot.teachingDays).toInt)

  private def audienceOf(audience: LessonAudience): Audience = audience match
    case LessonAudience.WHOLE_CLASS => Audience.WHOLE_CLASS
    case LessonAudience.GROUP_1     => Audience.GROUP_1
    case LessonAudience.GROUP_2     => Audience.GROUP_2
