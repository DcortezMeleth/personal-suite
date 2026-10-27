package bel.solver

import bel.db.Database
import bel.domain.*
import bel.repository.*
import cats.effect.*
import cats.syntax.foldable.*
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
        val plain = rest.filterNot(_.startsWith("--"))
        val seconds = plain.headOption.flatMap(_.toLongOption).getOrElse(300L)
        val workers = plain.drop(1).headOption.flatMap(_.toIntOption).getOrElse(0)
        solve(UUID.fromString(schoolId), seconds, workers, diagnose, limit)

  private def solve(schoolId: UUID, seconds: Long, workers: Int, diagnose: Boolean,
                    limit: Option[Int]): IO[ExitCode] =
    val config = ConfigFactory.load()
    Database.transactor(config).use { xa =>
      val loader = SnapshotLoader(
        SchoolRepository(xa), SchoolClassRepository(xa), TeacherRepository(xa),
        SubjectRepository(xa), LessonLineRepository(xa), CrossClassUnitRepository(xa),
        RoomRepository(xa))

      for
        snapshot <- loader.load(schoolId)
        rooms    <- loader.roomsOf(schoolId)
        code     <- snapshot match
          case None => IO.println("Nie ma takiej szkoły").as(ExitCode(1))
          case Some(full) =>
            val s = limit.fold(full)(n => narrow(full, n))
            if diagnose then explain(s, rooms, seconds, workers)
            else run(s, rooms, seconds, workers)
      yield code
    }

  private def run(
    snapshot: SchoolSnapshot,
    rooms: List[Room],
    seconds: Long,
    workers: Int
  ): IO[ExitCode] =
    // Say no before spending half an hour proving it. The checks decide by
    // counting what the solver would have to discover by searching.
    val blocking = Validation.run(snapshot).filter(_.severity == Severity.Blocking)

    val problem = SolverInput.build(snapshot, rooms, seconds, workers)
    for
      _ <- IO.println(
             s"${snapshot.classes.size} oddziałów, ${problem.lessons.size} bloków do ułożenia, " +
               s"${snapshot.teachers.size} nauczycieli, ${rooms.size} sal; " +
               s"${problem.days}×${problem.slotsPerDay} slotów, " +
               s"docelowa długość dnia ${problem.settings.targetDayLength}")
      _ <- IO.whenA(blocking.nonEmpty)(
             IO.println(s"\n${blocking.size} problemów blokujących — plan się nie uda:") *>
               blocking.take(10).traverse_(f => IO.println(s"  ${f.subject}: ${f.message}")))
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

    val first = snapshot.classes.sortBy(_.name).headOption
    first.traverse_ { cls =>
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
