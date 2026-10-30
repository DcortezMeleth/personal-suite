package bel.solver

import bel.domain.*
import cats.effect.unsafe.implicits.global
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.duration.*

/**
 * Running FET as a process, when it misbehaves.
 *
 * None of this needs FET: what is being checked is how we hold a long-running
 * outside program, and a shell command that sleeps or fails stands in for one
 * perfectly. The happy path is covered by FetRoundTripSpec, which exercises
 * the translation without a process at all.
 */
class FetRunnerSpec extends AnyFunSuite:

  private val snapshot = SchoolSnapshot(
    SchedulingSettings(2, 5, 8), slotsPerDay = 8, teachingDays = 5,
    classes = Nil, teachers = Nil, subjects = Nil, lessonLines = Nil,
    crossClassUnits = Nil, individualPlans = Nil,
    teacherSubjects = Map.empty, roomsOfKind = Map.empty, totalRooms = 0)

  private def runWith(command: List[String], timeout: FiniteDuration) =
    val work = Files.createTempDirectory("fet-runner-spec")
    val config = FetRunner.Config(command, timeout = timeout, workRoot = work)
    val started = System.currentTimeMillis
    val outcome = FetRunner(snapshot, Nil, config).attempt.unsafeRunSync()
    (outcome, (System.currentTimeMillis - started).millis, work)

  /**
   * The one that was not true before it was tested. `IO.blocking` cannot be
   * cancelled, so a cats-effect timeout around it ended nothing: a two-second
   * timeout let a twenty-second command finish and then reported success.
   */
  test("a run that overruns its timeout is given up on, near the timeout"):
    // Through a shell, because the runner appends FET's six seed flags to
    // whatever command it is given and `sleep` would reject them outright —
    // which is worth knowing about the contract, not just about this test.
    val (outcome, took, _) = runWith(List("sh", "-c", "sleep 20"), 2.seconds)
    assert(took < 8.seconds, s"gave up after $took, which is not a two-second bound")
    assert(outcome.isLeft)
    assert(outcome.left.exists(_.getMessage.contains("nie skończył")), outcome.toString)

  /**
   * Giving up has to kill the process, not merely stop waiting for it. An
   * abandoned fet-cl keeps a container alive and the next run queues behind a
   * search nobody wants any more.
   */
  test("giving up kills the process rather than leaving it running"):
    val (_, _, work) = runWith(List("sh", "-c", "sleep 4; touch {dir}/finished-anyway"), 1.second)
    // Well past when the command would have left its mark had it survived.
    Thread.sleep(6000)
    val survivors = Option(work.toFile.listFiles).toList.flatten
      .flatMap(d => Option(d.listFiles).toList.flatten)
      .filter(_.getName == "finished-anyway")
    assert(survivors.isEmpty, s"the process outlived the run: $survivors")

  test("a non-zero exit carries FET's own complaint through"):
    val (outcome, _, _) = runWith(List("sh", "-c", "echo 'nie da sie ulozyc'; exit 3"), 30.seconds)
    assert(outcome.isLeft)
    val message = outcome.fold(_.getMessage, _ => "")
    assert(message.contains("kodem 3"), message)
    assert(message.contains("nie da sie ulozyc"), message)
