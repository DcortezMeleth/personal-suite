package bel.domain

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import java.util.UUID

/** Who a line is taught to. Groups are halves of one class, and the split is
  * the same across every subject that uses it — except PE, which is grouped
  * independently and modelled separately. */
enum LessonAudience:
  case WHOLE_CLASS, GROUP_1, GROUP_2

object LessonAudience:
  def parse(s: String): Option[LessonAudience] = scala.util.Try(valueOf(s)).toOption
  given Encoder[LessonAudience] = Encoder[String].contramap(_.toString)
  given Decoder[LessonAudience] = Decoder[String].emap(s =>
    parse(s).toRight(s"Nieznany odbiorca lekcji: $s"))

/** Base hours versus the extra hours an extended class gets. One subject, two
  * lines — not two subjects, which is how the arkusz codes it and what the
  * school says is wrong. */
enum LessonKind:
  case BASE, EXTENSION

object LessonKind:
  def parse(s: String): Option[LessonKind] = scala.util.Try(valueOf(s)).toOption
  given Encoder[LessonKind] = Encoder[String].contramap(_.toString)
  given Decoder[LessonKind] = Decoder[String].emap(s =>
    parse(s).toRight(s"Nieznany rodzaj lekcji: $s"))

case class LessonLine(
  id: UUID,
  classId: UUID,
  subjectId: UUID,
  audience: LessonAudience,
  kind: LessonKind,
  teacherId: Option[UUID],
  supportTeacherId: Option[UUID],
  // The shape, not just the total: List(1, 1, 2) is four hours as two singles
  // and one double. The solver places blocks, so this is the unit.
  blocks: List[Int]
):
  def hours: Int = blocks.sum

object LessonLine:
  given Encoder[LessonLine] = Encoder.forProduct9(
    "id", "classId", "subjectId", "audience", "kind",
    "teacherId", "supportTeacherId", "blocks", "hours"
  )(l => (l.id, l.classId, l.subjectId, l.audience, l.kind,
          l.teacherId, l.supportTeacherId, l.blocks, l.hours))
  given Decoder[LessonLine] = deriveDecoder

case class LessonLineInput(
  classId: UUID,
  subjectId: UUID,
  audience: LessonAudience,
  kind: LessonKind,
  teacherId: Option[UUID],
  supportTeacherId: Option[UUID],
  blocks: List[Int]
)

object LessonLineInput:
  given Encoder[LessonLineInput] = deriveEncoder
  given Decoder[LessonLineInput] = deriveDecoder

/**
 * Block shapes are validated the same way wherever they appear — on a lesson
 * line and on a cross-class unit — so the rule lives once.
 */
object BlockValidation:

  def validate(blocks: List[Int], slotsPerDay: Int): Either[String, Unit] =
    if blocks.isEmpty then Left("Podaj układ bloków, np. 1, 1, 2")
    else if blocks.exists(_ < 1) then Left("Każdy blok musi mieć co najmniej jedną godzinę")
    // A block is placed on consecutive slots of one day, so one longer than the
    // day can never be placed — and discovering that during generation would
    // cost hours.
    else if slotsPerDay > 0 && blocks.exists(_ > slotsPerDay) then
      Left(s"Blok nie może być dłuższy niż liczba lekcji w dniu ($slotsPerDay)")
    else Right(())

object LessonLineValidation:

  def validate(input: LessonLineInput, slotsPerDay: Int): Either[String, Unit] =
    BlockValidation.validate(input.blocks, slotsPerDay).flatMap { _ =>
      if input.supportTeacherId.exists(s => input.teacherId.contains(s)) then
        Left("Nauczyciel wspomagający musi być inną osobą niż prowadzący")
      else Right(())
    }

/**
 * Checks that span a whole class rather than a single line. These are reported
 * rather than enforced on save: a user adding a split subject has to save the
 * first group before the second can exist, so demanding consistency at write
 * time would make the data impossible to enter.
 */
case class AllocationSummary(occupiedHours: Int, problems: List[String])

object AllocationSummary:
  given Encoder[AllocationSummary] = deriveEncoder
  given Decoder[AllocationSummary] = deriveDecoder

object ClassAllocation:

  /**
   * `blocking` separates "no arrangement of these hours can work" from "this
   * looks incomplete". Unequal group hours are the former — one half would sit
   * idle while the other had a lesson. A missing teacher is the latter: the
   * lesson still occupies its class and room, it just is not finished.
   */
  case class Problem(message: String, blocking: Boolean)

  def summary(
    lines: List[LessonLine],
    crossClassHours: Int,
    subjectName: Map[UUID, String]
  ): AllocationSummary =
    AllocationSummary(
      occupiedHours(lines) + crossClassHours,
      problems(lines, subjectName).map(_.message)
    )

  def problems(lines: List[LessonLine], subjectName: Map[UUID, String]): List[Problem] =
    val bySubject = lines.groupBy(_.subjectId)

    val groupMismatches =
      bySubject.toList.sortBy((id, _) => subjectName.getOrElse(id, "")).flatMap { (subjectId, subjectLines) =>
        val first  = subjectLines.filter(_.audience == LessonAudience.GROUP_1).map(_.hours).sum
        val second = subjectLines.filter(_.audience == LessonAudience.GROUP_2).map(_.hours).sum
        // Both halves must be busy at the same time, so unequal hours leave one
        // group with nothing to do while the other has a lesson.
        if first != second then
          Some(Problem(
            s"${subjectName.getOrElse(subjectId, "?")}: grupa 1 ma $first godz., a grupa 2 — $second. " +
              "Obie grupy muszą być zajęte w tym samym czasie.",
            blocking = true))
        else None
      }

    val missingTeacher =
      lines.filter(_.teacherId.isEmpty) match
        case Nil   => Nil
        case unset => List(Problem(s"Nieprzypisany nauczyciel: ${unset.size} przydz.", blocking = false))

    groupMismatches ++ missingTeacher

  /** Hours the class occupies through its own lesson lines. A slot where the
    * class is split counts once, not once per group.
    *
    * Cross-class lessons are added on top by `summary`: they occupy the class
    * just as much, but they are not its lines — they belong to a unit shared
    * with other classes. */
  def occupiedHours(lines: List[LessonLine]): Int =
    val whole = lines.filter(_.audience == LessonAudience.WHOLE_CLASS).map(_.hours).sum
    val split = lines.filter(_.audience == LessonAudience.GROUP_1).map(_.hours).sum
    whole + split
