package bel.solver

import java.util.UUID

/**
 * What a placement refers to, in our own terms.
 *
 * Every engine we hand a timetable to numbers or names the things it places
 * however it likes, and hands those back. FET answers with the integers the
 * file gave it; CP-SAT answers with the strings the model was built from.
 * Neither knows what a lesson line is, so this is what the answer is turned
 * back into before anything else looks at it — and it is deliberately not a
 * type either engine can see, which is ADR 0001's rule holding.
 *
 * `key` and `parse` are the string form, for an engine that can only carry
 * one. They are inverses, and the test says so; FET needs neither, because it
 * carries integers and we keep the mapping ourselves.
 */
enum LessonRef:
  case Line(lineId: UUID, block: Int)
  case CrossClass(unitId: UUID, groupId: UUID, classId: UUID, block: Int)
  /** The taught group, as distinct from the classes it draws from. */
  case CrossClassGroupRef(unitId: UUID, groupId: UUID, block: Int)
  /** One student taught alone: books a teacher and a room, and no class. */
  case Individual(lessonId: UUID, block: Int)

  /** How this is written when an engine can only carry a string. */
  def key: String = this match
    case Line(lineId, block)             => s"line|$lineId|$block"
    case CrossClass(u, g, c, block)      => s"cross|$u|$g|$c|$block"
    case CrossClassGroupRef(u, g, block) => s"group|$u|$g|$block"
    case Individual(lessonId, block)     => s"individual|$lessonId|$block"

object LessonRef:

  def parse(id: String): Option[LessonRef] =
    id.split('|').toList match
      case "line" :: line :: block :: Nil =>
        for
          l <- uuid(line)
          b <- block.toIntOption
        yield LessonRef.Line(l, b)
      case "group" :: unit :: group :: block :: Nil =>
        for
          u <- uuid(unit)
          g <- uuid(group)
          b <- block.toIntOption
        yield LessonRef.CrossClassGroupRef(u, g, b)
      case "cross" :: unit :: group :: cls :: block :: Nil =>
        for
          u <- uuid(unit)
          g <- uuid(group)
          c <- uuid(cls)
          b <- block.toIntOption
        yield LessonRef.CrossClass(u, g, c, b)
      case "individual" :: lesson :: block :: Nil =>
        for
          l <- uuid(lesson)
          b <- block.toIntOption
        yield LessonRef.Individual(l, b)
      case _ => None

  private def uuid(s: String): Option[UUID] = scala.util.Try(UUID.fromString(s)).toOption
