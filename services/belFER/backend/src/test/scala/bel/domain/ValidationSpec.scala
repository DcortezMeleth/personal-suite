package bel.domain

import java.time.OffsetDateTime
import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite

class ValidationSpec extends AnyFunSuite:

  private val settings = SchedulingSettings(false, 2, 5, 8)
  private val mathsId = UUID.randomUUID
  private val homeroomId = UUID.randomUUID

  private def cls(name: String, wychowawca: Option[UUID] = Some(UUID.randomUUID)) =
    SchoolClass(UUID.randomUUID, name.head.asDigit, name.tail, None, wychowawca, None, None)

  private def teacher(id: UUID = UUID.randomUUID, pensum: Option[Int] = None,
                      blocked: List[UnavailabilityBlock] = Nil, days: Option[Int] = None) =
    Teacher(id, "XX", "Jan", "Kowalski", None, Nil, days, None, pensum, blocked)

  private def line(classId: UUID, blocks: List[Int], teacherId: Option[UUID] = None,
                   subject: UUID = mathsId, audience: LessonAudience = LessonAudience.WHOLE_CLASS) =
    LessonLine(UUID.randomUUID, classId, subject, audience, LessonKind.BASE, teacherId, None, blocks)

  private def snapshot(
    classes: List[SchoolClass] = Nil,
    teachers: List[Teacher] = Nil,
    lines: List[LessonLine] = Nil,
    units: List[CrossClassUnit] = Nil,
    teacherSubjects: Map[UUID, Set[UUID]] = Map.empty,
    rooms: Map[UUID, Int] = Map.empty
  ) = SchoolSnapshot(
    settings, slotsPerDay = 8, teachingDays = 5, classes, teachers,
    subjects = List(
      Subject(mathsId, "matematyka", "Matematyka", false, None, false),
      Subject(homeroomId, "zaj. z wych.", "Zajęcia z wychowawcą", false, None, false)
    ),
    lines, units, teacherSubjects, rooms)

  // ── Classes ───────────────────────────────────────────────────────────────

  test("a class needing more hours than the week holds cannot be scheduled") {
    val c = cls("1A")
    val lines = List.fill(41)(line(c.id, List(1)))
    val found = Validation.run(snapshot(classes = List(c), lines = lines))
    assert(found.exists(f => f.severity == Severity.Blocking && f.message.contains("nie da się")))
  }

  test("a class filling the week exactly is flagged, but not as blocking") {
    val c = cls("1A")
    val found = Validation.run(snapshot(classes = List(c), lines = List.fill(40)(line(c.id, List(1)))))
    assert(found.exists(_.severity == Severity.Warning))
    assert(!found.exists(_.severity == Severity.Blocking))
  }

  test("a comfortable class raises nothing") {
    val c = cls("1A")
    val staffed = List.fill(30)(line(c.id, List(1), Some(UUID.randomUUID)))
    assert(Validation.run(snapshot(classes = List(c), lines = staffed)).isEmpty)
  }

  // Incomplete rather than impossible: the lesson still occupies its class and
  // its room, it simply has nobody to teach it yet.
  test("a lesson with no teacher is a warning, not a blockage") {
    val c = cls("1A")
    val found = Validation.run(snapshot(classes = List(c), lines = List(line(c.id, List(1)))))
    assert(found.exists(f => f.severity == Severity.Warning && f.message.contains("Nieprzypisany")))
    assert(!found.exists(_.severity == Severity.Blocking))
  }

  // Cross-class lessons occupy the class too, so they count toward its week.
  test("cross-class hours count toward a class's capacity") {
    val c = cls("1A")
    val unit = CrossClassUnit(UUID.randomUUID, mathsId, "WF", None, List(1, 1),
      List(CrossClassGroup(UUID.randomUUID, "gr", None, List(c.id))))
    val lines = List.fill(39)(line(c.id, List(1)))
    val found = Validation.run(snapshot(classes = List(c), lines = lines, units = List(unit)))
    assert(found.exists(_.severity == Severity.Blocking))
  }

  // At most one block of a subject per day, so more blocks than days is
  // impossible however much room there is.
  test("more blocks than teaching days cannot be placed") {
    val c = cls("1A")
    val found = Validation.run(snapshot(classes = List(c), lines = List(line(c.id, List(1,1,1,1,1,1)))))
    assert(found.exists(f => f.severity == Severity.Blocking && f.message.contains("bloków")))
  }

  // ── Teachers ──────────────────────────────────────────────────────────────

  test("a teacher allocated more than their blocked time leaves is blocking") {
    val t = teacher(blocked = List(UnavailabilityBlock(1, 1, 8), UnavailabilityBlock(2, 1, 8)))
    val c = cls("1A")
    val lines = List.fill(25)(line(c.id, List(1), Some(t.id)))
    val found = Validation.run(snapshot(classes = List(c), teachers = List(t), lines = lines))
    assert(found.exists(f => f.severity == Severity.Blocking && f.message.contains("dostępność")))
  }

  test("a teacher's working-day limit bounds them as much as their availability") {
    val t = teacher(days = Some(2)) // 2 days x 8 lessons = 16
    val c = cls("1A")
    val lines = List.fill(20)(line(c.id, List(1), Some(t.id)))
    val found = Validation.run(snapshot(classes = List(c), teachers = List(t), lines = lines))
    assert(found.exists(f => f.severity == Severity.Blocking && f.message.contains("limity")))
  }

  // Overtime is normal and paid, so it is one line with a count rather than a
  // line per teacher — otherwise it buries everything worth acting on.
  test("teachers over pensum are summarised once, not listed") {
    val c = cls("1A")
    val staff = List.fill(5)(teacher(pensum = Some(18)))
    val lines = staff.flatMap(t => List.fill(20)(line(c.id, List(1), Some(t.id))))
    val found = Validation.run(snapshot(classes = List(c), teachers = staff, lines = lines))
    val pensum = found.filter(_.subject == "Nadgodziny")
    assert(pensum.sizeIs == 1)
    assert(pensum.head.message.contains("5 nauczycieli"))
  }

  test("a teacher within pensum raises nothing") {
    val t = teacher(pensum = Some(18))
    val c = cls("1A")
    val lines = List.fill(18)(line(c.id, List(1), Some(t.id)))
    assert(Validation.run(snapshot(classes = List(c), teachers = List(t), lines = lines)).isEmpty)
  }

  test("a teacher allocated a subject they do not teach is flagged") {
    val t = teacher()
    val c = cls("1A")
    val other = UUID.randomUUID
    val found = Validation.run(snapshot(
      classes = List(c), teachers = List(t), lines = List(line(c.id, List(1), Some(t.id))),
      teacherSubjects = Map(t.id -> Set(other))))
    assert(found.exists(_.message.contains("nie jest wpisany")))
  }

  test("a teacher with no subjects recorded is not flagged, since nothing is known") {
    val t = teacher()
    val c = cls("1A")
    assert(Validation.run(snapshot(
      classes = List(c), teachers = List(t), lines = List(line(c.id, List(1), Some(t.id))))).isEmpty)
  }

  // ── Wychowawca ────────────────────────────────────────────────────────────

  test("a class with no wychowawca is flagged") {
    val found = Validation.run(snapshot(classes = List(cls("1A", None))))
    assert(found.exists(_.message.contains("brak wychowawcy")))
  }

  // The homeroom hour belongs to the wychowawca by definition, so anyone else
  // taking it means one of the two is wrong.
  test("the homeroom hour taught by anyone else is flagged") {
    val wychowawca = UUID.randomUUID
    val other = UUID.randomUUID
    val c = cls("1A", Some(wychowawca))
    val found = Validation.run(snapshot(
      classes = List(c), lines = List(line(c.id, List(1), Some(other), subject = homeroomId))))
    assert(found.exists(_.message.contains("ktoś inny niż wychowawca")))
  }

  test("the homeroom hour taught by the wychowawca raises nothing") {
    val wychowawca = UUID.randomUUID
    val c = cls("1A", Some(wychowawca))
    assert(Validation.run(snapshot(
      classes = List(c), lines = List(line(c.id, List(1), Some(wychowawca), subject = homeroomId)))).isEmpty)
  }

  // ── Cross-class lessons ───────────────────────────────────────────────────

  test("more groups than rooms of the required kind is blocking") {
    val kind = UUID.randomUUID
    val c = cls("1A")
    val unit = CrossClassUnit(UUID.randomUUID, mathsId, "WF", Some(kind), List(1),
      List.fill(4)(CrossClassGroup(UUID.randomUUID, "gr", Some(UUID.randomUUID), List(c.id))))
    val found = Validation.run(snapshot(classes = List(c), units = List(unit), rooms = Map(kind -> 3)))
    assert(found.exists(f => f.severity == Severity.Blocking && f.message.contains("sal tego typu")))
  }
