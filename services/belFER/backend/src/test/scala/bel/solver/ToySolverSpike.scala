package bel.solver

import java.time.Duration
import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters.*

/**
 * Phase 0.5 spike. This is not a test of belFER's logic — there is none yet —
 * but of whether Timefold can be built and run at all under this repo's build
 * constraints: rules_jvm_external's explicit-dependency rule, Bazel's sealed
 * sandbox, and a Scala caller reaching a Java planning domain.
 *
 * The problem is a 2x2 Latin square: two teachers, two groups, four lessons,
 * four slots. Exactly one shape of answer is feasible, so a solver that is
 * merely running but not actually searching will fail this.
 */
class ToySolverSpike extends AnyFunSuite:

  private val slots = List(
    ToySlot(1, 1), ToySlot(1, 2),
    ToySlot(2, 1), ToySlot(2, 2)
  )

  private def lessons = List(
    ToyLesson("L1", "Kowalska", "1A"),
    ToyLesson("L2", "Kowalska", "1B"),
    ToyLesson("L3", "Nowak", "1A"),
    ToyLesson("L4", "Nowak", "1B")
  )

  private def solved: ToyTimetable =
    ToySolver.solve(
      ToyTimetable(slots.asJava, lessons.asJava),
      Duration.ofSeconds(30)
    )

  test("Timefold runs under Bazel and returns a feasible timetable") {
    val result = solved
    assert(
      result.getScore.hardScore == 0,
      s"expected no hard constraint violations, got ${result.getScore}"
    )
  }

  test("every lesson is assigned a slot") {
    val placed = solved.getLessons.asScala.toList
    assert(placed.forall(_.getSlot != null), "some lesson was left unplaced")
  }

  // Checked independently of the score, so that a constraint which silently
  // fails to fire cannot make the run look successful.
  test("no teacher and no class is in two places at once") {
    val placed = solved.getLessons.asScala.toList

    val byTeacher = placed.groupBy(l => (l.getSlot, l.getTeacher))
    assert(byTeacher.values.forall(_.size == 1), s"teacher clash in $placed")

    val byGroup = placed.groupBy(l => (l.getSlot, l.getStudentGroup))
    assert(byGroup.values.forall(_.size == 1), s"class clash in $placed")
  }
