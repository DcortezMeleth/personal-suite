package bel.solver

import bel.domain.*
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import org.scalatest.funsuite.AnyFunSuite
import org.w3c.dom.Element

/**
 * The translation, held against itself.
 *
 * ADR 0004 bought FET's search at a stated price: the translation becomes the
 * thing that can be wrong. Three of the faults found the afternoon that
 * decision was taken were in the translation rather than in the data, and the
 * exporter and the importer agree about several things by convention rather
 * than by any check — the one that matters most being length. The exporter
 * writes a `Duration` per activity; the importer does not read it, and
 * recovers the length from the block the activity's identifier points at. Two
 * sides deriving one number separately is exactly the arrangement that drifts.
 *
 * So this sends a school out and reads an answer back without FET in the
 * middle. A real run is not needed to catch a translation fault: the answer
 * FET gives is four fields, and a stand-in that fills them honestly exercises
 * every step that could lose or mislabel a lesson.
 */
class FetRoundTripSpec extends AnyFunSuite:

  private val maths = UUID.randomUUID
  private val pe = UUID.randomUUID
  private val polish = UUID.randomUUID
  private val anna = UUID.randomUUID
  private val piotr = UUID.randomUUID
  private val ewa = UUID.randomUUID

  private val gymKind = RoomKind(UUID.randomUUID, "Sala gimnastyczna")
  private val tuitionKind = RoomKind(UUID.randomUUID, IndividualTuition.RoomKindName)

  private val rooms = List(
    Room(UUID.randomUUID, "12", None, true, Nil),
    Room(UUID.randomUUID, "13", None, true, Nil),
    Room(UUID.randomUUID, "GYM", Some("Hala"), true, List(gymKind)),
    Room(UUID.randomUUID, "IND", None, false, List(tuitionKind)))

  private val a1 = SchoolClass(UUID.randomUUID, 1, "A", None, None, Some(30), Some(15))
  private val b1 = SchoolClass(UUID.randomUUID, 1, "B", None, None, Some(28), Some(14))

  private def teacher(id: UUID, code: String) =
    Teacher(id, code, "Jan", "Kowalski", None, Nil, None, None, None, Nil)

  /**
   * Two blocks of one line, so that block *index* is load-bearing: the first
   * is an hour and the second is a double, and nothing but the index tells
   * them apart once they are activity numbers in a file.
   */
  private val mathsLine =
    LessonLine(UUID.randomUUID, a1.id, maths, LessonAudience.WHOLE_CLASS,
      LessonKind.BASE, Some(anna), None, List(1, 2))

  /** A split line, which reaches FET as a group rather than a year. */
  private val polishHalf =
    LessonLine(UUID.randomUUID, a1.id, polish, LessonAudience.GROUP_1,
      LessonKind.BASE, Some(piotr), None, List(1))

  private val groupBoys = CrossClassGroup(UUID.randomUUID, "chłopcy", Some(piotr), List(a1.id, b1.id))
  private val groupGirls = CrossClassGroup(UUID.randomUUID, "dziewczęta", Some(ewa), List(a1.id, b1.id))

  private val peUnit =
    CrossClassUnit(UUID.randomUUID, pe, "WF 1", Some(gymKind.id), List(1),
      List(groupBoys, groupGirls))

  private val tuitionLesson = IndividualLesson(UUID.randomUUID, maths, Some(anna), List(1))
  private val tuitionPlan = IndividualPlan(UUID.randomUUID, b1.id, 1, List(tuitionLesson))

  private val snapshot = SchoolSnapshot(
    SchedulingSettings(2, 5, 8),
    slotsPerDay = 8,
    teachingDays = 5,
    classes = List(a1, b1),
    teachers = List(teacher(anna, "AN"), teacher(piotr, "PI"), teacher(ewa, "EW")),
    subjects = List(
      Subject(maths, "mat", "Matematyka", false, None, false),
      Subject(polish, "pol", "Polski", false, None, false),
      Subject(pe, "wf", "Wychowanie fizyczne", false, Some(gymKind), false)),
    lessonLines = List(mathsLine, polishHalf),
    crossClassUnits = List(peUnit),
    individualPlans = List(tuitionPlan),
    teacherSubjects = Map.empty,
    roomsOfKind = Map(gymKind.id -> 1, tuitionKind.id -> 1),
    totalRooms = rooms.size)

  private val days = FetExport.dayNames(snapshot.teachingDays)
  private val written = FetExport(snapshot, rooms)

  // ── Reading our own export back, the way FET would ─────────────────────

  private def parse(xml: String) =
    DocumentBuilderFactory.newInstance().newDocumentBuilder()
      .parse(java.io.ByteArrayInputStream(xml.getBytes("UTF-8")))

  private def text(e: Element, tag: String): Option[String] =
    val found = e.getElementsByTagName(tag)
    Option.when(found.getLength > 0)(found.item(0).getTextContent.trim)

  /** What the file told FET each activity lasts, by activity number. */
  private val durations: Map[Int, Int] =
    val nodes = parse(written.xml).getElementsByTagName("Activity")
    (0 until nodes.getLength).toList.flatMap { i =>
      val e = nodes.item(i).asInstanceOf[Element]
      (for
        id <- text(e, "Id").flatMap(_.toIntOption)
        d  <- text(e, "Duration").flatMap(_.toIntOption)
      yield id -> d).toList
    }.toMap

  /** Which activity number stands for a given lesson of ours. */
  private val numberOf: Map[SolverInput.LessonRef, Int] =
    written.means.map((id, ref) => ref -> id)

  /**
   * An answer in FET's shape: four fields per activity and nothing else.
   * Deliberately built from what the export produced rather than from the
   * fixture, so a mistake in the export cannot be cancelled out here.
   */
  private def answer(placed: Seq[(Int, String, Int, Option[String])]): String =
    val b = StringBuilder("<?xml version=\"1.0\"?>\n<Activities_Timetable>\n")
    for (id, day, hour, room) <- placed do
      b ++= s"  <Activity><Id>$id</Id><Day>$day</Day><Hour>$hour</Hour>"
      b ++= s"<Room>${room.getOrElse("")}</Room></Activity>\n"
    b ++= "</Activities_Timetable>\n"
    b.result()

  private def at(ref: SolverInput.LessonRef, day: String, hour: Int, room: String) =
    (numberOf(ref), day, hour, Some(room))

  private val mathsSingle = SolverInput.LessonRef.Line(mathsLine.id, 0)
  private val mathsDouble = SolverInput.LessonRef.Line(mathsLine.id, 1)
  private val polishBlock = SolverInput.LessonRef.Line(polishHalf.id, 0)
  private val boysBlock = SolverInput.LessonRef.CrossClassGroupRef(peUnit.id, groupBoys.id, 0)
  private val girlsBlock = SolverInput.LessonRef.CrossClassGroupRef(peUnit.id, groupGirls.id, 0)
  private val tuitionBlock = SolverInput.LessonRef.Individual(tuitionLesson.id, 0)

  /** A plausible week. The two PE groups share an hour, because they must. */
  private val week = List(
    at(mathsSingle, "Poniedziałek", 1, "12"),
    at(mathsDouble, "Środa", 3, "12"),
    at(polishBlock, "Wtorek", 2, "13"),
    at(boysBlock, "Wtorek", 5, "GYM"),
    at(girlsBlock, "Wtorek", 5, "GYM"),
    at(tuitionBlock, "Piątek", 8, "IND"))

  private val result = FetImport(answer(week), written.means, days)

  // ── The round trip ─────────────────────────────────────────────────────

  test("every lesson sent out is recognised coming back"):
    assert(result.unplaced === 0)
    assert(result.complete)
    assert(result.placed.map(_.what).toSet === written.means.values.toSet)

  test("a day is read by the name the file gave it, and an hour counts from zero"):
    val double = result.placed.find(_.what == mathsDouble).get
    // Środa is the third name in the list we wrote, and FET's first hour is ours.
    assert(double.day === 2)
    assert(double.position === 2)

    val monday = result.placed.find(_.what == mathsSingle).get
    assert(monday.day === 0)
    assert(monday.position === 0)

  test("the room FET names is the room we asked about"):
    assert(result.placed.find(_.what == tuitionBlock).get.room === Some("IND"))

  /**
   * The invariant the two sides hold separately. If the exporter ever numbers
   * blocks differently from the way `expand` indexes them, a double becomes an
   * hour here and nowhere else.
   */
  test("a lesson lasts what the file said it lasts"):
    val scheduled = FetImport.expand(snapshot, result.placed, rooms)
    val bySlot = scheduled.filterNot(_.derived).map(x => (x.day, x.from) -> x).toMap

    for (id, ref) <- written.means do
      val placed = result.placed.find(_.what == ref).get
      val got = bySlot((placed.day, placed.position))
      assert(got.length === durations(id),
        s"$ref was exported as ${durations(id)}h and came back as ${got.length}h")

  test("the block index survives: one line's hour and double stay told apart"):
    val scheduled = FetImport.expand(snapshot, result.placed, rooms)
    val mathsForClass = scheduled
      .filter(x => x.subjectId == maths && x.classIds.contains(a1.id))
    assert(mathsForClass.map(x => (x.day, x.length)).toSet === Set((0, 1), (2, 2)))

  // ── The fault FET caught that we had not ───────────────────────────────

  test("a cross-class hour books its classes once, however many groups they span"):
    val scheduled = FetImport.expand(snapshot, result.placed, rooms)
    val occupancy = scheduled.filter(_.derived)

    assert(occupancy.size === 1, "one hour for the classes, not one per group")
    assert(occupancy.head.classIds === Set(a1.id, b1.id))
    assert(occupancy.head.day === 1 && occupancy.head.from === 4)

    // And the teaching side: a lesson each, booking a teacher and no class.
    val teaching = scheduled.filter(x => x.subjectId == pe && !x.derived)
    assert(teaching.size === 2)
    assert(teaching.flatMap(_.classIds).isEmpty)
    assert(teaching.flatMap(_.teacherIds).toSet === Set(piotr, ewa))

  test("tuition books the teacher and leaves the class free"):
    val scheduled = FetImport.expand(snapshot, result.placed, rooms)
    val tuition = scheduled.find(x => x.day == 4 && x.from == 7).get
    assert(tuition.teacherIds === Set(anna))
    assert(tuition.classIds.isEmpty, "the student is not in the class while this happens")
    assert(tuition.needsKind === Some(tuitionKind.id))

  // ── Answers that are not the answer we asked for ───────────────────────

  test("a lesson FET says nothing about is counted, not quietly lost"):
    val short = FetImport(answer(week.filterNot(_._1 == numberOf(polishBlock))),
      written.means, days)
    assert(short.unplaced === 1)
    assert(!short.complete)

  test("an activity we never asked about is ignored"):
    val extra = week :+ ((9999, "Poniedziałek", 1, Some("12")))
    val read = FetImport(answer(extra), written.means, days)
    assert(read.unplaced === 0)
    assert(read.placed.size === written.means.size)

  /**
   * Days are matched by name, so a file written with one set of names and read
   * with another loses everything. It is worth knowing that this fails loudly
   * in the count rather than returning a half week that looks plausible.
   */
  test("day names that do not match the file lose the week, visibly"):
    val read = FetImport(answer(week), written.means, List("Monday", "Tuesday", "Wednesday"))
    assert(read.placed.isEmpty)
    assert(read.unplaced === written.means.size)

  test("the export names every day it says it has"):
    assert(days.size === snapshot.teachingDays)
    val doc = parse(written.xml)
    val named = doc.getElementsByTagName("Day")
    val names = (0 until named.getLength).toList
      .flatMap(i => text(named.item(i).asInstanceOf[Element], "Name").toList)
    assert(names === days)
