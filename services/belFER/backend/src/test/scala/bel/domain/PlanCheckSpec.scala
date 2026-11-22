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
      lines, Nil, Nil, Map.empty, roomsOfKind = Map.empty, totalRooms = 50)

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

  // RULES S4 and S5, on 1C biologia's shape: one whole-class lesson and two
  // for each half. Days only, as in the worked examples in RULES.
  private def halvesWeek(whole: Int, first: List[Int], second: List[Int]) =
    val a = cls("1C")
    val s = school(List(a), List(
      line(a.id),
      line(a.id, blocks = List(1, 1), audience = LessonAudience.GROUP_1),
      line(a.id, blocks = List(1, 1), audience = LessonAudience.GROUP_2)))
    val week = at(a.id, whole, 3) ::
      first.map(d => at(a.id, d, 0, audience = LessonAudience.GROUP_1)) :::
      second.map(d => at(a.id, d, 1, audience = LessonAudience.GROUP_2))
    PlanCheck(s, week)

  test("a half that has a subject on a whole-class day of it is caught"):
    assert(broken(halvesWeek(whole = 1, first = List(0, 3), second = List(0, 4))).isEmpty)
    // Half 1 has biologia on Tuesday, when the whole class has it too.
    assert(broken(halvesWeek(whole = 1, first = List(1, 3), second = List(0, 4))).contains("R5b"))

  test("a whole-class lesson with one half a lesson ahead is caught"):
    // The rows of the worked-examples table in RULES S5, in order.
    assert(!broken(halvesWeek(whole = 1, first = List(0, 3), second = List(0, 4))).contains("R16"))
    assert(broken(halvesWeek(whole = 1, first = List(0, 3), second = List(2, 3))).contains("R16"))
    assert(!broken(halvesWeek(whole = 0, first = List(1, 3), second = List(1, 4))).contains("R16"))
    assert(!broken(halvesWeek(whole = 2, first = List(0, 3), second = List(1, 4))).contains("R16"))
    assert(broken(halvesWeek(whole = 2, first = List(0, 1), second = List(0, 3))).contains("R16"))

  test("level is counted from Monday, not since the last whole-class lesson"):
    // Half 1 on Friday, half 2 on Monday, the whole class on Wednesday: one
    // lesson each "since last Wednesday", and half 2 ahead every week of term.
    val a = cls("2A")
    val s = school(List(a), List(
      line(a.id),
      line(a.id, audience = LessonAudience.GROUP_1),
      line(a.id, audience = LessonAudience.GROUP_2)))
    val week = List(at(a.id, 2, 3),
      at(a.id, 4, 0, audience = LessonAudience.GROUP_1),
      at(a.id, 0, 0, audience = LessonAudience.GROUP_2))
    assert(broken(PlanCheck(s, week)).contains("R16"))

  test("a class whose days are too uneven is caught, when the school asks"):
    val a = cls("1A")
    val lines = List(line(a.id, blocks = List.fill(10)(1)))
    def asking(spread: Int) = school(List(a), lines)
      .copy(settings = settings.copy(optimisation = Optimisation(maxClassDaySpread = Some(spread))))
    // Six lessons on Monday, one on each other day: 6 against 1.
    val lopsided = (0 until 6).map(h => at(a.id, 0, h)).toList ++ (1 to 4).map(d => at(a.id, d, 0))
    val even = (0 until 5).flatMap(d => List(at(a.id, d, 0), at(a.id, d, 1))).toList

    assert(!PlanCheck(school(List(a), lines), lopsided).rules.exists(_.code == "R17"),
      "not asked for, not checked")
    assert(broken(PlanCheck(asking(3), lopsided)).contains("R17"))
    assert(!broken(PlanCheck(asking(5), lopsided)).contains("R17"))
    assert(!broken(PlanCheck(asking(0), even)).contains("R17"))

  test("an empty day is the shortest day"):
    val a = cls("1A")
    val s = school(List(a), List(line(a.id, blocks = List.fill(8)(1))))
      .copy(settings = settings.copy(optimisation = Optimisation(maxClassDaySpread = Some(1))))
    // Two lessons Monday to Thursday, nothing on Friday.
    val week = (0 until 4).flatMap(d => List(at(a.id, d, 0), at(a.id, d, 1))).toList
    assert(broken(PlanCheck(s, week)).contains("R17"))

  // ── Religia and etyka: those who skip them must not wait (RULES S2) ──

  private val etyka = UUID.randomUUID
  private def withEtyka(s: SchoolSnapshot, attendance: Map[(UUID, UUID), Int] = Map.empty) =
    s.copy(subjects = s.subjects :+ Subject(etyka, "ety", "Etyka", true, None, false),
      optionalAttendance = attendance)

  test("religia in the middle of a day is a gap for those who skip it"):
    val a = cls("1A")
    val s = school(List(a), List(line(a.id), line(a.id, religia), line(a.id)))
    val mid = PlanCheck(s, List(
      at(a.id, 0, 0), at(a.id, 0, 1, religia, optional = true), at(a.id, 0, 2)))
    assert(broken(mid).contains("R9"))

    val last = PlanCheck(s, List(
      at(a.id, 0, 0), at(a.id, 0, 1), at(a.id, 0, 2, religia, optional = true)))
    assert(!broken(last).contains("R9"))

  test("religia that ends one half's day and starts the other's is legal"):
    // Half 1 at the first hour, religia for everyone, half 2 at the third.
    // Nobody waits. Judged against the whole class's day, it was rejected.
    val a = cls("1A")
    val s = school(List(a), List(
      line(a.id, audience = LessonAudience.GROUP_1), line(a.id, religia),
      line(a.id, audience = LessonAudience.GROUP_2)))
    val week = PlanCheck(s, List(
      at(a.id, 0, 0, audience = LessonAudience.GROUP_1),
      at(a.id, 0, 1, religia, optional = true),
      at(a.id, 0, 2, audience = LessonAudience.GROUP_2)))
    assert(!broken(week).contains("R9"))
    assert(!broken(week).contains("R1"))

  test("religia and etyka may share a mid-day hour when everyone attends one"):
    val a = cls("1A").copy(studentCount = Some(30))
    val base = school(List(a), List(line(a.id), line(a.id, religia), line(a.id, etyka), line(a.id)))
    val week = List(
      at(a.id, 0, 0), at(a.id, 0, 1, religia, optional = true),
      at(a.id, 0, 1, etyka, teachers = Set(piotr), optional = true), at(a.id, 0, 2))
    val everyone = PlanCheck(withEtyka(base, Map((a.id, religia) -> 20, (a.id, etyka) -> 10)), week)
    assert(!broken(everyone).contains("R1"), "two lessons for two different sets of pupils")
    assert(!broken(everyone).contains("R9"))
    // Nothing entered: those attending neither are assumed to exist, and wait.
    assert(broken(PlanCheck(withEtyka(base), week)).contains("R9"))

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


  // ── Fixed after the review of 2026-11-21 ─────────────────────────────

  test("class days are measured per attendance group once attendance is entered"):
    // REVIEW D3. Religia first and etyka last on Monday: each group's day is
    // 7 or 6, the half's is 8. Every other day is 5.
    val a = cls("1A").copy(studentCount = Some(30))
    val base = school(List(a), List(line(a.id), line(a.id, religia), line(a.id, etyka)))
    val monday = at(a.id, 0, 0, religia, optional = true) ::
      at(a.id, 0, 7, etyka, teachers = Set(piotr), optional = true) ::
      (1 to 6).map(h => at(a.id, 0, h)).toList
    val rest = (1 until 5).flatMap(d => (0 until 5).map(h => at(a.id, d, h))).toList
    val s = withEtyka(base, Map((a.id, religia) -> 10, (a.id, etyka) -> 10))
      .copy(settings = settings.copy(optimisation = Optimisation(maxClassDaySpread = Some(2))))
    assert(!broken(PlanCheck(s, monday ++ rest)).contains("R17"))

  test("a cross-class unit's groups at different hours are caught"):
    // REVIEW D4.
    val unit = UUID.randomUUID
    def group(from: Int) = PlanCheck.Scheduled(Set.empty, LessonAudience.WHOLE_CLASS, maths,
      Set(UUID.randomUUID), 0, from, 1, false, unitBlock = Some(unit -> 0))
    val s = school(Nil, Nil)
    assert(broken(PlanCheck(s, List(group(0), group(2)))).contains("R4"))
    assert(!broken(PlanCheck(s, List(group(0), group(0)))).contains("R4"))

  private val lab = RoomKind(UUID.randomUUID, "Pracownia")
  private val labRoom = Room(UUID.randomUUID, "LAB", None, true, List(lab))
  private val room12 = Room(UUID.randomUUID, "12", None, true, Nil)

  test("a lesson wanting a kind of room may use an ordinary one; one requiring it may not"):
    // REVIEW D1.
    val a = cls("1A")
    val s = school(List(a), List(line(a.id)))
    def inRoom12(hard: Boolean) = at(a.id, 0, 0).copy(
      roomId = Some(room12.id), needsKind = Some(lab.id), kindIsHard = hard)
    assert(!broken(PlanCheck(s, List(inRoom12(hard = false)), List(labRoom, room12))).contains("R3c"))
    assert(broken(PlanCheck(s, List(inRoom12(hard = true)), List(labRoom, room12))).contains("R3c"))
    assert(!broken(PlanCheck(s, List(inRoom12(hard = true)), List(room12))).contains("R3c"),
      "with no room of the kind at all, an ordinary one is what the export offers")

  test("a lesson with no room is caught"):
    // REVIEW D5.
    val a = cls("1A")
    val s = school(List(a), List(line(a.id)))
    assert(broken(PlanCheck(s, List(at(a.id, 0, 0)), List(room12))).contains("R3e"))
    assert(!broken(PlanCheck(s, List(at(a.id, 0, 0).copy(roomId = Some(room12.id))),
      List(room12))).contains("R3e"))

  test("a lesson outside the week is caught"):
    // REVIEW D6.
    val a = cls("1A")
    val s = school(List(a), List(line(a.id, blocks = List(2))))
    assert(broken(PlanCheck(s, List(at(a.id, 0, 7, length = 2)))).contains("R12"),
      "a double from the last lesson runs past the end of the day")
    assert(broken(PlanCheck(s, List(at(a.id, 5, 0, length = 2)))).contains("R12"),
      "there is no sixth day")
    assert(!broken(PlanCheck(s, List(at(a.id, 0, 6, length = 2)))).contains("R12"))

  test("a block placed twice and another never is caught, though the count adds up"):
    // REVIEW D7.
    val a = cls("1A")
    val maths2 = line(a.id, blocks = List(1, 1))
    val s = school(List(a), List(maths2))
    val twice = List(at(a.id, 0, 0).copy(block = Some(maths2.id -> 0)),
      at(a.id, 1, 0).copy(block = Some(maths2.id -> 0)))
    assert(broken(PlanCheck(s, twice)).contains("R7"))
    val once = List(at(a.id, 0, 0).copy(block = Some(maths2.id -> 0)),
      at(a.id, 1, 0).copy(block = Some(maths2.id -> 1)))
    assert(!broken(PlanCheck(s, once)).contains("R7"))

  // ── Teachers' free periods: run, day, week (RULES T5) ────────────────

  private def withGaps(day: Option[Int], week: Option[Int], run: Int = 1) =
    settings.copy(maxConsecutiveTeacherGaps = run,
      optimisation = Optimisation(maxTeacherGapsPerDay = day, maxTeacherGapsPerWeek = week))

  test("separate free periods are counted per day, though no run is too long"):
    // First, third and fifth hour: two single free periods, never two in a row.
    val classes = List(cls("1A"), cls("1B"), cls("1C"))
    val s = school(classes, classes.map(c => line(c.id)))
      .copy(settings = withGaps(day = Some(1), week = None))
    val week = classes.zip(List(0, 2, 4)).map((c, h) => at(c.id, 0, h))
    val report = PlanCheck(s, week)
    assert(!broken(report).contains("R15"))
    assert(broken(report).contains("R15b"))
    assert(!PlanCheck(s.copy(settings = withGaps(day = Some(2), week = None)), week)
      .broken.exists(_.code == "R15b"))

  test("free periods are counted across the week when the school sets a cap"):
    // One free period on each of three days, against a weekly cap of two.
    val a = cls("1A")
    val b = cls("1B")
    val s = school(List(a, b), List(line(a.id, blocks = List(1, 1, 1)), line(b.id, blocks = List(1, 1, 1))))
    val week = (0 until 3).flatMap(d => List(at(a.id, d, 0), at(b.id, d, 2))).toList
    assert(broken(PlanCheck(s.copy(settings = withGaps(Some(1), Some(2))), week)).contains("R15c"))
    assert(!broken(PlanCheck(s.copy(settings = withGaps(Some(1), Some(3))), week)).contains("R15c"))
    assert(!PlanCheck(s.copy(settings = withGaps(Some(1), None)), week).rules.exists(_.code == "R15c"),
      "no weekly cap set, nothing to check")

  test("an hour the teacher is away is not a free period"):
    // Lessons at the first and third hour, away for the second: not waiting.
    val a = cls("1A")
    val b = cls("1B")
    val s = school(List(a, b), List(line(a.id), line(b.id)),
      teachers = List(teacher(anna, away = List(UnavailabilityBlock(1, 2, 2))), teacher(piotr)))
      .copy(settings = withGaps(day = Some(0), week = Some(0), run = 0))
    val report = PlanCheck(s, List(at(a.id, 0, 0), at(b.id, 0, 2)))
    assert(!broken(report).contains("R15"))
    assert(!broken(report).contains("R15b"))
    assert(!broken(report).contains("R15c"))
