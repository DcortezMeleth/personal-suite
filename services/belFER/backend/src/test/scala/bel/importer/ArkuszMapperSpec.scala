package bel.importer

import bel.domain.{LessonAudience, LessonKind}
import org.scalatest.funsuite.AnyFunSuite

class ArkuszMapperSpec extends AnyFunSuite:

  private lazy val document: ArkuszDocument =
    val stream = getClass.getResourceAsStream("/services/belFER/testdata/arkusz/full-semester.xml")
    val bytes = stream.readAllBytes()
    stream.close()
    ArkuszParser.parse(bytes).fold(fail(_), identity)

  // Weeks 1–19 is the first semester in this export.
  private lazy val plan = ArkuszMapper.map(document, ArkuszMapper.WeekRange(1, 19))

  test("report what the real arkusz maps to") {
    info(s"school:            ${plan.schoolName}")
    info(s"teachers:          ${plan.teachers.size}")
    info(s"subjects:          ${plan.subjects.size}")
    info(s"classes:           ${plan.classes.size}")
    info(s"lesson lines:      ${plan.lessonLines.size}")
    info(s"cross-class units: ${plan.crossClassUnits.size}" +
      s" (${plan.crossClassUnits.map(_.groups.size).sum} groups)")
    plan.notes.foreach(n => info(s"${n.level}: ${n.message}"))
    assert(plan.classes.nonEmpty)
  }

  test("individual-teaching plans are skipped, not imported as classes") {
    assert(plan.classes.forall(_.code.length == 2))
    assert(plan.classes.size == 25)
  }

  test("extended hours become a second line on the base subject, not a second subject") {
    // Matching falls back to the name, because r_angielski's base is
    // j.angielski and r_informat.'s is informatyka — the code does not strip.
    assert(!plan.subjects.exists(_.code.startsWith("r_")))
    assert(plan.lessonLines.exists(_.kind == LessonKind.EXTENSION))
    val extensionSubjects = plan.lessonLines.filter(_.kind == LessonKind.EXTENSION).map(_.subjectCode)
    assert(extensionSubjects.forall(code => plan.subjects.exists(_.code == code)))
  }

  test("religia and etyka are marked optional, so they land first or last") {
    assert(plan.subjects.filter(_.optional).map(_.code).toSet.nonEmpty)
    assert(plan.subjects.find(_.code == "religia").exists(_.optional))
  }

  test("every planned line names a class and subject the plan also creates") {
    val classes = plan.classes.map(_.code).toSet
    val subjects = plan.subjects.map(_.code).toSet
    assert(plan.lessonLines.forall(l => classes.contains(l.classCode)))
    assert(plan.lessonLines.forall(l => subjects.contains(l.subjectCode)))
  }

  test("split lines use the two halves belFER models") {
    val split = plan.lessonLines.filter(_.audience != LessonAudience.WHOLE_CLASS)
    assert(split.nonEmpty)
    assert(split.forall(l => l.audience == LessonAudience.GROUP_1 || l.audience == LessonAudience.GROUP_2))
  }

  // WF is grouped independently of the class's halves, so it becomes a unit
  // rather than being forced into GROUP_1/GROUP_2 — which would tell the
  // solver two unrelated halves were the same students.
  test("WF never becomes a class-split line") {
    assert(!plan.lessonLines.exists(_.subjectCode == "wf"))
    assert(plan.crossClassUnits.exists(_.subjectCode == "wf"))
  }

  // In the real arkusz most classes teach one half themselves and merge the
  // other with a neighbouring class. Scheduling those apart would leave half
  // the class idle while the other half had a lesson, so they must be one unit.
  test("a class's own WF half and the half it merges away land in one unit") {
    val wf = plan.crossClassUnits.filter(_.subjectCode == "wf")
    val mixed = wf.filter { unit =>
      val sizes = unit.groups.map(_.classCodes.size)
      sizes.contains(1) && sizes.exists(_ > 1)
    }
    assert(mixed.nonEmpty, "expected units joining an in-class half to a merged one")
  }

  test("nothing is reported as unmappable any more") {
    assert(plan.warnings.isEmpty, plan.warnings.map(_.message).mkString("; "))
  }

  // Groups sharing a class must run together, so they must end up in one unit.
  // Two units of one subject sharing a class would each need that class free
  // at the same time — the components exist precisely to prevent it.
  test("no class appears in two units of the same subject") {
    plan.crossClassUnits.groupBy(_.subjectCode).foreach { (subject, units) =>
      val seen = scala.collection.mutable.Set.empty[String]
      units.foreach { unit =>
        val classes = unit.groups.flatMap(_.classCodes).toSet
        assert(classes.intersect(seen.toSet).isEmpty,
          s"$subject: ${classes.intersect(seen.toSet)} appears in two separate units")
        seen ++= classes
      }
    }
  }

  test("every cross-class group names at least one class") {
    assert(plan.crossClassUnits.forall(_.groups.exists(_.classCodes.nonEmpty)))
  }

  test("a narrower week range imports fewer rows") {
    val wide = ArkuszMapper.map(document, ArkuszMapper.WeekRange(1, 38))
    val narrow = ArkuszMapper.map(document, ArkuszMapper.WeekRange(1, 4))
    assert(narrow.lessonLines.size < wide.lessonLines.size)
  }
