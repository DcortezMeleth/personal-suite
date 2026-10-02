package bel.domain

import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite

class PeUnitValidationSpec extends AnyFunSuite:

  private val a = UUID.randomUUID // class 1A
  private val b = UUID.randomUUID // class 1B
  private val c = UUID.randomUUID // class 1C

  private def group(label: String, classes: UUID*) =
    PeGroupInput(label, Some(UUID.randomUUID), classes.toList)

  private val base = PeUnitInput(
    subjectId = UUID.randomUUID,
    name = "WF rocznik 1",
    requiredRoomKindId = None,
    blocks = List(1, 2),
    groups = List(group("chłopcy", a, b), group("dziewczęta", a, b))
  )

  test("accepts two classes merged into boys' and girls' groups") {
    assert(PeUnitValidation.validate(base, 8, None) == Right(()))
  }

  // The case the school described: one class's boys are numerous enough to
  // train alone, while its girls join another class.
  test("accepts a partial merge, where one group is a single class") {
    val partial = base.copy(groups = List(
      group("chłopcy 1A", a),
      group("chłopcy 1B", b),
      group("dziewczęta 1A+1B", a, b)
    ))
    assert(PeUnitValidation.validate(partial, 8, None) == Right(()))
  }

  test("accepts one class's students spread across groups with two different partners") {
    val spread = base.copy(groups = List(
      group("dziewczęta 1A+1B", a, b),
      group("dziewczęta 1A+1C", a, c),
      group("chłopcy", a, b, c)
    ))
    assert(PeUnitValidation.validate(spread, 8, None) == Right(()))
  }

  // Every group runs at the same time, so rooms are a hard ceiling — and saying
  // so now beats a generation run failing hours later.
  test("rejects more groups than there are rooms of the required kind") {
    val six = base.copy(groups = List.tabulate(6)(i => group(s"grupa $i", a)))
    assert(PeUnitValidation.validate(six, 8, Some(3)).isLeft)
  }

  test("accepts exactly as many groups as rooms") {
    val three = base.copy(groups = List.tabulate(3)(i => group(s"grupa $i", a)))
    assert(PeUnitValidation.validate(three, 8, Some(3)) == Right(()))
  }

  test("does not check rooms when no kind is required") {
    val six = base.copy(groups = List.tabulate(6)(i => group(s"grupa $i", a)))
    assert(PeUnitValidation.validate(six, 8, None) == Right(()))
  }

  test("rejects a unit with no groups") {
    assert(PeUnitValidation.validate(base.copy(groups = Nil), 8, None).isLeft)
  }

  test("rejects a group drawing from no class") {
    assert(PeUnitValidation.validate(base.copy(groups = List(group("pusta"))), 8, None).isLeft)
  }

  test("rejects a repeated class within one group") {
    assert(PeUnitValidation.validate(base.copy(groups = List(group("gr", a, a))), 8, None).isLeft)
  }

  test("rejects a blank unit or group name") {
    assert(PeUnitValidation.validate(base.copy(name = " "), 8, None).isLeft)
    assert(PeUnitValidation.validate(base.copy(groups = List(group(" ", a))), 8, None).isLeft)
  }

  test("rejects a block longer than the school day") {
    assert(PeUnitValidation.validate(base.copy(blocks = List(9)), 8, None).isLeft)
  }

class PeUnitSpec extends AnyFunSuite:

  private val a = UUID.randomUUID
  private val b = UUID.randomUUID

  // The unit occupies every class any of its groups draws from — that is what
  // forces them all into the same slots.
  test("a unit's classes are the union of what its groups draw from") {
    val unit = PeUnit(
      UUID.randomUUID, UUID.randomUUID, "WF", None, List(2),
      List(
        PeGroup(UUID.randomUUID, "chłopcy 1A", None, List(a)),
        PeGroup(UUID.randomUUID, "dziewczęta 1A+1B", None, List(a, b))
      )
    )
    assert(unit.classIds.toSet == Set(a, b))
    assert(unit.hours == 2)
  }
