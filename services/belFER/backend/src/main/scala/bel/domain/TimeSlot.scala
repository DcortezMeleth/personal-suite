package bel.domain

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import java.time.LocalTime
import java.util.UUID

/**
 * One slot in the bell schedule. The times do not constrain the solver at all —
 * it only counts slots — but the plan cannot be printed without them, and the
 * number of slots is what the per-day lesson limit is derived from.
 */
case class TimeSlot(
  id: UUID,
  position: Int,
  startsAt: LocalTime,
  endsAt: LocalTime
)

object TimeSlot:
  given Encoder[TimeSlot] = deriveEncoder
  given Decoder[TimeSlot] = deriveDecoder

case class TimeSlotInput(position: Int, startsAt: LocalTime, endsAt: LocalTime)

object TimeSlotInput:
  given Encoder[TimeSlotInput] = deriveEncoder
  given Decoder[TimeSlotInput] = deriveDecoder

object BellSchedule:

  /**
   * The schedule is replaced wholesale rather than edited slot by slot, so the
   * validation can assume it sees all of it.
   *
   * Contiguity from 1 matters beyond tidiness: slot positions are what "a
   * class's day starts at slot 1" and "a double block occupies consecutive
   * slots" are expressed in terms of. A gap in the numbering would make both
   * constraints quietly mean something else.
   */
  def validate(slots: List[TimeSlotInput]): Either[String, Unit] =
    val sorted = slots.sortBy(_.position)
    if slots.isEmpty then Left("Plan dzwonków musi mieć co najmniej jedną lekcję")
    else if sorted.map(_.position) != (1 to slots.size).toList then
      Left("Numery lekcji muszą być kolejne, począwszy od 1")
    else
      sorted
        .find(s => !s.endsAt.isAfter(s.startsAt))
        .map(s => s"Lekcja ${s.position} kończy się przed swoim początkiem")
        .orElse(
          sorted
            .sliding(2)
            .collectFirst {
              case List(a, b) if a.endsAt.isAfter(b.startsAt) =>
                s"Lekcje ${a.position} i ${b.position} nachodzą na siebie"
            }
        )
        .toLeft(())
