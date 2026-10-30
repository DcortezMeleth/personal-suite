package bel.solver

import bel.domain.*
import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite

/**
 * What the planner asked for, as FET hears it.
 *
 * ADR 0005: two kinds of setting, and which is which is FET's decision rather
 * than ours — it refuses a weight below 100 on the gap rules and on the
 * shortest day, and aborts rather than warning. So the thing worth testing is
 * not that a number reaches the file, but that it reaches it as the right
 * *kind* of constraint. A preference written where FET expects an absolute is
 * not a weaker rule; it is a run that does not start.
 */
class FetTuningSpec extends AnyFunSuite:

  private val maths = UUID.randomUUID
  private val chemistry = UUID.randomUUID
  private val lab = RoomKind(UUID.randomUUID, "Pracownia chemiczna")
  private val room12 = Room(UUID.randomUUID, "12", None, true, Nil)
  private val room13 = Room(UUID.randomUUID, "13", None, true, Nil)
  private val labRoom = Room(UUID.randomUUID, "LAB", None, true, List(lab))
  private val anna = UUID.randomUUID

  private val a1 = SchoolClass(UUID.randomUUID, 1, "A", None, None, Some(30), None)

  private def school(tuning: Optimisation, subjects: List[Subject],
                     lines: List[LessonLine], homeRoom: Option[UUID] = None) =
    SchoolSnapshot(
      SchedulingSettings(2, 5, 8, tuning), slotsPerDay = 8, teachingDays = 5,
      classes = List(a1),
      teachers = List(Teacher(anna, "AN", "Jan", "Kowalski", homeRoom, Nil,
        None, None, None, Nil)),
      subjects = subjects, lessonLines = lines, crossClassUnits = Nil,
      individualPlans = Nil, teacherSubjects = Map.empty,
      roomsOfKind = Map(lab.id -> 1), totalRooms = 3)

  private def mathsOnly = List(Subject(maths, "mat", "Matematyka", false, None, false))
  private def oneLesson =
    List(LessonLine(UUID.randomUUID, a1.id, maths, LessonAudience.WHOLE_CLASS,
      LessonKind.BASE, Some(anna), None, List(1)))

  private def weightOf(xml: String, constraint: String): Option[Int] =
    raw"<$constraint>\s*<Weight_Percentage>(\d+)".r.findFirstMatchIn(xml).map(_.group(1).toInt)

  private val rooms = List(room12, room13, labRoom)

  test("nothing asked for puts nothing in the file") {
    val xml = FetExport(school(Optimisation(), mathsOnly, oneLesson), rooms).xml
    assert(!xml.contains("ConstraintTeachersMaxGapsPerWeek"))
    assert(!xml.contains("ConstraintStudentsMinHoursDaily"))
    assert(!xml.contains("ConstraintStudentsMaxHoursDaily"))
    assert(!xml.contains("ConstraintTeacherHomeRoom"))
  }

  /** FET aborts on a weighted gap rule, so this one must go out absolute. */
  test("the weekly gap limit is written as a bound, at full strength") {
    val xml = FetExport(
      school(Optimisation(maxTeacherGapsPerWeek = Some(4)), mathsOnly, oneLesson), rooms).xml
    assert(xml.contains("<Max_Gaps>4</Max_Gaps>"))
    assert(weightOf(xml, "ConstraintTeachersMaxGapsPerWeek") === Some(100))
  }

  /** Same refusal applies to the short end of a class's day. */
  test("the shortest day is a bound; the longest carries its weight") {
    val tuning = Optimisation(
      minClassHoursDaily = Some(5), maxClassHoursDaily = Some(7), classDayWeight = 70)
    val xml = FetExport(school(tuning, mathsOnly, oneLesson), rooms).xml
    assert(weightOf(xml, "ConstraintStudentsMinHoursDaily") === Some(100))
    assert(weightOf(xml, "ConstraintStudentsMaxHoursDaily") === Some(70))
  }

  test("a preference at zero is not written at all") {
    val tuning = Optimisation(maxClassHoursDaily = Some(7), classDayWeight = 0)
    val xml = FetExport(school(tuning, mathsOnly, oneLesson), rooms).xml
    assert(!xml.contains("ConstraintStudentsMaxHoursDaily"))
  }

  test("a teacher's own room is a preference, written once per teacher who has one") {
    val tuning = Optimisation(homeRoomWeight = 60)
    val xml = FetExport(
      school(tuning, mathsOnly, oneLesson, homeRoom = Some(room12.id)), rooms).xml
    assert(weightOf(xml, "ConstraintTeacherHomeRoom") === Some(60))
    assert("<ConstraintTeacherHomeRoom>".r.findAllIn(xml).size === 1)
    assert(xml.contains("<Room>12</Room>"))
  }

  test("no home room set means no constraint, whatever the weight") {
    val xml = FetExport(
      school(Optimisation(homeRoomWeight = 90), mathsOnly, oneLesson), rooms).xml
    assert(!xml.contains("ConstraintTeacherHomeRoom"))
  }

  // ── The room requirement the exporter used to ignore ───────────────────

  private def chemistryLine =
    List(LessonLine(UUID.randomUUID, a1.id, chemistry, LessonAudience.WHOLE_CLASS,
      LessonKind.BASE, Some(anna), None, List(1)))

  private def chem(hard: Boolean) =
    List(Subject(chemistry, "chem", "Chemia", false, Some(lab), hard))

  test("a hard room requirement allows the lab and nothing else") {
    val xml = FetExport(school(Optimisation(), chem(true), chemistryLine), rooms).xml
    val block = xml.substring(xml.indexOf("<ConstraintActivityPreferredRooms>"))
      .take(xml.indexOf("</ConstraintActivityPreferredRooms>"))
    assert(block.contains("<Preferred_Room>LAB</Preferred_Room>"))
    assert(!block.contains("<Preferred_Room>12</Preferred_Room>"),
      "a firm requirement should not offer an ordinary room")
  }

  /**
   * The flag existed and the interface promised it meant something; the
   * exporter wrote every requirement as absolute regardless. Softening it has
   * to widen the allowed set as well as add the preference — the allowed set
   * also carries "big enough for a whole class", and weakening it wholesale
   * would let a class into a room for half of one.
   */
  test("a soft room requirement allows ordinary rooms, and prefers the lab") {
    val tuning = Optimisation(softRoomWeight = 50)
    val xml = FetExport(school(tuning, chem(false), chemistryLine), rooms).xml
    val hard = xml.substring(xml.indexOf("<ConstraintActivityPreferredRooms>"))
    assert(hard.contains("<Preferred_Room>12</Preferred_Room>"),
      "a soft requirement must still permit a fallback room")
    assert("<ConstraintActivityPreferredRooms>".r.findAllIn(xml).size === 2,
      "one constraint to allow, one to prefer")
    assert(xml.contains("<Weight_Percentage>50</Weight_Percentage>"))
  }

  test("a soft requirement with the weight off states no preference") {
    val xml = FetExport(school(Optimisation(), chem(false), chemistryLine), rooms).xml
    assert("<ConstraintActivityPreferredRooms>".r.findAllIn(xml).size === 1)
  }
