package bel.repository

import bel.domain.*
import cats.effect.IO
import cats.syntax.traverse.*
import java.util.UUID

/**
 * Gathers everything about a school that is needed to reason about its plan.
 *
 * Shared because two callers want the same picture: the checks that run before
 * anyone presses generate, and the generation itself. They differ only in what
 * they do with it, so assembling it twice would be two chances to disagree.
 */
class SnapshotLoader(
  schools: SchoolRepository,
  classes: SchoolClassRepository,
  teachers: TeacherRepository,
  subjects: SubjectRepository,
  lines: LessonLineRepository,
  crossClass: CrossClassUnitRepository,
  rooms: RoomRepository,
  individual: IndividualPlanRepository
):

  def load(schoolId: UUID): IO[Option[SchoolSnapshot]] =
    loadWithRooms(schoolId).map(_.map(_._1))

  /**
   * The same picture, keeping the rooms themselves.
   *
   * The snapshot counts rooms by kind, which is all validation ever needed.
   * Anything that lays a plan out needs the rooms, and they are already
   * fetched here to do the counting — so asking for them separately was a
   * second trip to the database for a list we had just thrown away.
   */
  def loadWithRooms(schoolId: UUID): IO[Option[(SchoolSnapshot, List[Room])]] =
    schools.find(schoolId).flatMap {
      case None => IO.pure(None)
      case Some(school) =>
        for
          slots       <- schools.slotCount(schoolId)
          allClasses  <- classes.findAll(schoolId)
          allTeachers <- teachers.findAll(schoolId)
          allSubjects <- subjects.findAll(schoolId)
          allLines    <- lines.findForSchool(schoolId)
          units       <- crossClass.findAll(schoolId)
          plans       <- individual.findAll(schoolId)
          allRooms    <- rooms.findRooms(schoolId)
          kinds       <- rooms.findKinds(schoolId)
          counts      <- kinds.traverse(kind => rooms.roomsOfKind(kind.id).map(count => kind.id -> count))
        yield Some((SchoolSnapshot(
          settings = school.settings,
          slotsPerDay = slots,
          teachingDays = 5,
          classes = allClasses,
          teachers = allTeachers,
          subjects = allSubjects,
          lessonLines = allLines,
          crossClassUnits = units,
          individualPlans = plans,
          teacherSubjects = allTeachers.map(t => t.id -> t.subjectIds.toSet).toMap,
          roomsOfKind = counts.toMap,
          totalRooms = allRooms.size
        ), allRooms))
    }
