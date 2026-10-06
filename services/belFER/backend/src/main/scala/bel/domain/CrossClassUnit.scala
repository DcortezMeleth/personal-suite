package bel.domain

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import java.util.UUID

/**
 * One PE teaching group. It names the classes it draws students from, not the
 * students themselves — the school forms the groups, and which classes take
 * part is all the solver needs.
 */
case class CrossClassGroup(
  id: UUID,
  label: String,
  teacherId: Option[UUID],
  classIds: List[UUID]
)

object CrossClassGroup:
  given Encoder[CrossClassGroup] = deriveEncoder
  given Decoder[CrossClassGroup] = deriveDecoder

case class CrossClassGroupInput(label: String, teacherId: Option[UUID], classIds: List[UUID])

object CrossClassGroupInput:
  given Encoder[CrossClassGroupInput] = deriveEncoder
  given Decoder[CrossClassGroupInput] = deriveDecoder

/**
 * A set of groups that must run at the same time.
 *
 * Every class taking part has all of its students spread across these groups,
 * so while the unit runs none of those classes can be doing anything else —
 * which is why the groups cannot simply be scheduled independently.
 */
case class CrossClassUnit(
  id: UUID,
  subjectId: UUID,
  name: String,
  requiredRoomKindId: Option[UUID],
  blocks: List[Int],
  groups: List[CrossClassGroup]
):
  def hours: Int = blocks.sum

  /** The classes the unit occupies: the union of what its groups draw from. */
  def classIds: List[UUID] = groups.flatMap(_.classIds).distinct

object CrossClassUnit:
  given Encoder[CrossClassUnit] = Encoder.forProduct7(
    "id", "subjectId", "name", "requiredRoomKindId", "blocks", "groups", "hours"
  )(u => (u.id, u.subjectId, u.name, u.requiredRoomKindId, u.blocks, u.groups, u.hours))
  given Decoder[CrossClassUnit] = deriveDecoder

case class CrossClassUnitInput(
  subjectId: UUID,
  name: String,
  requiredRoomKindId: Option[UUID],
  blocks: List[Int],
  groups: List[CrossClassGroupInput]
)

object CrossClassUnitInput:
  given Encoder[CrossClassUnitInput] = deriveEncoder
  given Decoder[CrossClassUnitInput] = deriveDecoder

object CrossClassUnitValidation:

  /**
   * `roomsOfKind` is how many rooms carry the required kind. Every group needs
   * one simultaneously, so more groups than rooms can never be placed — and
   * that is worth saying while the user is looking at the arrangement rather
   * than after a generation run fails.
   */
  def validate(input: CrossClassUnitInput, slotsPerDay: Int, roomsOfKind: Option[Int]): Either[String, Unit] =
    if input.name.trim.isEmpty then Left("Nazwa zespołu nie może być pusta")
    else if BlockValidation.validate(input.blocks, slotsPerDay).isLeft then
      BlockValidation.validate(input.blocks, slotsPerDay)
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
