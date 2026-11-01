package bel.solver

import bel.domain.*
import cats.effect.IO
import cats.effect.kernel.Resource
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * FET, as a process.
 *
 * One file in, one directory out, no service and no state — which is most of
 * why depending on it is tolerable. It is emphatically a job rather than a
 * call: the real school has been measured at three seconds and at fifteen
 * hours from the same data, depending on the rules asked for and on the dice,
 * so it reports progress and can be stopped.
 *
 * The command is configurable because the way it is reached differs by
 * machine and should not be a code change. On the home lab FET is a package
 * and `fet-cl` is on the path; on a laptop it is a container, because the
 * macOS build ships only the graphical program. Both are the same binary doing
 * the same thing, which is the point of using a container for the second.
 */
object FetRunner:

  /**
   * @param command how to reach fet-cl. `{dir}` is replaced by the working
   *     directory, which is how a container is told what to mount.
   */
  case class Config(
    command: List[String],
    /** Zero or less means no bound: the person watching decides. */
    timeout: FiniteDuration = Duration.Zero,
    /**
     * Where the file is written for FET to read.
     *
     * Not the system temporary directory, which on macOS is under /var/folders
     * and is not among the paths a container runtime shares by default — the
     * container starts, finds nothing, and says the input file does not exist.
     * Somewhere under the user's home is shared everywhere we care about and
     * is a predictable place to look when something goes wrong.
     */
    workRoot: Path = Path.of(System.getProperty("user.home"), ".belfer", "fet"))

  object Config:
    /** What a laptop uses: the image built from services/belFER/fet. */
    val container: Config = Config(List(
      "docker", "run", "--rm", "-v", "{dir}:/data", "belfer-fet:local",
      "--inputfile=/data/school.fet", "--outputdir=/data/out"))

    /** What a machine with FET installed uses. */
    val installed: Config = Config(List(
      "fet-cl", "--inputfile={dir}/school.fet", "--outputdir={dir}/out"))

    /**
     * How this deployment reaches FET, from `belfer.fet`.
     *
     * The command is read as a list of arguments or as one string to split on
     * whitespace, because the two ways it is set want different shapes: a
     * config file is happier with a list, and an environment variable — which
     * is how compose and a systemd unit will set it — can only carry a string.
     */
    def from(config: com.typesafe.config.Config): Config =
      val fet = config.getConfig("belfer.fet")
      val command = fet.getValue("command").unwrapped match
        case args: java.util.List[?] => args.asScala.map(String.valueOf).toList
        case one                     => String.valueOf(one).split("\\s+").filter(_.nonEmpty).toList
      require(command.nonEmpty, "belfer.fet.command is empty — nothing to run")
      Config(
        command = command,
        timeout = FiniteDuration(fet.getDuration("timeout").toMillis, MILLISECONDS),
        workRoot = Path.of(fet.getString("work-root")))

  case class Outcome(result: FetImport.Result, report: PlanCheck.Report, millis: Long,
                     /** What it was rolled with, so the week can be had again. */
                     seed: FetSeed)

  /**
   * How far FET has got, while it is still going.
   *
   * FET places activities one at a time in a difficulty order it decides for
   * itself, backtracking when it gets stuck, and it records every new furthest
   * point in `logs/max_placed_activities.txt`. That file is the progress
   * signal: it is written as the search runs, it needs no flag, and it is
   * bounded — one line per new maximum, so never more lines than there are
   * activities.
   *
   * `--verbose=true` reports the same thing in the output stream and was the
   * obvious candidate until it was measured: on the real school it writes
   * about four kilobytes a second, which is two hundred megabytes over a night
   * and unbounded after that, for nothing this file does not already say.
   *
   * Note that `placed` goes up and never down even though the search itself
   * backtracks constantly. It is a high-water mark, not a position, which is
   * the honest thing to show: a run wedged for an hour sits at the same number
   * rather than flickering.
   */
  case class Progress(placed: Int, total: Int)

  /**
   * Lay out the school, read the answer back, and check it.
   *
   * The checking is not optional and does not live behind a flag. A timetable
   * nobody has held against the rules is not a timetable, it is a file.
   */
  def apply(snapshot: SchoolSnapshot, rooms: List[Room], config: Config,
            seed: Option[FetSeed] = None,
            onProgress: Progress => IO[Unit] = _ => IO.unit): IO[Outcome] =
    workspace(config.workRoot).use { dir =>
      val written = FetExport(snapshot, rooms)
      for
        dice <- seed.fold(FetSeed.random)(IO.pure)
        _ <- IO.blocking(Files.writeString(dir.resolve("school.fet"), written.xml))
        started <- IO.monotonic
        _ <- run(config, dir, dice, written.activities, onProgress)
        xml <- readAnswer(dir)
        done <- IO.monotonic
        result = FetImport(xml, written.means, FetExport.dayNames(snapshot.teachingDays))
        report = PlanCheck(snapshot, FetImport.expand(snapshot, result.placed, rooms), rooms)
      yield Outcome(result, report, (done - started).toMillis, dice)
    }

  private def workspace(root: Path): Resource[IO, Path] =
    Resource.make(IO.blocking {
      Files.createDirectories(root)
      Files.createTempDirectory(root, "run-")
    })(dir =>
      IO.blocking {
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
      }.handleError(_ => ()))

  /**
   * The search, bounded.
   *
   * The bound is the JDK's own timed wait rather than a cats-effect timeout,
   * and that shape is the whole point. `IO.blocking` is uncancelable, so a
   * timeout around it ends nothing — measured before this was written: a
   * two-second timeout let a twenty-second command run to completion and then
   * reported success. Making the wait `interruptible` did not help either,
   * because the thread was parked in `readAllBytes` on the process's pipe,
   * which does not answer an interrupt. `waitFor(timeout)` answers the clock
   * directly and needs neither.
   *
   * Output goes to a file rather than a pipe for the same reason in reverse:
   * nothing reads it while the process runs, and a chatty program would
   * otherwise fill the pipe buffer and block for ever, against a timeout that
   * is waiting for it to finish.
   *
   * The process is held as a resource so that giving up kills it. Otherwise a
   * run nobody is waiting for any more keeps a container alive, and the next
   * one queues behind it.
   */
  private def run(config: Config, dir: Path, seed: FetSeed,
                  total: Int, onProgress: Progress => IO[Unit]): IO[Unit] =
    // The seed goes on the end, where trailing flags work for both shapes the
    // command takes — fet-cl directly, or docker run wrapping it.
    val argv = config.command.map(_.replace("{dir}", dir.toString)) ++ seed.args
    val log = dir.resolve("fet-cl.log")

    val started = Resource.make(
      IO.blocking(ProcessBuilder(argv.asJava)
        .redirectErrorStream(true)
        .redirectOutput(log.toFile)
        .start()))(p =>
      IO.blocking {
        if p.isAlive then
          // Politely first: `docker run` forwards the signal on, and --rm then
          // clears the container up. Forcibly only if that is ignored.
          p.destroy()
          if !p.waitFor(5, TimeUnit.SECONDS) then p.destroyForcibly()
      }.void)

    started.use { p =>
      for
        finished <- awaitExit(p, config.timeout, dir, total, onProgress)
        said     <- IO.blocking(if Files.exists(log) then Files.readString(log) else "")
        // No log tail here: FET prints nothing at all while it searches, so
        // everything it has said by now is the start-up dialogs, and pasting
        // those under "it did not finish" buries the one sentence that matters.
        _        <- IO.raiseUnless(finished)(RuntimeException(
                      s"Nie skończył w ${config.timeout.toMinutes} min i został przerwany. " +
                      "Poluzuj twarde ograniczenia albo zdejmij limit czasu."))
        code     <- IO.blocking(p.exitValue())
        // FET reports a failure to lay out the week in its exit code, and says
        // which rule defeated it on the way out. Carrying that through verbatim
        // is better than translating it: it is FET's vocabulary and a person
        // reading it is better served by the original.
        _        <- IO.raiseWhen(code != 0)(RuntimeException(
                      s"fet-cl zakończył się kodem $code:\n${complaint(said)}"))
      yield ()
    }

  /**
   * What FET actually said, with its doorway talk removed.
   *
   * It opens by announcing that the file claims a newer format, and again
   * that gap constraints are not optimised for speed, each as a dialog it
   * then answers itself. All of that is printed before the search starts and
   * none of it is why a run failed — but it is two hundred words, and pasted
   * in front of the real complaint it is all anybody reads.
   *
   * Everything after the last answered dialog is the run proper.
   */
  private def complaint(said: String): String =
    val marker = "Pressing default button"
    val proper = said.lastIndexOf(marker) match
      case -1 => said
      case at => said.drop(said.indexOf('\n', at) + 1)
    proper.trim.takeRight(2000)

  /**
   * Wait for the process, in slices.
   *
   * One long `waitFor` would be simpler and would make the run impossible to
   * abandon: `IO.blocking` cannot be cancelled, so asking to stop a search
   * would hang until the search finished anyway — half an hour, by the
   * timeout. Short waits put a cancellation point between each, so stopping
   * lands within a second and the resource above then kills the process.
   */
  private def awaitExit(process: java.lang.Process, within: FiniteDuration,
                        dir: Path, total: Int,
                        onProgress: Progress => IO[Unit]): IO[Boolean] =
    val unbounded = within <= Duration.Zero
    def waitFrom(left: FiniteDuration, said: Int): IO[Boolean] =
      if !unbounded && left <= Duration.Zero then IO.pure(false)
      else
        val slice = if unbounded then 1.second else left.min(1.second)
        IO.blocking(process.waitFor(slice.toMillis, TimeUnit.MILLISECONDS)).flatMap {
          case true  => IO.pure(true)
          case false =>
            // Between slices is also where progress is picked up, because the
            // slices already exist and a second is the right grain for a
            // number a person is watching.
            furthest(dir).flatMap { reached =>
              val fresh = reached.exists(_ != said)
              IO.whenA(fresh)(onProgress(Progress(reached.get, total)))
                .as(reached.getOrElse(said))
            }.flatMap(now => waitFrom(left - slice, now))
        }
    waitFrom(within, -1)

  /**
   * How many activities FET has placed at its best so far.
   *
   * It keeps a line per new maximum in `logs/max_placed_activities.txt`, of
   * the form "At time 0 h 0 m 3 s, FET reached 432 activities placed". The
   * last one is the high-water mark. Absent until the search proper starts,
   * and unreadable for an instant while FET appends to it, so every failure
   * here is simply "no news yet" rather than a fault — this is a progress
   * number, and no run should fail because one could not be read.
   */
  private val Reached = """FET reached (\d+) activities placed""".r

  private def furthest(dir: Path): IO[Option[Int]] =
    IO.blocking {
      val file = dir.resolve("out").resolve("logs").resolve("max_placed_activities.txt")
      if !Files.exists(file) then None
      else Reached.findAllMatchIn(Files.readString(file)).map(_.group(1).toInt).maxOption
    }.handleError(_ => None)



  /**
   * FET writes a directory of timetables, in several shapes, named after the
   * input file. The one with the placements in it is the activities list.
   */
  private def readAnswer(dir: Path): IO[String] =
    IO.blocking {
      val found = Files.walk(dir.resolve("out"))
        .filter(p => p.getFileName.toString.endsWith("_activities.xml"))
        .findFirst()
      if !found.isPresent then
        throw RuntimeException("fet-cl nie zostawił pliku z rozkładem zajęć")
      Files.readString(found.get)
    }

  /**
   * The placements, in the shape the database keeps them.
   *
   * FET answers with the room's name, which is the number we gave it, so the
   * rooms are looked back up here rather than thrown away — a plan without
   * them is a plan nobody can hang in a corridor.
   */
  def placements(result: FetImport.Result, rooms: List[Room]): List[Placement] =
    val byName = rooms.map(r => r.number -> r.id).toMap
    result.placed.flatMap { p =>
      val room = p.room.flatMap(byName.get)
      p.what match
        case LessonRef.Line(lineId, block) =>
          Some(Placement(Some(lineId), None, None, block, p.day + 1, p.position + 1, room))
        case LessonRef.CrossClassGroupRef(_, groupId, block) =>
          Some(Placement(None, Some(groupId), None, block, p.day + 1, p.position + 1, room))
        case LessonRef.Individual(lessonId, block) =>
          Some(Placement(None, None, Some(lessonId), block, p.day + 1, p.position + 1, room))
        case _ => None
    }
