package bel.solver

import bel.domain.*
import bel.repository.{PlanRepository, SnapshotLoader}
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.parallel.*
import java.util.UUID

/**
 * Laying out a week, from the school's data to a saved plan.
 *
 * This was only ever reachable from the command line, which made generation
 * something the planner needed a terminal for — the gap between a program that
 * works and one somebody can use. It is the same sequence either way: write
 * the school out, let FET search, read the answer back, hold it against our
 * own rules, and keep it only if it passes.
 *
 * Synchronous for now, which is a decision with a shelf life. ADR 0004 put
 * FET at two seconds for a school this size; measured on the real twenty-five
 * classes it was thirty-five seconds once and a hundred and fifteen the next
 * time, same input, same fifteen rules satisfied. That is still a request a
 * person can wait out, and the progress reporting and early stop REQUIREMENTS
 * §2.11 designed around a budget of hours would have little to report — but
 * it is not two seconds, and the spread says the number is not stable.
 *
 * Asking FET to *optimise* rather than merely satisfy is what will end this:
 * fewer teacher windows, evener days, all paid for in search time. Everything
 * here returns a value rather than printing one and the timeout is already
 * configuration, so when that day comes what changes is the caller, not this.
 */
object PlanGenerator:

  /** What came of asking. */
  enum Result:
    case NoSchool
    /**
     * FET answered, and the answer breaks a rule or leaves lessons unplaced.
     * Reported, never saved: a week nobody has held against the rules is not
     * a timetable, and one that fails them is not the school's.
     */
    case Rejected(report: PlanCheck.Report, placed: Int, unplaced: Int, millis: Long)
    case Saved(plan: Plan, report: PlanCheck.Report, placed: Int, millis: Long)
    /**
     * Called off by the person who started it.
     *
     * No plan comes of this, and that is not a shortcoming being worked
     * around. FET builds a week by placing activities one at a time, so what
     * it has when interrupted is a partial assignment — a few hundred of a
     * thousand lessons — and a timetable missing half its lessons is not a
     * worse timetable, it is not one. What is worth keeping is its account of
     * where it got stuck, which is the thing a person stopping a run actually
     * wants to know.
     */
    case Stopped(diagnosis: Option[FetRunner.Diagnosis], millis: Long)

  /**
   * Run several searches at once and keep whichever gets there first.
   *
   * Over any search at all rather than over FET specifically, because what is
   * worth pinning down here is the race — that the first good week wins, that
   * the losers are cancelled, that one broken search does not sink the rest,
   * that a hopeless school still reports the attempt which got furthest — and
   * none of that should need FET installed to check.
   *
   * The first sound and complete week completes the deferred, which wins the
   * race and cancels everything still going; cancelling a search unwinds its
   * hold on its process, which kills it.
   */
  def race(workers: Int, control: FetRunner.Control,
           search: FetRunner.Control => IO[FetRunner.Outcome]): IO[FetRunner.Outcome] =
    val many = workers.max(1)
    if many == 1 then search(control)
    else
      for
        // Each search keeps its own high-water mark; the screen wants the best
        // of them, and it must not go backwards because one worker happened to
        // report after another that was further along.
        best  <- Ref[IO].of(0)
        also  <- Ref[IO].of(List.empty[FetRunner.Outcome])
        broke <- Ref[IO].of(List.empty[Throwable])
        won   <- Deferred[IO, FetRunner.Outcome]
        watch  = (p: FetRunner.Progress) =>
                   best.modify(was => if p.placed > was then (p.placed, true) else (was, false))
                     .flatMap(IO.whenA(_)(control.onProgress(p)))
        // One search failing is not the generation failing: others are still
        // going, and what broke is usually local to the process — a container
        // that would not start, a workspace that could not be written. The
        // failure is kept for the one case where it is the answer, which is
        // every search failing.
        one    = search(control.copy(onProgress = watch)).attempt.flatMap {
                   case Right(o) if o.report.sound && o.result.complete => won.complete(o).void
                   case Right(o)    => also.update(o :: _)
                   case Left(error) => broke.update(error :: _)
                 }
        // Whichever happens first: somebody wins, or they all give up.
        ended <- List.fill(many)(one).parSequence_.race(won.get)
        out   <- ended match
                   case Right(winner) => IO.pure(winner)
                   case Left(_)       => bestOfTheRest(also, broke)
      yield out

  /**
   * What to report when no search produced a usable week.
   *
   * The one that placed the most got furthest before sticking, so its account
   * of what defeated it is the least speculative. A stopped search outranks
   * the rest regardless: somebody asked for that and is waiting to be told
   * about it, not about a sibling that happened to fail differently.
   */
  private def bestOfTheRest(also: Ref[IO, List[FetRunner.Outcome]],
                            broke: Ref[IO, List[Throwable]]): IO[FetRunner.Outcome] =
    for
      all     <- also.get
      errors  <- broke.get
      stopped  = all.filter(_.stopped)
      pick     = (if stopped.nonEmpty then stopped else all)
                   .maxByOption(o => o.diagnosis.map(_.placed).getOrElse(o.result.placed.size))
      // Every search failed, so the failure is the answer after all.
      outcome <- IO.fromOption(pick)(errors.headOption.getOrElse(
                   RuntimeException("Żadne z układań nie zwróciło wyniku")))
    yield outcome

class PlanGenerator(loader: SnapshotLoader, plans: PlanRepository, fet: FetRunner.Config):

  import PlanGenerator.Result

  def generate(schoolId: UUID,
               control: FetRunner.Control = FetRunner.Control()): IO[Result] =
    loader.loadWithRooms(schoolId).flatMap {
      case None                    => IO.pure(Result.NoSchool)
      case Some((snapshot, rooms)) => generateFrom(schoolId, snapshot, rooms, control)
    }

  /**
   * The same, for a caller that has already shaped the school.
   *
   * The command line can narrow to the first few classes or pair hours up
   * before laying anything out; it hands in what it narrowed rather than
   * having it loaded again underneath, which would silently ignore the flags.
   */
  def generateFrom(schoolId: UUID, snapshot: SchoolSnapshot, rooms: List[Room],
                   control: FetRunner.Control = FetRunner.Control()): IO[Result] =
    for
      outcome <- race(snapshot, rooms, control)
      result  <- keepIfSound(schoolId, rooms, outcome)
    yield result

  /**
   * Search the same week several times over, on different dice, and keep
   * whichever gets there first.
   *
   * This is the only way to spend more than one core on one timetable: FET's
   * generation is single-threaded and takes no flag for cores or memory. It
   * is also worth far more than the cores it costs, because a randomised
   * backtracking search has a heavy-tailed runtime — the seed decides the
   * order activities are tried in, and so decides whether this particular
   * week is three seconds of work or fifteen hours of thrashing. Racing
   * several cuts the bad tail off instead of dividing the average.
   *
   * The first sound and complete week wins and the rest are cancelled, which
   * unwinds their hold on their processes and kills them. If none wins, the
   * fallback is the one that got furthest, so a school that cannot be laid
   * out at all still reports the best account of why.
   */
  private def race(snapshot: SchoolSnapshot, rooms: List[Room],
                   control: FetRunner.Control): IO[FetRunner.Outcome] =
    PlanGenerator.race(fet.workers, control, c =>
      FetRunner(snapshot, rooms, fet, control = c))

  private def keepIfSound(schoolId: UUID, rooms: List[Room],
                          outcome: FetRunner.Outcome): IO[Result] =
    val placed = outcome.result.placed.size
    if outcome.stopped then
      IO.pure(Result.Stopped(outcome.diagnosis, outcome.millis))
    else if !outcome.report.sound || !outcome.result.complete then
      IO.pure(Result.Rejected(outcome.report, placed, outcome.result.unplaced, outcome.millis))
    else
      for
        today <- IO(java.time.LocalDate.now)
        saved <- plans.save(schoolId, NewPlan(
                   name = s"FET $today",
                   madeBy = "fet",
                   tookMillis = Some(outcome.millis.toInt),
                   rulesKept = Some(outcome.report.kept),
                   rulesTotal = Some(outcome.report.rules.size),
                   // So this exact week can be had again, and so that two
                   // settings can be compared from the same dice.
                   randomSeed = Some(outcome.seed.text),
                   placements = FetRunner.placements(outcome.result, rooms)))
        // The planner pressed generate, so the new week is the one they want
        // to look at. The old one stays in the list to go back to.
        _ <- plans.makeCurrent(schoolId, saved.id)
      yield Result.Saved(saved, outcome.report, placed, outcome.millis)
