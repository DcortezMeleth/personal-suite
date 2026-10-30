package bel.solver

import cats.effect.unsafe.implicits.global
import org.scalatest.funsuite.AnyFunSuite

/**
 * The dice, written down.
 *
 * FET refuses a triple that is all zeroes, and silently takes anything else —
 * so a seed generator that can produce one is a run that fails rarely and
 * inexplicably. The ranges are FET's own, from its help text.
 */
class FetSeedSpec extends AnyFunSuite:

  private val first = 4294967086L
  private val second = 4294944442L

  test("a seed survives being written down and read back"):
    val seed = FetSeed(11, 22, 33, 44, 55, 66)
    assert(FetSeed.parse(seed.text) === Some(seed))

  test("anything that is not six numbers is not a seed"):
    for junk <- List("", "1,2,3", "1,2,3,4,5", "1,2,3,4,5,6,7", "a,b,c,d,e,f", "1;2;3;4;5;6") do
      assert(FetSeed.parse(junk).isEmpty, s"'$junk' was read as a seed")

  test("the command line carries all six components"):
    val args = FetSeed(1, 2, 3, 4, 5, 6).args
    assert(args.size === 6)
    assert(args === List("--randomseeds10=1", "--randomseeds11=2", "--randomseeds12=3",
      "--randomseeds20=4", "--randomseeds21=5", "--randomseeds22=6"))

  /** FET rejects either triple being all zeroes, so neither may ever be. */
  test("a generated seed is always in range, and never all-zero"):
    val seeds = List.fill(500)(FetSeed.random.unsafeRunSync())
    for s <- seeds do
      assert(s.s10 >= 0 && s.s10 <= first, s.toString)
      assert(s.s11 >= 0 && s.s11 <= first, s.toString)
      assert(s.s12 >= 0 && s.s12 <= first, s.toString)
      assert(s.s20 >= 0 && s.s20 <= second, s.toString)
      assert(s.s21 >= 0 && s.s21 <= second, s.toString)
      assert(s.s22 >= 0 && s.s22 <= second, s.toString)
      assert(!(s.s10 == 0 && s.s11 == 0 && s.s12 == 0), "first triple all zero")
      assert(!(s.s20 == 0 && s.s21 == 0 && s.s22 == 0), "second triple all zero")

  test("two seeds are not the same seed"):
    val seeds = List.fill(50)(FetSeed.random.unsafeRunSync().text)
    assert(seeds.distinct.sizeIs == seeds.size, "generated the same dice twice")
