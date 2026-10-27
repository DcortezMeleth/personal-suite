package bel.domain

import java.time.OffsetDateTime
import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite

/**
 * One resolving, read two ways.
 *
 * A placement names a lesson and an hour and nothing else, so everything a
 * reader needs is a lookup away in the school. That lookup was written twice —
 * once for the screens and once for the printed sheet — and the copies had
 * already stopped agreeing about tuition: the sheet's comment said a class is
 * shown its student's hours for reference and its code never did it, which is
 * the correct behaviour arrived at by accident.
 *
 * So the sheet renders what the screens render, and these say what that is.
 */
class PlanLayoutSpec extends AnyFunSuite:

  private val maths = UUID.randomUUID
  private val pe = UUID.randomUUID
  private val anna = UUID.randomUUID
  private val piotr = UUID.randomUUID

  private val a1 = SchoolClass(UUID.randomUUID, 1, "A", None, None, Some(30), None)
  private val b1 = SchoolClass(UUID.randomUUID, 1, "B", None, None, Some(30), None)

  private val room12 = Room(UUID.randomUUID, "12", None, true, Nil)
  private val gym = Room(UUID.randomUUID, "GYM", None, true, Nil)

  private def teacher(id: UUID, code: String, last: String) =
    Teacher(id, code, "Jan", last, None, Nil, None, None, None, Nil)

  private val mathsLine =
    LessonLine(UUID.randomUUID, a1.id, maths, LessonAudience.GROUP_1,
      LessonKind.BASE, Some(anna), None, List(1, 2))

  private val peGroup = CrossClassGroup(UUID.randomUUID, "chłopcy", Some(piotr), List(a1.id, b1.id))
  private val peUnit =
    CrossClassUnit(UUID.randomUUID, pe, "WF", None, List(1), List(peGroup))

  private val tuition = IndividualLesson(UUID.randomUUID, maths, Some(anna), List(1))
  private val tuitionPlan = IndividualPlan(UUID.randomUUID, b1.id, 1, List(tuition))

  private val school = SchoolSnapshot(
    SchedulingSettings(2, 5, 8), slotsPerDay = 8, teachingDays = 5,
    classes = List(a1, b1),
    teachers = List(teacher(anna, "AN", "Kowalska"), teacher(piotr, "PI", "Nowak")),
    subjects = List(
      Subject(maths, "mat", "Matematyka", false, None, false),
      Subject(pe, "wf", "Wychowanie fizyczne", false, None, false)),
    lessonLines = List(mathsLine),
    crossClassUnits = List(peUnit),
    individualPlans = List(tuitionPlan),
    teacherSubjects = Map.empty, roomsOfKind = Map.empty, totalRooms = 2)

  // Days and positions are one-based in storage, zero-based once resolved.
  private val placements = List(
    Placement(Some(mathsLine.id), None, None, 0, 1, 1, Some(room12.id)),
    Placement(Some(mathsLine.id), None, None, 1, 3, 4, Some(room12.id)),
    Placement(None, Some(peGroup.id), None, 0, 2, 5, Some(gym.id)),
    Placement(None, None, Some(tuition.id), 0, 5, 8, None))

  private val rooms = List(room12, gym)
  private val read = PlanLayout.of(school, placements, rooms)
  private val lessons = read.lessons

  private def at(day: Int, hour: Int) =
    lessons.find(l => l.day == day && l.position == hour).get

  test("a stored day and hour become a zero-based day and hour"):
    val first = at(0, 0)
    assert(first.subject === "Matematyka")
    assert(first.room === Some("12"))

  test("a line's block index decides how long the lesson is"):
    assert(at(0, 0).length === 1)
    assert(at(2, 3).length === 2)

  test("a split line says which half of the class it is for"):
    assert(at(0, 0).half === Some(1))
    assert(at(0, 0).classes === List("1A"))

  test("a cross-class group names every class it draws from, and no half"):
    val wf = at(1, 4)
    assert(wf.classes === List("1A", "1B"))
    assert(wf.half === None)
    assert(wf.teachers === List("Nowak Jan (PI)"))

  test("tuition names a teacher and no class at all"):
    val ind = at(4, 7)
    assert(ind.individual)
    assert(ind.classes.isEmpty, "the student is not in the class while this happens")
    assert(ind.teachers === List("Kowalska Jan (AN)"))

  test("how a teacher is written is the only thing the two readers differ on"):
    val short = PlanLayout.of(school, placements, rooms, PlanLayout.shortName).lessons
    assert(short.flatMap(_.teachers).toSet === Set("AN", "PI"))
    // Everything else is identical, which is the point of sharing it.
    assert(short.map(_.copy(teachers = Nil)) === lessons.map(_.copy(teachers = Nil)))

  test("a week whose lessons all still exist reads as complete"):
    assert(read.complete)
    assert(read.unresolved === 0)

  /**
   * A plan outlives the assignments it was built from — re-importing an
   * arkusz is exactly how a saved week comes to name lessons that are gone.
   * Dropping those quietly leaves a timetable that is missing hours and
   * looks merely shorter, so they are counted.
   */
  test("a placement whose lesson no longer exists is counted, not dropped"):
    val orphan = Placement(Some(UUID.randomUUID), None, None, 0, 1, 1, None)
    val partial = PlanLayout.of(school, placements :+ orphan, rooms)
    assert(partial.lessons.size === lessons.size)
    assert(partial.unresolved === 1)
    assert(!partial.complete)

  test("the printed sheet says so too"):
    val orphan = Placement(Some(UUID.randomUUID), None, None, 0, 1, 1, None)
    assert(PlanSheet(school, plan, placements :+ orphan, rooms).contains("Uwaga:"))
    assert(!sheet.contains("Uwaga:"))

  // ── The sheet, which must say the same things ──────────────────────────

  private val plan = Plan(UUID.randomUUID, UUID.randomUUID, "FET",
    OffsetDateTime.parse("2026-10-27T10:00:00Z"), "fet", None, Some(11), Some(11))

  private val sheet = PlanSheet(school, plan, placements, rooms)

  test("the sheet prints a grid per class and per teacher"):
    assert(sheet.contains(">1A<"))
    assert(sheet.contains(">1B<"))
    // Short names, because a grid cell has no room for the long form.
    assert(sheet.contains(">AN<") && sheet.contains(">PI<"))
    assert(!sheet.contains("Kowalska Jan"))

  test("the sheet marks tuition, and keeps it off the class it belongs to"):
    assert(sheet.contains("Matematyka (ind.)"))
    // 1B's own grid must not carry its student's private hours. Everything
    // from the class heading to the teachers' section is 1B's and 1A's.
    val classSheets = sheet.substring(
      sheet.indexOf("Plany oddziałów"), sheet.indexOf("Plany nauczycieli"))
    assert(!classSheets.contains("(ind.)"), "a class is not busy during tuition")
    val teacherSheets = sheet.substring(sheet.indexOf("Plany nauczycieli"))
    assert(teacherSheets.contains("(ind.)"), "the teacher is")

  test("the sheet names the days the plan actually has"):
    assert(sheet.contains("<th>Poniedziałek</th>"))
    assert(sheet.contains("<th>Piątek</th>"))
    assert(!sheet.contains("Sobota"), "five teaching days, not seven")
