package bel.api

import bel.domain.*
import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite

/**
 * Tuition, as the screen that checks an import reads it.
 *
 * The point of that screen is holding a hundred and twenty-one imported hours
 * against the arkusz, so the order has to be the arkusz's order and the totals
 * have to be its totals. A list that is right but shuffled is a list nobody
 * can check.
 */
class TuitionViewSpec extends AnyFunSuite:

  private val maths = UUID.randomUUID
  private val biology = UUID.randomUUID
  private val anna = UUID.randomUUID
  private val piotr = UUID.randomUUID

  private val a1 = SchoolClass(UUID.randomUUID, 1, "A", None, None, None, None)
  private val d1 = SchoolClass(UUID.randomUUID, 1, "D", None, None, None, None)

  private def teacher(id: UUID, code: String, last: String) =
    Teacher(id, code, "Jan", last, None, Nil, None, None, None, Nil)

  private def lesson(subject: UUID, who: Option[UUID], blocks: List[Int]) =
    IndividualLesson(UUID.randomUUID, subject, who, blocks)

  private def school(plans: List[IndividualPlan]) = SchoolSnapshot(
    SchedulingSettings(2, 5, 8), slotsPerDay = 8, teachingDays = 5,
    classes = List(a1, d1),
    teachers = List(teacher(anna, "AN", "Kowalska"), teacher(piotr, "PI", "Nowak")),
    subjects = List(
      Subject(maths, "mat", "Matematyka", false, None, false),
      Subject(biology, "bio", "Biologia", false, None, false)),
    lessonLines = Nil, crossClassUnits = Nil, individualPlans = plans,
    teacherSubjects = Map.empty, roomsOfKind = Map.empty, totalRooms = 0)

  test("a school with no tuition reports none, rather than nothing"):
    val view = TuitionView.of(school(Nil))
    assert(view.plans === 0 && view.hours === 0 && view.teachers === 0)
    assert(view.byClass.isEmpty)

  test("hours are the blocks added up, per lesson and per plan"):
    val plan = IndividualPlan(UUID.randomUUID, d1.id, 1, List(
      lesson(maths, Some(anna), List(1, 2)),
      lesson(biology, Some(piotr), List(1))))
    val view = TuitionView.of(school(List(plan)))
    assert(view.hours === 4)
    assert(view.byClass.head.hours === 4)
    assert(view.byClass.head.lessons.map(_.hours).sorted === List(1, 3))

  /** The arkusz lists by class, so this does too, or they cannot be compared. */
  test("plans come out by class, then by ordinal within it"):
    val plans = List(
      IndividualPlan(UUID.randomUUID, d1.id, 2, List(lesson(maths, Some(anna), List(1)))),
      IndividualPlan(UUID.randomUUID, a1.id, 1, List(lesson(maths, Some(anna), List(1)))),
      IndividualPlan(UUID.randomUUID, d1.id, 1, List(lesson(maths, Some(anna), List(1)))))
    val view = TuitionView.of(school(plans))
    assert(view.byClass.map(p => s"${p.className}/${p.ordinal}") === List("1A/1", "1D/1", "1D/2"))

  test("lessons within a plan come out by subject"):
    val plan = IndividualPlan(UUID.randomUUID, a1.id, 1, List(
      lesson(maths, Some(anna), List(1)),
      lesson(biology, Some(anna), List(1))))
    val view = TuitionView.of(school(List(plan)))
    assert(view.byClass.head.lessons.map(_.subject) === List("Biologia", "Matematyka"))

  /**
   * The headline figure is how many people this falls to, because that is the
   * part competing with everything else they teach — not how many lessons.
   */
  test("a teacher taking several of these is counted once"):
    val plans = List(
      IndividualPlan(UUID.randomUUID, a1.id, 1, List(
        lesson(maths, Some(anna), List(1)), lesson(biology, Some(anna), List(1)))),
      IndividualPlan(UUID.randomUUID, d1.id, 1, List(lesson(maths, Some(anna), List(1)))))
    assert(TuitionView.of(school(plans)).teachers === 1)

  /** Worth seeing rather than hiding: those hours block nobody's time. */
  test("a lesson the arkusz named nobody for comes through as nobody"):
    val plan = IndividualPlan(UUID.randomUUID, a1.id, 1, List(lesson(maths, None, List(2))))
    val view = TuitionView.of(school(List(plan)))
    assert(view.byClass.head.lessons.head.teacher === None)
    assert(view.teachers === 0)
    assert(view.hours === 2, "the hours still count even if nobody is named")

  test("a teacher is named the way every other screen names one"):
    val plan = IndividualPlan(UUID.randomUUID, a1.id, 1, List(lesson(maths, Some(piotr), List(1))))
    assert(TuitionView.of(school(List(plan))).byClass.head.lessons.head.teacher
      === Some("Nowak Jan (PI)"))
