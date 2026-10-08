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

case class Finding(severity: Severity, subject: String, message: String)

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
  teacherSubjects: Map[UUID, Set[UUID]],
  roomsOfKind: Map[UUID, Int],
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
      blocksAcrossDays(s) ++
      groupBalance(s) ++
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
          s"$total godz. tygodniowo, a w planie mieści się $capacity — nie da się ułożyć"))
      else if total == capacity then
        Some(Finding(Severity.Warning, cls.name,
          s"$total godz. wypełnia cały tydzień co do godziny — brak miejsca na jakikolwiek ruch"))
      else None
    }

  /**
   * At most one block of a subject per day, so a subject needing more blocks
   * than there are teaching days cannot be placed however much room there is.
   */
  private def blocksAcrossDays(s: SchoolSnapshot): List[Finding] =
    val names = s.subjects.map(x => x.id -> x.name).toMap
    val classNames = s.classes.map(c => c.id -> c.name).toMap
    s.lessonLines.flatMap { line =>
      if line.blocks.sizeIs > s.teachingDays then
        Some(Finding(Severity.Blocking, classNames.getOrElse(line.classId, "?"),
          s"${names.getOrElse(line.subjectId, "?")}: ${line.blocks.size} bloków, " +
            s"a dni nauki jest ${s.teachingDays} — najwyżej jeden blok dziennie"))
      else None
    }

  /** Both halves must be busy at once, so their hours have to match. */
  private def groupBalance(s: SchoolSnapshot): List[Finding] =
    val names = s.subjects.map(x => x.id -> x.name).toMap
    s.classes.flatMap { cls =>
      ClassAllocation
        .problems(s.lessonLines.filter(_.classId == cls.id), names)
        .map(p => Finding(if p.blocking then Severity.Blocking else Severity.Warning,
          cls.name, p.message))
    }

  // ── Teachers ──────────────────────────────────────────────────────────────

  private def teacherLoad(s: SchoolSnapshot, capacity: Int): List[Finding] =
    s.teachers.flatMap { teacher =>
      val own = s.lessonLines.filter(_.teacherId.contains(teacher.id)).map(_.hours).sum
      val support = s.lessonLines.filter(_.supportTeacherId.contains(teacher.id)).map(_.hours).sum
      val cross = s.crossClassUnits
        .flatMap(u => u.groups.filter(_.teacherId.contains(teacher.id)).map(_ => u.hours)).sum
      val total = own + support + cross
      val name = s"${teacher.lastName} ${teacher.firstName}"

      val blocked = teacher.unavailability.map(b => b.toPosition - b.fromPosition + 1).sum
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
            s"(dostępność $available, limity $byLimits)"))
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
      val own = s.lessonLines.filter(_.teacherId.contains(teacher.id)).map(_.hours).sum
      val cross = s.crossClassUnits
        .flatMap(u => u.groups.filter(_.teacherId.contains(teacher.id)).map(_ => u.hours)).sum
      teacher.pensum.filter(p => p > 0 && own + cross > p).map(p => own + cross - p)
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
              "a przedmiot nie jest wpisany jako uczony"))
        else None
      }

  // ── Classes and their wychowawca ──────────────────────────────────────────

  private def homeroomHour(s: SchoolSnapshot): List[Finding] =
    val homeroomSubject = s.subjects.find(_.code == s.homeroomSubjectCode)
    s.classes.flatMap { cls =>
      val missing =
        if cls.homeroomTeacherId.isEmpty then
          List(Finding(Severity.Warning, cls.name, "brak wychowawcy"))
        else Nil

      // Godzina wychowawcza is the wychowawca's hour by definition; anyone else
      // taking it means one of the two is wrong.
      val wrongTeacher = (homeroomSubject, cls.homeroomTeacherId) match
        case (Some(subject), Some(wychowawca)) =>
          s.lessonLines
            .filter(l => l.classId == cls.id && l.subjectId == subject.id)
            .filter(l => l.teacherId.exists(_ != wychowawca))
            .map(_ => Finding(Severity.Warning, cls.name,
              s"${subject.name} prowadzi ktoś inny niż wychowawca"))
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
            "wszystkie odbywają się jednocześnie"))

      val unassigned = unit.groups.count(_.teacherId.isEmpty)
      val noTeacher =
        if unassigned > 0 then
          Some(Finding(Severity.Warning, unit.name, s"$unassigned grup bez nauczyciela"))
        else None

      tooManyGroups.toList ++ noTeacher.toList
    }
