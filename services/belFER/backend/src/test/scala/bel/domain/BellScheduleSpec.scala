package bel.domain

import java.time.LocalTime
import org.scalatest.funsuite.AnyFunSuite

class BellScheduleSpec extends AnyFunSuite:

  private def slot(position: Int, from: String, to: String) =
    TimeSlotInput(position, LocalTime.parse(from), LocalTime.parse(to))

  private val valid = List(
    slot(1, "08:00", "08:45"),
    slot(2, "08:50", "09:35"),
    slot(3, "09:45", "10:30")
  )

  test("accepts a contiguous schedule with gaps between lessons for breaks") {
    assert(BellSchedule.validate(valid) == Right(()))
  }

  test("accepts slots given out of order, since position is what counts") {
    assert(BellSchedule.validate(valid.reverse) == Right(()))
  }

  test("rejects an empty schedule") {
    assert(BellSchedule.validate(Nil).isLeft)
  }

  // Positions are the vocabulary the solver's constraints are written in —
  // "the day starts at slot 1", "a double block occupies consecutive slots" —
  // so a hole in the numbering would quietly change what both of them mean.
  test("rejects a hole in the numbering") {
    assert(BellSchedule.validate(List(slot(1, "08:00", "08:45"), slot(3, "09:45", "10:30"))).isLeft)
  }

  test("rejects numbering that does not start at 1") {
    assert(BellSchedule.validate(List(slot(2, "08:00", "08:45"), slot(3, "08:50", "09:35"))).isLeft)
  }

  test("rejects a lesson that ends before it starts") {
    assert(BellSchedule.validate(List(slot(1, "09:00", "08:00"))).isLeft)
  }

  test("rejects a lesson of zero length") {
    assert(BellSchedule.validate(List(slot(1, "08:00", "08:00"))).isLeft)
  }

  test("rejects overlapping lessons") {
    assert(BellSchedule.validate(List(slot(1, "08:00", "09:00"), slot(2, "08:30", "09:15"))).isLeft)
  }

class SchoolValidationSpec extends AnyFunSuite:

  private val settings = SchedulingSettings(
    allowClassGaps = false,
    maxConsecutiveTeacherGaps = 2,
    defaultTeacherMaxWorkingDays = 5,
    defaultTeacherMaxLessonsPerDay = 8
  )

  test("accepts a sensible configuration") {
    assert(SchoolValidation.validate("I LO", 4, settings) == Right(()))
  }

  test("rejects a blank name, whitespace included") {
    assert(SchoolValidation.validate("   ", 4, settings).isLeft)
  }

  test("rejects an impossible number of years") {
    assert(SchoolValidation.validate("I LO", 0, settings).isLeft)
    assert(SchoolValidation.validate("I LO", 99, settings).isLeft)
  }

  test("rejects teacher defaults that cannot be satisfied") {
    assert(SchoolValidation.validate("I LO", 4, settings.copy(defaultTeacherMaxWorkingDays = 0)).isLeft)
    assert(SchoolValidation.validate("I LO", 4, settings.copy(defaultTeacherMaxLessonsPerDay = 0)).isLeft)
    assert(SchoolValidation.validate("I LO", 4, settings.copy(maxConsecutiveTeacherGaps = -1)).isLeft)
  }
