package bel.importer

import bel.domain.LessonKind
import org.scalatest.funsuite.AnyFunSuite

/**
 * The importer faults found by the review of 2026-11-21 (I2, I5, I6, I7, I11),
 * each on a document small enough to see the whole of. The real fixture runs
 * every tuition row over all 38 weeks, which is why none of them showed there.
 */
class ArkuszMapperFixesSpec extends AnyFunSuite:

  private def klass(code: String, students: Option[Int] = Some(30), girls: Option[Int] = Some(15)) =
    ArkuszClass(code, None, None, None, students, girls, None)
  private val teachers = List(ArkuszTeacher("AN", "Jan", "Kowalski", None))
  private val subjects = List(
    ArkuszSubject("matematyka", "Matematyka"),
    ArkuszSubject("r_matematyka", "Matematyka rozszerzona"))

  private def row(cls: String, subject: String, hours: Int, teacher: Option[String] = Some("AN"),
                  from: Int = 1, to: Int = 38, crossClass: Option[String] = None) =
    ArkuszAssignment(Some(cls), crossClass, subject, teacher, hours, None, None, None, None, from, to)

  private def map(rows: List[ArkuszAssignment], classes: List[ArkuszClass] = List(klass("1A")),
                  weeks: ArkuszMapper.WeekRange = ArkuszMapper.WeekRange(1, 19),
                  teacherList: List[ArkuszTeacher] = teachers) =
    ArkuszMapper.map(
      ArkuszDocument(ArkuszSchool("X", "Szkoła"), teacherList, classes, subjects, Nil, rows), weeks)

  private def warnings(plan: ImportPlan) =
    plan.notes.filter(_.level == NoteLevel.Warning).map(_.message)

  test("just enough singles are paired to fit the week, not all of them (I6)"):
    // Four basic hours and two extended: six singles, one too many. One
    // double is enough; pairing a line's singles at once made two.
    val plan = map(List(row("1A", "matematyka", 4), row("1A", "r_matematyka", 2)))
    val shapes = plan.lessonLines.map(l => l.kind -> l.blocks).toMap
    assert(shapes(LessonKind.BASE) === List(1, 1, 2))
    assert(shapes(LessonKind.EXTENSION) === List(1, 1))

  private val pupil = klass("1Aab", None, None)

  test("tuition comes from the chosen weeks, one window at a time (I2)"):
    val rows = List(
      row("1Aab", "matematyka", 2, from = 1, to = 19),
      row("1Aab", "matematyka", 1, from = 20, to = 38))
    val second = map(rows, List(klass("1A"), pupil), ArkuszMapper.WeekRange(20, 29))
    assert(second.individualPlans.flatMap(_.lessons).map(_.blocks.sum) === List(1),
      "the second semester's hour, not both windows added up")
    val whole = map(rows, List(klass("1A"), pupil), ArkuszMapper.WeekRange(1, 38))
    assert(whole.individualPlans.flatMap(_.lessons).map(_.blocks.sum).sum < 3)

  test("extended tuition is tuition in the base subject (I5)"):
    val plan = map(List(row("1Aab", "r_matematyka", 1)), List(klass("1A"), pupil))
    assert(plan.individualPlans.flatMap(_.lessons).map(_.subjectCode) === List("matematyka"))

  test("a pupil's place in a cross-class group is not tuition (I11)"):
    val plan = map(List(row("1Aab", "matematyka", 2, crossClass = Some("WF1"))),
      List(klass("1A"), pupil))
    assert(plan.individualPlans.isEmpty)

  test("a subject the file never defines is reported and left out (I7)"):
    val plan = map(List(row("1A", "matematyka", 3), row("1A", "chemia", 2)))
    assert(plan.lessonLines.map(_.subjectCode).distinct === List("matematyka"))
    assert(warnings(plan).exists(_.contains("chemia")))

  test("a teacher the file never defines is reported, and the lesson kept without one (I7)"):
    val plan = map(List(row("1A", "matematyka", 3, teacher = Some("ZZ"))))
    assert(plan.lessonLines.map(_.teacherCode) === List(None))
    assert(warnings(plan).exists(_.contains("ZZ")))

  test("what the database would refuse is said instead: repeated codes, girls over the roll"):
    val plan = map(List(row("1A", "matematyka", 3)),
      classes = List(klass("1A", Some(20), Some(25))),
      teacherList = teachers :+ ArkuszTeacher("AN", "Anna", "Nowak", None))
    assert(plan.teachers.size === 1)
    assert(plan.classes.head.girlCount === None)
    assert(warnings(plan).exists(_.contains("AN")))
    assert(warnings(plan).exists(_.contains("1A")))


  test("pseudo-class codes that differ only in case are one pupil"):
    // The school confirms it is an error in the data (2026-11-22).
    val lower = klass("1Aab", None, None)
    val upper = klass("1AAB", None, None)
    val plan = map(List(row("1Aab", "matematyka", 2), row("1AAB", "matematyka", 2),
      row("1AAB", "r_matematyka", 1, teacher = None)), List(klass("1A"), lower, upper))
    assert(plan.individualPlans.size === 1)
    assert(plan.individualPlans.head.lessons.map(l => l.subjectCode -> l.blocks.sum).sorted ===
      List("matematyka" -> 1, "matematyka" -> 2))
    assert(plan.notes.exists(_.message.contains("to ten sam uczeń")))
