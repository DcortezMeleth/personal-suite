package bel.domain

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The knobs that change what the solver treats as acceptable. They are school
 * configuration rather than constants because the point of belFER is that the
 * rules are editable — a constraint the planner cannot adjust is how the
 * previous software ended up needing manual rework.
 */
case class SchedulingSettings(
  allowClassGaps: Boolean,
  maxConsecutiveTeacherGaps: Int,
  defaultTeacherMaxWorkingDays: Int,
  defaultTeacherMaxLessonsPerDay: Int
)

object SchedulingSettings:
  given Encoder[SchedulingSettings] = deriveEncoder
  given Decoder[SchedulingSettings] = deriveDecoder

  /** Rejected rather than silently clamped — a nonsensical limit is a typo. */
  def validate(s: SchedulingSettings): Either[String, Unit] =
    if s.maxConsecutiveTeacherGaps < 0 then
      Left("Maksymalna liczba okienek pod rząd nie może być ujemna")
    else if s.defaultTeacherMaxWorkingDays < 1 || s.defaultTeacherMaxWorkingDays > 7 then
      Left("Liczba dni pracy w tygodniu musi mieścić się w przedziale 1–7")
    else if s.defaultTeacherMaxLessonsPerDay < 1 then
      Left("Maksymalna liczba lekcji dziennie musi być dodatnia")
    else Right(())

case class School(
  id: UUID,
  name: String,
  years: Int,
  settings: SchedulingSettings,
  createdAt: OffsetDateTime
)

object School:
  given Encoder[School] = deriveEncoder
  given Decoder[School] = deriveDecoder

case class CreateSchool(name: String, years: Int)

object CreateSchool:
  given Decoder[CreateSchool] = deriveDecoder

case class UpdateSchool(name: String, years: Int, settings: SchedulingSettings)

object UpdateSchool:
  given Decoder[UpdateSchool] = deriveDecoder

object SchoolValidation:
  def validate(name: String, years: Int, settings: SchedulingSettings): Either[String, Unit] =
    if name.trim.isEmpty then Left("Nazwa szkoły nie może być pusta")
    else if years < 1 || years > 12 then Left("Liczba roczników musi mieścić się w przedziale 1–12")
    else SchedulingSettings.validate(settings)
