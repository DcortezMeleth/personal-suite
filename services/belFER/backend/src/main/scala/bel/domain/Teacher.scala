package bel.domain

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import java.util.UUID

/**
 * A weekly-repeating block during which a teacher cannot be scheduled —
 * typically because they teach at another school those hours.
 *
 * Being able to state this easily is the single feature the school's current
 * software lacks most painfully, so it is a first-class part of the teacher
 * rather than a note someone keeps on paper.
 */
case class UnavailabilityBlock(dayOfWeek: Int, fromPosition: Int, toPosition: Int)

object UnavailabilityBlock:
  given Encoder[UnavailabilityBlock] = deriveEncoder
  given Decoder[UnavailabilityBlock] = deriveDecoder

case class Teacher(
  id: UUID,
  code: String,
  firstName: String,
  lastName: String,
  homeRoomId: Option[UUID],
  subjectIds: List[UUID],
  // None means "inherit the school default" rather than "no limit", so raising
  // the school default raises it for everyone who had not overridden it.
  maxWorkingDays: Option[Int],
  maxLessonsPerDay: Option[Int],
  pensum: Option[Int],
  unavailability: List[UnavailabilityBlock]
)

object Teacher:
  given Encoder[Teacher] = deriveEncoder
  given Decoder[Teacher] = deriveDecoder

case class TeacherInput(
  code: String,
  firstName: String,
  lastName: String,
  homeRoomId: Option[UUID],
  subjectIds: List[UUID],
  maxWorkingDays: Option[Int],
  maxLessonsPerDay: Option[Int],
  pensum: Option[Int],
  unavailability: List[UnavailabilityBlock]
)

object TeacherInput:
  given Encoder[TeacherInput] = deriveEncoder
  given Decoder[TeacherInput] = deriveDecoder

object TeacherValidation:

  /**
   * `slotCount` is the number of lessons in the bell schedule. Blocked slots
   * are checked against it here rather than at generation time: a block
   * pointing at a lesson that does not exist is a typo, and finding it while
   * the user is looking at the teacher is worth far more than finding it
   * hours into a solver run.
   */
  def validate(input: TeacherInput, slotCount: Int): Either[String, Unit] =
    if input.code.trim.isEmpty then Left("Kod nauczyciela nie może być pusty")
    else if input.lastName.trim.isEmpty then Left("Nazwisko nie może być puste")
    else if input.subjectIds.distinct.size != input.subjectIds.size then
      Left("Przedmiot nie może się powtarzać")
    else if input.maxWorkingDays.exists(d => d < 1 || d > 7) then
      Left("Liczba dni pracy musi mieścić się w przedziale 1–7")
    else if input.maxLessonsPerDay.exists(_ < 1) then
      Left("Limit lekcji dziennie musi być dodatni")
    else if input.pensum.exists(_ < 0) then
      Left("Pensum nie może być ujemne")
    else validateBlocks(input.unavailability, slotCount)

  private def validateBlocks(blocks: List[UnavailabilityBlock], slotCount: Int): Either[String, Unit] =
    blocks
      .collectFirst {
        case b if b.dayOfWeek < 1 || b.dayOfWeek > 5 =>
          "Dzień tygodnia musi mieścić się w przedziale poniedziałek–piątek"
        case b if b.fromPosition < 1 || b.toPosition < b.fromPosition =>
          "Zakres zablokowanych lekcji jest nieprawidłowy"
        case b if slotCount > 0 && b.toPosition > slotCount =>
          s"Zablokowano lekcję ${b.toPosition}, a plan dzwonków ma ich tylko $slotCount"
      }
      .orElse(overlap(blocks))
      .toLeft(())

  // Overlapping blocks are harmless to the solver — the union is the same —
  // but they mean the user entered the same thing twice, and silently merging
  // them would hide that.
  private def overlap(blocks: List[UnavailabilityBlock]): Option[String] =
    blocks
      .groupBy(_.dayOfWeek)
      .toList
      .sortBy(_._1)
      .flatMap { case (day, sameDay) =>
        sameDay
          .sortBy(_.fromPosition)
          .sliding(2)
          .collectFirst {
            case List(a, b) if b.fromPosition <= a.toPosition =>
              s"Zablokowane godziny nakładają się na siebie (dzień $day)"
          }
      }
      .headOption
