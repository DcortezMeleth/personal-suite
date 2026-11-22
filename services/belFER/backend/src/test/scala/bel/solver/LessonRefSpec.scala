package bel.solver

import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite

/**
 * The string form of a lesson reference, written into each FET activity's
 * comment so an answer can be read without trusting a later export to number
 * lessons the same way (REVIEW_2026-11-21 F2).
 */
class LessonRefSpec extends AnyFunSuite:

  private val every = List(
    LessonRef.Line(UUID.randomUUID, 0),
    LessonRef.Line(UUID.randomUUID, 7),
    LessonRef.CrossClassGroupRef(UUID.randomUUID, UUID.randomUUID, 2),
    LessonRef.Individual(UUID.randomUUID, 0))

  test("every kind of reference survives being written and read back"):
    for ref <- every do
      assert(LessonRef.parse(ref.key) === Some(ref), s"$ref did not survive ${ref.key}")

  test("each kind is written distinguishably from the others"):
    assert(every.map(_.key).distinct.size === every.size)

  test("anything else reads as nothing, not as a lesson"):
    assert(LessonRef.parse("") === None)
    assert(LessonRef.parse("Wyeksportowane z belFER") === None)
    assert(LessonRef.parse("line|not-a-uuid|0") === None)
