package bel.domain

import java.time.OffsetDateTime
import java.util.UUID

/**
 * A week, as laid out.
 *
 * Kept as a thing in its own right rather than written back over the data it
 * came from: the school builds a plan twice a year and wants to hold what it
 * has against what it might have, and going back to the old one should be a
 * matter of saying so rather than of undoing a thousand edits.
 */
case class Plan(
  id: UUID,
  schoolId: UUID,
  name: String,
  createdAt: OffsetDateTime,
  /** Which program laid it out — "fet" or "belfer". */
  madeBy: String,
  tookMillis: Option[Int],
  /** What the checker made of it when it was saved. */
  rulesKept: Option[Int],
  rulesTotal: Option[Int]
):
  def sound: Boolean = (rulesKept, rulesTotal) match
    case (Some(kept), Some(total)) => kept == total
    case _                         => false

/** One lesson at one hour. Exactly one of the two references is set. */
case class Placement(
  lessonLineId: Option[UUID],
  crossClassGroupId: Option[UUID],
  individualLessonId: Option[UUID],
  blockIndex: Int,
  /** One-based, as the bell schedule is. */
  dayOfWeek: Int,
  position: Int,
  roomId: Option[UUID]
)

case class NewPlan(
  name: String,
  madeBy: String,
  tookMillis: Option[Int],
  rulesKept: Option[Int],
  rulesTotal: Option[Int],
  placements: List[Placement]
)
