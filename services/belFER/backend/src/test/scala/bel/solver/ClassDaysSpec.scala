package bel.solver

import org.scalatest.funsuite.AnyFunSuite

/**
 * The range of day lengths a class is given, for RULES P2.
 *
 * The expected values are the ones the throwaway script picked for the real
 * school (RUNLOG R8–R10), so the app's file says what the measured runs said.
 */
class ClassDaysSpec extends AnyFunSuite:

  test("layouts are counted in order, every day within the range"):
    // 32 hours as five days of 5–7: 7/7/7/6/5 in its 20 orders, 7/7/6/6/6 in 10.
    assert(ClassDays.layouts(32, 5, 5, 7) === 30)
    assert(ClassDays.layouts(38, 5, 6, 8) === 15)
    assert(ClassDays.layouts(36, 5, 5, 7) === 0, "five days of at most 7 cannot hold 36")

  test("at a spread of two, the ranges R8 used"):
    assert(ClassDays.range(32, 5, 8, 2) === Some((5, 7)))
    assert(ClassDays.range(28, 5, 8, 2) === Some((5, 7)))
    assert(ClassDays.range(37, 5, 8, 2) === Some((6, 8)))
    assert(ClassDays.range(38, 5, 8, 2) === Some((6, 8)))

  test("at a spread of three, the ranges R9 and R10 used"):
    assert(ClassDays.range(32, 5, 8, 3) === Some((5, 8)))
    assert(ClassDays.range(30, 5, 8, 3) === Some((4, 7)), "5–8 allows as many; a tie goes to the lower range")
    assert(ClassDays.range(29, 5, 8, 3) === Some((4, 7)))
    assert(ClassDays.range(38, 5, 8, 3) === Some((6, 8)), "nothing runs past the eighth lesson")

  test("the range kept is the one that loses least"):
    val (lo, hi) = ClassDays.range(32, 5, 8, 2).get
    val alternatives = (1 to 6).map(l => ClassDays.layouts(32, 5, l, l + 2))
    assert(ClassDays.layouts(32, 5, lo, hi) === alternatives.max)

  test("a week too small to fill every day gets no range"):
    assert(ClassDays.range(3, 5, 8, 2) === None)
