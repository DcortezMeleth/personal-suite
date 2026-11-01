package bel.solver

import bel.domain.{Plan, PlanCheck}
import cats.effect.{Deferred, IO}
import cats.effect.std.Supervisor
import cats.effect.unsafe.implicits.global
import java.time.OffsetDateTime
import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.duration.*

/**
 * The bookkeeping around a run that outlives its request.
 *
 * Laying out a week takes a minute or two, so it is started and then asked
 * about rather than waited on. What that buys has to actually hold: pressing
 * the button twice must not search twice, a page reloaded mid-run must find
 * the run, and a generation that throws must come back as a reported failure
 * rather than a fiber that died quietly leaving the screen saying "running"
 * for ever.
 *
 * None of that needs FET, so none of it is tested with FET.
 */
class PlanGenerationRunsSpec extends AnyFunSuite:

  private val school = UUID.randomUUID
  private val emptyReport = PlanCheck.Report(Nil)

  private def saved = PlanGenerator.Result.Saved(
    Plan(UUID.randomUUID, school, "FET", OffsetDateTime.now, "fet", None, Some(1), Some(1), None),
    emptyReport, placed = 10, millis = 1234)

  /** Runs a body with a supervisor, the way Main does. */
  private def withRuns[A](generate: UUID => IO[PlanGenerator.Result])
                         (body: PlanGenerationRuns => IO[A]): A =
    Supervisor[IO].use(s =>
      PlanGenerationRuns.from((id, _) => generate(id), s).flatMap(body)).unsafeRunSync()

  /** Waits for a condition rather than sleeping a guessed amount. */
  private def until(check: IO[Boolean]): IO[Unit] =
    check.flatMap(ok => if ok then IO.unit else IO.sleep(10.millis) *> until(check))

  test("a school that has never generated has nothing to report"):
    val run = withRuns(_ => IO.pure(saved))(_.status(school))
    assert(run.isEmpty)

  test("a started run is running, and then is not"):
    val result = withRuns(_ => IO.pure(saved)) { runs =>
      for
        started <- runs.start(school)
        _       <- until(runs.status(school).map(_.exists(!_.running)))
        after   <- runs.status(school)
      yield (started, after)
    }
    val (started, after) = result
    assert(started.running, "start answers with a run in flight")
    assert(after.exists(!_.running))
    assert(after.flatMap(_.finishedAt).isDefined, "a finished run says when")
    assert(after.map(_.state).exists {
      case PlanGenerationRuns.State.Finished(_: PlanGenerator.Result.Saved) => true
      case _                                                                => false
    })

  /**
   * The one that matters for a button somebody may press twice. The second
   * press must join the first rather than start another search — FET is a
   * minute of CPU, and two of them would also race to save a plan.
   */
  test("pressing twice while one is running joins it rather than starting another"):
    val (calls, first, second) = withRuns_counted
    assert(calls == 1, s"generation ran $calls times, expected once")
    assert(first.startedAt == second.startedAt, "the second press got the first run back")

  private def withRuns_counted =
    (for
      gate   <- Deferred[IO, Unit]
      counter <- IO.ref(0)
      result <- Supervisor[IO].use { s =>
                  PlanGenerationRuns.from(
                    (_, _) => counter.update(_ + 1) *> gate.get.as(saved), s).flatMap { runs =>
                    for
                      first  <- runs.start(school)
                      second <- runs.start(school)
                      _      <- gate.complete(())
                      _      <- until(runs.status(school).map(_.exists(!_.running)))
                      calls  <- counter.get
                    yield (calls, first, second)
                  }
                }
     yield result).unsafeRunSync()

  test("once a run has finished, pressing again starts a new one"):
    val (first, second) = withRuns(_ => IO.pure(saved)) { runs =>
      for
        first  <- runs.start(school)
        _      <- until(runs.status(school).map(_.exists(!_.running)))
        second <- runs.start(school)
        _      <- until(runs.status(school).map(_.exists(!_.running)))
      yield (first, second)
    }
    assert(first.running && second.running, "each press begins a run of its own")

  /**
   * Stopping has to reach the search, not merely relabel it.
   *
   * It is asked for rather than imposed: the registry raises a flag, the
   * search reads it and winds itself up. That is what lets FET write down
   * where it got to, and it is the part worth pinning — a stop that does not
   * reach the search would leave the run saying "running" for ever.
   */
  test("a run can be stopped, and the search is the one that sees it"):
    val after = withRuns_cancelled
    assert(after.map(_.state) === Some(
      PlanGenerationRuns.State.Finished(stoppedAt432)), after.toString)
    assert(after.flatMap(_.finishedAt).isDefined, "a stopped run says when it stopped")
    assert(after.exists(!_.running))

  private val stoppedAt432 = PlanGenerator.Result.Stopped(
    Some(FetRunner.Diagnosis(432, 1331, Some("Kowalska Anna - Matematyka - 1A"))), millis = 7)

  private def withRuns_cancelled =
    (for
      reached <- Deferred[IO, Unit]
      result <- Supervisor[IO].use { s =>
                  // Stands in for FET: runs until asked to stop, then reports
                  // where it got to, exactly as the real search does.
                  val search = (_: UUID, control: FetRunner.Control) =>
                    reached.complete(()) *>
                      until(control.stopAsked).as(stoppedAt432)
                  PlanGenerationRuns.from(search, s).flatMap { runs =>
                    for
                      _     <- runs.start(school)
                      _     <- reached.get
                      _     <- runs.cancel(school)
                      _     <- until(runs.status(school).map(_.exists(!_.running)))
                      after <- runs.status(school)
                    yield after
                  }
                }
     yield result).unsafeRunSync()

  test("stopping something already finished changes nothing"):
    val after = withRuns(_ => IO.pure(saved)) { runs =>
      for
        _      <- runs.start(school)
        _      <- until(runs.status(school).map(_.exists(!_.running)))
        before <- runs.status(school)
        _      <- runs.cancel(school)
        after  <- runs.status(school)
      yield (before, after)
    }
    assert(after._1.map(_.state) === after._2.map(_.state))

  test("a generation that throws is reported, not lost"):
    val after = withRuns(_ => IO.raiseError(RuntimeException("fet-cl: not found"))) { runs =>
      runs.start(school) *>
        until(runs.status(school).map(_.exists(!_.running))) *>
        runs.status(school)
    }
    assert(after.map(_.state).exists {
      case PlanGenerationRuns.State.Failed(message) => message.contains("fet-cl")
      case _                                        => false
    }, s"expected a reported failure, got ${after.map(_.state)}")
    assert(after.flatMap(_.finishedAt).isDefined)

  test("two schools generate independently"):
    val other = UUID.randomUUID
    val (a, b) = withRuns(_ => IO.pure(saved)) { runs =>
      for
        _ <- runs.start(school)
        _ <- until(runs.status(school).map(_.exists(!_.running)))
        a <- runs.status(school)
        b <- runs.status(other)
      yield (a, b)
    }
    assert(a.isDefined)
    assert(b.isEmpty, "one school's run is not another's")

  /**
   * The search reports how far it has got; the screen asks at its own pace.
   * What has to hold is that the last thing said is what the next question
   * gets back — a run wedged at the same number is the signal, so it must not
   * be lost between polls.
   */
  test("progress reported by the search is there for whoever asks next"):
    val seen = (for
      gate   <- Deferred[IO, Unit]
      result <- Supervisor[IO].use { s =>
                  PlanGenerationRuns.from(
                    (_, control) =>
                      control.onProgress(FetRunner.Progress(432, 1331)) *> gate.get.as(saved), s)
                    .flatMap { runs =>
                      for
                        _       <- runs.start(school)
                        _       <- until(runs.status(school).map(_.exists(_.progress.isDefined)))
                        running <- runs.status(school)
                        _       <- gate.complete(())
                        _       <- until(runs.status(school).map(_.exists(!_.running)))
                      yield running
                    }
                }
     yield result).unsafeRunSync()
    assert(seen.flatMap(_.progress) === Some(FetRunner.Progress(432, 1331)))
