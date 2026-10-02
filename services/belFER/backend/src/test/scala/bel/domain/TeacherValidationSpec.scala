package bel.domain

import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite

class TeacherValidationSpec extends AnyFunSuite:

  private val base = TeacherInput(
    code = "KC",
    firstName = "Anna",
    lastName = "Nowak",
    homeRoomId = None,
    subjectIds = Nil,
    maxWorkingDays = None,
    maxLessonsPerDay = None,
    pensum = Some(18),
    unavailability = Nil
  )

  private val slots = 8

  test("accepts a teacher with no overrides, inheriting the school defaults") {
    assert(TeacherValidation.validate(base, slots) == Right(()))
  }

  test("accepts blocked hours stated as a weekly range") {
    // The common case: teaches at another school on Tuesday mornings.
    val blocked = base.copy(unavailability = List(UnavailabilityBlock(2, 1, 4)))
    assert(TeacherValidation.validate(blocked, slots) == Right(()))
  }

  test("accepts several blocks on different days, and several on one day") {
    val blocked = base.copy(unavailability = List(
      UnavailabilityBlock(1, 1, 2),
      UnavailabilityBlock(1, 5, 6),
      UnavailabilityBlock(3, 7, 8)
    ))
    assert(TeacherValidation.validate(blocked, slots) == Right(()))
  }

  // A block pointing past the end of the bell schedule is a typo. Catching it
  // here is worth far more than discovering it hours into a solver run.
  test("rejects a block reaching past the last lesson of the day") {
    val blocked = base.copy(unavailability = List(UnavailabilityBlock(1, 7, 9)))
    assert(TeacherValidation.validate(blocked, slots).isLeft)
  }

  test("does not check against the bell schedule when there is none yet") {
    val blocked = base.copy(unavailability = List(UnavailabilityBlock(1, 7, 9)))
    assert(TeacherValidation.validate(blocked, 0) == Right(()))
  }

  test("rejects a day outside the teaching week") {
    assert(TeacherValidation.validate(base.copy(unavailability = List(UnavailabilityBlock(6, 1, 2))), slots).isLeft)
    assert(TeacherValidation.validate(base.copy(unavailability = List(UnavailabilityBlock(0, 1, 2))), slots).isLeft)
  }

  test("rejects a backwards range") {
    assert(TeacherValidation.validate(base.copy(unavailability = List(UnavailabilityBlock(1, 5, 3))), slots).isLeft)
  }

  // Harmless to the solver, since the union is the same — but it means the
  // same thing was entered twice, and merging silently would hide that.
  test("rejects overlapping blocks on the same day") {
    val overlapping = base.copy(unavailability = List(
      UnavailabilityBlock(1, 1, 4),
      UnavailabilityBlock(1, 3, 6)
    ))
    assert(TeacherValidation.validate(overlapping, slots).isLeft)
  }

  test("allows blocks that merely touch, since they do not overlap") {
    val touching = base.copy(unavailability = List(
      UnavailabilityBlock(1, 1, 3),
      UnavailabilityBlock(1, 4, 6)
    ))
    assert(TeacherValidation.validate(touching, slots) == Right(()))
  }

  test("allows the same range on different days") {
    val sameRange = base.copy(unavailability = List(
      UnavailabilityBlock(1, 1, 4),
      UnavailabilityBlock(2, 1, 4)
    ))
    assert(TeacherValidation.validate(sameRange, slots) == Right(()))
  }

  test("rejects blank code or surname") {
    assert(TeacherValidation.validate(base.copy(code = " "), slots).isLeft)
    assert(TeacherValidation.validate(base.copy(lastName = ""), slots).isLeft)
  }

  test("rejects a repeated subject") {
    val id = UUID.randomUUID
    assert(TeacherValidation.validate(base.copy(subjectIds = List(id, id)), slots).isLeft)
  }

  test("rejects overrides that cannot be satisfied") {
    assert(TeacherValidation.validate(base.copy(maxWorkingDays = Some(0)), slots).isLeft)
    assert(TeacherValidation.validate(base.copy(maxWorkingDays = Some(8)), slots).isLeft)
    assert(TeacherValidation.validate(base.copy(maxLessonsPerDay = Some(0)), slots).isLeft)
    assert(TeacherValidation.validate(base.copy(pensum = Some(-1)), slots).isLeft)
  }
