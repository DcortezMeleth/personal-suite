package bel.domain

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import java.util.UUID

/**
 * One PE teaching group. It names the classes it draws students from, not the
 * students themselves — the school forms the groups, and which classes take
 * part is all the solver needs.
 */
case class PeGroup(
  id: UUID,
  label: String,
  teacherId: Option[UUID],
  classIds: List[UUID]
)

object PeGroup:
  given Encoder[PeGroup] = deriveEncoder
  given Decoder[PeGroup] = deriveDecoder

case class PeGroupInput(label: String, teacherId: Option[UUID], classIds: List[UUID])

object PeGroupInput:
  given Encoder[PeGroupInput] = deriveEncoder
  given Decoder[PeGroupInput] = deriveDecoder

/**
 * A set of groups that must run at the same time.
 *
 * Every class taking part has all of its students spread across these groups,
 * so while the unit runs none of those classes can be doing anything else —
 * which is why the groups cannot simply be scheduled independently.
 */
case class PeUnit(
  id: UUID,
  subjectId: UUID,
  name: String,
  requiredRoomKindId: Option[UUID],
  blocks: List[Int],
  groups: List[PeGroup]
):
  def hours: Int = blocks.sum

  /** The classes the unit occupies: the union of what its groups draw from. */
  def classIds: List[UUID] = groups.flatMap(_.classIds).distinct

object PeUnit:
  given Encoder[PeUnit] = Encoder.forProduct7(
    "id", "subjectId", "name", "requiredRoomKindId", "blocks", "groups", "hours"
  )(u => (u.id, u.subjectId, u.name, u.requiredRoomKindId, u.blocks, u.groups, u.hours))
  given Decoder[PeUnit] = deriveDecoder

case class PeUnitInput(
  subjectId: UUID,
  name: String,
  requiredRoomKindId: Option[UUID],
  blocks: List[Int],
  groups: List[PeGroupInput]
)

object PeUnitInput:
  given Encoder[PeUnitInput] = deriveEncoder
  given Decoder[PeUnitInput] = deriveDecoder

object PeUnitValidation:

  /**
   * `roomsOfKind` is how many rooms carry the required kind. Every group needs
   * one simultaneously, so more groups than rooms can never be placed — and
   * that is worth saying while the user is looking at the arrangement rather
   * than after a generation run fails.
   */
  def validate(input: PeUnitInput, slotsPerDay: Int, roomsOfKind: Option[Int]): Either[String, Unit] =
    if input.name.trim.isEmpty then Left("Nazwa zespołu nie może być pusta")
    else if input.blocks.isEmpty then Left("Podaj układ bloków, np. 1, 2")
    else if input.blocks.exists(_ < 1) then Left("Każdy blok musi mieć co najmniej jedną godzinę")
    else if slotsPerDay > 0 && input.blocks.exists(_ > slotsPerDay) then
      Left(s"Blok nie może być dłuższy niż liczba lekcji w dniu ($slotsPerDay)")
    else if input.groups.isEmpty then Left("Zespół musi mieć co najmniej jedną grupę")
    else if input.groups.exists(_.label.trim.isEmpty) then Left("Nazwa grupy nie może być pusta")
    else if input.groups.exists(_.classIds.isEmpty) then
      Left("Każda grupa musi obejmować co najmniej jeden oddział")
    else if input.groups.exists(g => g.classIds.distinct.size != g.classIds.size) then
      Left("Oddział nie może powtarzać się w jednej grupie")
    else
      roomsOfKind
        .filter(_ < input.groups.size)
        .map(available =>
          s"Grup jest ${input.groups.size}, a sal tego typu tylko $available — " +
            "wszystkie grupy odbywają się jednocześnie")
        .toLeft(())
