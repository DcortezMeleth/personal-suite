package bel.solver

import bel.domain.*
import bel.repository.{PlanRepository, SnapshotLoader}
import cats.effect.IO
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

class PlanGenerator(loader: SnapshotLoader, plans: PlanRepository, fet: FetRunner.Config):

  import PlanGenerator.Result

  def generate(schoolId: UUID): IO[Result] =
    loader.loadWithRooms(schoolId).flatMap {
      case None                    => IO.pure(Result.NoSchool)
      case Some((snapshot, rooms)) => generateFrom(schoolId, snapshot, rooms)
    }

  /**
   * The same, for a caller that has already shaped the school.
   *
   * The command line can narrow to the first few classes or pair hours up
   * before laying anything out; it hands in what it narrowed rather than
   * having it loaded again underneath, which would silently ignore the flags.
   */
  def generateFrom(schoolId: UUID, snapshot: SchoolSnapshot,
                   rooms: List[Room]): IO[Result] =
    for
      outcome <- FetRunner(snapshot, rooms, fet)
      result  <- keepIfSound(schoolId, rooms, outcome)
    yield result

  private def keepIfSound(schoolId: UUID, rooms: List[Room],
                          outcome: FetRunner.Outcome): IO[Result] =
    val placed = outcome.result.placed.size
    if !outcome.report.sound || !outcome.result.complete then
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
