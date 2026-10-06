package bel.domain

import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite

class LessonLineValidationSpec extends AnyFunSuite:

  private val base = LessonLineInput(
    classId = UUID.randomUUID,
    subjectId = UUID.randomUUID,
    audience = LessonAudience.WHOLE_CLASS,
    kind = LessonKind.BASE,
    teacherId = Some(UUID.randomUUID),
    supportTeacherId = None,
    blocks = List(1, 1, 2)
  )

  test("accepts four hours shaped as two singles and a double") {
    assert(LessonLineValidation.validate(base, 8) == Right(()))
  }

  test("accepts a line with no teacher yet, since the arkusz arrives that way") {
    assert(LessonLineValidation.validate(base.copy(teacherId = None), 8) == Right(()))
  }

  test("rejects an empty block list") {
    assert(LessonLineValidation.validate(base.copy(blocks = Nil), 8).isLeft)
  }

  test("rejects a zero or negative block") {
    assert(LessonLineValidation.validate(base.copy(blocks = List(1, 0)), 8).isLeft)
    assert(LessonLineValidation.validate(base.copy(blocks = List(-1)), 8).isLeft)
  }

  // A block occupies consecutive slots of a single day, so one longer than the
  // day can never be placed — and finding that out during generation is hours
  // too late.
  test("rejects a block longer than the school day") {
    assert(LessonLineValidation.validate(base.copy(blocks = List(9)), 8).isLeft)
    assert(LessonLineValidation.validate(base.copy(blocks = List(8)), 8) == Right(()))
  }

  test("skips the day-length check when there is no bell schedule yet") {
    assert(LessonLineValidation.validate(base.copy(blocks = List(99)), 0) == Right(()))
  }

  test("rejects a support teacher who is also the teacher") {
    val same = UUID.randomUUID
    assert(LessonLineValidation.validate(
      base.copy(teacherId = Some(same), supportTeacherId = Some(same)), 8).isLeft)
  }

class ClassAllocationSpec extends AnyFunSuite:

  private val maths = UUID.randomUUID
  private val english = UUID.randomUUID
  private val names = Map(maths -> "Matematyka", english -> "Język angielski")

  private def line(
    subject: UUID,
    audience: LessonAudience,
    blocks: List[Int],
    kind: LessonKind = LessonKind.BASE,
    teacher: Option[UUID] = Some(UUID.randomUUID)
  ) = LessonLine(UUID.randomUUID, UUID.randomUUID, subject, audience, kind, teacher, None, blocks)

  test("a whole-class subject raises nothing") {
    val lines = List(line(maths, LessonAudience.WHOLE_CLASS, List(1, 1, 2)))
    assert(ClassAllocation.problems(lines, names) == Nil)
  }

  test("matched group hours raise nothing") {
    val lines = List(
      line(english, LessonAudience.GROUP_1, List(1, 1, 1)),
      line(english, LessonAudience.GROUP_2, List(1, 1, 1))
    )
    assert(ClassAllocation.problems(lines, names) == Nil)
  }

  // Both halves must be busy at the same time, so unequal hours leave one group
  // idle while the other has a lesson — the plan cannot be built from it.
  test("flags groups with different hours") {
    val lines = List(
      line(english, LessonAudience.GROUP_1, List(1, 1, 1)),
      line(english, LessonAudience.GROUP_2, List(1, 1))
    )
    assert(ClassAllocation.problems(lines, names).exists(_.message.contains("Język angielski")))
  }

  test("flags a group that exists only on one side") {
    val lines = List(line(english, LessonAudience.GROUP_1, List(1, 1)))
    assert(ClassAllocation.problems(lines, names).nonEmpty)
  }

  test("counts base and extension hours together when matching groups") {
    val lines = List(
      line(maths, LessonAudience.GROUP_1, List(1, 1)),
      line(maths, LessonAudience.GROUP_2, List(1), kind = LessonKind.BASE),
      line(maths, LessonAudience.GROUP_2, List(1), kind = LessonKind.EXTENSION)
    )
    assert(ClassAllocation.problems(lines, names) == Nil)
  }

  test("reports lines with no teacher assigned") {
    val lines = List(line(maths, LessonAudience.WHOLE_CLASS, List(1), teacher = None))
    assert(ClassAllocation.problems(lines, names).exists(_.message.contains("Nieprzypisany")))
  }

  test("a class's total includes the cross-class lessons it takes part in") {
    // WF and religia occupy the class as much as its own lines do; they just
    // belong to a unit shared with other classes, so they are not in its lines.
    val lines = List(line(maths, LessonAudience.WHOLE_CLASS, List(1, 1, 2)))
    assert(ClassAllocation.summary(lines, 3, names).occupiedHours == 7)
    assert(ClassAllocation.summary(lines, 0, names).occupiedHours == 4)
  }

  // A split slot occupies the class once, not once per group — otherwise a
  // class looks like it needs twice the timetable it does.
  test("counts a split slot once toward the class's occupied hours") {
    val lines = List(
      line(maths, LessonAudience.WHOLE_CLASS, List(1, 1, 2)),
      line(english, LessonAudience.GROUP_1, List(1, 1, 1)),
      line(english, LessonAudience.GROUP_2, List(1, 1, 1))
    )
    assert(ClassAllocation.occupiedHours(lines) == 7)
  }
