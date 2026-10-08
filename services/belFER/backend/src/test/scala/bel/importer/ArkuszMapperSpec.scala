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

  // One warning stands, and should: the arkusz changes some allocations
  // part-way through the semester, which a single plan cannot express.
  test("the only warning left is the mid-period change in hours") {
    assert(plan.warnings.sizeIs == 1, plan.warnings.map(_.message).mkString("; "))
    assert(plan.warnings.head.message.contains("zmienia liczbę godzin"))
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

  // Every WF group would otherwise read "Wychowanie fizyczne", since that is
  // the zajecie's Nazwa — useless for telling three groups of one unit apart.
  test("groups within a unit have labels that distinguish them") {
    plan.crossClassUnits.foreach { unit =>
      val labels = unit.groups.map(_.label)
      assert(labels.distinct.size == labels.size,
        s"${unit.name}: repeated group labels ${labels.mkString(", ")}")
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

  // Seven classes take "zajęcia rozwijające zainteresowania" two to four times
  // over, each hour with its own teacher. Merging those by class and subject
  // kept one teacher and threw the rest away.
  test("allocations differing only by teacher are kept apart") {
    // Every teacher the arkusz names for a class and subject survives. Merging
    // on class and subject alone kept the first and dropped the rest.
    val interest = plan.lessonLines.filter(_.subjectCode == "ZRZU_k_nauko")
    assert(interest.nonEmpty)
    val perClass = interest.groupBy(_.classCode).view.mapValues(_.flatMap(_.teacherCode).distinct)
    assert(perClass.exists((_, teachers) => teachers.sizeIs > 2),
      "expected a class taking this subject from three or more teachers")
    assert(interest.flatMap(_.teacherCode).distinct.sizeIs >= 15)
  }

  // Rows the arkusz splits across week ranges ARE one allocation, as long as
  // the teacher is the same.
  test("one teacher's split week ranges still join into a single allocation") {
    val key = plan.lessonLines.groupBy(l => (l.classCode, l.subjectCode, l.audience, l.kind, l.teacherCode))
    assert(key.forall((_, lines) => lines.sizeIs == 1))
  }

  // "historia 2h in weeks 1-12, 1h in weeks 12-19" means two hours a week and
  // then one — never three. Adding them would have the solver place hours the
  // class never has.
  test("hours that change part-way through are not added together") {
    // 4A takes historia 2h over weeks 1-12 and 1h over 12-19. The first covers
    // more of the imported stretch, so it wins; adding them would give three.
    val historia = plan.lessonLines
      .filter(l => l.classCode == "4A" && l.subjectCode == "historia" && l.kind == LessonKind.BASE)
    assert(historia.sizeIs == 1, historia.toString)
    assert(historia.head.blocks.sum == 2, s"got ${historia.head.blocks.sum}h")
  }

  test("the imported week range needs about as many hours as that range really has") {
    // In-class allocations in weeks 1-18 come to 1040-1047 h/week in the file.
    // Summing overlapping windows instead of choosing between them gave 1155.
    val inClass = plan.lessonLines.map(_.blocks.sum).sum
    assert(inClass > 900 && inClass < 1100, s"got $inClass h/week from lesson lines")
  }

  test("a mid-period change in hours is reported rather than silently resolved") {
    assert(plan.warnings.exists(_.message.contains("zmienia liczbę godzin")))
  }

  // Religia carries a group number because only some students attend, but the
  // class's slot is occupied either way. Reading it as a half left every class
  // with a group 1 and no group 2, which no plan can satisfy.
  test("religia occupies the whole class rather than becoming a half") {
    val religia = plan.lessonLines.filter(_.subjectCode == "religia")
    assert(religia.nonEmpty)
    assert(religia.forall(_.audience == LessonAudience.WHOLE_CLASS),
      religia.map(_.audience).distinct.toString)
  }
