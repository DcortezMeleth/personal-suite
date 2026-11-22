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
