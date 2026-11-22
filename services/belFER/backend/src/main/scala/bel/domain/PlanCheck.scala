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
    needsKind: Option[UUID] = None,
    /**
     * Whether that kind is required or only wanted. A wanted kind may also
     * be met by an ordinary classroom, which is what the export offers FET.
     */
    kindIsHard: Boolean = true,
    /**
     * Which block of what this is: the id of a lesson line, a cross-class
     * group or a tuition lesson, and the block's index in it. Lets R7 tell a
     * block placed twice from two blocks placed once. None on bookkeeping.
     */
    block: Option[(UUID, Int)] = None,
    /** The cross-class unit and the unit's block this is part of (RULES S3). */
    unitBlock: Option[(UUID, Int)] = None,
    /** The individual plan whose pupil this lesson is for (RULES S9). */
    pupil: Option[UUID] = None
  ):
    def positions: Range = from until (from + length)
    /** Which halves of a class this occupies — a whole-class lesson takes both. */
    def halves: Set[Int] = audience match
      case LessonAudience.GROUP_1 => Set(1)
      case LessonAudience.GROUP_2 => Set(2)
      case _                      => Set(1, 2)

  case class Rule(code: String, what: String, ok: Boolean, detail: String)

  /**
   * Rules of RULES.md this cannot speak to, and why. Reported alongside the
   * ones it can, so that "all kept" is not read as more than it says.
   */
  val notChecked: List[(String, String)] = List(
    "R6"  -> "blok wielogodzinny jest ciągły — wynika z zapisu, nie da się złamać")

  /**
   * Pupils of a class who share one timetable (RULES S2): a half, and within
   * it those attending one optional subject (`attends`), or none of them.
   */
  private sealed trait Who
  private case class Pupils(classId: UUID, half: Int, attends: Option[UUID]) extends Who
  /** One pupil on individual tuition (RULES S9), by their plan. */
  private case class Individual(planId: UUID) extends Who

  /**
   * How an individual pupil is planned. Their kind, or on-site until the
   * planner has set it, and also for a mixed pupil whose class has lessons in
   * halves but whose half is not set: on-site never double-books a pupil or
   * leaves them waiting, though it misses a mixed pupil's class lessons. The
   * export makes the same choice. `own` is the subjects taught to them
   * individually, which a mixed pupil does not take with the class.
   */
  private case class Taught(planId: UUID, classId: UUID, kind: TuitionKind, half: Int, own: Set[UUID])

  private def individuals(s: SchoolSnapshot): List[Taught] =
    val split = s.lessonLines.filter(_.audience != LessonAudience.WHOLE_CLASS).map(_.classId).toSet
    s.individualPlans.map { p =>
      val kind = p.kind match
        case Some(TuitionKind.MIXED) if p.half.isEmpty && split.contains(p.classId) => TuitionKind.ON_SITE
        case Some(k) => k
        case None    => TuitionKind.ON_SITE
      Taught(p.id, p.classId, kind, p.half.getOrElse(1), p.lessons.map(_.subjectId).toSet)
    }

  /**
   * The attendance groups of each class, by the optional subject they attend
   * (None: none of them). A pupil attends at most one, as the school says.
   *
   * Where the school has entered how many attend, a group nobody is in does
   * not exist. Where it has not, the group is assumed to (RULES G1): that is
   * exactly what makes religia and etyka "first or last" when nothing is
   * known, because the pupils who skip them must not wait.
   */
  private def attendanceGroups(s: SchoolSnapshot): Map[UUID, List[Option[UUID]]] =
    val optional = s.subjects.filter(_.optional).map(_.id).toSet
    val taught = (s.lessonLines.map(l => l.classId -> l.subjectId) ++
      s.crossClassUnits.flatMap(u => u.classIds.map(_ -> u.subjectId)))
      .filter((_, subject) => optional.contains(subject))
      .groupMap(_._1)(_._2).view.mapValues(_.distinct).toMap
    s.classes.map { c =>
      val entered = taught.getOrElse(c.id, Nil).map(subject =>
        subject -> s.optionalAttendance.get((c.id, subject)))
      val attending = entered.collect { case (subject, n) if n.forall(_ > 0) => Some(subject) }
      val accounted = entered.flatMap(_._2).sum
      val nobodyElse = entered.forall(_._2.isDefined) && c.studentCount.exists(_ <= accounted)
      c.id -> (attending ++ Option.when(!nobodyElse || attending.isEmpty)(None))
    }.toMap

  case class Report(rules: List[Rule]):
    def kept: Int = rules.count(_.ok)
    def broken: List[Rule] = rules.filterNot(_.ok)
    def sound: Boolean = broken.isEmpty

  def apply(s: SchoolSnapshot, plan: List[Scheduled], rooms: List[Room] = Nil): Report =
    val capacity = s.slotsPerDay
    val rules = List.newBuilder[Rule]
    def rule(code: String, what: String, bad: Int, unit: String) =
      rules += Rule(code, what, bad == 0, if bad == 0 then "" else s"$bad $unit")

    // ── Everything that has to happen, happening once ────────────────────
    //
    // Counted, and matched: a count alone passes a week with one block placed
    // twice and another not at all (REVIEW_2026-11-21 D7).
    val wanted = s.lessonLines.map(_.blocks.size).sum +
      s.crossClassUnits.map(u => u.blocks.size * u.groups.size).sum +
      s.individualPlans.flatMap(_.lessons).map(_.blocks.size).sum
    val known: Set[(UUID, Int)] =
      (s.lessonLines.flatMap(l => l.blocks.indices.map(l.id -> _)) ++
        s.crossClassUnits.flatMap(u => u.groups.flatMap(g => u.blocks.indices.map(g.id -> _))) ++
        s.individualPlans.flatMap(_.lessons).flatMap(l => l.blocks.indices.map(l.id -> _))).toSet
    val taughtBlocks = plan.filterNot(_.derived)
    val ids = taughtBlocks.flatMap(_.block)
    val repeated = ids.groupBy(identity).count(_._2.size > 1)
    val unknown = ids.count(!known.contains(_))
    val placedOnce = taughtBlocks.size == wanted && repeated == 0 && unknown == 0
    rules += Rule("R7", "każdy blok ułożony dokładnie raz", placedOnce,
      List(
        Option.when(taughtBlocks.size != wanted)(s"${taughtBlocks.size} z $wanted"),
        Option.when(repeated > 0)(s"$repeated ułożonych więcej niż raz"),
        Option.when(unknown > 0)(s"$unknown spoza przydziałów")).flatten.mkString(", "))

    // Every lesson inside the week: the days the school teaches, and the
    // lessons of its day. Nothing else enforces it — a double from the last
    // lesson runs past the end of the day, and a lesson on a sixth day is
    // simply not looked at by the rules that count days (REVIEW D6). With it,
    // S8 (at most a day's worth of lessons) follows from R1.
    val outside = plan.count(x =>
      x.day < 0 || x.day >= s.teachingDays || x.from < 0 || x.from + x.length > capacity)
    rule("R12", s"każda lekcja mieści się w tygodniu (${s.teachingDays} dni po $capacity lekcji)",
      outside, "lekcji")

    // ── Nobody in two places ─────────────────────────────────────────────
    //
    // Per group of pupils, not per class: religia and etyka at the same hour
    // are two lessons for two different sets of pupils (RULES S2).
    val groups = attendanceGroups(s)
    val taught = individuals(s)
    val mixedIn = taught.filter(_.kind == TuitionKind.MIXED).groupBy(_.classId)
    // Everyone a lesson occupies: the class's groups of pupils, the pupil an
    // individual lesson is for, and a mixed pupil sitting the class lesson
    // with everyone else — unless it is a subject they are taught on their
    // own, or religia/etyka, which they are assumed not to attend (as for the
    // class's "neither" group, RULES G1).
    def pupils(x: Scheduled): Set[Who] =
      val classGroups: Set[Who] =
        for
          c <- x.classIds
          h <- x.halves
          g <- groups.getOrElse(c, List(None)).toSet
          if !x.optional || g.contains(x.subjectId)
        yield Pupils(c, h, g)
      val withClass: Set[Who] =
        if x.optional then Set.empty
        else
          for
            c <- x.classIds
            t <- mixedIn.getOrElse(c, Nil).toSet
            if x.halves.contains(t.half) && !t.own.contains(x.subjectId)
          yield Individual(t.planId)
      classGroups ++ withClass ++ x.pupil.map(Individual(_))
    def busy(keep: Who => Boolean) = occupancy(plan)(x =>
      for p <- pupils(x) if keep(p); pos <- x.positions yield (p, x.day, pos))
    rule("R1", "oddział nigdy nie jest w dwóch miejscach naraz",
      busy(_.isInstanceOf[Pupils]), "kolizji")
    rule("R18", "uczeń nauczania indywidualnego nigdy nie ma dwóch zajęć naraz",
      busy(_.isInstanceOf[Individual]), "kolizji")

    // RULES S3: every group of a cross-class unit's block at the same hour.
    // The classes' side of a unit is one bookkeeping entry, at the first
    // group's hour, so a group placed elsewhere would leave its pupils in two
    // places with nothing else noticing (REVIEW_2026-11-21 D4).
    val apart = plan.filterNot(_.derived)
      .flatMap(x => x.unitBlock.map(_ -> (x.day, x.from)))
      .groupMap(_._1)(_._2).count(_._2.distinct.size > 1)
    rule("R4", "grupy zespołu międzyoddziałowego mają zajęcia o tej samej godzinie",
      apart, "bloków")

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

    // ── Teachers' free periods (RULES T5) ────────────────────────────────
    //
    // A free period is an hour between a teacher's first and last lesson of a
    // day with nothing to teach. An hour the teacher is away is not one: they
    // are not waiting at school, and FET does not count it either.
    //
    // Three limits, as the school's settings and FET have them: a run of free
    // periods (the rule as the school states it), how many in a day, and how
    // many in a week. Only the first used to be checked; the other two were
    // FET's word (OPEN_ITEMS 2.4).
    val gapLimit = s.settings.maxConsecutiveTeacherGaps
    val teacherHours = plan.flatMap(x => x.teacherIds.map(t => (t, x.day) -> x.positions.toSet))
      .groupBy(_._1).view.mapValues(_.flatMap(_._2).toSet).toMap
    def freePeriods(teacher: UUID, day: Int, hours: Set[Int]): Set[Int] =
      if hours.isEmpty then Set.empty
      else (hours.min to hours.max).filterNot(p => hours.contains(p) || away((teacher, day, p))).toSet
    val free = teacherHours.map { case ((t, day), hours) => (t, day) -> freePeriods(t, day, hours) }

    val longGaps = free.count((_, idle) => longestRun(idle) > gapLimit)
    rule("R15", s"nauczyciel nie przesiaduje dłużej niż $gapLimit godz. bez lekcji",
      longGaps, "dni nauczycieli")

    // The daily count FET is told: the planner's setting, or the run length
    // standing in for it, as the export does.
    val dailyLimit = s.settings.optimisation.maxTeacherGapsPerDay.getOrElse(gapLimit)
    rule("R15b", s"nauczyciel ma najwyżej ${okienka(dailyLimit)} dziennie",
      free.count((_, idle) => idle.size > dailyLimit), "dni nauczycieli")

    for weeklyLimit <- s.settings.optimisation.maxTeacherGapsPerWeek do
      val weekly = free.toList.groupMapReduce(_._1._1)(_._2.size)(_ + _)
      rule("R15c", s"nauczyciel ma najwyżej ${okienka(weeklyLimit)} w tygodniu",
        weekly.count(_._2 > weeklyLimit), "nauczycieli")

    // ── The shape of a pupil's day ───────────────────────────────────────
    //
    // Per group of pupils, because a gap is something a pupil sits through.
    // A class whose halves finish at different hours has a hole in its own
    // column and nobody waiting in it. And pupils who skip religia must not
    // wait through it, which is how "religia first or last" is checked: a
    // religia lesson mid-day is a gap in the day of those who skip it. It used
    // to be its own rule, R10, judged against the whole class's day, which
    // rejected religia that ends one half's day and starts the other's
    // (REVIEW_2026-11-21 D2).
    val pupilHours = plan.flatMap(x => pupils(x).toList.map(p => (p, x.day) -> x.positions.toSet))
      .groupBy(_._1).view.mapValues(_.flatMap(_._2).toSet).toMap
    val gaps = pupilHours.count((key, hours) => key._1.isInstanceOf[Pupils] && longestGap(hours) > 0)
    rule("R9", "żadna grupa uczniów nie ma okienka", gaps, "dni grup")

    // RULES S9: an individual pupil has no windows either, unless remote.
    val remote: Set[Who] = taught.filter(_.kind == TuitionKind.REMOTE).map(t => Individual(t.planId)).toSet
    val pupilGaps = pupilHours.count((key, hours) =>
      key._1.isInstanceOf[Individual] && !remote.contains(key._1) && longestGap(hours) > 0)
    rule("R19", "uczeń nauczania indywidualnego nie ma okienka (poza zdalnym)", pupilGaps, "dni uczniów")

    // RULES P2, when the school has asked for it. A day is a pupil's, first
    // lesson to last; a day with nothing in it is a day of length zero, and
    // so as short as a day can be. Measured on what FET is told to bound:
    // each attendance group, once the school has entered attendance for the
    // class, and each half until then (REVIEW D3).
    for spread <- s.settings.optimisation.maxClassDaySpread do
      val entered = s.optionalAttendance.collect { case ((c, _), n) if n > 0 => c }.toSet
      val halfHours = plan.flatMap(x =>
        for c <- x.classIds; h <- x.halves yield (c, h, x.day) -> x.positions.toSet)
        .groupBy(_._1).view.mapValues(_.flatMap(_._2).toSet).toMap
      def spreadOf(hoursOn: Int => Option[Set[Int]]): Int =
        val lengths = (0 until s.teachingDays).map(d =>
          hoursOn(d).filter(_.nonEmpty).fold(0)(hours => hours.max - hours.min + 1))
        if lengths.isEmpty then 0 else lengths.max - lengths.min
      val measured: List[Int => Option[Set[Int]]] =
        for
          c <- s.classes
          h <- List(1, 2)
          day <- if entered.contains(c.id)
                 then groups.getOrElse(c.id, Nil).map(g =>
                   (d: Int) => pupilHours.get((Pupils(c.id, h, g), d)))
                 else List((d: Int) => halfHours.get((c.id, h, d)))
        yield day
      rule("R17", s"najdłuższy dzień grupy jest najwyżej o $spread lekcji dłuższy od najkrótszego",
        measured.count(spreadOf(_) > spread), "grup")

    // ── One block of a subject a day, per audience ───────────────────────
    val twice = plan
      .flatMap(x => x.classIds.map(c => (c, x.subjectId, x.audience, x.day)))
      .groupBy(identity).count(_._2.size > 1)
    rule("R5", "najwyżej jeden blok przedmiotu dziennie na grupę", twice, "przypadków")

    // ── Whole-class and half lessons of one subject — RULES S4 and S5 ────
    //
    // Only lessons of a class's own lines: a cross-class unit has no halves
    // of the class's split, and the bookkeeping entry for one is not taught.
    val ownLessons = plan.filter(x => !x.derived && x.classIds.nonEmpty)
    val bySubject = ownLessons
      .flatMap(x => x.classIds.map(c => (c, x.subjectId) -> x))
      .groupBy(_._1).view.mapValues(_.map(_._2)).toMap

    // S4: a half that has the subject on a day the whole class also has it
    // sees it twice that day.
    val sharedDays = bySubject.values.toList.map { lessons =>
      val whole = lessons.filter(_.audience == LessonAudience.WHOLE_CLASS).map(_.day).toSet
      val halves = lessons.filter(_.audience != LessonAudience.WHOLE_CLASS).map(_.day).toSet
      (whole intersect halves).size
    }.sum
    rule("R5b", "lekcja całej klasy i lekcja grupy z przedmiotu nie tego samego dnia",
      sharedDays, "dni")

    // S5, exactly as RULES states it: when a whole-class lesson starts, both
    // halves have had equally many of their own lessons of the subject since
    // Monday. Not "since the last whole-class lesson" — read that way, one
    // half can stay a lesson ahead all term.
    def earlier(x: Scheduled, than: Scheduled) =
      x.day < than.day || (x.day == than.day && x.from < than.from)
    val unlevel = bySubject.values.toList.map { lessons =>
      val whole = lessons.filter(_.audience == LessonAudience.WHOLE_CLASS)
      val first = lessons.filter(_.audience == LessonAudience.GROUP_1)
      val second = lessons.filter(_.audience == LessonAudience.GROUP_2)
      if first.isEmpty && second.isEmpty then 0
      else whole.count(w => first.count(earlier(_, w)) != second.count(earlier(_, w)))
    }.sum
    rule("R16", "obie grupy są na równi, gdy zaczyna się lekcja całej klasy",
      unlevel, "lekcji całej klasy")


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

      // Only of a lesson that has a class in it. One student taught alone
      // fits anywhere, which is the whole point of the small rooms the school
      // keeps for it.
      val tooSmall = taught.count(x =>
        x.classIds.nonEmpty && x.audience == LessonAudience.WHOLE_CLASS &&
          x.roomId.flatMap(byId.get).exists(!_.fitsWholeClass))
      rule("R3b", "cała klasa mieści się w sali, którą dostała", tooSmall, "lekcji")

      // What a lesson may use, as RULES RM2–RM4 say and the export offers:
      // its kind of room; an ordinary classroom too when the requirement is
      // only wanted (REVIEW D1), or when the school has no room of that kind;
      // and an ordinary classroom when it needs nothing in particular, which
      // keeps it out of specialist rooms something else needs.
      val kindExists = rooms.flatMap(_.kinds).map(_.id).toSet
      val wrongKind = taught.count { x =>
        x.roomId.flatMap(byId.get) match
          case Some(room) =>
            val ordinary = room.kinds.isEmpty
            x.needsKind match
              case Some(kind) if room.kinds.exists(_.id == kind) => false
              case Some(kind) => !(ordinary && (!x.kindIsHard || !kindExists(kind)))
              case None => !ordinary
          case None => false
      }
      rule("R3c", "sala specjalistyczna służy temu, do czego jest", wrongKind, "lekcji")

      val double = taught.flatMap(x => x.roomId.toList.flatMap(r => x.positions.map((r, x.day, _))))
        .groupBy(identity).count(_._2.size > 1)
      rule("R3d", "dwie lekcje nigdy nie dzielą sali", double, "kolizji")

      // A lesson FET was given no room for comes back with none, and every
      // check above passes it (REVIEW D5).
      rule("R3e", "każda lekcja ma salę", taught.count(_.roomId.isEmpty), "lekcji")

    Report(rules.result())

  /** How many (thing, hour) pairs are claimed by more than one lesson. */
  private def occupancy[A](plan: List[Scheduled])(keys: Scheduled => Iterable[A]): Int =
    plan.flatMap(keys).groupBy(identity).count(_._2.size > 1)

  /** "1 okienko", "2 okienka", "5 okienek". */
  private def okienka(n: Int): String =
    val word =
      if n == 1 then "okienko"
      else if n % 10 >= 2 && n % 10 <= 4 && !(n % 100 >= 12 && n % 100 <= 14) then "okienka"
      else "okienek"
    s"$n $word"

  /** The longest run of consecutive hours in a set. */
  private def longestRun(hours: Set[Int]): Int =
    hours.toList.sorted.foldLeft((0, 0, Int.MinValue)) { case ((run, best, last), h) =>
      val next = if h == last + 1 then run + 1 else 1
      (next, math.max(best, next), h)
    }._2

  /** The longest run of free hours between the first and last lesson of a day. */
  private def longestGap(hours: Set[Int]): Int =
    if hours.isEmpty then 0
    else
      val (lo, hi) = (hours.min, hours.max)
      (lo to hi).foldLeft((0, 0)) { case ((run, worst), p) =>
        val next = if hours.contains(p) then 0 else run + 1
        (next, math.max(worst, next))
      }._2
