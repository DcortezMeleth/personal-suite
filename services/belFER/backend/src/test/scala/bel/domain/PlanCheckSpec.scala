package bel.domain

import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite

/**
 * The checker, checked.
 *
 * It is the one piece ADR 0004 asks to be right: FET generates the week and
 * nothing else stands between its answer and the school. A checker that
 * reports a clean timetable because it is looking in the wrong place is worse
 * than no checker, and I wrote two of those by hand this afternoon before
 * writing this one — a count of gaps in a class rather than in a half, and a
 * double-booking test that compared every lesson with itself.
 *
 * So each rule gets a week that keeps it and a week that breaks it, and the
 * breaking one has to be reported.
 */
class PlanCheckSpec extends AnyFunSuite:

  private val maths = UUID.randomUUID
  private val religia = UUID.randomUUID
  private val anna = UUID.randomUUID
  private val piotr = UUID.randomUUID

  private val settings = SchedulingSettings(2, 5, 8)

  private def cls(name: String) =
    SchoolClass(UUID.randomUUID, name.head.asDigit, name.tail, None, None, None, None)

  private def teacher(id: UUID, days: Option[Int] = None, perDay: Option[Int] = None,
                      away: List[UnavailabilityBlock] = Nil) =
    Teacher(id, "XX", "Jan", "Kowalski", None, Nil, days, perDay, None, away)

  private def school(classes: List[SchoolClass], lines: List[LessonLine],
                     teachers: List[Teacher] = List(teacher(anna), teacher(piotr))) =
    SchoolSnapshot(
      settings, slotsPerDay = 8, teachingDays = 5, classes, teachers,
      subjects = List(
        Subject(maths, "mat", "Matematyka", false, None, false),
        Subject(religia, "rel", "Religia", true, None, false)),
      lines, Nil, Map.empty, Map.empty, totalRooms = 50)

  private def line(classId: UUID, subject: UUID = maths, blocks: List[Int] = List(1),
                   teacherId: Option[UUID] = Some(anna),
                   audience: LessonAudience = LessonAudience.WHOLE_CLASS) =
    LessonLine(UUID.randomUUID, classId, subject, audience, LessonKind.BASE, teacherId, None, blocks)

  private def at(classId: UUID, day: Int, from: Int, subject: UUID = maths,
                 teachers: Set[UUID] = Set(anna), length: Int = 1,
                 audience: LessonAudience = LessonAudience.WHOLE_CLASS,
                 optional: Boolean = false) =
    PlanCheck.Scheduled(Set(classId), audience, subject, teachers, day, from, length, optional)

  private def broken(report: PlanCheck.Report): Set[String] =
    report.broken.map(_.code).toSet

  test("a week that keeps every rule is reported as keeping every rule"):
    val a = cls("1A")
    val s = school(List(a), List(line(a.id), line(a.id)))
    val report = PlanCheck(s, List(at(a.id, 0, 0), at(a.id, 1, 0)))
    assert(report.sound, report.broken.map(r => s"${r.code} ${r.detail}").mkString("; "))

  test("a class in two places at once is caught"):
    val a = cls("1A")
    val s = school(List(a), List(line(a.id), line(a.id, teacherId = Some(piotr))))
    val report = PlanCheck(s, List(at(a.id, 0, 0), at(a.id, 0, 0, teachers = Set(piotr))))
    assert(broken(report).contains("R1"))

  test("a teacher in two places at once is caught"):
    val a = cls("1A")
    val b = cls("1B")
    val s = school(List(a, b), List(line(a.id), line(b.id)))
    val report = PlanCheck(s, List(at(a.id, 0, 0), at(b.id, 0, 0)))
    assert(broken(report).contains("R2"))

  test("a gap in a group's day is caught, and a gap in a class's is not"):
    val a = cls("1A")
    val s = school(List(a), List(line(a.id), line(a.id)))
    // One lesson at the first hour and one at the third: whoever is in that
    // room has an hour of nothing in between.
    val holed = PlanCheck(s, List(at(a.id, 0, 0), at(a.id, 0, 2)))
    assert(broken(holed).contains("R9"))

    // The two halves at either end of the morning. The class's own column has
    // a hole in it and no student is sitting in it: one half goes home, the
    // other comes in later.
    val halves = PlanCheck(s, List(
      at(a.id, 0, 0, audience = LessonAudience.GROUP_1),
      at(a.id, 0, 2, audience = LessonAudience.GROUP_2)))
    assert(!broken(halves).contains("R9"),
      "a hole nobody waits in is not a gap")

  test("two blocks of one subject in a day are caught, for the same audience only"):
    val a = cls("1A")
    val s = school(List(a), List(line(a.id, blocks = List(1, 1))))
    val twice = PlanCheck(s, List(at(a.id, 0, 0), at(a.id, 0, 1)))
    assert(broken(twice).contains("R5"))

    // The language swap: one half sits maths while the other sits religia,
    // then they trade. Same subject, same day, different halves — allowed.
    val swapped = PlanCheck(s, List(
      at(a.id, 0, 0, audience = LessonAudience.GROUP_1),
      at(a.id, 0, 1, audience = LessonAudience.GROUP_2)))
    assert(!broken(swapped).contains("R5"))

  test("an optional subject in the middle of a day is caught"):
    val a = cls("1A")
    val s = school(List(a), List(line(a.id), line(a.id, religia), line(a.id)))
    val mid = PlanCheck(s, List(
      at(a.id, 0, 0), at(a.id, 0, 1, religia, optional = true), at(a.id, 0, 2)))
    assert(broken(mid).contains("R10"))

    val last = PlanCheck(s, List(
      at(a.id, 0, 0), at(a.id, 0, 1), at(a.id, 0, 2, religia, optional = true)))
    assert(!broken(last).contains("R10"))

  test("a teacher left sitting through a long gap is caught"):
    val a = cls("1A")
    val b = cls("1B")
    val lines = List(line(a.id), line(b.id))
    val s = school(List(a, b), lines)
    // First hour and then the sixth: four hours of nothing, against a limit
    // of two.
    val idle = PlanCheck(s, List(at(a.id, 0, 0), at(b.id, 0, 5)))
    assert(broken(idle).contains("R15"))

  test("a teacher working more days than allowed is caught"):
    val a = cls("1A")
    val s = school(List(a), List(line(a.id), line(a.id), line(a.id)),
      teachers = List(teacher(anna, days = Some(2)), teacher(piotr)))
    val over = PlanCheck(s, List(at(a.id, 0, 0), at(a.id, 1, 0), at(a.id, 2, 0)))
    assert(broken(over).contains("R13"))

  test("a lesson in an hour the teacher is away for is caught"):
    val a = cls("1A")
    val s = school(List(a), List(line(a.id)),
      teachers = List(teacher(anna, away = List(UnavailabilityBlock(1, 1, 2))), teacher(piotr)))
    val clash = PlanCheck(s, List(at(a.id, 0, 0)))
    assert(broken(clash).contains("R11"))

  test("a missing lesson is caught"):
    val a = cls("1A")
    val s = school(List(a), List(line(a.id, blocks = List(1, 1))))
    val short = PlanCheck(s, List(at(a.id, 0, 0)))
    assert(broken(short).contains("R7"))
