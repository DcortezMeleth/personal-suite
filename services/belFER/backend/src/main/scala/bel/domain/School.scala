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
  maxConsecutiveTeacherGaps: Int,
  defaultTeacherMaxWorkingDays: Int,
  defaultTeacherMaxLessonsPerDay: Int,
  /**
   * What a good week is, as distinct from a legal one — ADR 0005.
   *
   * Two kinds, and which is which is not ours to choose: FET refuses a weight
   * below 100 on the gap constraints and on the daily minimum, and accepts one
   * on the daily maximum and on both room preferences.
   *
   * Bounds are absolute. Set one too tight and there is no week, which is why
   * they all start unset: a school upgrading into this generates exactly what
   * it generated before until somebody asks for something different.
   */
  optimisation: Optimisation = Optimisation()
)

/**
 * @param maxTeacherGapsPerWeek bound. Free hours a teacher may have across the
 *   week. The one with leverage — `maxConsecutiveTeacherGaps` of two already
 *   permits ten across five days, and this school averages four and a half.
 * @param minClassHoursDaily bound. The short end of a class's day; with the
 *   maximum below it is how "longest minus shortest at most three" gets said,
 *   because FET cannot speak about a spread.
 * @param maxClassHoursDaily preference. The long end of the same.
 * @param classDayWeight strength of that preference, 0 to 100. Zero is off.
 * @param homeRoomWeight preference. A lesson in its teacher's own room.
 * @param softRoomWeight preference. A subject in a room of its kind, where the
 *   subject says the requirement is soft. Subjects that call it hard are
 *   unaffected and stay at full strength.
 */
case class Optimisation(
  maxTeacherGapsPerWeek: Option[Int] = None,
  minClassHoursDaily: Option[Int] = None,
  maxClassHoursDaily: Option[Int] = None,
  classDayWeight: Int = 0,
  homeRoomWeight: Int = 0,
  softRoomWeight: Int = 0
):
  /** A preference nobody has asked for is not written into the file at all. */
  def wantsClassDay: Boolean = maxClassHoursDaily.isDefined && classDayWeight > 0
  def wantsHomeRooms: Boolean = homeRoomWeight > 0
  def wantsSoftRooms: Boolean = softRoomWeight > 0

object Optimisation:
  given Encoder[Optimisation] = deriveEncoder

  /** Every field optional, for the same reason as above. */
  given Decoder[Optimisation] = Decoder.instance { c =>
    for
      gaps     <- c.getOrElse[Option[Int]]("maxTeacherGapsPerWeek")(None)
      shortest <- c.getOrElse[Option[Int]]("minClassHoursDaily")(None)
      longest  <- c.getOrElse[Option[Int]]("maxClassHoursDaily")(None)
      dayWeight <- c.getOrElse[Int]("classDayWeight")(0)
      homeRoom <- c.getOrElse[Int]("homeRoomWeight")(0)
      softRoom <- c.getOrElse[Int]("softRoomWeight")(0)
    yield Optimisation(gaps, shortest, longest, dayWeight, homeRoom, softRoom)
  }

  def validate(o: Optimisation): Either[String, Unit] =
    val weights = List(
      "Waga długości dnia" -> o.classDayWeight,
      "Waga sali własnej" -> o.homeRoomWeight,
      "Waga sali specjalistycznej" -> o.softRoomWeight)
    weights.collectFirst { case (name, w) if w < 0 || w > 100 =>
      s"$name musi mieścić się w przedziale 0–100"
    } match
      case Some(problem) => Left(problem)
      case None =>
        if o.maxTeacherGapsPerWeek.exists(_ < 0) then
          Left("Maksymalna liczba okienek w tygodniu nie może być ujemna")
        else if o.minClassHoursDaily.exists(_ < 0) then
          Left("Najkrótszy dzień oddziału nie może być ujemny")
        else if o.maxClassHoursDaily.exists(_ < 1) then
          Left("Najdłuższy dzień oddziału musi być dodatni")
        else if o.minClassHoursDaily.zip(o.maxClassHoursDaily).exists((lo, hi) => lo > hi) then
          Left("Najkrótszy dzień oddziału nie może być dłuższy niż najdłuższy")
        else Right(())

object SchedulingSettings:
  given Encoder[SchedulingSettings] = deriveEncoder

  /**
   * Written by hand so that `optimisation` may be absent.
   *
   * Derived decoders do not fall back to a field's default, so a derived one
   * here would reject every request the Szkoła page already sends — it knows
   * nothing about optimisation and has no reason to. A screen that edits one
   * half of a record should not have to send the other half back untouched.
   */
  given Decoder[SchedulingSettings] = Decoder.instance { c =>
    for
      gaps    <- c.get[Int]("maxConsecutiveTeacherGaps")
      days    <- c.get[Int]("defaultTeacherMaxWorkingDays")
      lessons <- c.get[Int]("defaultTeacherMaxLessonsPerDay")
      tuning  <- c.getOrElse[Optimisation]("optimisation")(Optimisation())
    yield SchedulingSettings(gaps, days, lessons, tuning)
  }

  /** Rejected rather than silently clamped — a nonsensical limit is a typo. */
  def validate(s: SchedulingSettings): Either[String, Unit] =
    if s.maxConsecutiveTeacherGaps < 0 then
      Left("Maksymalna liczba okienek pod rząd nie może być ujemna")
    else if s.defaultTeacherMaxWorkingDays < 1 || s.defaultTeacherMaxWorkingDays > 7 then
      Left("Liczba dni pracy w tygodniu musi mieścić się w przedziale 1–7")
    else if s.defaultTeacherMaxLessonsPerDay < 1 then
      Left("Maksymalna liczba lekcji dziennie musi być dodatnia")
    else Optimisation.validate(s.optimisation)

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
