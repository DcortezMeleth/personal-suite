package bel.domain

import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite

class SubjectValidationSpec extends AnyFunSuite:

  private val base = SubjectInput(
    code = "matematyka",
    name = "Matematyka",
    optional = false,
    requiredRoomKindId = None,
    roomRequirementHard = false
  )

  test("accepts a plain subject with no room requirement") {
    assert(SubjectValidation.validate(base) == Right(()))
  }

  test("accepts a soft room requirement, which is the normal case") {
    // Specialist rooms run short, so the requirement is usually a preference
    // the solver weighs rather than a rule it must satisfy.
    assert(SubjectValidation.validate(base.copy(requiredRoomKindId = Some(UUID.randomUUID))) == Right(()))
  }

  test("accepts a hard room requirement when a kind is named") {
    val hard = base.copy(requiredRoomKindId = Some(UUID.randomUUID), roomRequirementHard = true)
    assert(SubjectValidation.validate(hard) == Right(()))
  }

  // Otherwise the setting cannot be satisfied or even reported on: there is no
  // room kind to check against, so it is almost certainly a half-finished edit.
  test("rejects a hard room requirement with no room kind named") {
    assert(SubjectValidation.validate(base.copy(roomRequirementHard = true)).isLeft)
  }

  test("rejects blank code or name") {
    assert(SubjectValidation.validate(base.copy(code = "  ")).isLeft)
    assert(SubjectValidation.validate(base.copy(name = "")).isLeft)
  }

class RoomValidationSpec extends AnyFunSuite:

  private val base = RoomInput(number = "101", name = None, fitsWholeClass = true, kindIds = Nil)

  test("accepts an ordinary room with no kinds") {
    assert(RoomValidation.validate(base) == Right(()))
  }

  test("accepts a room serving several kinds at once") {
    // A gym section is a PE room; a language room may double as an ordinary one.
    assert(RoomValidation.validate(base.copy(kindIds = List(UUID.randomUUID, UUID.randomUUID))) == Right(()))
  }

  test("rejects a blank number, which is how the school refers to the room") {
    assert(RoomValidation.validate(base.copy(number = "   ")).isLeft)
  }

  test("rejects a repeated kind, which would silently collapse on save") {
    val id = UUID.randomUUID
    assert(RoomValidation.validate(base.copy(kindIds = List(id, id))).isLeft)
  }
