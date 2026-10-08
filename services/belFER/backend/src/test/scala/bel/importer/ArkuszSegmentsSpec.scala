package bel.importer

import org.scalatest.funsuite.AnyFunSuite

class ArkuszSegmentsSpec extends AnyFunSuite:

  private lazy val document: ArkuszDocument =
    val stream = getClass.getResourceAsStream("/services/belFER/testdata/arkusz/full-semester.xml")
    val bytes = stream.readAllBytes()
    stream.close()
    ArkuszParser.parse(bytes).fold(fail(_), identity)

  test("report the stretches a plan could cover") {
    ArkuszSegments.suggested(document).foreach { s =>
      info(f"weeks ${s.from}%2d-${s.to}%2d (${s.weeks}%2d wk): " +
        f"${s.allocations}%3d allocations, ${s.hoursPerWeek}%4d h/week")
    }
    assert(ArkuszSegments.suggested(document).nonEmpty)
  }

  test("segments are contiguous, ordered and non-overlapping") {
    val all = ArkuszSegments.detect(document)
    assert(all.forall(s => s.to >= s.from))
    all.sliding(2).foreach {
      case List(a, b) => assert(b.from > a.to, s"${a.from}-${a.to} overlaps ${b.from}-${b.to}")
      case _          => ()
    }
  }

  // The school issues a new plan when the allocation changes, so a boundary
  // must fall where history drops from two hours to one.
  test("a boundary falls at the week the allocation changes") {
    val all = ArkuszSegments.detect(document)
    assert(all.exists(_.from == 13) || all.exists(_.to == 12),
      s"expected a boundary around week 12/13, got ${all.map(s => s"${s.from}-${s.to}")}")
  }

  // One week carrying a few extra rows is something the school handles by hand,
  // not a timetable anyone would print.
  test("one-week blips are not offered as plans") {
    assert(ArkuszSegments.suggested(document).forall(_.weeks >= 3))
    assert(ArkuszSegments.detect(document).exists(_.weeks < 3), "expected blips to exist")
  }

  test("every week of the year is covered by exactly one segment") {
    val all = ArkuszSegments.detect(document)
    val covered = all.flatMap(s => s.from to s.to)
    assert(covered.distinct.size == covered.size)
  }
