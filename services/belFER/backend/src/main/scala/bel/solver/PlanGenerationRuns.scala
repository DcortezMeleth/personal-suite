package bel.solver

import cats.effect.{Fiber, IO, Ref}
import cats.effect.std.Supervisor
import java.time.Instant
import java.util.UUID
import org.typelevel.log4cats.slf4j.Slf4jLogger

/**
 * Generation runs, started and then asked about.
 *
 * Laying out a week takes a minute or two — assigning a room to every hour is
 * most of it, see the postscript to ADR 0004 — which is too long to hold a
 * request open for. Not because the browser cannot wait, but because every
 * hop in between has an opinion about how long it will: nginx cuts a reply off
 * at sixty seconds by default, and a connection dropped halfway through would
 * lose the answer to a run the backend had finished and saved. It will also
 * only get longer, because the search this times is FET being asked merely to
 * satisfy the rules, and optimising comes out of the same budget.
 *
 * So the run is started and then asked about. Kept in memory and keyed by
 * school, which is enough for the two things that matter: a second press while
 * one is already running joins it rather than starting another, and reloading
 * the page finds the run still going rather than losing it.
 *
 * What is deliberately *not* promised is that the record survives a restart.
 * It does not need to: a finished run has already written the plan, so the
 * durable answer is in the database either way, and all that is lost is being
 * told about it. A run in flight when the server stops is gone, which is the
 * honest outcome — nothing was saved.
 */
object PlanGenerationRuns:

  enum State:
    case Running
    case Finished(result: PlanGenerator.Result)
    case Failed(message: String)
    /** Stopped by the person who started it, rather than by a fault. */
    case Cancelled

  case class Run(
    startedAt: Instant,
    finishedAt: Option[Instant],
    state: State,
    /**
     * How far FET has got, while it is still going.
     *
     * Kept on the run rather than asked of FET, because the screen polls at
     * its own pace and the search reports at its own; the last thing said is
     * the right answer to give whenever the question comes.
     */
    progress: Option[FetRunner.Progress] = None,
    /**
     * Set when somebody has asked this run to stop. The search reads it once
     * a second and winds itself up; the run then settles as `Stopped` with
     * whatever FET managed to say about where it got to.
     */
    stopping: Boolean = false,
    /**
     * Kept so the run can be stopped: the fiber is the only handle on a
     * search already running. Not part of what a screen is told — PlanRoutes
     * builds its own view — and never compared, which is why `running` asks
     * the state rather than the whole record.
     */
    fiber: Option[Fiber[IO, Throwable, Unit]] = None
  ):
    def running: Boolean = state == State.Running

  def apply(generator: PlanGenerator, supervisor: Supervisor[IO]): IO[PlanGenerationRuns] =
    from(generator.generate, supervisor)

  /**
   * The same, over anything that can produce a result.
   *
   * What is worth testing here is the bookkeeping — that a second press joins
   * the first, that a finished run stops being running, that a thrown
   * exception becomes a reported failure rather than a lost fiber — and none
   * of that should need FET installed to check.
   */
  def from(generate: (UUID, FetRunner.Control) => IO[PlanGenerator.Result],
           supervisor: Supervisor[IO]): IO[PlanGenerationRuns] =
    Ref[IO].of(Map.empty[UUID, Run]).map(new PlanGenerationRuns(generate, supervisor, _))

class PlanGenerationRuns private (
  generate: (UUID, FetRunner.Control) => IO[PlanGenerator.Result],
  supervisor: Supervisor[IO],
  runs: Ref[IO, Map[UUID, PlanGenerationRuns.Run]]
):
  import PlanGenerationRuns.*

  private val log = Slf4jLogger.getLoggerFromName[IO]("bel.solver.PlanGenerationRuns")

  /**
   * Start one, or hand back the one already going.
   *
   * Pressing the button twice should not search twice: the second press is
   * somebody checking it is still working, and the honest answer is the run
   * that is already in flight.
   */
  def start(schoolId: UUID): IO[Run] =
    for
      now <- IO(Instant.now)
      fresh = Run(now, None, State.Running)
      decided <- runs.modify { all =>
                   all.get(schoolId) match
                     case Some(going) if going.running => (all, Left(going))
                     case _                            => (all + (schoolId -> fresh), Right(fresh))
                 }
      run <- decided match
               case Left(going) => IO.pure(going)
               case Right(started) =>
                 supervisor.supervise(run(schoolId)).flatMap { fiber =>
                   runs.update(_.updatedWith(schoolId)(_.map(_.copy(fiber = Some(fiber)))))
                     .as(started)
                 }
    yield run

  def status(schoolId: UUID): IO[Option[Run]] = runs.get.map(_.get(schoolId))

  /**
   * Stop a search somebody no longer wants.
   *
   * This used to cancel the fiber, which unwound FetRunner's hold on the
   * process and killed it — correct, and wasteful. FET answers SIGTERM by
   * writing down the furthest week it reached and its account of what
   * defeated it, and killing the fiber threw all of that away along with the
   * workspace it was written in.
   *
   * So the stop is asked for rather than imposed: the flag goes up, the
   * search reads it within the second, and the run settles as `Stopped`
   * carrying the diagnosis. Cancelling the fiber remains what happens if the
   * server goes down, and still kills the process; it is simply no longer the
   * ordinary path.
   */
  def cancel(schoolId: UUID): IO[Option[Run]] =
    runs.updateAndGet(_.updatedWith(schoolId)(_.map(run =>
      if run.running then run.copy(stopping = true) else run))).map(_.get(schoolId))

  private def run(schoolId: UUID): IO[Unit] =
    val control = FetRunner.Control(
      onProgress = note(schoolId),
      stopAsked = runs.get.map(_.get(schoolId).exists(_.stopping)))
    generate(schoolId, control).attempt.flatMap { outcome =>
      val state = outcome match
        case Right(result) => State.Finished(result)
        // The message is what the planner is shown, so it says what failed
        // rather than only that something did — most often this is FET not
        // being reachable the way configuration says it is.
        case Left(error) =>
          State.Failed(Option(error.getMessage).getOrElse(error.getClass.getName))
      // A failed run used to leave only that one line, on the screen of
      // whoever was looking (REVIEW_2026-11-21 F9). The stack trace goes to
      // the log.
      val logged = outcome.left.toOption.fold(IO.unit)(error =>
        log.error(error)(s"generation failed for school $schoolId"))
      logged *> settle(schoolId, state)
    }.onCancel(settle(schoolId, State.Cancelled))

  /** Record how far the search has got, for whoever asks next. */
  private def note(schoolId: UUID)(progress: FetRunner.Progress): IO[Unit] =
    runs.update(_.updatedWith(schoolId)(_.map(_.copy(progress = Some(progress)))))

  private def settle(schoolId: UUID, state: State): IO[Unit] =
    IO(Instant.now).flatMap(at =>
      runs.update(_.updatedWith(schoolId)(_.map(_.copy(
        finishedAt = Some(at), state = state, fiber = None)))))
