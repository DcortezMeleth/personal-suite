package bel.solver

import com.typesafe.config.ConfigFactory
import java.nio.file.Path
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.duration.*

/**
 * How this machine reaches FET.
 *
 * FET is a separate program and the way it is reached differs by deployment:
 * installed and on the path, or a container, or something else again. That
 * belongs in configuration, and configuration has two shapes to satisfy — a
 * file, which is happier with a list of arguments, and an environment
 * variable, which can only carry a string. Both have to arrive as the same
 * command.
 */
class FetConfigSpec extends AnyFunSuite:

  private def load(fet: String) =
    FetRunner.Config.from(ConfigFactory.parseString(
      s"""belfer.fet {
         |  $fet
         |  timeout = 5 minutes
         |  work-root = "/tmp/belfer-fet"
         |}""".stripMargin).resolve())

  test("a command written as a list arrives argument for argument"):
    val c = load("""command = ["fet-cl", "--inputfile={dir}/school.fet"]""")
    assert(c.command === List("fet-cl", "--inputfile={dir}/school.fet"))

  test("a command written as one string is split on whitespace"):
    val c = load("""command = "fet-cl --inputfile={dir}/school.fet --outputdir={dir}/out" """)
    assert(c.command === List("fet-cl", "--inputfile={dir}/school.fet", "--outputdir={dir}/out"))

  test("the container form survives the splitting with its arguments intact"):
    val c = load(
      """command = "docker run --rm -v {dir}:/data belfer-fet:local --inputfile=/data/school.fet" """)
    assert(c.command === List("docker", "run", "--rm", "-v", "{dir}:/data",
      "belfer-fet:local", "--inputfile=/data/school.fet"))

  test("runs of whitespace do not become empty arguments"):
    val c = load("""command = "  fet-cl    --inputfile={dir}/x  " """)
    assert(c.command === List("fet-cl", "--inputfile={dir}/x"))

  test("the timeout and the working directory come through"):
    val c = load("""command = "fet-cl" """)
    assert(c.timeout === 5.minutes)
    assert(c.workRoot === Path.of("/tmp/belfer-fet"))

  /**
   * An empty command would otherwise surface as a process failure at the
   * point somebody presses generate, rather than as a misconfiguration.
   */
  test("a command that would run nothing is refused"):
    assertThrows[IllegalArgumentException](load("""command = "  " """))
    assertThrows[IllegalArgumentException](load("""command = []"""))

  /**
   * The file we ship has to satisfy its own reader. The database password is
   * deliberately given no default — the JVM should refuse to start rather
   * than try an empty one — so a test that loads the file has to stand in for
   * it; that is the only reason this is not a one-liner.
   */
  test("the shipped default is a command, whatever it is"):
    val shipped = FetRunner.Config.from(
      ConfigFactory.parseResources("application.conf")
        .withFallback(ConfigFactory.parseString("BELFER_DATABASE_PASSWORD = unused"))
        // work-root is written against ${user.home}, which reaches the file
        // from the system properties ConfigFactory.load() folds in.
        .withFallback(ConfigFactory.systemProperties())
        .resolve())
    assert(shipped.command.nonEmpty)
    assert(shipped.command.mkString(" ").contains("{dir}"),
      "the working directory has to reach the command somehow")
    assert(shipped.timeout.toSeconds > 0)
