package bel.domain

import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}
import java.util.UUID

case class SchoolClass(
  id: UUID,
  year: Int,
  letter: String,
  specialisation: Option[String],
  homeroomTeacherId: Option[UUID],
  studentCount: Option[Int],
  girlCount: Option[Int]
):
  /** How the school writes it: "1A", "3F". */
  def name: String = s"$year$letter"

object SchoolClass:
  given Encoder[SchoolClass] = Encoder.forProduct8(
    "id", "year", "letter", "specialisation", "homeroomTeacherId",
    "studentCount", "girlCount", "name"
  )(c => (c.id, c.year, c.letter, c.specialisation, c.homeroomTeacherId,
          c.studentCount, c.girlCount, c.name))
  given Decoder[SchoolClass] = deriveDecoder

case class SchoolClassInput(
  year: Int,
  letter: String,
  specialisation: Option[String],
  homeroomTeacherId: Option[UUID],
  studentCount: Option[Int],
  girlCount: Option[Int]
)

object SchoolClassInput:
  given Encoder[SchoolClassInput] = deriveEncoder
  given Decoder[SchoolClassInput] = deriveDecoder

/**
 * Letters are given explicitly rather than as a count, because the school's
 * classes are not contiguous — the real arkusz has no 1B — and generating
 * A..H to then delete one is worse than naming the seven that exist.
 */
case class GenerateClasses(year: Int, letters: List[String])

object GenerateClasses:
  given Decoder[GenerateClasses] = deriveDecoder

object SchoolClassValidation:

  def normaliseLetter(letter: String): String = letter.trim.toUpperCase

  def validate(input: SchoolClassInput, schoolYears: Int): Either[String, Unit] =
    val letter = normaliseLetter(input.letter)
    if letter.isEmpty then Left("Litera oddziału nie może być pusta")
    else if !letter.forall(_.isLetter) then Left("Litera oddziału może zawierać tylko litery")
    else if input.year < 1 || input.year > schoolYears then
      Left(s"Rocznik musi mieścić się w przedziale 1–$schoolYears")
    else if input.studentCount.exists(_ < 0) || input.girlCount.exists(_ < 0) then
      Left("Liczba uczniów nie może być ujemna")
    else if input.studentCount.zip(input.girlCount).exists((all, girls) => girls > all) then
      Left("Liczba dziewcząt nie może przekraczać liczby uczniów")
    else Right(())

  def validateGeneration(cmd: GenerateClasses, schoolYears: Int): Either[String, Unit] =
    val letters = cmd.letters.map(normaliseLetter).filter(_.nonEmpty)
    if cmd.year < 1 || cmd.year > schoolYears then
      Left(s"Rocznik musi mieścić się w przedziale 1–$schoolYears")
    else if letters.isEmpty then Left("Podaj litery oddziałów")
    else if letters.distinct.size != letters.size then Left("Litery oddziałów nie mogą się powtarzać")
    else if !letters.forall(_.forall(_.isLetter)) then
      Left("Litera oddziału może zawierać tylko litery")
    else Right(())
