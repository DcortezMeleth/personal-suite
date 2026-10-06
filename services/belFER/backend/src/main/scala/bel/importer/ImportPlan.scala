package bel.importer

import bel.domain.{LessonAudience, LessonKind}

/**
 * What an import would create, expressed in the arkusz's own codes. Resolving
 * codes to identifiers happens when the plan is applied; keeping the plan in
 * codes means it can be computed, inspected and tested without a database.
 */
case class PlannedTeacher(code: String, firstName: String, lastName: String, pensum: Option[Int])

case class PlannedSubject(code: String, name: String, optional: Boolean)

case class PlannedClass(
  code: String,
  year: Int,
  letter: String,
  profile: Option[String],
  homeroomTeacherCode: Option[String],
  studentCount: Option[Int],
  girlCount: Option[Int]
)

case class PlannedLessonLine(
  classCode: String,
  subjectCode: String,
  audience: LessonAudience,
  kind: LessonKind,
  teacherCode: Option[String],
  blocks: List[Int]
)

case class PlannedCrossClassGroup(label: String, teacherCode: Option[String], classCodes: List[String])

case class PlannedCrossClassUnit(
  subjectCode: String,
  name: String,
  blocks: List[Int],
  groups: List[PlannedCrossClassGroup]
)

enum NoteLevel:
  case Info, Warning

case class ImportNote(level: NoteLevel, message: String)

case class ImportPlan(
  schoolName: String,
  teachers: List[PlannedTeacher],
  subjects: List[PlannedSubject],
  classes: List[PlannedClass],
  lessonLines: List[PlannedLessonLine],
  crossClassUnits: List[PlannedCrossClassUnit],
  notes: List[ImportNote]
):
  def warnings: List[ImportNote] = notes.filter(_.level == NoteLevel.Warning)
