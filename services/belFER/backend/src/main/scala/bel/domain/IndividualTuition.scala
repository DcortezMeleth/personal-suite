package bel.domain

import java.util.UUID

/**
 * One student, taught alone.
 *
 * Nauczanie indywidualne is arranged for a single student — usually one who
 * cannot attend school normally — and the arkusz carries it as a pseudo-class
 * whose code is that student's class followed by their initials. The import
 * skipped these from the beginning, which kept a named minor and their health
 * provision out of our database and is the right instinct. It also kept the
 * hours out, and that was not right: they are taught by the people who teach
 * the classes, so a teacher with five hours of tuition has five fewer for
 * everything else and a plan that does not know it will overcommit them.
 *
 * So the hours come in and the student does not. Which class they belong to is
 * kept, because the tuition is that class's business and the planner has to
 * find it; their initials are not, so a plan is numbered within its class.
 */
object IndividualTuition:
  /**
   * The kind of room the school keeps for this. Matched by name because the
   * arkusz does not mention rooms at all, so the kind is created by migration
   * and recognised by what it is called.
   */
  val RoomKindName = "Nauczanie indywidualne"

case class IndividualPlan(
  id: UUID,
  classId: UUID,
  /** First, second, third such plan in that class. */
  ordinal: Int,
  lessons: List[IndividualLesson]
):
  def hours: Int = lessons.map(_.hours).sum

case class IndividualLesson(
  id: UUID,
  subjectId: UUID,
  teacherId: Option[UUID],
  blocks: List[Int]
):
  def hours: Int = blocks.sum
