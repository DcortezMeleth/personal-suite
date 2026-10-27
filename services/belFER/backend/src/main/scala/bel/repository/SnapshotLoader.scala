package bel.repository

import bel.domain.*
import cats.effect.IO
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
        yield Some((SchoolSnapshot(
          settings = school.settings,
          slotsPerDay = slots,
          teachingDays = SchoolWeek.TeachingDays,
          classes = allClasses,
          teachers = allTeachers,
          subjects = allSubjects,
          lessonLines = allLines,
          crossClassUnits = units,
          individualPlans = plans,
          teacherSubjects = allTeachers.map(t => t.id -> t.subjectIds.toSet).toMap,
          // Counted from the rooms already in hand rather than asked for one
          // query per kind: findRooms attaches each room's kinds, and the
          // assignment table is keyed on (room, kind) so a room counts once.
          // Listed over the kinds rather than grouped over the rooms, so that
          // a kind no room carries still reports zero — which is the case
          // validation cares about.
          roomsOfKind = kinds.map(k => k.id -> allRooms.count(_.kinds.exists(_.id == k.id))).toMap,
          totalRooms = allRooms.size
        ), allRooms))
    }
