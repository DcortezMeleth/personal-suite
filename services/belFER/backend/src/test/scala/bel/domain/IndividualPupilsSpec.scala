package bel.domain

import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite

/**
 * What the planner sets on an individual plan (RULES S9), and what Walidacja
 * says while it is missing.
 */
class IndividualPupilsSpec extends AnyFunSuite:

  private val maths = UUID.randomUUID
  private val c1 = SchoolClass(UUID.randomUUID, 1, "C", None, None, Some(30), None)

  private def plan(kind: Option[TuitionKind], half: Option[Int] = None) =
    IndividualPlan(UUID.randomUUID, c1.id, 1,
      List(IndividualLesson(UUID.randomUUID, maths, None, List(1))), kind, half)

  private def school(plans: List[IndividualPlan], split: Boolean) =
    val audience = if split then LessonAudience.GROUP_1 else LessonAudience.WHOLE_CLASS
    SchoolSnapshot(
      SchedulingSettings(2, 5, 8), slotsPerDay = 8, teachingDays = 5,
      classes = List(c1), teachers = Nil,
      subjects = List(Subject(maths, "mat", "Matematyka", false, None, false)),
      lessonLines = List(LessonLine(UUID.randomUUID, c1.id, maths, audience, LessonKind.BASE,
        None, None, List(1))),
      crossClassUnits = Nil, individualPlans = plans, teacherSubjects = Map.empty,
      roomsOfKind = Map.empty, totalRooms = 10)

  test("a half is 1 or 2, and only a mixed pupil has one"):
    assert(TuitionSettings.validate(TuitionSettings(Some(TuitionKind.MIXED), Some(1))).isRight)
    assert(TuitionSettings.validate(TuitionSettings(Some(TuitionKind.MIXED), None)).isRight)
    assert(TuitionSettings.validate(TuitionSettings(Some(TuitionKind.MIXED), Some(3))).isLeft)
    assert(TuitionSettings.validate(TuitionSettings(Some(TuitionKind.ON_SITE), Some(1))).isLeft)
    assert(TuitionSettings.validate(TuitionSettings(None, None)).isRight, "clearing it is allowed")

  test("the settings decode from what the page sends, either part absent"):
    assert(io.circe.parser.decode[TuitionSettings]("""{"kind":"MIXED","half":2}""") ===
      Right(TuitionSettings(Some(TuitionKind.MIXED), Some(2))))
    assert(io.circe.parser.decode[TuitionSettings]("{}") === Right(TuitionSettings(None, None)))
    assert(io.circe.parser.decode[TuitionSettings]("""{"kind":"HYBRID"}""").isLeft)

  test("a pupil with no kind yet is blocking"):
    val found = Validation.run(school(List(plan(None)), split = false))
      .filter(_.message.startsWith("Nie wybrano rodzaju"))
    assert(found.map(_.severity) === List(Severity.Blocking))
    assert(found.head.subject === "1C / 1")
    assert(found.head.entityId === Some(c1.id), "shown beside the class")

  test("a mixed pupil needs a half only where the class has lessons in halves"):
    def missingHalf(s: SchoolSnapshot) = Validation.run(s).count(_.message.startsWith("Uczeń mieszany"))
    assert(missingHalf(school(List(plan(Some(TuitionKind.MIXED))), split = true)) === 1)
    assert(missingHalf(school(List(plan(Some(TuitionKind.MIXED))), split = false)) === 0)
    assert(missingHalf(school(List(plan(Some(TuitionKind.MIXED), Some(1))), split = true)) === 0)

  test("remote and on-site pupils need nothing more"):
    for kind <- List(TuitionKind.REMOTE, TuitionKind.ON_SITE) do
      assert(!Validation.run(school(List(plan(Some(kind))), split = true))
        .exists(f => f.message.startsWith("Nie wybrano") || f.message.startsWith("Uczeń mieszany")))
