package bel.solver

import cats.effect.{IO, Ref}
import cats.effect.std.Supervisor
import java.time.Instant
import java.util.UUID

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

  case class Run(startedAt: Instant, finishedAt: Option[Instant], state: State):
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
  def from(generate: UUID => IO[PlanGenerator.Result],
           supervisor: Supervisor[IO]): IO[PlanGenerationRuns] =
    Ref[IO].of(Map.empty[UUID, Run]).map(new PlanGenerationRuns(generate, supervisor, _))

class PlanGenerationRuns private (
  generate: UUID => IO[PlanGenerator.Result],
  supervisor: Supervisor[IO],
  runs: Ref[IO, Map[UUID, PlanGenerationRuns.Run]]
):
  import PlanGenerationRuns.*

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
               case Right(started) => supervisor.supervise(run(schoolId)).as(started)
    yield run

  def status(schoolId: UUID): IO[Option[Run]] = runs.get.map(_.get(schoolId))

  private def run(schoolId: UUID): IO[Unit] =
    generate(schoolId).attempt.flatMap { outcome =>
      val state = outcome match
        case Right(result) => State.Finished(result)
        // The message is what the planner is shown, so it says what failed
        // rather than only that something did — most often this is FET not
        // being reachable the way configuration says it is.
        case Left(error) =>
          State.Failed(Option(error.getMessage).getOrElse(error.getClass.getName))
      IO(Instant.now).flatMap(at =>
        runs.update(_.updatedWith(schoolId)(_.map(_.copy(finishedAt = Some(at), state = state)))))
    }
