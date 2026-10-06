package bel.importer

import org.scalatest.funsuite.AnyFunSuite

/**
 * Read against the committed arkusz rather than a hand-written sample. The
 * point of this phase is to find out whether the model survives the real file,
 * so the tests work on the real file.
 */
class ArkuszParserSpec extends AnyFunSuite:

  private lazy val document: ArkuszDocument =
    val stream = getClass.getResourceAsStream("/services/belFER/testdata/arkusz/full-semester.xml")
    assert(stream != null, "the arkusz fixture is not on the test classpath")
    val bytes = stream.readAllBytes()
    stream.close()
    ArkuszParser.parse(bytes).fold(error => fail(error), identity)

  test("reads every element of the real arkusz") {
    assert(document.teachers.size == 75)
    assert(document.classes.size == 28)
    assert(document.subjects.size == 31)
    assert(document.crossClassLessons.size == 23)
    assert(document.assignments.size == 797)
  }

  test("reads the school") {
    assert(document.school.name.nonEmpty)
  }

  test("trims the trailing space the export leaves on some forenames") {
    assert(document.teachers.forall(t => t.firstName == t.firstName.trim))
    assert(document.teachers.forall(_.lastName.nonEmpty))
  }

  test("teacher codes are unique, which is what everything else joins on") {
    assert(document.teachers.map(_.code).distinct.size == document.teachers.size)
  }

  // The three row shapes, told apart only by which references are present.
  test("recognises all three shapes of allocation row") {
    val ordinary   = document.assignments.count(_.isOrdinary)
    val membership = document.assignments.count(_.isCrossClassMembership)
    val group      = document.assignments.count(_.isCrossClassGroup)

    assert(ordinary == 718)
    assert(membership == 56)
    assert(group == 23)
    assert(ordinary + membership + group == document.assignments.size)
  }

  test("one cross-class lesson is one teaching group, each with its own teacher") {
    val definitions = document.assignments.filter(_.isCrossClassGroup)
    assert(definitions.size == document.crossClassLessons.size)
    assert(definitions.forall(_.teacherCode.isDefined))
  }

  // Participation rows deliberately carry no teacher: naming one there would
  // count that teacher's hours twice.
  test("membership rows name no teacher") {
    assert(document.assignments.filter(_.isCrossClassMembership).forall(_.teacherCode.isEmpty))
  }

  test("every reference resolves") {
    val teachers = document.teachers.map(_.code).toSet
    val classes  = document.classes.map(_.code).toSet
    val subjects = document.subjects.map(_.code).toSet
    val lessons  = document.crossClassLessons.map(_.code).toSet

    assert(document.assignments.flatMap(_.teacherCode).forall(teachers.contains))
    assert(document.assignments.flatMap(_.classCode).forall(classes.contains))
    assert(document.assignments.map(_.subjectCode).forall(subjects.contains))
    assert(document.assignments.flatMap(_.crossClassCode).forall(lessons.contains))
    assert(document.classes.flatMap(_.homeroomTeacherCode).forall(teachers.contains))
  }

  test("week ranges are present and sane") {
    assert(document.assignments.forall(a => a.weekFrom >= 1 && a.weekTo >= a.weekFrom))
    // Most of the year, but far from all of it — which is the whole reason
    // validity windows are modelled at all.
    val wholeYear = document.assignments.count(a => a.weekFrom == 1 && a.weekTo == 38)
    assert(wholeYear > 400 && wholeYear < document.assignments.size)
  }

  test("overlap is inclusive at both ends") {
    val a = document.assignments.head.copy(weekFrom = 5, weekTo = 10)
    assert(a.overlaps(10, 20))
    assert(a.overlaps(1, 5))
    assert(!a.overlaps(11, 20))
    assert(!a.overlaps(1, 4))
  }

  test("refuses a file that is not an arkusz") {
    assert(ArkuszParser.parse("<inne/>".getBytes("UTF-8")).isLeft)
    assert(ArkuszParser.parse("not xml at all".getBytes("UTF-8")).isLeft)
  }

  // An uploaded file is untrusted input, and a parser that resolves external
  // entities will read whatever the sender names.
  test("refuses a document declaring a DTD, rather than resolving it") {
    val xxe =
      """<?xml version="1.0"?>
        |<!DOCTYPE planOrg [ <!ENTITY secret SYSTEM "file:///etc/passwd"> ]>
        |<planOrg><placowka kod="X" nazwa="&secret;" /></planOrg>""".stripMargin
    assert(ArkuszParser.parse(xxe.getBytes("UTF-8")).isLeft)
  }
