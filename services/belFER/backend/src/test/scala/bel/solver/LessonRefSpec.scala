package bel.solver

import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite

/**
 * The string form of a lesson identifier, and its inverse.
 *
 * Only CP-SAT needs this: it names what it places with a string and hands the
 * string back, so the week it returns is unreadable if writing and reading
 * ever disagree. FET carries integers and we keep the mapping ourselves, which
 * is why this lives beside the enum rather than inside either engine.
 *
 * They were written apart once — a builder in the model and a parser in the
 * reporting — and the pair was incomplete: tuition could be written and not
 * read. Keeping them in one place is what makes "they are inverses" a thing a
 * test can say.
 */
class LessonRefSpec extends AnyFunSuite:

  private val line = UUID.randomUUID
  private val unit = UUID.randomUUID
  private val group = UUID.randomUUID
  private val cls = UUID.randomUUID
  private val lesson = UUID.randomUUID

  private val every = List(
    LessonRef.Line(line, 0),
    LessonRef.Line(line, 7),
    LessonRef.CrossClass(unit, group, cls, 1),
    LessonRef.CrossClassGroupRef(unit, group, 2),
    LessonRef.Individual(lesson, 0))

  test("every kind of reference survives being written and read back"):
    for ref <- every do
      assert(LessonRef.parse(ref.key) === Some(ref), s"$ref did not survive ${ref.key}")

  test("each kind is written distinguishably from the others"):
    assert(every.map(_.key).distinct.size === every.size)

  test("nothing that is not one of ours is read as one of ours"):
    for junk <- List("", "line", "line|not-a-uuid|0", s"line|$line", s"line|$line|x",
                     s"line|$line|0|extra", "nonsense", s"$line") do
      assert(LessonRef.parse(junk) === None, s"'$junk' was read as a lesson")
