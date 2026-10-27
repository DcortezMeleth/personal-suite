package bel.solver

import bel.domain.*
import bel.solver.TimetableProblem.Audience
import java.util.UUID
import scala.jdk.CollectionConverters.*

/**
 * Turns the school's data into CP-SAT's problem.
 *
 * The one real piece of work is expansion. A lesson line is four hours shaped
 * 1+2+1; the solver places *blocks*, so that line becomes three things to
 * place. A cross-class unit is two hours across four groups drawn from six
 * classes; that becomes, per hour, one lesson per class, tagged with the group
 * it belongs to so the solver knows the group is taught once and the unit runs
 * as one.
 *
 * Ids are carried out and back as strings so no solver type reaches the rest
 * of the backend — see ADR 0001. `LessonRef.key` writes them and
 * `LessonRef.parse` reads them back.
 *
 * ADR 0004 took this off the critical path: FET generates the week now, and
 * nothing the server serves reaches this file. It is kept because it solves
 * fifteen and twenty classes, it is the only thing that can express "first or
 * last, whichever suits", and it is a second opinion on instances FET finds
 * hard. Being kept is not the same as being carried, though — it builds
 * separately from the server so that OR-Tools and its four platforms' worth of
 * native libraries stay out of the image the school runs.
 */
object CpSatInput:

  /**
   * How long each day of a class's week should be.
   *
   * A class's hours spread as evenly as the week allows, plus room for the
   * hours when half of it is in school without the other — those take a slot
   * of the class's day that an evenly-divided week does not account for. Where
   * a split's two halves share a teacher they cannot be taught together, so
   * the hours that cannot be paired against another split subject are counted
   * here as the extra the shape has to carry.
   */
  def evenShape(snapshot: SchoolSnapshot, days: Int, slotsPerDay: Int): Map[UUID, Seq[Int]] =
    shapes(snapshot, days, slotsPerDay, "even")

  /**
   * @param strategy how to lay a class's hours across the week.
   *
   *   even     as flat as the week allows, longer days first. Simple, and the
   *            worst possible first guess for a whole school: every class then
   *            has its long day on Monday and its short one on Friday, so they
   *            all want the late hours at the same time and all go home at the
   *            same time.
   *   rotated  the same lengths, turned by one day for each successive class,
   *            so the long days fall on different days for different classes.
   *            Identical for any one class, and quite different for the school.
   *   compact  days filled to the brim until the hours run out, which buys a
   *            short day at the end of the week at the cost of long ones before.
   */
  def shapes(snapshot: SchoolSnapshot, days: Int, slotsPerDay: Int,
             strategy: String): Map[UUID, Seq[Int]] =
    snapshot.classes.sortBy(_.name).zipWithIndex.map { (cls, index) =>
      val mine = snapshot.lessonLines.filter(_.classId == cls.id)
      val own = ClassAllocation.occupiedHours(mine)
      val cross = snapshot.crossClassUnits.filter(_.classIds.contains(cls.id)).map(_.hours).sum
      val hours = own + cross + unpairableHours(mine)

      val lengths = strategy match
        case "compact" =>
          (0 until days).map(d => math.max(0, math.min(slotsPerDay, hours - d * slotsPerDay)))
        case other =>
          val base = hours / days
          val extra = hours % days
          val flat = (0 until days)
            .map(d => math.min(slotsPerDay, base + (if d < extra then 1 else 0)))
          if other == "rotated" then
            val by = index % days
            (0 until days).map(d => flat((d + by) % days))
          else flat
      cls.id -> lengths
    }.toMap

  /**
   * Why a shape cannot work, checked by counting alone.
   *
   * A shape is a guess, and a wrong guess is expensive: the solver spends its
   * whole budget before admitting it got nowhere. Most wrong guesses, though,
   * are wrong for reasons arithmetic can see — so ask arithmetic first, and
   * keep the budget for the guesses that might actually pay.
   *
   * Empty means "nothing here rules it out", not "this will work".
   */
  def shapeFaults(snapshot: SchoolSnapshot, shape: Map[UUID, Seq[Int]],
                  slotsPerDay: Int, rooms: Int): Seq[String] =
    snapshot.classes.sortBy(_.name).filter(cls => shape.contains(cls.id)).flatMap { cls =>
      val lengths = shape(cls.id)
      val mine = snapshot.lessonLines.filter(_.classId == cls.id)
      val cross = snapshot.crossClassUnits.filter(_.classIds.contains(cls.id)).map(_.hours).sum
      val hours = ClassAllocation.occupiedHours(mine) + cross + unpairableHours(mine)

      // One block of a subject a day means a subject wanting four blocks
      // wants four separate days, whatever else the week looks like.
      val daysWanted = mine
        .groupBy(l => (l.subjectId, l.audience))
        .values.map(_.map(_.blocks.size).sum)
        .maxOption.getOrElse(0)
      val daysGiven = lengths.count(_ > 0)

      val tooLong = lengths.zipWithIndex.collect {
        case (len, d) if len > slotsPerDay =>
          f"${cls.name}: day ${d + 1} is $len lessons long, and a day holds $slotsPerDay"
      }
      val tooFewDays = Option.when(daysWanted > daysGiven)(
        f"${cls.name}: a subject needs $daysWanted separate days and the shape gives $daysGiven")
      val wrongTotal = Option.when(lengths.sum != hours)(
        f"${cls.name}: the shape adds up to ${lengths.sum} lessons and the class has $hours")

      tooLong ++ tooFewDays ++ wrongTotal
    } ++ roomFaults(shape, slotsPerDay, rooms)

  /**
   * Hours where the shape puts more classes in school than there are rooms.
   *
   * A class in school occupies at least one room, so this is a floor and not
   * an estimate: when it trips, no arrangement of anything else can save the
   * shape. It says nothing when it passes, because a split class wants two.
   */
  private def roomFaults(shape: Map[UUID, Seq[Int]], slotsPerDay: Int,
                         rooms: Int): Seq[String] =
    val days = shape.values.map(_.size).maxOption.getOrElse(0)
    for
      d <- 0 until days
      p <- 0 until slotsPerDay
      inSchool = shape.values.count(lengths => d < lengths.size && p < lengths(d))
      if inSchool > rooms
    yield f"dzień ${d + 1}, lekcja ${p + 1}: $inSchool oddziałów w szkole i tylko $rooms sal"

  /** Hours of a split that have nothing in the other half to sit against. */
  private def unpairableHours(lines: List[LessonLine]): Int =
    val split = lines.filter(_.audience != LessonAudience.WHOLE_CLASS)
    val hours = split.groupBy(l => (l.subjectId, l.audience)).view.mapValues(_.map(_.hours).sum).toMap
    val teachers = split.groupBy(l => (l.subjectId, l.audience)).view.mapValues(_.flatMap(_.teacherId).toSet).toMap
    val totalFirst = hours.collect { case ((_, LessonAudience.GROUP_1), h) => h }.sum
    val totalSecond = hours.collect { case ((_, LessonAudience.GROUP_2), h) => h }.sum

    split.map(_.subjectId).distinct.map { subjectId =>
      val shared = teachers.getOrElse((subjectId, LessonAudience.GROUP_1), Set.empty)
        .intersect(teachers.getOrElse((subjectId, LessonAudience.GROUP_2), Set.empty))
      if shared.isEmpty then 0
      else
        val first = hours.getOrElse((subjectId, LessonAudience.GROUP_1), 0)
        val second = hours.getOrElse((subjectId, LessonAudience.GROUP_2), 0)
        math.max(0, first - (totalSecond - second)) + math.max(0, second - (totalFirst - first))
    }.sum


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
    dayLengthWeight: Int = 1,
    // Leaving half a class idle is a last resort, so it costs far more than an
    // over-long day. It is not forbidden: one teacher sometimes takes both
    // halves of a split and cannot teach them at once.
    halvesApartWeight: Int = 20,
    hints: Map[String, Int] = Map.empty,
    shape: Map[UUID, Seq[Int]] = Map.empty,
    partial: Boolean = false,
    shapeSlack: Int = 0,
    explain: Boolean = false,
    // Which single day to fill, when the week is being built one day at a
    // time. The hardest rule we have — a class's day has no holes in it —
    // is a rule about one day, so a day on its own is a far smaller problem
    // that still carries it in full.
    // Which days of the week to fill, when the week is being built a piece
    // at a time. Empty means all of them. One day at a time is how a week
    // gets built; two or three at a time is how a day that was filled too
    // greedily gets taken apart and done again.
    onlyDays: Seq[Int] = Nil,
    // Lessons already given a place on an earlier day.
    skip: Set[String] = Set.empty,
    // Teachers who have used up their working days on days already filled.
    spentTeachers: Set[String] = Set.empty
  ): TimetableProblem =
    val subjects = snapshot.subjects.map(s => s.id -> s).toMap
    val lessons = List.newBuilder[TimetableProblem.Lesson]

    for
      line  <- snapshot.lessonLines
      subject = subjects.get(line.subjectId)
      (length, index) <- line.blocks.zipWithIndex
    do
      lessons += TimetableProblem.Lesson(
        LessonRef.Line(line.id, index).key,
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
          LessonRef.CrossClassGroupRef(unit.id, group.id, index).key,
          null,
          unit.subjectId.toString,
          Audience.WHOLE_CLASS,
          group.teacherId.map(_.toString).orNull,
          null,
          length,
          optional = false,
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
          LessonRef.CrossClass(unit.id, group.id, classId, index).key,
          classId.toString,
          unit.subjectId.toString,
          Audience.WHOLE_CLASS,
          null,
          null,
          length,
          optional = false,
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
          .filter(b => onlyDays.isEmpty || onlyDays.contains(b.day))
          .map(b =>
            if onlyDays.isEmpty then b
            else TimetableProblem.Blocked(onlyDays.indexOf(b.day), b.fromPosition, b.toPosition))
          .concat(
            // A teacher who has already worked as many days as they are
            // allowed cannot work today either. Saying so as an absence is
            // what lets a week built a day at a time still respect a weekly
            // limit, which the day on its own knows nothing about.
            if spentTeachers.contains(t.id.toString) then
              (0 until math.max(1, onlyDays.size))
                .map(d => TimetableProblem.Blocked(d, 0, snapshot.slotsPerDay - 1))
            else Nil)
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

    TimetableProblem(
      if onlyDays.isEmpty then snapshot.teachingDays else onlyDays.size,
      snapshot.slotsPerDay,
      lessons.result().filterNot(l => skip.contains(l.id)).asJava,
      teachers.asJava,
      solverRooms.asJava,
      TimetableProblem.Settings(
        snapshot.settings.maxConsecutiveTeacherGaps,
        // 0 means "work it out per class" — the solver knows each class's
        // hours and an even week means a different length for each of them.
        targetDayLength,
        dayLengthWeight,
        halvesApartWeight,
        timeLimitSeconds,
        workers,
        relaxed.asJava,
        partial,
        shapeSlack,
        explain
      ),
      hints.view.mapValues(Integer.valueOf).toMap.asJava,
      (if onlyDays.isEmpty then shape
       else shape.view.mapValues(days => onlyDays.map(d => days.lift(d).getOrElse(0))).toMap)
        .map((id, days) => id.toString -> days.toArray).asJava
    )

  private def audienceOf(audience: LessonAudience): Audience = audience match
    case LessonAudience.WHOLE_CLASS => Audience.WHOLE_CLASS
    case LessonAudience.GROUP_1     => Audience.GROUP_1
    case LessonAudience.GROUP_2     => Audience.GROUP_2
