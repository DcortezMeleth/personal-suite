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
 */
enum LessonRef:
  case Line(lineId: UUID, block: Int)
  case CrossClass(unitId: UUID, groupId: UUID, classId: UUID, block: Int)
  /** The taught group, as distinct from the classes it draws from. */
  case CrossClassGroupRef(unitId: UUID, groupId: UUID, block: Int)
  /** One student taught alone: books a teacher and a room, and no class. */
  case Individual(lessonId: UUID, block: Int)
