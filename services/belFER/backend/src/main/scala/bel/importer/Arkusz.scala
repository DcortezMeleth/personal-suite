package bel.importer

/**
 * The arkusz organizacyjny exactly as Vulcan writes it — no interpretation yet.
 *
 * Keeping the parse honest about the file's own shape, rather than mapping
 * straight onto belFER's model, means the two can be read and tested
 * separately. The mapping is where the interesting decisions live, and they
 * are easier to argue about when the input is plainly visible.
 */
case class ArkuszSchool(code: String, name: String)

case class ArkuszTeacher(
  code: String,
  firstName: String,
  lastName: String,
  pensum: Option[Int]
)

case class ArkuszClass(
  code: String,
  level: Option[Int],
  profile: Option[String],
  shortName: Option[String],
  studentCount: Option[Int],
  girlCount: Option[Int],
  homeroomTeacherCode: Option[String]
)

case class ArkuszSubject(code: String, name: String)

/** A cross-class lesson. One `<zajecie>` is one teaching group. */
case class ArkuszCrossClassLesson(code: String, name: String)

/**
 * One row of the allocation. The file uses three shapes of this row, and which
 * one it is can only be told from which references are present:
 *
 *   class, no lesson, teacher  — an ordinary in-class allocation
 *   class, lesson,    no teacher — this class contributes students to that lesson
 *   no class, lesson, teacher  — the cross-class group itself, and who teaches it
 */
case class ArkuszAssignment(
  classCode: Option[String],
  crossClassCode: Option[String],
  subjectCode: String,
  teacherCode: Option[String],
  hours: Int,
  studentCount: Option[Int],
  groupNumber: Option[Int],
  groupScheme: Option[String],
  groupLabel: Option[String],
  weekFrom: Int,
  weekTo: Int
):
  def isCrossClassMembership: Boolean = classCode.isDefined && crossClassCode.isDefined
  def isCrossClassGroup: Boolean      = classCode.isEmpty && crossClassCode.isDefined
  def isOrdinary: Boolean             = classCode.isDefined && crossClassCode.isEmpty

  /** True when this allocation applies at any point in the given week range. */
  def overlaps(from: Int, to: Int): Boolean = weekFrom <= to && weekTo >= from

case class ArkuszDocument(
  school: ArkuszSchool,
  teachers: List[ArkuszTeacher],
  classes: List[ArkuszClass],
  subjects: List[ArkuszSubject],
  crossClassLessons: List[ArkuszCrossClassLesson],
  assignments: List[ArkuszAssignment]
)
