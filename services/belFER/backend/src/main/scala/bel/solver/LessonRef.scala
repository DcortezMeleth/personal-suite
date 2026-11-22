package bel.solver

import java.util.UUID

/**
 * What a placement refers to, in our own terms.
 *
 * FET numbers the activities it places with the integers the file gave it and
 * hands those back. It does not know what a lesson line is, so this is what
 * its answer is turned back into before anything else looks at it — and it is
 * deliberately not a type FET's side ever sees, which is ADR 0001's rule
 * holding. FetExport keeps the mapping from FET's integers to these.
 *
 * It also writes each one into its activity's comment, as `key`, because the
 * integers alone are not enough to read an answer later: they are positions
 * in an export, and the next export of a school that has since been edited
 * numbers its lessons differently (REVIEW_2026-11-21 F2). FET copies the
 * comments into its own output, so an answer carries what each activity was.
 */
enum LessonRef:
  case Line(lineId: UUID, block: Int)
  /** The taught group, as distinct from the classes it draws from. */
  case CrossClassGroupRef(unitId: UUID, groupId: UUID, block: Int)
  /** One student taught alone: books a teacher and a room, and no class. */
  case Individual(lessonId: UUID, block: Int)

  /** How this is written into a FET activity's comment. `parse` reads it back. */
  def key: String = this match
    case Line(lineId, block)             => s"line|$lineId|$block"
    case CrossClassGroupRef(u, g, block) => s"group|$u|$g|$block"
    case Individual(lessonId, block)     => s"individual|$lessonId|$block"

object LessonRef:

  def parse(text: String): Option[LessonRef] =
    def uuid(s: String) = scala.util.Try(UUID.fromString(s)).toOption
    text.trim.split('|').toList match
      case "line" :: line :: block :: Nil =>
        for l <- uuid(line); b <- block.toIntOption yield Line(l, b)
      case "group" :: unit :: group :: block :: Nil =>
        for u <- uuid(unit); g <- uuid(group); b <- block.toIntOption yield CrossClassGroupRef(u, g, b)
      case "individual" :: lesson :: block :: Nil =>
        for l <- uuid(lesson); b <- block.toIntOption yield Individual(l, b)
      case _ => None
