package bel.solver

import bel.db.Database
import bel.domain.*
import bel.solver.TimetableProblem.Audience
import bel.repository.*
import cats.effect.*
import cats.syntax.foldable.*
import cats.syntax.traverse.*
import com.typesafe.config.ConfigFactory
import java.util.UUID
import scala.jdk.CollectionConverters.*

/**
 * Runs the solver over a school's real data and prints what came back.
 *
 * Phase 3.4 turns generation into an async job with progress and early stop.
 * Until then this is how a plan actually gets produced — and it stays useful
 * afterwards, because when a run comes back infeasible the first question is
 * whether it is the data or the model, and this answers it without a browser.
 *
 *   bazel run //services/belFER/backend:solve -- <schoolId> [seconds] [workers]
 */
object SolveCli extends IOApp:

  override def run(args: List[String]): IO[ExitCode] =
    args match
      case Nil => IO.println("usage: solve <schoolId> [seconds] [workers]").as(ExitCode(2))
      case schoolId :: rest =>
        val diagnose = rest.contains("--diagnose")
        // --limit N keeps only the first N classes and whatever belongs to
        // them. When a whole school comes back infeasible, the quickest
        // question is how much of it still is.
        val limit = rest.collectFirst { case s if s.startsWith("--limit=") => s.drop(8).toInt }
        // --doubles pairs single hours up before solving, without saving it.
        // The arkusz states hours and never their shape, so an import is
        // almost all single lessons — far more things to place than a real
        // timetable has. This asks what the count is costing us.
        val doubles = rest.contains("--doubles")
        // --warm solves the school a handful of classes at a time and hands
        // the result to the full run as a starting point.
        val warm = rest.collectFirst { case s if s.startsWith("--warm=") => s.drop(7).toInt }
        // --apart=N changes what an idle half costs, to tell "cannot be done"
        // from "could be done but not cheaply".
        val apart = rest.collectFirst { case s if s.startsWith("--apart=") => s.drop(8).toInt }
        // --rooms=N pretends there are N ordinary rooms. Nothing is saved; it
        // answers whether the room count is what the plan is straining against.
        val pretendRooms = rest.collectFirst { case s if s.startsWith("--rooms=") => s.drop(8).toInt }
        // --shape settles how many lessons each class has each day before
        // asking where anything goes.
        val shaped = rest.collectFirst {
          case "--shape"                           => "even"
          case s if s.startsWith("--shape=")       => s.drop(8)
        }
        // --day=N fills one day of the week and leaves the rest alone. The
        // literature settles on this decomposition for good reason: no gaps
        // for a class is a rule about a single day, so a day on its own is a
        // fifth of the problem that still carries the rule that costs us most.
        val onlyDay = rest.collectFirst { case s if s.startsWith("--day=") => s.drop(6).toInt }
        // --week does the same five times over, keeping each day as it goes.
        val week = rest.contains("--week")
        // A rule is kept on the day it was proved on. Carrying it across the
        // week converges faster and is, somewhere, wrong: fifteen classes
        // produce a timetable when rules stay put and are proved to have no
        // week when they travel, which cannot both be true of sound rules.
        // --all-days puts it back for anyone chasing that.
        val oneDay = !rest.contains("--all-days")
        val literal = rest.contains("--literal")
        // --fet=<plik> writes the school out for FET instead of solving it.
        val fet = rest.collectFirst { case s if s.startsWith("--fet=") => s.drop(6) }
        // --read=<plik> takes FET's answer back, by re-deriving what we sent.
        val readBack = rest.collectFirst { case s if s.startsWith("--read=") => s.drop(7) }
        // --generate lays the week out with FET and keeps it, if it holds up.
        val generate = rest.contains("--generate")
        // --import=<plik> wczytuje arkusz od nowa, bez stawiania serwera.
        val reimport = rest.collectFirst { case s if s.startsWith("--import=") => s.drop(9) }
        // --weeks=od-do zawęża import do jednego semestru, jak robi to UI.
        val weeks = rest.collectFirst { case s if s.startsWith("--weeks=") => s.drop(8) }
          .flatMap(_.split("-") match
            case Array(a, b) => for x <- a.toIntOption; y <- b.toIntOption
                                yield bel.importer.ArkuszMapper.WeekRange(x, y)
            case _ => None)
          .getOrElse(bel.importer.ArkuszMapper.WeekRange(1, 19))
        // --slack=N lets a day run N hours either side of its shape.
        val slack = rest.collectFirst { case s if s.startsWith("--slack=") => s.drop(8).toInt }
          .getOrElse(0)
        // --spread settles which day every lesson falls on before any of them
        // is given a time, so a day cannot spend what a later day needs.
        val spread = rest.contains("--spread")
        // --rounds=N caps how many times the plan may be corrected.
        val rounds = rest.collectFirst { case s if s.startsWith("--rounds=") => s.drop(9).toInt }
          .getOrElse(30)
        // --relax=A,B drops named rules, to find out which one a day is
        // actually failing on rather than guessing.
        val relaxed = rest.collectFirst { case s if s.startsWith("--relax=") => s.drop(8) }
          .toList.flatMap(_.split(",")).map(TimetableProblem.Rule.valueOf).toSet
        val plain = rest.filterNot(_.startsWith("--"))
        val seconds = plain.headOption.flatMap(_.toLongOption).getOrElse(300L)
        val workers = plain.drop(1).headOption.flatMap(_.toIntOption).getOrElse(0)
        solve(UUID.fromString(schoolId), seconds, workers, diagnose, limit, doubles, warm, apart,
          pretendRooms, shaped, onlyDay, week, slack, spread, relaxed, rounds, oneDay, literal, fet, readBack, generate,
          reimport, weeks)

  private def solve(schoolId: UUID, seconds: Long, workers: Int, diagnose: Boolean,
                    limit: Option[Int], doubles: Boolean, warm: Option[Int],
                    apart: Option[Int], pretendRooms: Option[Int], shaped: Option[String],
                    onlyDay: Option[Int], week: Boolean, slack: Int,
                    spread: Boolean, relaxed: Set[TimetableProblem.Rule],
                    rounds: Int, oneDay: Boolean, literal: Boolean,
                    fet: Option[String], readBack: Option[String],
                    generate: Boolean, reimport: Option[String],
                    weeks: bel.importer.ArkuszMapper.WeekRange): IO[ExitCode] =
    val config = ConfigFactory.load()
    Database.transactor(config).use { xa =>
      val loader = SnapshotLoader(
        SchoolRepository(xa), SchoolClassRepository(xa), TeacherRepository(xa),
        SubjectRepository(xa), LessonLineRepository(xa), CrossClassUnitRepository(xa),
        RoomRepository(xa), IndividualPlanRepository(xa))

      for
        snapshot <- loader.load(schoolId)
        actual   <- loader.roomsOf(schoolId)
        rooms    =  pretendRooms.fold(actual)(n => actual ++ (actual.size until n).map(i =>
                      Room(UUID.randomUUID, s"X$i", None, true, Nil)))
        code     <- snapshot match
          case None => IO.println("Nie ma takiej szkoły").as(ExitCode(1))
          case Some(full) =>
            val narrowed = limit.fold(full)(n => narrow(full, n))
            val s = if doubles then pairUpHours(narrowed) else narrowed
            if reimport.isDefined then readArkusz(xa, schoolId, reimport.get, weeks)
            else if generate then generateWithFet(xa, schoolId, s, rooms)
            else if diagnose then explain(s, rooms, seconds, workers)
            else warm match
              case Some(chunk) => warmStart(s, rooms, seconds, workers, chunk)
              case None        => run(s, rooms, seconds, workers, apart = apart, shaped = shaped,
                                        onlyDay = onlyDay, week = week, slack = slack, spread = spread, relaxed = relaxed, rounds = rounds, oneDay = oneDay, literal = literal, fet = fet, readBack = readBack, generate = generate, reimport = reimport, weeks = weeks)
      yield code
    }

  private def run(
    snapshot: SchoolSnapshot,
    rooms: List[Room],
    seconds: Long,
    workers: Int,
    hints: Map[String, Int] = Map.empty,
    apart: Option[Int] = None,
    shaped: Option[String] = None,
    onlyDay: Option[Int] = None,
    week: Boolean = false,
    slack: Int = 0,
    spread: Boolean = false,
    relaxed: Set[TimetableProblem.Rule] = Set.empty,
    rounds: Int = 30,
    oneDay: Boolean = false,
    literal: Boolean = false,
    fet: Option[String] = None,
    readBack: Option[String] = None,
    generate: Boolean = false,
    reimport: Option[String] = None,
    weeks: bel.importer.ArkuszMapper.WeekRange = bel.importer.ArkuszMapper.WeekRange(1, 19)
  ): IO[ExitCode] =
    // Say no before spending half an hour proving it. The checks decide by
    // counting what the solver would have to discover by searching.
    val blocking = Validation.run(snapshot).filter(_.severity == Severity.Blocking)

    val shape = shaped.fold(Map.empty[UUID, Seq[Int]])(strategy =>
      SolverInput.shapes(snapshot, snapshot.teachingDays, snapshot.slotsPerDay, strategy))
    val faults = SolverInput.shapeFaults(snapshot, shape, snapshot.slotsPerDay, rooms.size)
    val problem = SolverInput.build(snapshot, rooms, seconds, workers, hints = hints,
      halvesApartWeight = apart.getOrElse(20), shape = shape,
      partial = onlyDay.isDefined, onlyDays = onlyDay.toSeq)
    for
      _ <- IO.println(
             s"${snapshot.classes.size} oddziałów, ${problem.lessons.size} bloków do ułożenia, " +
               s"${snapshot.teachers.size} nauczycieli, ${rooms.size} sal; " +
               s"${problem.days}×${problem.slotsPerDay} slotów, " +
               s"docelowa długość dnia ${problem.settings.targetDayLength}")
      _ <- IO.whenA(blocking.nonEmpty)(
             IO.println(s"\n${blocking.size} problemów blokujących — plan się nie uda:") *>
               blocking.take(10).traverse_(f => IO.println(s"  ${f.subject}: ${f.message}")))
      _ <- IO.whenA(faults.nonEmpty)(
             IO.println(s"\n${faults.size} powodów, dla których ten kształt tygodnia nie zadziała:") *>
               faults.take(10).traverse_(f => IO.println(s"  $f")))
      code <- if readBack.isDefined then readFet(snapshot, rooms, readBack.get)
              else if fet.isDefined then writeFet(snapshot, rooms, fet.get)
              else if faults.nonEmpty then IO.pure(ExitCode(1))
              else if week then solveWeek(snapshot, rooms, seconds, workers, shape, slack, spread, relaxed, rounds,
                            oneDay, literal)
              else solveIt(snapshot, problem)
    yield code

  /**
   * Builds the week a day at a time, going back over days that do not work.
   *
   * The rule that costs us most — a class's day has no holes in it — is a rule
   * about a single day. Asked about the whole week at once it ties every
   * placement to every other and the search never finishes; asked about Monday
   * it is small. So this asks five small questions instead of one impossible
   * one, which is the decomposition the high school timetabling literature
   * arrives at.
   *
   * Asking them in order is not enough on its own, because a day spends more
   * than hours. Only one block of a subject may fall on a day, so Monday takes
   * subjects as well as time, and a class can reach Thursday with hours to
   * fill and nothing left to teach in them. The answer, from the same
   * literature, is to stop treating a filled day as settled: when a day cannot
   * be filled, throw away the day before it and do both together, then three
   * together, until it comes out.
   */
  private def solveWeek(snapshot: SchoolSnapshot, rooms: List[Room], seconds: Long,
                        workers: Int, shape: Map[UUID, Seq[Int]], slack: Int,
                        useSpread: Boolean,
                        relaxed: Set[TimetableProblem.Rule],
                        maxRounds: Int, oneDay: Boolean, literal: Boolean): IO[ExitCode] =
    val slotsPerDay = snapshot.slotsPerDay
    val maxDays = snapshot.teachers.map(t =>
      t.id.toString -> t.maxWorkingDays.getOrElse(snapshot.settings.defaultTeacherMaxWorkingDays)).toMap
    val everyLesson = SolverInput.build(snapshot, rooms, 1, 0, shape = shape)
      .lessons.asScala.map(l => l.id -> l).toMap

    def teachersOf(lessonId: String): List[String] =
      everyLesson.get(lessonId).toList
        .flatMap(l => List(Option(l.teacherId), Option(l.supportTeacherId)).flatten)

    def dayOf(p: TimetableSolution.Placement): Int = p.startSlot / slotsPerDay

    /**
     * Teachers who have already used up their working days elsewhere in the
     * week. A day on its own knows nothing of a weekly limit, so it is handed
     * to them as an absence instead.
     */
    def spentOutside(placed: List[TimetableSolution.Placement], window: Seq[Int]): Set[String] =
      placed.filterNot(p => window.contains(dayOf(p)))
        .foldLeft(Map.empty[String, Set[Int]]) { (acc, p) =>
          teachersOf(p.lessonId).foldLeft(acc)((m, t) =>
            m.updated(t, m.getOrElse(t, Set.empty) + dayOf(p)))
        }
        .collect { case (t, days) if days.size >= maxDays.getOrElse(t, 5) => t }
        .toSet

    def groupOf(lessonId: String): Option[String] =
      everyLesson.get(lessonId)
        .filter(_.classId != null)
        .map(l => l.classId + "|" + l.subjectId + "|" + l.audience)

    /**
     * Lessons a day is not offered because their subject is ahead of itself.
     *
     * A subject with three blocks and a five day week wants them on three
     * different days, and nothing says which — so a day left to choose takes
     * whatever suits it and the subject is used up by Wednesday. The class
     * then reaches Thursday with hours to fill and nothing to fill them with.
     *
     * Holding each subject to its share of the week by the day in question
     * costs the solver nothing: a subject that has had its turn is simply not
     * among today's lessons. A day being taken apart and done again is exempt,
     * because by then the pacing is what we are trying to get out of.
     */
    def aheadOfPace(day: Int, placed: List[TimetableSolution.Placement]): Set[String] =
      val total = everyLesson.keys.groupBy(groupOf).collect {
        case (Some(g), ids) => g -> ids.size
      }
      val used = placed.map(_.lessonId).groupBy(groupOf).collect {
        case (Some(g), ids) => g -> ids.size
      }
      val days = snapshot.teachingDays
      everyLesson.keys.filter { id =>
        groupOf(id).exists { g =>
          val share = math.ceil(total(g).toDouble * (day + 1) / days).toInt
          used.getOrElse(g, 0) >= share
        }
      }.toSet

    // A teacher who takes both halves of a split cannot teach them at once,
    // so the class spends an hour a split normally gets for free — and that
    // hour has to fall on some day, which an evenly divided week has no room
    // for. The planner is given an hour of give for it unless told otherwise.
    val give = if useSpread && slack == 0 then 1 else slack

    val weekHours = shape.map((id, days) => id.toString -> days.sum)

    /** Slots a class actually occupies on a day, splits counted once. */
    def occupiedOn(placed: List[TimetableSolution.Placement], classId: String, day: Int): Int =
      placed.filter(p => dayOf(p) == day)
        .filter(p => everyLesson.get(p.lessonId).exists(l => classId == l.classId))
        .flatMap(p => (0 until everyLesson(p.lessonId).length).map(p.startSlot + _))
        .distinct.size

    /**
     * What each class should get on each day of the window, worked out from
     * what it has rather than from what was planned for it.
     *
     * A day allowed to run short passes the difference on, and the day that
     * cannot pass it any further is the last one — so a fixed plan with any
     * give in it quietly loads the end of the week. Re-reading the plan from
     * the placements each time means a short day is made up the next day
     * instead of accumulating into an impossible Friday.
     */
    def shapeNow(window: Seq[Int], placed: List[TimetableSolution.Placement]): Map[UUID, Seq[Int]] =
      val settled = (0 until snapshot.teachingDays).filterNot(window.contains)
      val toFill = (window ++ settled.filter(_ > window.max)).sorted
      snapshot.classes.map { cls =>
        val key = cls.id.toString
        val done = settled.filterNot(toFill.contains).map(occupiedOn(placed, key, _)).sum
        val left = math.max(0, weekHours.getOrElse(key, 0) - done)
        val base = left / toFill.size
        val extra = left % toFill.size
        val perDay = toFill.zipWithIndex.map((d, i) => d -> (base + (if i < extra then 1 else 0))).toMap
        // Indexed by the day of the week, because that is how the builder
        // reads it, even though only the window's days are looked at.
        cls.id -> (0 until snapshot.teachingDays).map(perDay.getOrElse(_, 0))
      }.toMap

    /** Fills `window`, discarding whatever those days held before. */
    def attempt(window: Seq[Int], placed: List[TimetableSolution.Placement])
        : IO[Option[List[TimetableSolution.Placement]]] =
      val keep = placed.filterNot(p => window.contains(dayOf(p)))
      val paced =
        if window.size == 1 then aheadOfPace(window.head, keep) else Set.empty[String]
      // A lesson may wait for a later day, but only while there is one. Once
      // the window reaches the end of the week there is nowhere left to put
      // anything off to, so everything still unplaced has to go in now —
      // otherwise the solver answers by quietly teaching less.
      val lastDay = window.contains(snapshot.teachingDays - 1)
      val problem = SolverInput.build(snapshot, rooms, seconds, workers, relaxed = relaxed,
        shape = shapeNow(window, keep),
        partial = !lastDay, shapeSlack = give, onlyDays = window,
        skip = keep.map(_.lessonId).toSet ++ paced,
        spentTeachers = spentOutside(placed, window))
      for
        solution <- IO.blocking(TimetableSolver.solve(problem))
        _ <- IO.println(
               f"  dni ${window.map(_ + 1).mkString("+")}: ${problem.lessons.size} bloków, " +
                 f"${solution.status} w ${solution.millis / 1000.0}%.1f s")
      yield Option.when(solution.solved)(
        keep ++ solution.placements.asScala.map(p =>
          TimetableSolution.Placement(
            p.lessonId,
            window(dayOf(p)) * slotsPerDay + p.startSlot % slotsPerDay,
            p.roomId)))

    def fill(day: Int, placed: List[TimetableSolution.Placement]): IO[ExitCode] =
      if day >= snapshot.teachingDays then
        val missing = everyLesson.keySet -- placed.map(_.lessonId)
        if missing.nonEmpty then
          IO.println(s"\nTydzień pełny, ale ${missing.size} bloków nigdzie nie trafiło")
            .as(ExitCode(1))
        else
          val solution = TimetableSolution(
            TimetableSolution.Status.FEASIBLE, placed.asJava, 0L, 0L, "")
          IO.println(s"\nCały tydzień ułożony, wszystkie ${placed.size} bloków") *>
            report(snapshot, solution).as(ExitCode.Success)
      else
        attempt(Seq(day), placed).flatMap {
          case Some(next) => fill(day + 1, next)
          case None       => widen(day, 1, placed)
        }

    /** A day that will not go in on its own, retried with the days before it. */
    def widen(day: Int, back: Int, placed: List[TimetableSolution.Placement]): IO[ExitCode] =
      if day - back < 0 then
        IO.println(s"dnia ${day + 1} nie da się ułożyć nawet razem z całym tygodniem przed nim")
          .as(ExitCode(1))
      else
        attempt((day - back) to day, placed).flatMap {
          case Some(next) => fill(day + 1, next)
          case None       => widen(day, back + 1, placed)
        }

    /**
     * Plan the week's days, try to lay one out, and when one will not go in,
     * learn why and plan again.
     *
     * Each half can do what the other cannot. The planner sees the whole week
     * at once but works in counts, so it cannot tell whether a day it has
     * arranged can actually be laid out without a hole in somebody's morning.
     * The placement sees exactly that, and settles a day in under a second —
     * but only ever sees one day, so it cannot choose a better week.
     *
     * So the placement's failures are the planner's missing information. An
     * impossible day comes back naming the handful of lessons that cannot
     * share it, that becomes a thing the planner may not do again, and the
     * next plan is a different plan rather than the same one retried.
     */
    def byPlan(cuts: List[DaySpread.Cut], round: Int, counting: Boolean = true): IO[ExitCode] =
      val whole = SolverInput.build(snapshot, rooms, seconds, workers, shape = shape)
      // Charging a class the extra hour a shared teacher costs it is worth a
      // great deal where the week still adds up with it in, and past about
      // twenty-two classes it does not. Rather than guess which school this
      // is, ask for the better plan and settle for the plainer one.
      def ask(counting: Boolean) =
        IO.blocking(DaySpread.plan(whole, seconds, workers, give, cuts.asJava, counting))
      for
        tried <- ask(counting)
        fall  =  tried.days == null && counting && round == 1
        _ <- IO.whenA(fall)(
               IO.println("  tydzień nie wychodzi, gdy dzielone grupy kosztują osobno — bez tego"))
        got  <- if fall then ask(false) else IO.pure(tried)
        plan =  Option(got.days).map(_.asScala.view.mapValues(_.toInt).toMap)
        keep =  counting && !fall
        code <- plan match
                  case None if got.proven =>
                    IO.println(
                      s"po ${cuts.size} poprawkach nie da się już rozłożyć przedmiotów na dni")
                      .as(ExitCode(1))
                  case None =>
                    IO.println(
                      s"po ${cuts.size} poprawkach nie udało się rozłożyć przedmiotów " +
                        s"w ciągu $seconds s — to nie znaczy, że się nie da")
                      .as(ExitCode(1))
                  case Some(days) =>
                    IO.println(s"plan $round: ${days.size} bloków rozłożonych na dni") *>
                      layOut(days, cuts, round, 0, Nil, keep)
      yield code

    /** Lays out the planned days one at a time, stopping at the first that will not. */
    def layOut(plan: Map[String, Int], cuts: List[DaySpread.Cut], round: Int,
               day: Int, placed: List[TimetableSolution.Placement],
               counting: Boolean): IO[ExitCode] =
      if day >= snapshot.teachingDays then
        val missing = everyLesson.keySet -- placed.map(_.lessonId)
        if missing.nonEmpty then
          IO.println(s"\nTydzień pełny, ale ${missing.size} bloków nigdzie nie trafiło")
            .as(ExitCode(1))
        else
          IO.println(s"\nCały tydzień ułożony, wszystkie ${placed.size} bloków") *>
            report(snapshot, TimetableSolution(
              TimetableSolution.Status.FEASIBLE, placed.asJava, 0L, 0L, "")).as(ExitCode.Success)
      else
        val elsewhere = plan.collect { case (id, d) if d != day => id }.toSet
        val problem = SolverInput.build(snapshot, rooms, seconds, workers, relaxed = relaxed,
          shape = Map.empty, explain = true, onlyDays = Seq(day), skip = elsewhere,
          spentTeachers = Set.empty)
        for
          solution <- IO.blocking(TimetableSolver.solve(problem))
          _ <- IO.println(
                 f"  dzień ${day + 1}: ${problem.lessons.size} bloków, " +
                   f"${solution.status} w ${solution.millis / 1000.0}%.1f s")
          code <- carryDay(plan, cuts, round, day, placed, solution, counting)
        yield code

    /** Keeps a day that went in, or learns from one that did not. */
    def carryDay(plan: Map[String, Int], cuts: List[DaySpread.Cut], round: Int, day: Int,
                 placed: List[TimetableSolution.Placement],
                 solution: TimetableSolution, counting: Boolean): IO[ExitCode] =
      if !solution.solved then learn(cuts, round, day, solution, counting)
      else
        layOut(plan, cuts, round, day + 1,
          placed ++ solution.placements.asScala.map(p =>
            TimetableSolution.Placement(
              p.lessonId, day * snapshot.slotsPerDay + p.startSlot, p.roomId)), counting)

    /**
     * Narrows a failed day to a small set of lessons that still cannot share it.
     *
     * The solver's own account of a failure is sufficient but not small — it
     * will happily blame every lesson of the day, and a rule against that
     * exact day forbids one arrangement out of astronomically many, which
     * teaches the planner almost nothing.
     *
     * A day costs a fifth of a second, so it is cheaper to ask again than to
     * accept the first answer. Whole classes go first, because most of what
     * makes a day impossible is one class's own business and dropping a class
     * that has nothing to do with it removes a dozen lessons for one question;
     * then what is left is pared a lesson at a time.
     */
    /**
     * Rules that a smaller day can break and a fuller one cannot.
     *
     * Asking whether a handful of lessons can share a day means asking about
     * those lessons and nothing else, and that only answers the question for
     * rules which adding lessons can only make harder. These two work the
     * other way about. Take a teacher's other classes away and she sits idle
     * through the middle of the day; take a class's other lessons away and its
     * morning acquires the holes they were filling. Both make a day look
     * impossible when the real one, with everything in it, is fine.
     *
     * So the probes leave them out. A quarrel that survives without them is
     * about something that more lessons cannot mend, and a rule drawn from it
     * is true of the day it came from.
     */
    def onlyWithEverything: Set[TimetableProblem.Rule] = Set(
      TimetableProblem.Rule.CLASS_DAY_CONTIGUOUS,
      TimetableProblem.Rule.TEACHER_GAP_LIMIT)

    def narrow(day: Int, blamed: Set[String]): IO[Set[String]] =
      // Proved impossible, not merely not managed. A probe that runs out of
      // time has shown nothing, and treating it as proof would mint a rule
      // forbidding an arrangement that works — which the planner would then
      // obey until it ran out of weeks it was allowed to propose.
      def impossible(subset: Set[String]): IO[Boolean] =
        if subset.isEmpty then IO.pure(false)
        else
          // Short, because the answer that matters arrives in well under a
          // second and the one that does not arrive is not needed: a probe
          // that cannot prove the lessons still clash simply keeps the lesson
          // in. Waiting longer for that buys a slightly smaller rule at the
          // price of a far slower round.
          val problem = SolverInput.build(snapshot, rooms, 10, workers,
            relaxed = relaxed ++ onlyWithEverything,
            shape = Map.empty, onlyDays = Seq(day), skip = everyLesson.keySet -- subset)
          IO.blocking(TimetableSolver.solve(problem))
            .map(_.status == TimetableSolution.Status.INFEASIBLE)

      def pare(keep: Set[String], rest: List[Set[String]]): IO[Set[String]] = rest match
        case Nil          => IO.pure(keep)
        case next :: more =>
          if (keep & next).isEmpty then pare(keep, more)
          else impossible(keep -- next).flatMap(gone =>
            pare(if gone then keep -- next else keep, more))

      val classes = blamed
        .groupBy(id => Option(everyLesson(id).classId))
        .values.toList.sortBy(-_.size)
      for
        fewerClasses <- pare(blamed, classes)
        smallest     <- pare(fewerClasses, fewerClasses.toList.map(Set(_)))
      yield smallest

    /**
     * The days a lesson set is barred from.
     *
     * A set of lessons that cannot share a Monday usually cannot share a
     * Thursday either, and learning it once for the week rather than five
     * times over is most of the difference between a loop that converges and
     * one that grinds. But "usually" has to be checked rather than argued: a
     * teacher is away on one day and not another, and the argument for when
     * that cannot matter has now been got wrong twice.
     *
     * A day costs a fifth of a second, so the other four are simply tried. The
     * rule is then issued for exactly the days on which it was proved, and
     * nothing is being claimed anywhere it was not.
     */
    def everywhere(day: Int, lessons: Set[String]): IO[List[DaySpread.Cut]] =
      // A rule names the kinds of lesson that may not share a day, and bounds
      // how many of them may appear at one fewer than it names. That is only
      // the thing that was proved while the lessons proved about are of
      // distinct kinds. Two of a kind cannot share a day anyway — another rule
      // sees to that — so a quarrel containing two says less than it looks:
      // the bound would forbid a combination nobody showed to be impossible.
      //
      // Such a quarrel is kept to the very lessons it was about.
      val kinds = lessons.map(id => DaySpread.sameAs(everyLesson(id)))
      val collapsed = kinds.size < lessons.size
      val subjects =
        if literal || collapsed then lessons.map(id => "lesson:" + id) else kinds
      val others =
        if oneDay then Nil else (0 until snapshot.teachingDays).filterNot(_ == day).toList
      others
        .traverse(d => alsoImpossible(d, lessons).map(Option.when(_)(d)))
        .map(found => (day :: found.flatten).map(d => DaySpread.Cut(d, subjects.asJava)))
        .flatTap(cuts => IO.whenA(collapsed)(IO.println(
          s"    ${lessons.size} lekcji, ale tylko ${kinds.size} rodzajów — reguła tylko o nich")))

    /** Whether these same lessons are impossible on another day too. */
    def alsoImpossible(day: Int, lessons: Set[String]): IO[Boolean] =
      val problem = SolverInput.build(snapshot, rooms, 10, workers,
        relaxed = relaxed ++ onlyWithEverything,
        shape = Map.empty, onlyDays = Seq(day), skip = everyLesson.keySet -- lessons)
      IO.blocking(TimetableSolver.solve(problem))
        .map(_.status == TimetableSolution.Status.INFEASIBLE)

    /**
     * Which rule a small quarrel is actually about.
     *
     * A handful of lessons that cannot share a day is a fact, and on its own
     * it says nothing about why. Asking again with each rule lifted in turn
     * says it outright: whichever one makes the day possible is the rule the
     * quarrel is about. Cheap only for small quarrels, and those are the ones
     * worth understanding.
     */
    def blamedRule(day: Int, lessons: Set[String]): IO[List[String]] =
      val candidates = List(
        TimetableProblem.Rule.CLASS_DAY_CONTIGUOUS,
        TimetableProblem.Rule.CLASS_DAY_STARTS_FIRST,
        TimetableProblem.Rule.ONE_BLOCK_PER_SUBJECT_PER_DAY,
        TimetableProblem.Rule.OPTIONAL_AT_DAY_EDGE,
        TimetableProblem.Rule.TEACHER_GAP_LIMIT,
        TimetableProblem.Rule.ROOM_CAPACITY,
        TimetableProblem.Rule.ROOM_KIND,
        TimetableProblem.Rule.CROSS_CLASS_TOGETHER)
      candidates.filterNot(relaxed.contains).traverse { rule =>
        val problem = SolverInput.build(snapshot, rooms, 15, workers,
          relaxed = relaxed + rule, shape = Map.empty, onlyDays = Seq(day),
          skip = everyLesson.keySet -- lessons)
        IO.blocking(TimetableSolver.solve(problem)).map(s => Option.when(s.solved)(rule.toString))
      }.map(_.flatten)

    /** Turns a day that will not go in into something the planner can use. */
    def learn(cuts: List[DaySpread.Cut], round: Int, day: Int,
              solution: TimetableSolution, counting: Boolean): IO[ExitCode] =
      val core = solution.core.asScala.toSet
      if core.isEmpty then
        // No account of the failure means the day was not proved impossible,
        // only not managed — so there is nothing to learn and nothing to
        // re-plan around, and planning again would produce the same week. The
        // honest thing is to say which day ran out of time rather than to
        // report that the week cannot be built.
        IO.println(s"  dzień ${day + 1} nie zmieścił się w czasie, jaki na niego przypada " +
          s"— spróbuj dłuższego limitu niż $seconds s").as(ExitCode(1))
      else if round >= maxRounds then
        IO.println(s"  poddaję się po $maxRounds planach").as(ExitCode(1))
      else
        for
          small <- narrow(day, core)
          _ <- IO.println({
                 // What the quarrel is made of. If the same shape of conflict
                 // keeps coming back there is a rule to be had rather than
                 // another few hundred cuts.
                 val kinds = small.toList.map(everyLesson).groupBy(_.audience)
                   .map((a, ls) => s"$a=${ls.size}").toList.sorted.mkString(" ")
                 val units = small.count(id => everyLesson(id).crossClassUnitId != null)
                 val spare = small.count(id => everyLesson(id).optional)
                 val named =
                   if small.size > 6 then ""
                   else small.toList.map(everyLesson).map { l =>
                     val subject = snapshot.subjects.find(_.id.toString == l.subjectId)
                       .fold("?")(_.name.take(12))
                     val who = l.audience match
                       case Audience.GROUP_1 => "/1"
                       case Audience.GROUP_2 => "/2"
                       case _                => ""
                     val cross = if l.crossClassUnitId != null then "*" else ""
                     val edge = if l.optional then "!" else ""
                     s"$subject$who$cross$edge(${l.length}h)"
                   }.sorted.mkString("\n      ", "\n      ", "")
                 s"    kształt sporu: $kinds" + (if units > 0 then s" międzyodd=$units" else "") +
                   (if spare > 0 then s" nieobowiązkowe=$spare" else "") + named
               })
          why <- if small.size <= 6 then blamedRule(day, small) else IO.pure(Nil)
          _ <- IO.whenA(why.nonEmpty)(
                 IO.println(s"      bez tej reguły dzień by się ułożył: ${why.mkString(", ")}"))
          _ <- IO.println(s"  winnych lekcji: ${small.size} z ${core.size} (" +
                 small.flatMap(id => Option(everyLesson(id).classId)).flatMap(c =>
                   snapshot.classes.find(_.id.toString == c).map(_.name)).toList.sorted
                   .distinct.mkString(",") + ")")
          spread <- everywhere(day, small)
          _ <- IO.println(s"    ta sama przeszkoda w ${spread.size} dniach")
          code <- byPlan(cuts ++ spread, round + 1, counting)
        yield code

    if useSpread then byPlan(Nil, 1) else fill(0, Nil)

  /**
   * The whole thing: lay the week out, check it, keep it if it holds.
   *
   * A timetable that breaks a rule is not saved. It is reported, with the
   * rules it broke, and the planner decides what to do — but it does not
   * quietly become the school's week.
   */
  private def generateWithFet(xa: doobie.Transactor[IO], schoolId: UUID,
                              snapshot: SchoolSnapshot, rooms: List[Room]): IO[ExitCode] =
    for
      outcome <- FetRunner(snapshot, rooms, FetRunner.Config.container)
      _ <- IO.println(f"ułożone w ${outcome.millis / 1000.0}%.1f s, " +
             f"${outcome.result.placed.size} zajęć")
      _ <- outcome.report.rules.traverse_(r =>
             IO.println(f"  ${if r.ok then "ok" else "XX"}  ${r.code}%-4s ${r.what}%-52s ${r.detail}"))
      code <- keepIfSound(xa, schoolId, rooms, outcome)
    yield code

  /**
   * The arkusz, read again.
   *
   * The same path the upload takes, without a server in the way — which is how
   * an import can be exercised from a terminal and how a change to the mapper
   * gets tried against the real file in one step.
   */
  private def readArkusz(xa: doobie.Transactor[IO], schoolId: UUID, path: String,
                         weeks: bel.importer.ArkuszMapper.WeekRange): IO[ExitCode] =
    for
      bytes <- IO.blocking(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path)))
      parsed = bel.importer.ArkuszParser.parse(bytes)
      code <- parsed match
        case Left(why) => IO.println(s"nie da się wczytać: $why").as(ExitCode(1))
        case Right(doc) =>
          val plan = bel.importer.ArkuszMapper.map(doc, weeks)
          for
            _ <- plan.notes.traverse_(n => IO.println(s"  ${n.level}: ${n.message}"))
            applied <- bel.repository.ImportRepository(xa).apply(schoolId, plan)
            _ <- IO.println(s"wczytano: ${applied.classes} oddziałów, " +
                   s"${applied.lessonLines} linii, ${applied.crossClassUnits} zespołów, " +
                   s"${applied.individualLessons} godz. nauczania indywidualnego")
          yield ExitCode.Success
    yield code

  /** A week that breaks a rule is reported, never quietly made the school's. */
  private def keepIfSound(xa: doobie.Transactor[IO], schoolId: UUID,
                          rooms: List[Room], outcome: FetRunner.Outcome): IO[ExitCode] =
    if !outcome.report.sound || !outcome.result.complete then
      IO.println("plan łamie reguły — nie zapisuję").as(ExitCode(1))
    else
      val plans = PlanRepository(xa)
      for
        saved <- plans.save(schoolId, NewPlan(
                   name = s"FET ${java.time.LocalDate.now}",
                   madeBy = "fet",
                   tookMillis = Some(outcome.millis.toInt),
                   rulesKept = Some(outcome.report.kept),
                   rulesTotal = Some(outcome.report.rules.size),
                   placements = FetRunner.placements(outcome.result, rooms)))
        _ <- plans.makeCurrent(schoolId, saved.id)
        _ <- IO.println(s"zapisano plan ${saved.id} i ustawiono jako bieżący")
      yield ExitCode.Success

  /** FET's answer, read back and counted. */
  private def readFet(snapshot: SchoolSnapshot, rooms: List[Room], path: String): IO[ExitCode] =
    for
      written <- IO.pure(FetExport(snapshot, rooms))
      xml <- IO.blocking(java.nio.file.Files.readString(java.nio.file.Path.of(path)))
      result = FetImport(xml, written.means, FetExport.dayNames(snapshot.teachingDays))
      _ <- IO.println(f"odczytano ${result.placed.size} zajęć, " +
             f"${result.unplaced} bez godziny")
      report = PlanCheck(snapshot, FetImport.expand(snapshot, result.placed, rooms), rooms)
      _ <- IO.println(f"%nreguły: ${report.kept} z ${report.rules.size} sprawdzanych spełnionych" +
             f" (${PlanCheck.notChecked.size} reguł nie da się tu sprawdzić)")
      _ <- report.rules.traverse_(r =>
             IO.println(f"  ${if r.ok then "ok" else "XX"}  ${r.code}%-4s ${r.what}%-52s ${r.detail}"))
      _ <- PlanCheck.notChecked.traverse_((code, why) => IO.println(f"  --  $code%-4s $why"))
    yield if result.complete && report.sound then ExitCode.Success else ExitCode(1)

  /** The school written out for FET, which solves this size in minutes. */
  private def writeFet(snapshot: SchoolSnapshot, rooms: List[Room], path: String): IO[ExitCode] =
    for
      written <- IO.pure(FetExport(snapshot, rooms))
      _ <- IO.blocking(java.nio.file.Files.writeString(
             java.nio.file.Path.of(path), written.xml))
      _ <- IO.println(s"zapisano $path (${written.means.size} zajęć do odczytania z powrotem)")
    yield ExitCode.Success

  private def solveIt(snapshot: SchoolSnapshot, problem: TimetableProblem): IO[ExitCode] =
    for
      solution <- IO.blocking(TimetableSolver.solve(problem))
      _ <- IO.println(
             f"%n${solution.status} w ${solution.millis / 1000.0}%.1f s, kara ${solution.penalty}")
      code <- if solution.solved then report(snapshot, solution).as(ExitCode.Success)
              else IO.println(solution.detail).as(ExitCode(1))
    yield code

  /** One class's week, so the result can be eyeballed rather than trusted. */
  private def report(snapshot: SchoolSnapshot, solution: TimetableSolution): IO[Unit] =
    val subjects = snapshot.subjects.map(s => s.id -> s.name).toMap
    val lines = snapshot.lessonLines.map(l => l.id -> l).toMap
    val slotsPerDay = snapshot.slotsPerDay

    val placed = solution.placements.asScala.toList.flatMap { p =>
      SolverInput.LessonRef.parse(p.lessonId).map {
        case SolverInput.LessonRef.Line(id, _) =>
          val line = lines(id)
          (line.classId, p.startSlot, label(subjects, line))
        case SolverInput.LessonRef.CrossClass(_, _, classId, _) =>
          (classId, p.startSlot, "zaj. międzyoddz.")
        // The group itself has no class to show; its classes report themselves.
        case SolverInput.LessonRef.CrossClassGroupRef(_, _, _) =>
          (null, -1, "")
      }
    }

    // The first class, and any class where one half is in school without the
    // other — the arrangement the school asked for when one teacher has both
    // halves of a split, and the one worth seeing rather than taking on trust.
    val halfAlone = snapshot.classes.filter { cls =>
      val mine = placed.filter(_._1 == cls.id)
      val lines = snapshot.lessonLines.filter(_.classId == cls.id)
      (0 until snapshot.teachingDays * slotsPerDay).exists { slot =>
        val here = mine.filter(_._2 == slot).map(_._3)
        here.sizeIs == 1 && here.exists(l => l.endsWith("/1") || l.endsWith("/2"))
      }
    }
    (snapshot.classes.sortBy(_.name).take(1) ++ halfAlone).distinct.traverse_ { cls =>
      val mine = placed.filter(_._1 == cls.id)
      IO.println(s"\n--- ${cls.name} ---") *>
        (0 until snapshot.teachingDays).toList.traverse_ { day =>
          val row = (0 until slotsPerDay).map { p =>
            val slot = day * slotsPerDay + p
            mine.filter(_._2 == slot).map(_._3).mkString(",") match
              case ""    => "."
              case taken => taken
          }
          IO.println(f"  ${dayName(day)}%-3s ${row.map(c => f"$c%-18s").mkString}")
        }
    }

  private def label(subjects: Map[UUID, String], line: LessonLine): String =
    val name = subjects.getOrElse(line.subjectId, "?").take(8)
    line.audience match
      case LessonAudience.WHOLE_CLASS => name
      case LessonAudience.GROUP_1     => s"$name/1"
      case LessonAudience.GROUP_2     => s"$name/2"

  private def dayName(day: Int): String =
    Vector("pn", "wt", "śr", "cz", "pt").lift(day).getOrElse(day.toString)

  /**
   * Which rule is the one making this impossible.
   *
   * Drops each in turn and reports whether a plan appears without it. Blunt —
   * it finds one culprit, not the smallest set — but when a run comes back
   * infeasible with nothing in the checks to explain it, "without the teacher
   * gap limit it works" is most of the answer, and it costs one solve per rule.
   * UC-32 should do better than this; until then it beats guessing.
   */
  private def explain(
    snapshot: SchoolSnapshot,
    rooms: List[Room],
    seconds: Long,
    workers: Int
  ): IO[ExitCode] =
    val rules = TimetableProblem.Rule.values.toList
    IO.println(s"Sprawdzam, która reguła blokuje plan (${rules.size} prób po $seconds s)\n") *>
      rules.traverse_ { rule =>
        val problem = SolverInput.build(snapshot, rooms, seconds, workers, relaxed = Set(rule))
        IO.blocking(TimetableSolver.solve(problem)).flatMap { solution =>
          val verdict = if solution.solved then "PLAN SIĘ UKŁADA" else solution.status.toString
          IO.println(f"  bez ${rule.toString}%-32s $verdict%s (${solution.millis / 1000.0}%.0f s)")
        }
      }.as(ExitCode.Success)

  /** The school cut down to its first few classes, and nothing else. */
  private def narrow(snapshot: SchoolSnapshot, classes: Int): SchoolSnapshot =
    val kept = snapshot.classes.sortBy(_.name).take(classes)
    val keptIds = kept.map(_.id).toSet
    snapshot.copy(
      classes = kept,
      lessonLines = snapshot.lessonLines.filter(l => keptIds.contains(l.classId)),
      // A unit is only kept when every class it touches is kept, since dropping
      // one of them would change what the unit means.
      crossClassUnits = snapshot.crossClassUnits.filter(_.classIds.forall(keptIds.contains))
    )

  /**
   * Single hours paired into doubles, as a school would do by hand.
   *
   * The arkusz says how many hours a subject has and never how they are taught,
   * so an import makes every hour its own block. A real timetable has plenty of
   * doubles, and each one is a block the solver does not have to place.
   */
  private def pairUpHours(snapshot: SchoolSnapshot): SchoolSnapshot =
    snapshot.copy(lessonLines = snapshot.lessonLines.map { line =>
      val singles = line.blocks.count(_ == 1)
      val rest = line.blocks.filter(_ != 1)
      // Leave one single where the count is odd, so the hours still add up.
      val paired = List.fill(singles / 2)(2) ++ List.fill(singles % 2)(1)
      line.copy(blocks = (rest ++ paired).sorted)
    })

  /**
   * Solves the school a few classes at a time, then uses that as a start.
   *
   * Whole-school runs stop finding anything somewhere past fifteen classes, and
   * more time does not rescue them — two hours is no better than ten minutes.
   * But CP-SAT's large-neighbourhood workers are far better at improving an
   * arrangement than at finding the first one, so the trick is to give them
   * something to improve.
   *
   * The chunks are solved in isolation, so their answers conflict: two classes
   * from different chunks will have booked the same teacher at the same hour.
   * That is fine. A hint is a suggestion, and repairing one is the kind of work
   * the solver is good at; inventing a whole timetable from nothing is not.
   */
  private def warmStart(
    snapshot: SchoolSnapshot,
    rooms: List[Room],
    seconds: Long,
    workers: Int,
    chunk: Int
  ): IO[ExitCode] =
    val groups = snapshot.classes.sortBy(_.name).grouped(chunk).toList
    val perChunk = math.max(30L, seconds / (groups.size + 2))

    def solveChunk(classes: List[SchoolClass]): IO[Map[String, Int]] =
      val ids = classes.map(_.id).toSet
      val part = snapshot.copy(
        classes = classes,
        lessonLines = snapshot.lessonLines.filter(l => ids.contains(l.classId)),
        crossClassUnits = snapshot.crossClassUnits.filter(_.classIds.forall(ids.contains)))
      val problem = SolverInput.build(part, rooms, perChunk, workers)
      IO.blocking(TimetableSolver.solve(problem)).flatMap { solution =>
        IO.println(f"  ${classes.map(_.name).mkString(", ")}%-28s ${solution.status}%s " +
          f"(${solution.millis / 1000.0}%.0f s)").as(
          if solution.solved then
            solution.placements.asScala.map(p => p.lessonId -> p.startSlot).toMap
          else Map.empty)
      }

    for
      _     <- IO.println(s"Rozgrzewka: ${groups.size} grup po ${chunk} oddziałów, " +
                 s"$perChunk s każda")
      parts <- groups.traverse(solveChunk)
      hints = parts.foldLeft(Map.empty[String, Int])(_ ++ _)
      _     <- IO.println(s"\nPełny plan, ${hints.size} wskazówek z rozgrzewki")
      code  <- run(snapshot, rooms, seconds, workers, hints)
    yield code
