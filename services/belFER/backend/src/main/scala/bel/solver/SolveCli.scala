package bel.solver

import bel.db.Database
import bel.domain.*
import bel.repository.*
import cats.effect.*
import cats.syntax.foldable.*
import com.typesafe.config.ConfigFactory
import java.util.UUID

/**
 * The school's data, worked on from a terminal rather than a browser.
 *
 * Generation is FET's (ADR 0004) and the app drives it; this is for the steps
 * around it that are easier by hand: writing the file FET is given, checking
 * FET's answer against our rules, generating and keeping a week, reading the
 * arkusz again, and printing the current plan as a page. RUNLOG.md's runs are
 * made with it.
 *
 * Until 2026-11-21 it also drove our own CP-SAT solver. That is gone; the last
 * commit with it is 2a9a1a4 (OPEN_ITEMS.md).
 *
 *   bazel run //services/belFER/backend:solve -- <schoolId> <mode> [options]
 */
object SolveCli extends IOApp:

  private val usage =
    """usage: solve <schoolId> <mode> [options]
      |
      |modes:
      |  --fet=<file>      write the school out as FET sees it
      |  --read=<file>     read FET's answer back and check it against our rules
      |  --generate        generate with FET, check, and keep the week if it holds
      |  --sheet=<file>    write the current plan as a page to look at
      |  --import=<file>   read the arkusz again (--weeks=from-to, default 1-19)
      |
      |options, for --fet, --read and --generate; nothing is saved:
      |  --limit=N         only the first N classes and what belongs to them
      |  --doubles         pair single hours into doubles
      |  --rooms=N         pretend there are N rooms""".stripMargin

  override def run(args: List[String]): IO[ExitCode] =
    args match
      case schoolId :: rest if rest.exists(_.startsWith("--")) =>
        def value(flag: String) = rest.collectFirst { case s if s.startsWith(s"--$flag=") =>
          s.drop(flag.length + 3) }
        val options = Options(
          limit = value("limit").map(_.toInt),
          // The arkusz states hours and never their shape, so an import is
          // almost all single lessons — far more things to place than a real
          // timetable has. This asks what the count is costing us.
          doubles = rest.contains("--doubles"),
          // Answers whether the room count is what the week is straining against.
          pretendRooms = value("rooms").map(_.toInt))
        val weeks = value("weeks")
          .flatMap(_.split("-") match
            case Array(a, b) => for x <- a.toIntOption; y <- b.toIntOption
                                yield bel.importer.ArkuszMapper.WeekRange(x, y)
            case _ => None)
          .getOrElse(bel.importer.ArkuszMapper.WeekRange(1, 19))
        val mode: Option[Mode] =
          value("fet").map(Mode.WriteFet(_))
            .orElse(value("read").map(Mode.ReadFet(_)))
            .orElse(Option.when(rest.contains("--generate"))(Mode.Generate))
            .orElse(value("sheet").map(Mode.Sheet(_)))
            .orElse(value("import").map(Mode.Import(_, weeks)))
        mode match
          case Some(m) => solve(UUID.fromString(schoolId), m, options)
          case None    => IO.println(usage).as(ExitCode(2))
      case _ => IO.println(usage).as(ExitCode(2))

  private enum Mode:
    case WriteFet(path: String)
    case ReadFet(path: String)
    case Generate
    case Sheet(path: String)
    case Import(path: String, weeks: bel.importer.ArkuszMapper.WeekRange)

  private case class Options(limit: Option[Int], doubles: Boolean, pretendRooms: Option[Int])

  private def solve(schoolId: UUID, mode: Mode, options: Options): IO[ExitCode] =
    val config = ConfigFactory.load()
    Database.transactor(config).use { xa =>
      val loader = SnapshotLoader(
        SchoolRepository(xa), SchoolClassRepository(xa), TeacherRepository(xa),
        SubjectRepository(xa), LessonLineRepository(xa), CrossClassUnitRepository(xa),
        RoomRepository(xa), IndividualPlanRepository(xa),
        OptionalAttendanceRepository(xa))

      for
        loaded   <- loader.loadWithRooms(schoolId)
        actual   =  loaded.map(_._2).getOrElse(Nil)
        rooms    =  options.pretendRooms.fold(actual)(n => actual ++ (actual.size until n).map(i =>
                      Room(UUID.randomUUID, s"X$i", None, true, Nil)))
        code     <- loaded.map(_._1) match
          case None => IO.println("Nie ma takiej szkoły").as(ExitCode(1))
          case Some(full) =>
            val narrowed = options.limit.fold(full)(n => narrow(full, n))
            val s = if options.doubles then pairUpHours(narrowed) else narrowed
            mode match
              case Mode.WriteFet(path)      => summary(s, rooms) *> writeFet(s, rooms, path)
              case Mode.ReadFet(path)       => summary(s, rooms) *> readFet(s, rooms, path)
              case Mode.Generate            => generateWithFet(xa, schoolId, s, rooms)
              case Mode.Sheet(path)         => writeSheet(xa, schoolId, s, rooms, path)
              case Mode.Import(path, weeks) => readArkusz(xa, schoolId, path, weeks)
      yield code
    }

  /** What is being worked on, and anything that rules a week out before FET is asked. */
  private def summary(snapshot: SchoolSnapshot, rooms: List[Room]): IO[Unit] =
    val blocking = Validation.run(snapshot).filter(_.severity == Severity.Blocking)
    for
      _ <- IO.println(
             s"${snapshot.classes.size} oddziałów, ${snapshot.teachers.size} nauczycieli, " +
               s"${rooms.size} sal; ${snapshot.teachingDays}×${snapshot.slotsPerDay} slotów")
      _ <- IO.whenA(blocking.nonEmpty)(
             IO.println(s"\n${blocking.size} problemów blokujących — plan się nie uda:") *>
               blocking.take(10).traverse_(f => IO.println(s"  ${f.subject}: ${f.message}")))
    yield ()

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
      result <- PlanGenerator(
                  SnapshotLoader(
                    SchoolRepository(xa), SchoolClassRepository(xa), TeacherRepository(xa),
                    SubjectRepository(xa), LessonLineRepository(xa), CrossClassUnitRepository(xa),
                    RoomRepository(xa), IndividualPlanRepository(xa),
                    OptionalAttendanceRepository(xa)),
                  PlanRepository(xa),
                  FetRunner.Config.from(ConfigFactory.load()))
                  .generateFrom(schoolId, snapshot, rooms)
      code <- report(result)
    yield code

  /** The same outcome the screen shows, printed. */
  private def report(result: PlanGenerator.Result): IO[ExitCode] = result match
    case PlanGenerator.Result.NoSchool =>
      IO.println("Nie ma takiej szkoły").as(ExitCode(1))
    // Reachable from here only on a Ctrl-C that the shell turns into a
    // SIGTERM the searches see; printed rather than swallowed, because where
    // it got stuck is the reason to have been watching.
    case PlanGenerator.Result.Stopped(diagnosis, millis) =>
      for
        _ <- IO.println(f"przerwane po ${millis / 1000.0}%.1f s")
        _ <- diagnosis.traverse_(d =>
               IO.println(s"  umieszczono ${d.placed} z ${d.total}" +
                 d.blocking.fold("")(what => s", dalej nie przeszło: $what")))
      yield ExitCode(1)
    case PlanGenerator.Result.Rejected(r, placed, unplaced, millis) =>
      for
        _ <- IO.println(f"ułożone w ${millis / 1000.0}%.1f s, $placed zajęć, $unplaced bez godziny")
        _ <- printRules(r)
        _ <- IO.println("plan łamie reguły — nie zapisuję")
      yield ExitCode(1)
    case PlanGenerator.Result.Saved(plan, r, placed, millis) =>
      for
        _ <- IO.println(f"ułożone w ${millis / 1000.0}%.1f s, $placed zajęć")
        _ <- printRules(r)
        _ <- IO.println(s"zapisano plan ${plan.id} i ustawiono jako bieżący")
      yield ExitCode.Success

  private def printRules(report: PlanCheck.Report): IO[Unit] =
    report.rules.traverse_(x =>
      IO.println(f"  ${if x.ok then "ok" else "XX"}  ${x.code}%-4s ${x.what}%-52s ${x.detail}"))

  /**
   * The current plan, as a page to look at.
   *
   * Phase 4 builds these views in the application. Until then this is how a
   * generated week gets read by a person, which is the only way to find out
   * whether it resembles a timetable rather than merely satisfying the rules.
   */
  private def writeSheet(xa: doobie.Transactor[IO], schoolId: UUID, snapshot: SchoolSnapshot,
                         rooms: List[Room], path: String): IO[ExitCode] =
    val plans = PlanRepository(xa)
    plans.current(schoolId).flatMap {
      case None => IO.println("nie ma bieżącego planu — uruchom najpierw --generate").as(ExitCode(1))
      case Some(plan) =>
        for
          placed <- plans.placements(plan.id)
          html = PlanSheet(snapshot, plan, placed, rooms)
          _ <- IO.blocking(java.nio.file.Files.writeString(java.nio.file.Path.of(path), html))
          _ <- IO.println(s"zapisano $path (${placed.size} zajęć)")
        yield ExitCode.Success
    }

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

  /**
   * FET's answer, read back and counted.
   *
   * What each of FET's activities was comes from the run's own copy of its
   * input, beside the answer, not from exporting the school again: see
   * FetImport.recover. Without that copy, today's export is the only guide,
   * and it is said so.
   */
  private def readFet(snapshot: SchoolSnapshot, rooms: List[Room], path: String): IO[ExitCode] =
    val answer = java.nio.file.Path.of(path)
    val runInput = answer.resolveSibling("school_data_and_timetable.fet")
    for
      written <- IO.pure(FetExport(snapshot, rooms))
      xml <- IO.blocking(java.nio.file.Files.readString(answer))
      input <- IO.blocking(Option.when(java.nio.file.Files.exists(runInput))(
                 java.nio.file.Files.readString(runInput)))
      mapping = input.fold[Either[String, Map[Int, LessonRef]]](Right(written.means))(
                  FetImport.recover(_, written))
      code <- mapping match
        case Left(why) => IO.println(s"nie da się odczytać: $why").as(ExitCode(1))
        case Right(means) =>
          IO.whenA(input.isEmpty)(IO.println(
            "brak kopii danych wejściowych obok odpowiedzi — zajęcia odczytane według " +
              "dzisiejszego eksportu, co jest pewne tylko, jeśli od przebiegu nic się nie zmieniło")) *>
            checkAnswer(snapshot, rooms, xml, means)
    yield code

  private def checkAnswer(snapshot: SchoolSnapshot, rooms: List[Room], xml: String,
                          means: Map[Int, LessonRef]): IO[ExitCode] =
    for
      result <- IO.pure(FetImport(xml, means, FetExport.dayNames(snapshot.teachingDays)))
      _ <- IO.println(f"odczytano ${result.placed.size} zajęć, " +
             f"${result.unplaced} bez godziny")
      report = PlanCheck(snapshot, FetImport.expand(snapshot, result.placed, rooms), rooms)
      _ <- IO.println(f"%nreguły: ${report.kept} z ${report.rules.size} sprawdzanych spełnionych" +
             f" (${PlanCheck.notChecked.size} reguł nie da się tu sprawdzić)")
      _ <- printRules(report)
      _ <- PlanCheck.notChecked.traverse_((code, why) => IO.println(f"  --  $code%-4s $why"))
    yield if result.complete && report.sound then ExitCode.Success else ExitCode(1)

  /** The school written out for FET. */
  private def writeFet(snapshot: SchoolSnapshot, rooms: List[Room], path: String): IO[ExitCode] =
    for
      written <- IO.pure(FetExport(snapshot, rooms))
      _ <- IO.blocking(java.nio.file.Files.writeString(
             java.nio.file.Path.of(path), written.xml))
      _ <- IO.println(s"zapisano $path (${written.means.size} zajęć do odczytania z powrotem)")
    yield ExitCode.Success

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
