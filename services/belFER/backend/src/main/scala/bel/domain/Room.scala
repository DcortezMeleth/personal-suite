package bel.domain

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import java.util.UUID

case class RoomKind(id: UUID, name: String)

object RoomKind:
  given Encoder[RoomKind] = deriveEncoder
  given Decoder[RoomKind] = deriveDecoder

case class CreateRoomKind(name: String)

object CreateRoomKind:
  given Decoder[CreateRoomKind] = deriveDecoder

/**
 * A room the solver can assign a lesson to. A teacher's own room is a
 * preference the solver weighs, not a reservation — several teachers may share
 * one, and some have none — so the room is decided per lesson.
 */
case class Room(
  id: UUID,
  number: String,
  name: Option[String],
  fitsWholeClass: Boolean,
  kinds: List[RoomKind]
)

object Room:
  given Encoder[Room] = deriveEncoder
  given Decoder[Room] = deriveDecoder

case class RoomInput(
  number: String,
  name: Option[String],
  fitsWholeClass: Boolean,
  kindIds: List[UUID]
)

object RoomInput:
  given Encoder[RoomInput] = deriveEncoder
  given Decoder[RoomInput] = deriveDecoder

object RoomValidation:
  def validate(input: RoomInput): Either[String, Unit] =
    if input.number.trim.isEmpty then Left("Numer sali nie może być pusty")
    else if input.kindIds.distinct.size != input.kindIds.size then
      Left("Typ sali nie może się powtarzać")
    else Right(())
