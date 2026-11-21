package bel.solver

import bel.domain.*
import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite

/**
 * How uneven a class's week may be (RULES P2), as FET hears it: a range of
 * day lengths per class, from that class's own weekly load.
 */
class FetClassDaysSpec extends AnyFunSuite:

  private val maths = UUID.randomUUID
  private val religia = UUID.randomUUID
  private val anna = UUID.randomUUID
  private val rooms = List(Room(UUID.randomUUID, "12", None, true, Nil))

  private def cls(name: String) =
    SchoolClass(UUID.randomUUID, name.head.asDigit, name.tail, None, None, Some(30), None)
  private val a1 = cls("1A")
  private val b4 = cls("4B")

  private def hours(c: SchoolClass, n: Int, audience: LessonAudience = LessonAudience.WHOLE_CLASS,
                    subject: UUID = maths) =
    LessonLine(UUID.randomUUID, c.id, subject, audience, LessonKind.BASE, Some(anna), None,
      List.fill(n)(1))

  private def school(spread: Option[Int], classes: List[SchoolClass], lines: List[LessonLine],
                     attendance: Map[(UUID, UUID), Int] = Map.empty) =
    FetExport(SchoolSnapshot(
      SchedulingSettings(2, 5, 8, Optimisation(maxClassDaySpread = spread)),
      slotsPerDay = 8, teachingDays = 5, classes = classes,
      teachers = List(Teacher(anna, "AN", "Jan", "Kowalski", None, Nil, None, None, None, Nil)),
      subjects = List(
        Subject(maths, "mat", "Matematyka", false, None, false),
        Subject(religia, "rel", "Religia", true, None, false)),
      lessonLines = lines, crossClassUnits = Nil, individualPlans = Nil,
      teacherSubjects = Map.empty, optionalAttendance = attendance,
      roomsOfKind = Map.empty, totalRooms = 1), rooms).xml

  /** Each constraint of a kind, as (students, hours). */
  private def bounds(xml: String, kind: String, field: String): Map[String, Int] =
    raw"(?s)<$kind>(.*?)</$kind>".r.findAllMatchIn(xml).map(_.group(1)).map { c =>
      raw"<Students>(.*?)</Students>".r.findFirstMatchIn(c).get.group(1) ->
        raw"<$field>(\d+)</$field>".r.findFirstMatchIn(c).get.group(1).toInt
    }.toMap

  private def shortest(xml: String) =
    bounds(xml, "ConstraintStudentsSetMinHoursDaily", "Minimum_Hours_Daily")
  private def longest(xml: String) =
    bounds(xml, "ConstraintStudentsSetMaxHoursDaily", "Maximum_Hours_Daily")

  test("not asked for, a class is held only to the length of the day"):
    val xml = school(None, List(a1), List(hours(a1, 32)))
    assert(shortest(xml).isEmpty)
    assert(longest(xml) === Map("1A" -> 8))

  test("asked for, each class gets a range around its own average day"):
    val xml = school(Some(3), List(a1, b4), List(hours(a1, 32), hours(b4, 29)))
    assert(shortest(xml) === Map("1A" -> 5, "4B" -> 4))
    assert(longest(xml) === Map("1A" -> 8, "4B" -> 7))
    assert(xml.contains("<Allow_Empty_Days>false</Allow_Empty_Days>"),
      "an empty day is the shortest day there is")

  test("halves with different loads each get their own range"):
    // 30 whole-class hours, then 7 more for half 1 and 2 for half 2.
    val lines = List(hours(a1, 30), hours(a1, 7, LessonAudience.GROUP_1),
      hours(a1, 2, LessonAudience.GROUP_2))
    val xml = school(Some(2), List(a1), lines)
    assert(shortest(xml) === Map("1A/1s" -> 6, "1A/2s" -> 5))
    assert(longest(xml) === Map("1A" -> 8, "1A/1s" -> 8, "1A/2s" -> 7))

  test("pupils who skip religia have a shorter week, and get a range of their own"):
    val lines = List(hours(a1, 31), hours(a1, 1, subject = religia))
    val xml = school(Some(2), List(a1), lines, attendance = Map((a1.id, religia) -> 24))
    val declared = raw"<Subgroup>\s*<Name>(.*?)</Name>".r.findAllMatchIn(xml).map(_.group(1)).toSet
    assert(shortest(xml).keySet === declared,
      "every subgroup, and only subgroups the file declares — the names are kept in two places")
    val (attend, skip) = shortest(xml).keySet.partition(!_.endsWith("-ox"))
    assert(attend.nonEmpty && skip.nonEmpty)
    for s <- attend do assert(longest(xml)(s) === ClassDays.range(32, 5, 8, 2).get._2)
    for s <- skip do assert(shortest(xml)(s) === ClassDays.range(31, 5, 8, 2).get._1)
