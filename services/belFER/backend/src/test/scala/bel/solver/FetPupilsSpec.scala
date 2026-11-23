package bel.solver

import bel.domain.*
import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite

/**
 * Individual pupils as FET hears them (RULES S9). Until 2026-11-22 an
 * individual lesson named no student set, so FET did not know the pupil
 * existed and double-booked them.
 */
class FetPupilsSpec extends AnyFunSuite:

  private val maths = UUID.randomUUID
  private val anna = UUID.randomUUID
  private val c1 = SchoolClass(UUID.randomUUID, 1, "C", None, None, Some(30), None)
  private val rooms = List(Room(UUID.randomUUID, "12", None, true, Nil))

  private def plan(kind: Option[TuitionKind], ordinal: Int = 1) =
    IndividualPlan(UUID.randomUUID, c1.id, ordinal,
      List(IndividualLesson(UUID.randomUUID, maths, Some(anna), List(1, 1))), kind)

  private def xml(plans: List[IndividualPlan]) = FetExport(SchoolSnapshot(
    SchedulingSettings(2, 5, 8), slotsPerDay = 8, teachingDays = 5,
    classes = List(c1),
    teachers = List(Teacher(anna, "AN", "Jan", "Kowalski", None, Nil, None, None, None, Nil)),
    subjects = List(Subject(maths, "mat", "Matematyka", false, None, false)),
    lessonLines = List(LessonLine(UUID.randomUUID, c1.id, maths, LessonAudience.WHOLE_CLASS,
      LessonKind.BASE, Some(anna), None, List(1))),
    crossClassUnits = Nil, individualPlans = plans, teacherSubjects = Map.empty,
    roomsOfKind = Map.empty, totalRooms = 1), rooms).xml

  private def noGapsFor(x: String, set: String) =
    x.contains(s"<Max_Gaps>0</Max_Gaps>\n\t\t<Students>$set</Students>")

  test("a pupil taught apart is a student set of their own, named by class and ordinal"):
    val x = xml(List(plan(Some(TuitionKind.ON_SITE))))
    assert(x.contains("<Year>\n\t\t<Name>1C~i1</Name>\n\t\t<Number_of_Students>1</Number_of_Students>"))
    assert(raw"<Students>1C~i1</Students>".r.findAllMatchIn(x).size >= 2, "both lessons name the pupil")

  test("an on-site pupil gets no windows, a remote one may wait"):
    assert(noGapsFor(xml(List(plan(Some(TuitionKind.ON_SITE)))), "1C~i1"))
    assert(!noGapsFor(xml(List(plan(Some(TuitionKind.REMOTE)))), "1C~i1"))

  test("a pupil whose kind is not set yet is planned as on-site"):
    assert(noGapsFor(xml(List(plan(None))), "1C~i1"))

  test("a mixed pupil is not a class of their own"):
    // They are part of their class; the class has no halves, so no half is needed.
    assert(!xml(List(plan(Some(TuitionKind.MIXED)))).contains("1C~i1"))

  // ── A mixed pupil, inside their class (RULES S9) ─────────────────────

  private val history = UUID.randomUUID
  private val english = UUID.randomUUID

  /** 1C: maths and history for the whole class, English in halves. The pupil,
    * in half 2, takes maths on their own. */
  private def mixedSchool(spread: Option[Int] = None) =
    def line(subject: UUID, audience: LessonAudience) =
      LessonLine(UUID.randomUUID, c1.id, subject, audience, LessonKind.BASE, Some(anna), None, List(1))
    val pupil = IndividualPlan(UUID.randomUUID, c1.id, 1,
      List(IndividualLesson(UUID.randomUUID, maths, Some(anna), List(1))), Some(TuitionKind.MIXED), Some(2))
    FetExport(SchoolSnapshot(
      SchedulingSettings(2, 5, 8, Optimisation(maxClassDaySpread = spread)), slotsPerDay = 8, teachingDays = 5,
      classes = List(c1),
      teachers = List(Teacher(anna, "AN", "Jan", "Kowalski", None, Nil, None, None, None, Nil)),
      subjects = List(Subject(maths, "mat", "Matematyka", false, None, false),
        Subject(history, "his", "Historia", false, None, false),
        Subject(english, "ang", "Angielski", false, None, false)),
      lessonLines = List(line(maths, LessonAudience.WHOLE_CLASS), line(history, LessonAudience.WHOLE_CLASS),
        line(english, LessonAudience.GROUP_1), line(english, LessonAudience.GROUP_2)),
      crossClassUnits = Nil, individualPlans = List(pupil), teacherSubjects = Map.empty,
      roomsOfKind = Map.empty, totalRooms = 1), rooms).xml

  /** The student sets each activity of a subject is addressed to. */
  private def addressed(x: String, subject: String): List[List[String]] =
    raw"(?s)<Activity>(.*?)</Activity>".r.findAllMatchIn(x).map(_.group(1))
      .filter(_.contains(s"<Subject>$subject</Subject>"))
      .map(a => raw"<Students>(.*?)</Students>".r.findAllMatchIn(a).map(_.group(1)).toList.sorted)
      .toList.sortBy(_.mkString)

  test("a mixed pupil is a subgroup of their own half"):
    val x = mixedSchool()
    assert(x.contains("<Name>1C/2</Name>"))
    val half2 = raw"(?s)<Group>\s*<Name>1C/2</Name>(.*?)</Group>".r.findFirstMatchIn(x).get.group(1)
    assert(half2.contains("<Name>1C/2s-i1</Name>"))
    assert(!x.contains("<Name>1C~i1</Name>"), "not a class of their own")

  test("the class's lessons of the pupil's own subject leave them out; the rest include them"):
    val x = mixedSchool()
    // Maths: the whole class except the pupil (as the class's other subgroups),
    // and the pupil's own lesson.
    assert(addressed(x, "Matematyka").toSet === Set(List("1C/1s", "1C/2s"), List("1C/2s-i1")))
    // History and the halves' English are addressed as always, which includes them.
    assert(addressed(x, "Historia") === List(List("1C")))
    assert(addressed(x, "Angielski") === List(List("1C/1"), List("1C/2")))

  test("every set an activity names is one the file declares"):
    val x = mixedSchool()
    val declared = raw"<Name>(.*?)</Name>".r.findAllMatchIn(
      raw"(?s)<Students_List>(.*?)</Students_List>".r.findFirstMatchIn(x).get.group(1)).map(_.group(1)).toSet
    val named = raw"<Students>(.*?)</Students>".r.findAllMatchIn(
      raw"(?s)<Activities_List>(.*?)</Activities_List>".r.findFirstMatchIn(x).get.group(1)).map(_.group(1)).toSet
    assert(named.subsetOf(declared), named -- declared)

  test("the class-day range is not put on a mixed pupil"):
    val x = mixedSchool(spread = Some(3))
    assert(!x.contains("<Students>1C/2s-i1</Students>\n\t\t<Allow_Empty_Days>"))
    assert(!raw"(?s)<ConstraintStudentsSetM(in|ax)HoursDaily>[^/]*?<Students>(1C|1C/2s-i1)</Students>".r
      .findAllMatchIn(x).exists(m => m.group(0).contains("Minimum_Hours_Daily") && m.group(2) == "1C"),
      "no class-level minimum, which would reach the pupil")
