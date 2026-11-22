package bel.domain

import io.circe.{Decoder, Encoder}
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

/**
 * The kind of individual pupil (RULES S9). Not in the arkusz; the planner
 * sets it. It describes the provision, not the pupil.
 */
enum TuitionKind:
  /** Only individual lessons, taken remotely; windows allowed. */
  case REMOTE
  /** Only individual lessons, at school; no windows. */
  case ON_SITE
  /** Some lessons with the class (in `half`), some individually; no windows. */
  case MIXED

object TuitionKind:
  def parse(s: String): Option[TuitionKind] = scala.util.Try(valueOf(s)).toOption
  given Encoder[TuitionKind] = Encoder[String].contramap(_.toString)
  given Decoder[TuitionKind] = Decoder[String].emap(s =>
    parse(s).toRight(s"Nieznany rodzaj nauczania indywidualnego: $s"))

case class IndividualPlan(
  id: UUID,
  classId: UUID,
  /** First, second, third such plan in that class. */
  ordinal: Int,
  lessons: List[IndividualLesson],
  /** Unset until the planner says; see TuitionKind. */
  kind: Option[TuitionKind] = None,
  /** For a mixed pupil, the half of their class they are in: 1 or 2. */
  half: Option[Int] = None
):
  def hours: Int = lessons.map(_.hours).sum

  /**
   * How the pupil is planned: their kind, or on-site until the planner has
   * set it, and also for a mixed pupil whose class has lessons in halves but
   * whose half is not set. On-site never double-books a pupil and never leaves
   * them waiting, though it misses a mixed pupil's lessons with the class
   * (INDIVIDUAL_TUITION.md §6). The export and the checker both read it here.
   *
   * @param classSplit whether the pupil's class has lessons in halves
   */
  def plannedKind(classSplit: Boolean): TuitionKind = kind match
    case Some(TuitionKind.MIXED) if half.isEmpty && classSplit => TuitionKind.ON_SITE
    case Some(k) => k
    case None    => TuitionKind.ON_SITE

/** What the planner sets on a plan: the two things the arkusz does not say. */
case class TuitionSettings(kind: Option[TuitionKind], half: Option[Int])

object TuitionSettings:
  given Decoder[TuitionSettings] = Decoder.instance { c =>
    for
      kind <- c.getOrElse[Option[TuitionKind]]("kind")(None)
      half <- c.getOrElse[Option[Int]]("half")(None)
    yield TuitionSettings(kind, half)
  }

  def validate(t: TuitionSettings): Either[String, Unit] =
    if t.half.exists(h => h != 1 && h != 2) then Left("Grupa to 1 albo 2")
    else if t.half.isDefined && !t.kind.contains(TuitionKind.MIXED) then
      Left("Grupę wybiera się tylko dla ucznia mieszanego — pozostali nie chodzą na lekcje z oddziałem")
    else Right(())

case class IndividualLesson(
  id: UUID,
  subjectId: UUID,
  teacherId: Option[UUID],
  blocks: List[Int]
):
  def hours: Int = blocks.sum
