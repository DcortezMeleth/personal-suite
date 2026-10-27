package bel.domain

import java.util.UUID

/**
 * A finished timetable, held against the rules.
 *
 * This is the piece ADR 0004 turns on. FET generates the week and we do not
 * take its word for anything: whatever comes back is checked here, against our
 * own statement of the rules, in our own vocabulary, before anybody is shown
 * it. That is what makes depending on an outside program safe — not trusting
 * it, but never needing to.
 *
 * It earns its place twice over. Moving a lesson and swapping two (UC-41 to
 * UC-43) are the same question asked of a timetable that already exists, so
 * the rules have to live somewhere that is not the solver whatever generates
 * the week.
 *
 * Deliberately apart from the translation that talks to FET. A mistake made
 * there should produce a timetable that fails these checks — not one that
 * passes because the same mistake was made at both ends.
 */
object PlanCheck:

  /** One lesson, at an hour, with everything a rule might ask about it. */
  case class Scheduled(
    classIds: Set[UUID],
    audience: LessonAudience,
    subjectId: UUID,
    /** Teaching and supporting alike: both are busy. */
    teacherIds: Set[UUID],
    day: Int,
    from: Int,
    length: Int,
    optional: Boolean,
    /**
     * Bookkeeping rather than a lesson. A cross-class block is one hour for
     * the classes and several lessons for the teachers; the classes' side of
     * it is written as its own entry so that a class split over three groups
     * is busy once, and that entry is not a thing anybody teaches.
     */
    derived: Boolean = false,
    /** The room it was put in, once one has been chosen. */
    roomId: Option[UUID] = None,
    /** The kind of room it needs, when it needs a particular one. */
    needsKind: Option[UUID] = None
  ):
    def positions: Range = from until (from + length)
    /** Which halves of a class this occupies — a whole-class lesson takes both. */
    def halves: Set[Int] = audience match
      case LessonAudience.GROUP_1 => Set(1)
      case LessonAudience.GROUP_2 => Set(2)
      case _                      => Set(1, 2)

  case class Rule(code: String, what: String, ok: Boolean, detail: String)

  /**
   * The rules in REQUIREMENTS §2.9 this cannot speak to, and why. Reported
   * alongside the ones it can, because "eleven of eleven" invites the reader
   * to think sixteen were asked about.
   */
  val notChecked: List[(String, String)] = List(
    "R6"  -> "blok wielogodzinny jest ciągły — wynika z zapisu, nie da się złamać",
    "R10" -> "obie grupy zajęte naraz — potwierdzone, że to nie jest twarda reguła")

  case class Report(rules: List[Rule]):
    def kept: Int = rules.count(_.ok)
    def broken: List[Rule] = rules.filterNot(_.ok)
    def sound: Boolean = broken.isEmpty

  def apply(s: SchoolSnapshot, plan: List[Scheduled], rooms: List[Room] = Nil): Report =
    val capacity = s.slotsPerDay
    val subjects = s.subjects.map(x => x.id -> x).toMap
    val rules = List.newBuilder[Rule]
    def rule(code: String, what: String, bad: Int, unit: String) =
      rules += Rule(code, what, bad == 0, if bad == 0 then "" else s"$bad $unit")

    // ── Everything that has to happen, happening once ────────────────────
    val wanted = s.lessonLines.map(_.blocks.size).sum +
      s.crossClassUnits.map(u => u.blocks.size * u.groups.size).sum
    val taught = plan.count(!_.derived)
    rules += Rule("R7", "każdy blok ułożony dokładnie raz", taught == wanted,
      if taught == wanted then "" else s"$taught z $wanted")

    // ── Nobody in two places ─────────────────────────────────────────────
    val classAt = occupancy(plan)(x =>
      for c <- x.classIds; h <- x.halves; p <- x.positions yield (c, h, x.day, p))
    rule("R1", "oddział nigdy nie jest w dwóch miejscach naraz", classAt, "kolizji")

    val teacherAt = occupancy(plan)(x =>
      for t <- x.teacherIds; p <- x.positions yield (t, x.day, p))
    rule("R2", "nauczyciel nigdy nie jest w dwóch miejscach naraz", teacherAt, "kolizji")

    // ── Teacher availability and limits ──────────────────────────────────
    val away = s.teachers.flatMap(t =>
      t.unavailability.flatMap(u =>
        (u.fromPosition to u.toPosition).map(p => (t.id, u.dayOfWeek - 1, p - 1)))).toSet
    val clash = plan.count(x =>
      x.teacherIds.exists(t => x.positions.exists(p => away.contains((t, x.day, p)))))
    rule("R11", "nauczyciel uczy tylko wtedy, gdy jest dostępny", clash, "zajęć")

    val teacherDays = plan.flatMap(x => x.teacherIds.map(_ -> x.day)).groupBy(_._1)
      .view.mapValues(_.map(_._2).toSet.size).toMap
    val overDays = s.teachers.count { t =>
      val limit = t.maxWorkingDays.getOrElse(s.settings.defaultTeacherMaxWorkingDays)
      teacherDays.getOrElse(t.id, 0) > limit
    }
    rule("R13", "nauczyciel nie pracuje więcej dni, niż może", overDays, "nauczycieli")

    val teacherLoad = plan.flatMap(x => x.teacherIds.map(t => (t, x.day) -> x.length))
      .groupBy(_._1).view.mapValues(_.map(_._2).sum).toMap
    val overLoad = s.teachers.count { t =>
      val limit = t.maxLessonsPerDay.getOrElse(s.settings.defaultTeacherMaxLessonsPerDay)
      (0 until s.teachingDays).exists(d => teacherLoad.getOrElse((t.id, d), 0) > limit)
    }
    rule("R14", "nauczyciel nie ma więcej lekcji dziennie, niż może", overLoad, "nauczycieli")

    // A free period in the middle of a teacher's day is tolerable; a stretch of
    // them is what the school objects to.
    val gapLimit = s.settings.maxConsecutiveTeacherGaps
    val teacherHours = plan.flatMap(x => x.teacherIds.map(t => (t, x.day) -> x.positions.toSet))
      .groupBy(_._1).view.mapValues(_.flatMap(_._2).toSet).toMap
    val longGaps = teacherHours.count((_, hours) => longestGap(hours) > gapLimit)
    rule("R15", s"nauczyciel nie przesiaduje dłużej niż $gapLimit godz. bez lekcji",
      longGaps, "dni nauczycieli")

    // ── The shape of a class's day ───────────────────────────────────────
    // Per half, because a gap is something a student sits through, and a
    // student is in one half. A class whose halves finish at different hours
    // has a hole in its own column and nobody waiting in it.
    val halfHours = plan.flatMap(x =>
      for c <- x.classIds; h <- x.halves yield (c, h, x.day) -> x.positions.toSet)
      .groupBy(_._1).view.mapValues(_.flatMap(_._2).toSet).toMap
    val gaps = halfHours.count((_, hours) => longestGap(hours) > 0)
    rule("R9", "żadna grupa nie ma okienka", gaps, "dni grup")

    val tooLong = halfHours.count((_, hours) => hours.size > capacity)
    rule("R12", s"oddział ma najwyżej $capacity lekcji dziennie", tooLong, "dni grup")

    // ── One block of a subject a day, per audience ───────────────────────
    val twice = plan
      .flatMap(x => x.classIds.map(c => (c, x.subjectId, x.audience, x.day)))
      .groupBy(identity).count(_._2.size > 1)
    rule("R5", "najwyżej jeden blok przedmiotu dziennie na grupę", twice, "przypadków")

    // ── Religia i etyka na brzegu dnia ───────────────────────────────────
    val classHours = plan.flatMap(x => x.classIds.map(c => (c, x.day) -> x.positions.toSet))
      .groupBy(_._1).view.mapValues(_.flatMap(_._2).toSet).toMap
    val midDay = plan.count { x =>
      x.optional && x.classIds.exists { c =>
        val hours = classHours.getOrElse((c, x.day), Set.empty)
        hours.nonEmpty && x.from != hours.min && x.from + x.length - 1 != hours.max
      }
    }
    rule("R10", "przedmiot nieobowiązkowy jest pierwszy albo ostatni", midDay, "zajęć")

    // ── Somewhere to actually be ─────────────────────────────────────────
    //
    // Every lesson being taught needs a room, and the school has as many as it
    // has. Nothing in the plan says which room, and that is a separate job —
    // but whether enough of them exist at a given hour is arithmetic, and
    // arithmetic that was quietly wrong: the first week we generated put
    // thirty-six lessons into the same hour in a building with thirty-two
    // rooms, and this check did not exist to say so.
    val atOnce = plan.filterNot(_.derived)
      .flatMap(x => x.positions.map(p => (x.day, p)))
      .groupBy(identity).view.mapValues(_.size)
    val overcrowded = atOnce.count((_, used) => used > s.totalRooms)
    val worst = atOnce.values.maxOption.getOrElse(0)
    rules += Rule("R3",
      s"nigdy nie potrzeba więcej sal, niż szkoła ma (${s.totalRooms})",
      overcrowded == 0,
      if overcrowded == 0 then "" else s"$overcrowded godzin, najwięcej $worst naraz")

    // ── The right room, not just a room ──────────────────────────────────
    //
    // Counting that enough rooms exist says nothing about whether a class fits
    // in the one it was given, or whether a maths lesson has been put in the
    // sports hall. Both happened the first time rooms were assigned: fifty-five
    // whole-class lessons in rooms that hold half a class, and sixty-odd
    // ordinary lessons in the gym, because the file offered every room to
    // every lesson.
    if rooms.nonEmpty then
      val byId = rooms.map(r => r.id -> r).toMap
      val taught = plan.filterNot(_.derived)

      val tooSmall = taught.count(x =>
        x.audience == LessonAudience.WHOLE_CLASS &&
          x.roomId.flatMap(byId.get).exists(!_.fitsWholeClass))
      rule("R3b", "cała klasa mieści się w sali, którą dostała", tooSmall, "lekcji")

      val wrongKind = taught.count { x =>
        x.roomId.flatMap(byId.get) match
          case Some(room) => x.needsKind match
            // A lesson that needs a particular kind of room has to have one.
            case Some(kind) => !room.kinds.exists(_.id == kind)
            // And one that needs nothing in particular should not be taking up
            // a specialist room that something else needs.
            case None => room.kinds.nonEmpty
          case None => false
      }
      rule("R3c", "sala specjalistyczna służy temu, do czego jest", wrongKind, "lekcji")

      val double = taught.flatMap(x => x.roomId.toList.flatMap(r => x.positions.map((r, x.day, _))))
        .groupBy(identity).count(_._2.size > 1)
      rule("R3d", "dwie lekcje nigdy nie dzielą sali", double, "kolizji")

    Report(rules.result())

  /** How many (thing, hour) pairs are claimed by more than one lesson. */
  private def occupancy[A](plan: List[Scheduled])(keys: Scheduled => Iterable[A]): Int =
    plan.flatMap(keys).groupBy(identity).count(_._2.size > 1)

  /** The longest run of free hours between the first and last lesson of a day. */
  private def longestGap(hours: Set[Int]): Int =
    if hours.isEmpty then 0
    else
      val (lo, hi) = (hours.min, hours.max)
      (lo to hi).foldLeft((0, 0)) { case ((run, worst), p) =>
        val next = if hours.contains(p) then 0 else run + 1
        (next, math.max(worst, next))
      }._2
