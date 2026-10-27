package bel.domain

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import java.util.UUID

enum Severity:
  /** The plan cannot exist as the data stands. */
  case Blocking
  /** It can, but something looks wrong and is worth a second look. */
  case Warning

object Severity:
  given Encoder[Severity] = Encoder[String].contramap(_.toString)
  given Decoder[Severity] = Decoder[String].emap(s =>
    scala.util.Try(valueOf(s)).toEither.left.map(_.getMessage))

/**
 * @param entityId the class, teacher or unit the finding is about, so a screen
 *   can show it beside the row it concerns rather than only on a page of its
 *   own. A list of problems somewhere else is a list someone has to remember
 *   to go and read.
 */
case class Finding(
  severity: Severity,
  subject: String,
  message: String,
  entityId: Option[UUID] = None
)

object Finding:
  given Encoder[Finding] = deriveEncoder
  given Decoder[Finding] = deriveDecoder

/** Everything the checks need, gathered once. */
case class SchoolSnapshot(
  settings: SchedulingSettings,
  slotsPerDay: Int,
  teachingDays: Int,
  classes: List[SchoolClass],
  teachers: List[Teacher],
  subjects: List[Subject],
  lessonLines: List[LessonLine],
  crossClassUnits: List[CrossClassUnit],
  /** One student taught alone — hours that book a teacher like any other. */
  individualPlans: List[IndividualPlan] = Nil,
  teacherSubjects: Map[UUID, Set[UUID]],
  roomsOfKind: Map[UUID, Int],
  totalRooms: Int,
  homeroomSubjectCode: String = "zaj. z wych."
)

/**
 * What the data says before anyone tries to schedule it.
 *
 * The expensive failure is a solver run that spends an hour proving something
 * impossible, so anything that can be decided by counting is decided here. The
 * severity says which: blocking means no arrangement of these hours can work,
 * warning means it can but something reads oddly.
 */
object Validation:

  def run(s: SchoolSnapshot): List[Finding] =
    val capacity = s.slotsPerDay * s.teachingDays
    classCapacity(s, capacity) ++
      halfCapacity(s, capacity) ++
      roomCapacity(s, capacity) ++
      specialistRooms(s, capacity) ++
      blocksAcrossDays(s) ++
      groupBalance(s) ++
      sharedTeacherSplits(s) ++
      teacherLoad(s, capacity) ++
      pensumSummary(s) ++
      teacherSubjects(s) ++
      homeroomHour(s) ++
      crossClassBalance(s)

  // ── Classes ───────────────────────────────────────────────────────────────

  private def classCapacity(s: SchoolSnapshot, capacity: Int): List[Finding] =
    s.classes.flatMap { cls =>
      val own = ClassAllocation.occupiedHours(s.lessonLines.filter(_.classId == cls.id))
      val cross = s.crossClassUnits.filter(_.classIds.contains(cls.id)).map(_.hours).sum
      val total = own + cross
      if total > capacity then
        Some(Finding(Severity.Blocking, cls.name,
          s"$total godz. tygodniowo, a w planie mieści się $capacity — nie da się ułożyć",
          Some(cls.id)))
      else if total == capacity then
        Some(Finding(Severity.Warning, cls.name,
          s"$total godz. wypełnia cały tydzień co do godziny — brak miejsca na jakikolwiek ruch",
          Some(cls.id)))
      else None
    }

  /**
   * The same sum again, but for each half of a class on its own.
   *
   * The check above counts a class's week as its whole-class hours plus one
   * half's, which is right while the halves have the same number of hours —
   * they sit in the same slots, so one of them stands for both. When they do
   * not, the longer half is in school for hours the sum never counted, and a
   * class can pass the first check while one of its halves has a forty-four
   * hour week in a building that is open for forty.
   *
   * We did not have this, and found out by handing the school to a program
   * that did: FET refused the file in seconds over exactly this sum. Worth
   * keeping for its own sake — it costs nothing and it is the difference
   * between a clear answer now and a search that fails later for reasons that
   * take an afternoon to attribute.
   */
  private def halfCapacity(s: SchoolSnapshot, capacity: Int): List[Finding] =
    s.classes.flatMap { cls =>
      val mine = s.lessonLines.filter(_.classId == cls.id)
      val whole = mine.filter(_.audience == LessonAudience.WHOLE_CLASS).map(_.hours).sum
      val cross = s.crossClassUnits.filter(_.classIds.contains(cls.id)).map(_.hours).sum
      List(LessonAudience.GROUP_1, LessonAudience.GROUP_2).flatMap { half =>
        val own = mine.filter(_.audience == half).map(_.hours).sum
        val total = whole + cross + own
        val label = if half == LessonAudience.GROUP_1 then "1" else "2"
        Option.when(total > capacity)(Finding(Severity.Blocking, cls.name,
          s"grupa $label ma $total godz. tygodniowo, a w planie mieści się $capacity " +
            s"— nie da się ułożyć",
          Some(cls.id)))
      }
    }

  /**
   * Two lessons cannot share a room, so the week has only so much room-time in
   * it. Counting that is instant; discovering it by search costs a run.
   *
   * A slot where a class is split needs two rooms, not one — which is why this
   * counts lines rather than the class's occupied hours.
   */
  private def roomCapacity(s: SchoolSnapshot, capacity: Int): List[Finding] =
    val needed =
      s.lessonLines.map(_.hours).sum +
        // Each group of a unit is taught somewhere of its own, simultaneously.
        s.crossClassUnits.map(u => u.hours * u.groups.size).sum
    val available = s.totalRooms * capacity
    if s.totalRooms == 0 then
      List(Finding(Severity.Blocking, "Sale",
        "Nie wpisano żadnej sali — arkusz ich nie zawiera, trzeba je dodać ręcznie"))
    else if needed > available then
      List(Finding(Severity.Blocking, "Sale",
        s"$needed godzin lekcyjnych wymaga sali, a ${s.totalRooms} sal daje tylko " +
          s"$available miejsc w tygodniu"))
    else Nil

  /**
   * At most one block of a subject per day, so a subject needing more blocks
   * than there are teaching days cannot be placed however much room there is.
   *
   * Counted across every line of the subject, not per line. Base and extension
   * hours are one subject and compete for the same days, and the import makes
   * every hour its own block — so a class with five base and two extension
   * hours of maths arrives as seven blocks wanting seven days, while no single
   * line looks wrong. That is the shape this check exists to catch.
   */
  /**
   * A specialist room is only so many hours of the week.
   *
   * The sports hall has three sections, so it offers three lessons an hour and
   * a hundred and twenty in a week however cleverly they are arranged. Asking
   * for a hundred and thirty-eight is not a hard scheduling problem, it is an
   * impossible one, and counting says so before anything is searched.
   *
   * This went unnoticed because nothing required the hall at all: the room
   * kind existed, the three sections carried it, and no subject asked for it —
   * so PE was timetabled into ordinary classrooms and the arithmetic never
   * came up. A requirement nobody states cannot be broken.
   */
  private def specialistRooms(s: SchoolSnapshot, capacity: Int): List[Finding] =
    val kinds = s.subjects.flatMap(x => x.requiredRoomKind.map(_ -> x)).groupBy(_._1)
    kinds.toList.sortBy(_._1.name).flatMap { (kind, subjects) =>
      val wanted = subjects.map(_._2.id).toSet
      val lineHours = s.lessonLines.filter(l => wanted.contains(l.subjectId)).map(_.hours).sum
      // Every group of a unit needs a room of its own, at the same hour.
      val unitHours = s.crossClassUnits.filter(u => wanted.contains(u.subjectId))
        .map(u => u.hours * u.groups.size).sum
      val needed = lineHours + unitHours
      val have = s.roomsOfKind.getOrElse(kind.id, 0)
      val offered = have * capacity

      if have == 0 && needed > 0 then
        Some(Finding(Severity.Blocking, kind.name,
          s"$needed godz. wymaga tej pracowni, a nie ma ani jednej takiej sali"))
      else if needed > offered then
        Some(Finding(Severity.Blocking, kind.name,
          s"$needed godz. wymaga tej pracowni, a $have sal daje najwyżej $offered godz. " +
            "tygodniowo — nie da się ułożyć"))
      else None
    }

  private def blocksAcrossDays(s: SchoolSnapshot): List[Finding] =
    val names = s.subjects.map(x => x.id -> x.name).toMap
    val classNames = s.classes.map(c => c.id -> c.name).toMap
    s.lessonLines
      .groupBy(l => (l.classId, l.subjectId, l.audience))
      .toList
      .sortBy((key, _) => (classNames.getOrElse(key._1, ""), names.getOrElse(key._2, "")))
      .flatMap { case ((classId, subjectId, audience), lines) =>
        val blocks = lines.map(_.blocks.size).sum
        if blocks > s.teachingDays then
          val half = audience match
            case LessonAudience.WHOLE_CLASS => ""
            case LessonAudience.GROUP_1     => " (grupa 1)"
            case LessonAudience.GROUP_2     => " (grupa 2)"
          Some(Finding(Severity.Blocking, classNames.getOrElse(classId, "?"),
            s"${names.getOrElse(subjectId, "?")}$half: $blocks bloków, a dni nauki jest " +
              s"${s.teachingDays} — najwyżej jeden blok dziennie. Połącz godziny w bloki " +
              "dwugodzinne.",
            Some(classId)))
        else None
      }

  /** Both halves must be busy at once, so their hours have to match. */
  private def groupBalance(s: SchoolSnapshot): List[Finding] =
    val names = s.subjects.map(x => x.id -> x.name).toMap
    s.classes.flatMap { cls =>
      ClassAllocation
        .problems(s.lessonLines.filter(_.classId == cls.id), names)
        .map(p => Finding(if p.blocking then Severity.Blocking else Severity.Warning,
          cls.name, p.message, Some(cls.id)))
    }

  /**
   * A split whose two halves are taught by the same person.
   *
   * They cannot be taught at once, so the other half of the class is not in
   * school for them. That is schedulable — the lesson goes at an end of the
   * day so the other half comes in later or leaves earlier — but it is worth
   * saying out loud, because it is not what the school wants and it is easy to
   * create by accident when assigning teachers.
   */
  private def sharedTeacherSplits(s: SchoolSnapshot): List[Finding] =
    val names = s.subjects.map(x => x.id -> x.name).toMap
    s.classes.flatMap { cls =>
      val affected = s.lessonLines
        .filter(l => l.classId == cls.id && l.audience != LessonAudience.WHOLE_CLASS)
        .groupBy(_.subjectId)
        .toList
        .filter { (_, lines) =>
          val first = lines.filter(_.audience == LessonAudience.GROUP_1).flatMap(_.teacherId).toSet
          val second = lines.filter(_.audience == LessonAudience.GROUP_2).flatMap(_.teacherId).toSet
          first.intersect(second).nonEmpty
        }
        .map((subjectId, _) => names.getOrElse(subjectId, "?"))
        .sorted

      // One line per class, listing its subjects. The school's own arkusz has
      // nearly a hundred of these; a line each would bury everything else on
      // the page, and beside a class row the list is what is useful anyway.
      if affected.isEmpty then None
      else
        Some(Finding(Severity.Warning, cls.name,
          s"${affected.mkString(", ")}: obie grupy prowadzi ta sama osoba, więc nie mogą " +
            "odbywać się jednocześnie — zostaną zaplanowane na początku lub końcu dnia, " +
            "a druga połowa klasy przyjdzie później albo wyjdzie wcześniej",
          Some(cls.id)))
    }

  // ── Teachers ──────────────────────────────────────────────────────────────

  /**
   * A unit's hours count once for a teacher however many of its groups they
   * lead. Every group runs at the same time, so leading two is not twice the
   * work — it is impossible, which `crossClassBalance` reports separately.
   */
  private def crossClassHours(s: SchoolSnapshot, teacherId: UUID): Int =
    s.crossClassUnits.filter(_.groups.exists(_.teacherId.contains(teacherId))).map(_.hours).sum

  /** Everything a teacher stands in front of a class for. */
  private def teacherHours(s: SchoolSnapshot, teacherId: UUID): Int =
    s.lessonLines.filter(_.teacherId.contains(teacherId)).map(_.hours).sum +
      // Supporting a lesson is time spent teaching it, so it counts against
      // pensum the same way the load ceiling already counts it.
      s.lessonLines.filter(_.supportTeacherId.contains(teacherId)).map(_.hours).sum +
      crossClassHours(s, teacherId)

  private def teacherLoad(s: SchoolSnapshot, capacity: Int): List[Finding] =
    s.teachers.flatMap { teacher =>
      val total = teacherHours(s, teacher.id)
      val name = s"${teacher.lastName} ${teacher.firstName}"

      // Counted as distinct slots rather than summed lengths: two blocks that
      // overlap — Monday 1-4 and Monday 3-6 — describe six blocked slots, not
      // eight, and summing them invents a shortage that is not there.
      val blocked = teacher.unavailability
        .flatMap(b => (b.fromPosition to b.toPosition).map(b.dayOfWeek -> _))
        .distinct
        .size
      val available = capacity - blocked
      val maxDays = teacher.maxWorkingDays.getOrElse(s.settings.defaultTeacherMaxWorkingDays)
      val maxPerDay = teacher.maxLessonsPerDay.getOrElse(s.settings.defaultTeacherMaxLessonsPerDay)
      val byLimits = maxDays * maxPerDay

      // Blocked hours and the per-teacher limits both reduce what can be
      // scheduled; whichever bites first is the real ceiling.
      val ceiling = math.min(available, byLimits)

      if total > ceiling then
        Some(Finding(Severity.Blocking, name,
          s"$total godz. przydziału, a zmieścić się może najwyżej $ceiling " +
            s"(dostępność $available, limity $byLimits)", Some(teacher.id)))
      else None
    }

  /**
   * Teaching above pensum is nadgodziny, which is normal and paid — so it is
   * one line saying how many, not a line per teacher. Thirty-eight identical
   * warnings would bury the two that need acting on, and the figures are in
   * the teacher table underneath anyway.
   */
  private def pensumSummary(s: SchoolSnapshot): List[Finding] =
    val over = s.teachers.flatMap { teacher =>
      val taught = teacherHours(s, teacher.id)
      teacher.pensum.filter(p => p > 0 && taught > p).map(p => taught - p)
    }
    if over.isEmpty then Nil
    else
      List(Finding(Severity.Warning, "Nadgodziny",
        s"${over.size} nauczycieli ponad pensum, łącznie ${over.sum} godz. " +
          s"(najwięcej ${over.max}) — szczegóły w tabeli poniżej"))

  private def teacherSubjects(s: SchoolSnapshot): List[Finding] =
    val subjectNames = s.subjects.map(x => x.id -> x.name).toMap
    val teacherNames = s.teachers.map(t => t.id -> s"${t.lastName} ${t.firstName}").toMap
    s.lessonLines
      .flatMap(line => line.teacherId.map(_ -> line.subjectId))
      .distinct
      .flatMap { (teacherId, subjectId) =>
        val teaches = s.teacherSubjects.getOrElse(teacherId, Set.empty)
        if teaches.nonEmpty && !teaches.contains(subjectId) then
          Some(Finding(Severity.Warning, teacherNames.getOrElse(teacherId, "?"),
            s"przydzielono ${subjectNames.getOrElse(subjectId, "?")}, " +
              "a przedmiot nie jest wpisany jako uczony", Some(teacherId)))
        else None
      }

  // ── Classes and their wychowawca ──────────────────────────────────────────

  private def homeroomHour(s: SchoolSnapshot): List[Finding] =
    val homeroomSubject = s.subjects.find(_.code == s.homeroomSubjectCode)
    s.classes.flatMap { cls =>
      val missing =
        if cls.homeroomTeacherId.isEmpty then
          List(Finding(Severity.Warning, cls.name, "brak wychowawcy", Some(cls.id)))
        else Nil

      // Godzina wychowawcza is the wychowawca's hour by definition; anyone else
      // taking it means one of the two is wrong.
      val wrongTeacher = (homeroomSubject, cls.homeroomTeacherId) match
        case (Some(subject), Some(wychowawca)) =>
          // One line per class however many of its hours are mis-assigned:
          // the same sentence twice says nothing the first did not.
          if s.lessonLines.exists(l =>
               l.classId == cls.id && l.subjectId == subject.id &&
                 l.teacherId.exists(_ != wychowawca))
          then
            List(Finding(Severity.Warning, cls.name,
              s"${subject.name} prowadzi ktoś inny niż wychowawca", Some(cls.id)))
          else Nil
        case _ => Nil

      missing ++ wrongTeacher
    }

  // ── Cross-class lessons ───────────────────────────────────────────────────

  private def crossClassBalance(s: SchoolSnapshot): List[Finding] =
    s.crossClassUnits.flatMap { unit =>
      val tooManyGroups = unit.requiredRoomKindId
        .flatMap(s.roomsOfKind.get)
        .filter(_ < unit.groups.size)
        .map(available => Finding(Severity.Blocking, unit.name,
          s"${unit.groups.size} grup, a sal tego typu ${available} — " +
            "wszystkie odbywają się jednocześnie", Some(unit.id)))

      // Every group of a unit runs at once, so one teacher cannot hold two of
      // them. Nothing downstream would notice: the solver would place the unit
      // happily and the teacher would be in two rooms at the same time.
      val doubleBooked = unit.groups
        .flatMap(_.teacherId)
        .groupBy(identity)
        .collect { case (teacherId, repeats) if repeats.sizeIs > 1 => teacherId }
        .toList
      val sameTeacherTwice =
        if doubleBooked.nonEmpty then
          Some(Finding(Severity.Blocking, unit.name,
            s"${doubleBooked.size} nauczycieli prowadzi po kilka grup tego zespołu, " +
              "a wszystkie odbywają się jednocześnie", Some(unit.id)))
        else None

      val unassigned = unit.groups.count(_.teacherId.isEmpty)
      val noTeacher =
        if unassigned > 0 then
          Some(Finding(Severity.Warning, unit.name, s"$unassigned grup bez nauczyciela", Some(unit.id)))
        else None

      tooManyGroups.toList ++ sameTeacherTwice.toList ++ noTeacher.toList
    }
