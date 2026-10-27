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

  /**
   * Hours spread over as many subjects as it takes, since no subject may have
   * more blocks than there are teaching days. Filling a week with forty hours
   * of one subject is not a thing a school can do.
   */
  private def filler(classId: UUID, hours: Int, teacherId: Option[UUID] = None) =
    (0 until hours).toList.map { i =>
      line(classId, List(1), teacherId, subject = subjectFor(i / 5))
    }

  private val fillerSubjects = scala.collection.mutable.Map.empty[Int, UUID]
  private def subjectFor(index: Int): UUID =
    fillerSubjects.getOrElseUpdate(index, UUID.randomUUID)

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
    lines, units, teacherSubjects, rooms,
    // Enough that rooms are never the thing under test here; the room bound
    // has its own case below.
    totalRooms = 100)

  // ── Classes ───────────────────────────────────────────────────────────────

  test("a class needing more hours than the week holds cannot be scheduled") {
    val c = cls("1A")
    val lines = filler(c.id, 41)
    val found = Validation.run(snapshot(classes = List(c), lines = lines))
    assert(found.exists(f => f.severity == Severity.Blocking && f.message.contains("nie da się")))
  }

  test("a class filling the week exactly is flagged, but not as blocking") {
    val c = cls("1A")
    val found = Validation.run(snapshot(classes = List(c), lines = filler(c.id, 40)))
    assert(found.exists(_.severity == Severity.Warning))
    assert(!found.exists(_.severity == Severity.Blocking))
  }

  test("a comfortable class raises nothing") {
    val c = cls("1A")
    val staffed = filler(c.id, 30, Some(UUID.randomUUID))
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
    val lines = filler(c.id, 39)
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
    val lines = filler(c.id, 25, Some(t.id))
    val found = Validation.run(snapshot(classes = List(c), teachers = List(t), lines = lines))
    assert(found.exists(f => f.severity == Severity.Blocking && f.message.contains("dostępność")))
  }

  test("a teacher's working-day limit bounds them as much as their availability") {
    val t = teacher(days = Some(2)) // 2 days x 8 lessons = 16
    val c = cls("1A")
    val lines = filler(c.id, 20, Some(t.id))
    val found = Validation.run(snapshot(classes = List(c), teachers = List(t), lines = lines))
    assert(found.exists(f => f.severity == Severity.Blocking && f.message.contains("limity")))
  }

  // Overtime is normal and paid, so it is one line with a count rather than a
  // line per teacher — otherwise it buries everything worth acting on.
  test("teachers over pensum are summarised once, not listed") {
    val c = cls("1A")
    val staff = List.fill(5)(teacher(pensum = Some(18)))
    val lines = staff.flatMap(t => filler(c.id, 20, Some(t.id)))
    val found = Validation.run(snapshot(classes = List(c), teachers = staff, lines = lines))
    val pensum = found.filter(_.subject == "Nadgodziny")
    assert(pensum.sizeIs == 1)
    assert(pensum.head.message.contains("5 nauczycieli"))
  }

  test("a teacher within pensum raises nothing") {
    val t = teacher(pensum = Some(18))
    val c = cls("1A")
    val lines = filler(c.id, 18, Some(t.id))
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

  // ── Regressions found reviewing the checks themselves ─────────────────────

  // Two blocks on the same day that overlap describe fewer blocked slots than
  // their lengths add up to. Summing them invented a shortage: a teacher with
  // Monday 1-4 and Monday 3-6 looked unavailable for eight slots out of forty
  // rather than six, and a full timetable then read as impossible.
  test("overlapping unavailability is counted once, not twice") {
    val t1 = teacher(blocked = List(
      UnavailabilityBlock(1, 1, 8), UnavailabilityBlock(1, 1, 8),
      UnavailabilityBlock(2, 1, 8), UnavailabilityBlock(2, 1, 8),
      UnavailabilityBlock(3, 1, 8), UnavailabilityBlock(3, 1, 8)))
    val c = cls("1A")
    // 24 real blocked slots out of 40 leaves 16, and the limits allow 40.
    val found = Validation.run(snapshot(
      classes = List(c), teachers = List(t1),
      lines = filler(c.id, 16, Some(t1.id))))
    assert(!found.exists(_.severity == Severity.Blocking), found.map(_.message).toString)
  }

  // Every group of a unit runs at the same time, so a teacher holding two of
  // them would have to be in two rooms at once. Nothing downstream noticed.
  test("one teacher leading two groups of the same unit cannot work") {
    val shared = UUID.randomUUID
    val c = cls("1A")
    val unit = CrossClassUnit(UUID.randomUUID, mathsId, "WF", None, List(1),
      List(
        CrossClassGroup(UUID.randomUUID, "dziewczęta", Some(shared), List(c.id)),
        CrossClassGroup(UUID.randomUUID, "chłopcy", Some(shared), List(c.id))))
    val found = Validation.run(snapshot(
      classes = List(c), teachers = List(teacher(shared)), units = List(unit)))
    assert(found.exists(f => f.severity == Severity.Blocking && f.message.contains("jednocześnie")),
      found.map(_.message).toString)
  }

  // The same unit's hours were added once per group its teacher led, so being
  // double-booked also made the teacher look twice as loaded as they were.
  test("a unit's hours count once for its teacher however many groups they hold") {
    val id = UUID.randomUUID
    val c = cls("1A")
    val unit = CrossClassUnit(UUID.randomUUID, mathsId, "WF", None, List(1, 1),
      List(
        CrossClassGroup(UUID.randomUUID, "a", Some(id), List(c.id)),
        CrossClassGroup(UUID.randomUUID, "b", Some(id), List(c.id))))
    val found = Validation.run(snapshot(
      classes = List(c), teachers = List(teacher(id, pensum = Some(3))), units = List(unit)))
    assert(!found.exists(_.subject == "Nadgodziny"), found.map(_.message).toString)
  }

  // Supporting a lesson is time spent teaching it. The load ceiling already
  // counted it; pensum did not, so a teacher could be over and not be listed.
  test("hours spent supporting another teacher count towards pensum") {
    val supporter = UUID.randomUUID
    val c = cls("1A")
    val supported = LessonLine(UUID.randomUUID, c.id, mathsId, LessonAudience.WHOLE_CLASS,
      LessonKind.BASE, Some(UUID.randomUUID), Some(supporter), List(1, 1, 1))
    val found = Validation.run(snapshot(
      classes = List(c), teachers = List(teacher(supporter, pensum = Some(2))),
      lines = List(supported)))
    assert(found.exists(_.subject == "Nadgodziny"), found.map(_.message).toString)
  }

  // Two mis-assigned hours of godzina wychowawcza are one problem, not two.
  test("a class says once that someone else takes its godzina wychowawcza") {
    val wychowawca = UUID.randomUUID
    val someoneElse = UUID.randomUUID
    val c = cls("1A", Some(wychowawca))
    val found = Validation.run(snapshot(
      classes = List(c), teachers = List(teacher(wychowawca), teacher(someoneElse)),
      lines = List(
        line(c.id, List(1), Some(someoneElse), subject = homeroomId),
        line(c.id, List(1), Some(someoneElse), subject = homeroomId))))
    assert(found.count(_.message.contains("prowadzi ktoś inny")) == 1,
      found.map(_.message).toString)
  }

  // The arkusz carries no rooms, so an imported school has none until someone
  // enters them — and every lesson needs one. Deciding that by counting takes
  // no time; letting the solver discover it costs a run.
  test("a school with no rooms cannot produce a plan") {
    val c = cls("1A")
    val found = Validation.run(snapshot(classes = List(c),
      lines = List(line(c.id, List(1)))).copy(totalRooms = 0))
    assert(found.exists(f => f.severity == Severity.Blocking && f.subject == "Sale"),
      found.map(_.message).toString)
  }

  test("too few rooms for the hours that need one is decided by counting") {
    val c = cls("1A")
    // 40 hours of lessons against one room's 40 slots is exactly enough; 41 is
    // not, and no arrangement can rescue it.
    val found = Validation.run(snapshot(classes = List(c),
      lines = filler(c.id, 41)).copy(totalRooms = 1))
    assert(found.exists(f => f.severity == Severity.Blocking && f.subject == "Sale"),
      found.map(_.message).toString)
  }

  test("enough rooms raises nothing about rooms") {
    val c = cls("1A")
    val found = Validation.run(snapshot(classes = List(c),
      lines = filler(c.id, 20)).copy(totalRooms = 3))
    assert(!found.exists(_.subject == "Sale"), found.map(_.message).toString)
  }

  // Both halves of a class are busy at once, so an hour one half spends on a
  // split subject is an hour the other must spend on a different one. When the
  // two halves share a teacher they cannot be that different subject for each
  // other, and there may be nothing else to pair with.
  test("a split whose halves share a teacher needs other split hours to pair with") {
    val c = cls("1A")
    val shared = UUID.randomUUID
    val english = UUID.randomUUID
    val lines = List(
      line(c.id, List(1, 1), Some(shared), subject = english, audience = LessonAudience.GROUP_1),
      line(c.id, List(1, 1), Some(shared), subject = english, audience = LessonAudience.GROUP_2))
    val found = Validation.run(snapshot(classes = List(c), lines = lines))
    assert(found.exists(f => f.severity == Severity.Blocking && f.message.contains("ta sama osoba")),
      found.map(_.message).toString)
  }

  test("the same split pairs fine once each half has somewhere else to be") {
    val c = cls("1A")
    val shared = UUID.randomUUID
    val english = UUID.randomUUID
    val info = UUID.randomUUID
    val lines = List(
      line(c.id, List(1, 1), Some(shared), subject = english, audience = LessonAudience.GROUP_1),
      line(c.id, List(1, 1), Some(shared), subject = english, audience = LessonAudience.GROUP_2),
      // Two more hours each, under teachers of their own, for English to sit against.
      line(c.id, List(1, 1), Some(UUID.randomUUID), subject = info, audience = LessonAudience.GROUP_1),
      line(c.id, List(1, 1), Some(UUID.randomUUID), subject = info, audience = LessonAudience.GROUP_2))
    val found = Validation.run(snapshot(classes = List(c), lines = lines))
    assert(!found.exists(_.message.contains("ta sama osoba")), found.map(_.message).toString)
  }

