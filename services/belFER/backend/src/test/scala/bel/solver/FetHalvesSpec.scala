package bel.solver

import bel.domain.*
import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite

/**
 * A subject a class has both whole-class and in halves, as FET hears it —
 * RULES S4 (never on the same day for a half) and S5 (the halves stay level).
 *
 * S5 is not something FET can state exactly, so what is tested here is the
 * stronger thing it is told instead: each half's lesson and its counterpart at
 * most a day apart, on top of the whole class being kept off the halves' days.
 * Whether a finished week keeps S5 itself is PlanCheck R16's job.
 */
class FetHalvesSpec extends AnyFunSuite:

  private val bio = UUID.randomUUID
  private val anna = UUID.randomUUID
  private val c1 = SchoolClass(UUID.randomUUID, 1, "C", None, None, Some(30), None)
  private val rooms = List(Room(UUID.randomUUID, "12", None, true, Nil))

  private def line(audience: LessonAudience, kind: LessonKind) =
    LessonLine(UUID.randomUUID, c1.id, bio, audience, kind, Some(anna), None, List(1))

  // 1C biologia: one whole-class hour; each half a basic and an extended hour.
  private val whole = line(LessonAudience.WHOLE_CLASS, LessonKind.EXTENSION)
  private val firstBase = line(LessonAudience.GROUP_1, LessonKind.BASE)
  private val firstExt = line(LessonAudience.GROUP_1, LessonKind.EXTENSION)
  private val secondBase = line(LessonAudience.GROUP_2, LessonKind.BASE)
  private val secondExt = line(LessonAudience.GROUP_2, LessonKind.EXTENSION)

  private def written(lines: List[LessonLine]) = FetExport(SchoolSnapshot(
    SchedulingSettings(2, 5, 8, Optimisation()), slotsPerDay = 8, teachingDays = 5,
    classes = List(c1),
    teachers = List(Teacher(anna, "AN", "Jan", "Kowalski", None, Nil, None, None, None, Nil)),
    subjects = List(Subject(bio, "bio", "Biologia", false, None, false)),
    lessonLines = lines, crossClassUnits = Nil, individualPlans = Nil,
    teacherSubjects = Map.empty, roomsOfKind = Map.empty, totalRooms = 1), rooms)

  private val all = written(List(whole, secondExt, firstBase, secondBase, firstExt))
  private def id(l: LessonLine) = all.means.collectFirst {
    case (n, LessonRef.Line(lineId, 0)) if lineId == l.id => n }.get

  /** The activity numbers in each constraint of a kind, as sets. */
  private def groups(xml: String, kind: String): List[Set[Int]] =
    raw"(?s)<$kind>(.*?)</$kind>".r.findAllMatchIn(xml).map(_.group(1)).map(c =>
      raw"<Activity_Id>(\d+)</Activity_Id>".r.findAllMatchIn(c).map(_.group(1).toInt).toSet)
      .toList

  test("each half is spread together with the whole class, not with the other half"):
    val spread = groups(all.xml, "ConstraintMinDaysBetweenActivities").toSet
    assert(spread === Set(
      Set(id(whole), id(firstBase), id(firstExt)),
      Set(id(whole), id(secondBase), id(secondExt))))

  test("each half's lesson is at most a day from its counterpart, like for like"):
    val pairs = raw"(?s)<ConstraintMaxDaysBetweenActivities>(.*?)</ConstraintMaxDaysBetweenActivities>".r
      .findAllMatchIn(all.xml).map(_.group(1)).toList
    assert(pairs.forall(_.contains("<MaxDays>1</MaxDays>")))
    assert(groups(all.xml, "ConstraintMaxDaysBetweenActivities").toSet === Set(
      Set(id(firstBase), id(secondBase)),
      Set(id(firstExt), id(secondExt))))

  test("a subject taught only in halves is spread per half and left unpaired"):
    val halvesOnly = written(List(firstBase, firstExt, secondBase, secondExt))
    assert(groups(halvesOnly.xml, "ConstraintMaxDaysBetweenActivities").isEmpty,
      "with no whole-class lesson there is nothing for the halves to be level for")
    assert(groups(halvesOnly.xml, "ConstraintMinDaysBetweenActivities").map(_.size).sorted === List(2, 2))
