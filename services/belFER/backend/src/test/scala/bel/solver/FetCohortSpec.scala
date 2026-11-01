package bel.solver

import bel.domain.*
import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite

/**
 * Who actually sits in an optional lesson, as FET hears it.
 *
 * The school's rule is that a pupil must never be left with a free hour in the
 * middle of a day. Religia and etyka are attended by part of a class, so the
 * only way to promise that without saying who attends is to order every such
 * lesson to the end of its class's day — which is stricter than the rule, and
 * on the real school placed 432 activities of 1331 and then stopped for
 * fifteen hours.
 *
 * Said properly, the lesson is addressed to the pupils in it, the rest of the
 * class is visibly free, and FET's own no-gaps rule covers them. That was
 * checked against FET itself before any of this was written: pin such a lesson
 * to the middle of a day and FET calls the whole timetable impossible. What is
 * tested here is that we describe the class to it correctly — see ADR 0006.
 */
class FetCohortSpec extends AnyFunSuite:

  private val religia = UUID.randomUUID
  private val maths = UUID.randomUUID
  private val anna = UUID.randomUUID
  private val a1 = SchoolClass(UUID.randomUUID, 1, "A", None, None, Some(30), None)
  private val rooms = List(Room(UUID.randomUUID, "12", None, true, Nil))

  private val subjects = List(
    Subject(maths, "mat", "Matematyka", false, None, false),
    Subject(religia, "rel", "Religia", true, None, false))

  private def lesson(subject: UUID) =
    LessonLine(UUID.randomUUID, a1.id, subject, LessonAudience.WHOLE_CLASS,
      LessonKind.BASE, Some(anna), None, List(1))

  private def school(attendance: Map[(UUID, UUID), Int]) =
    SchoolSnapshot(
      SchedulingSettings(2, 5, 8, Optimisation()), slotsPerDay = 8, teachingDays = 5,
      classes = List(a1),
      teachers = List(Teacher(anna, "AN", "Jan", "Kowalski", None, Nil, None, None, None, Nil)),
      subjects = subjects,
      lessonLines = List(lesson(maths), lesson(religia)),
      crossClassUnits = Nil, individualPlans = Nil, teacherSubjects = Map.empty,
      optionalAttendance = attendance, roomsOfKind = Map.empty, totalRooms = 1)

  /** The activity carrying a subject, and the student set it is addressed to. */
  private def addressedTo(xml: String, subject: String): List[String] =
    raw"(?s)<Activity>(.*?)</Activity>".r.findAllMatchIn(xml)
      .map(_.group(1)).filter(_.contains(s"<Subject>$subject</Subject>"))
      .flatMap(a => raw"<Students>(.*?)</Students>".r.findAllMatchIn(a).map(_.group(1)))
      .toList

  test("with nobody accounted for, the blunt rule stays"):
    val xml = FetExport(school(Map.empty), rooms).xml
    assert(xml.contains("ConstraintActivityEndsStudentsDay"),
      "a pupil nobody has counted must not quietly be allowed a window")
    assert(addressedTo(xml, "Religia") === List("1A"), "the whole class, for want of better")

  test("told who attends, the lesson is addressed to them and the rule is dropped"):
    val xml = FetExport(school(Map((a1.id, religia) -> 24)), rooms).xml
    assert(!xml.contains("ConstraintActivityEndsStudentsDay"),
      "the cohorts say who is free, so the lesson need not end the day")
    assert(addressedTo(xml, "Religia") !== List("1A"),
      "addressed to the attenders, not to everyone")
    assert(addressedTo(xml, "Maths").isEmpty)
    // Maths is for everyone and must stay that way.
    assert(addressedTo(xml, "Matematyka") === List("1A"))

  test("the pupils attending none of it are described, or FET cannot protect them"):
    val xml = FetExport(school(Map((a1.id, religia) -> 24)), rooms).xml
    val subgroups = raw"<Subgroup>\s*<Name>(.*?)</Name>".r
      .findAllMatchIn(xml).map(_.group(1)).toList.distinct
    assert(subgroups.exists(_.endsWith("-ox")),
      s"nobody stands for the six who attend neither: $subgroups")
    assert(subgroups.count(_.contains("1A/1s")) >= 2,
      "the attendance split cuts across the language split rather than replacing it")

  test("a class nobody has counted keeps the rule even when another is counted"):
    val b1 = SchoolClass(UUID.randomUUID, 1, "B", None, None, Some(30), None)
    val both = school(Map((a1.id, religia) -> 24)).copy(
      classes = List(a1, b1),
      lessonLines = List(lesson(maths), lesson(religia),
        LessonLine(UUID.randomUUID, b1.id, religia, LessonAudience.WHOLE_CLASS,
          LessonKind.BASE, Some(anna), None, List(1))))
    val xml = FetExport(both, rooms).xml
    assert(xml.contains("ConstraintActivityEndsStudentsDay"),
      "1B is still unaccounted for and must keep its fallback")
    assert(xml.sliding("ConstraintActivityEndsStudentsDay>".length)
      .count(_ == "ConstraintActivityEndsStudentsDay>") == 2,
      "exactly one such constraint, opened and closed, for 1B alone")
