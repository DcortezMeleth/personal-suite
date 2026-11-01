package bel.solver

import bel.domain.PlanCheck
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.duration.*

/**
 * Several searches at once, on different dice, first good week wins.
 *
 * This is the only way to spend more than one core on one timetable — FET's
 * generation is single-threaded — and it is worth more than the cores it
 * costs, because the seed decides whether a given week is three seconds of
 * work or fifteen hours of thrashing.
 *
 * What has to hold is the race itself, so none of it is tested with FET.
 */
class PlanRaceSpec extends AnyFunSuite:

  private val dice = FetSeed(1, 2, 3, 4, 5, 6)

  private def outcome(unplaced: Int, stopped: Boolean = false,
                      got: Option[Int] = None) =
    FetRunner.Outcome(
      FetImport.Result(Nil, unplaced), PlanCheck.Report(Nil), millis = 1, seed = dice,
      stopped = stopped,
      diagnosis = got.map(FetRunner.Diagnosis(_, 1331, Some("Kowalska - Matematyka - 1A"))))

  /** A complete, sound week: the only thing that wins a race. */
  private val good = outcome(unplaced = 0)

  test("the first good week wins and the rest are called off"):
    val (winner, cancelled) = (for
      stoppedEarly <- Ref[IO].of(0)
      started      <- Ref[IO].of(0)
      search = (_: FetRunner.Control) =>
        started.modify(n => (n + 1, n)).flatMap {
          // One finishes at once; the others would run for a very long time
          // and must be cancelled rather than waited for.
          case 0 => IO.sleep(20.millis).as(good)
          case _ => (IO.sleep(1.hour).as(good)).onCancel(stoppedEarly.update(_ + 1))
        }
      won  <- PlanGenerator.race(4, FetRunner.Control(), search)
      gone <- stoppedEarly.get
    yield (won, gone)).unsafeRunSync()

    assert(winner === good)
    assert(cancelled == 3, s"expected the three losers to be cancelled, $cancelled were")

  test("one search breaking does not sink the others"):
    val won = (for
      turn <- Ref[IO].of(0)
      search = (_: FetRunner.Control) =>
        turn.modify(n => (n + 1, n)).flatMap {
          case 0 => IO.raiseError(RuntimeException("docker: no such image"))
          case _ => IO.sleep(20.millis).as(good)
        }
      out <- PlanGenerator.race(3, FetRunner.Control(), search)
    yield out).unsafeRunSync()
    assert(won === good)

  test("when every search fails, the failure is the answer"):
    val boom = intercept[RuntimeException](
      PlanGenerator.race(3, FetRunner.Control(),
        _ => IO.raiseError(RuntimeException("docker: no such image"))).unsafeRunSync())
    assert(boom.getMessage.contains("no such image"))

  /**
   * A school that cannot be laid out at all is the case where the report
   * matters most, so it must come from the attempt that learned the most.
   */
  test("with no winner, the search that got furthest is the one reported"):
    val reported = (for
      turn <- Ref[IO].of(0)
      search = (_: FetRunner.Control) =>
        turn.modify(n => (n + 1, n)).map(n => outcome(unplaced = 900, got = Some(100 * n)))
      out <- PlanGenerator.race(4, FetRunner.Control(), search)
    yield out).unsafeRunSync()
    assert(reported.diagnosis.map(_.placed) === Some(300), "the furthest attempt wins the report")

  test("a stopped search outranks one that merely failed"):
    val reported = (for
      turn <- Ref[IO].of(0)
      search = (_: FetRunner.Control) =>
        turn.modify(n => (n + 1, n)).map {
          // The one that got furthest was not the one somebody stopped.
          case 0 => outcome(unplaced = 900, got = Some(999))
          case _ => outcome(unplaced = 900, stopped = true, got = Some(432))
        }
      out <- PlanGenerator.race(3, FetRunner.Control(), search)
    yield out).unsafeRunSync()
    assert(reported.stopped, "somebody asked for a stop and is waiting to hear about it")
    assert(reported.diagnosis.map(_.placed) === Some(432))

  /**
   * Each search has its own high-water mark and they report independently, so
   * the number on the screen would jump backwards whenever a worker behind
   * the leader reported next.
   *
   * The order is forced rather than raced: the trailing worker is held until
   * the leader has reported, because left to chance the two reports land in
   * whichever order the scheduler picks and the test passes half the time
   * without the guard it exists to pin.
   */
  test("progress never goes backwards, whichever worker reports"):
    val seen = (for
      saw    <- Ref[IO].of(List.empty[Int])
      ahead  <- cats.effect.Deferred[IO, Unit]
      turn   <- Ref[IO].of(0)
      control = FetRunner.Control(onProgress = p => saw.update(p.placed :: _))
      search  = (c: FetRunner.Control) =>
        turn.modify(n => (n + 1, n)).flatMap {
          // The leader gets well ahead, and says so.
          case 0 => c.onProgress(FetRunner.Progress(800, 1331)) *>
                      ahead.complete(()).as(outcome(unplaced = 900))
          // The straggler only reports once the leader has, so its smaller
          // number is unambiguously a step backwards.
          case _ => ahead.get *> c.onProgress(FetRunner.Progress(50, 1331))
                      .as(outcome(unplaced = 900))
        }
      _    <- PlanGenerator.race(2, control, search)
      list <- saw.get
    yield list.reverse).unsafeRunSync()

    assert(seen === List(800), s"the straggler's 50 should never have been shown: $seen")
