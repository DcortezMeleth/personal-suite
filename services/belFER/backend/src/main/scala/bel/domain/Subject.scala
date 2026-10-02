package bel.domain

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import java.util.UUID

/**
 * A subject as the school names it. The arkusz codes base and extended hours
 * separately ("matematyka" and "r_matematyka"); belFER keeps both as subjects
 * here and lets a class combine them into one subject's hours at the lesson
 * line, which is where that distinction actually belongs.
 */
case class Subject(
  id: UUID,
  code: String,
  name: String,
  optional: Boolean,
  requiredRoomKind: Option[RoomKind],
  roomRequirementHard: Boolean
)

object Subject:
  given Encoder[Subject] = deriveEncoder
  given Decoder[Subject] = deriveDecoder

case class SubjectInput(
  code: String,
  name: String,
  optional: Boolean,
  requiredRoomKindId: Option[UUID],
  roomRequirementHard: Boolean
)

object SubjectInput:
  given Encoder[SubjectInput] = deriveEncoder
  given Decoder[SubjectInput] = deriveDecoder

object SubjectValidation:
  def validate(input: SubjectInput): Either[String, Unit] =
    if input.code.trim.isEmpty then Left("Kod przedmiotu nie może być pusty")
    else if input.name.trim.isEmpty then Left("Nazwa przedmiotu nie może być pusta")
    // A hard room requirement with no room kind named is a setting that cannot
    // be satisfied or reported on — almost certainly a half-finished edit.
    else if input.roomRequirementHard && input.requiredRoomKindId.isEmpty then
      Left("Wymóg sali może być obowiązkowy tylko wtedy, gdy wskazano typ sali")
    else Right(())
