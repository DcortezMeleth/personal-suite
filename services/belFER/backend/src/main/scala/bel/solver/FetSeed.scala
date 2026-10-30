package bel.solver

import cats.effect.IO

/**
 * The dice FET rolls.
 *
 * Its search is seeded and, left alone, seeds itself from the clock — the same
 * file generated twice gives two different weeks. FET's own manual promises
 * that the same six components, the same input and the same version return the
 * same timetable, which was checked before this type existed: two runs of one
 * file differed in three thousand lines unseeded and were identical pinned.
 *
 * Chosen per run rather than fixed, because a re-run finding a better week is
 * worth keeping — and recorded with the plan, so that any week can be had
 * again, and so that two settings can be compared from the same dice instead
 * of the setting and the luck moving together.
 *
 * The ranges are FET's, and it rejects all three of a triple being zero.
 */
case class FetSeed(s10: Long, s11: Long, s12: Long, s20: Long, s21: Long, s22: Long):

  def args: List[String] = List(
    s"--randomseeds10=$s10", s"--randomseeds11=$s11", s"--randomseeds12=$s12",
    s"--randomseeds20=$s20", s"--randomseeds21=$s21", s"--randomseeds22=$s22")

  /** How it is kept beside the plan: six numbers, in order. */
  def text: String = List(s10, s11, s12, s20, s21, s22).mkString(",")

object FetSeed:

  /** FET's stated maxima for the two triples. */
  private val FirstMax = 4294967086L
  private val SecondMax = 4294944442L

  def random: IO[FetSeed] = IO {
    val rng = scala.util.Random
    // One past zero in each triple, so neither can come out all-zero — which
    // FET refuses — without biasing the rest of the range.
    FetSeed(
      1L + rng.nextLong(FirstMax), rng.nextLong(FirstMax + 1), rng.nextLong(FirstMax + 1),
      1L + rng.nextLong(SecondMax), rng.nextLong(SecondMax + 1), rng.nextLong(SecondMax + 1))
  }

  def parse(text: String): Option[FetSeed] =
    text.split(',').toList.map(_.trim.toLongOption) match
      case List(Some(a), Some(b), Some(c), Some(d), Some(e), Some(f)) =>
        Some(FetSeed(a, b, c, d, e, f))
      case _ => None
