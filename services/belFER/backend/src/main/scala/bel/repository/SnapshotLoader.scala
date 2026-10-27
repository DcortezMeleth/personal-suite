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
  rooms: RoomRepository
):

  def load(schoolId: UUID): IO[Option[SchoolSnapshot]] =
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
          allRooms    <- rooms.findRooms(schoolId)
          kinds       <- rooms.findKinds(schoolId)
          counts      <- kinds.traverse(kind => rooms.roomsOfKind(kind.id).map(count => kind.id -> count))
        yield Some(SchoolSnapshot(
          settings = school.settings,
          slotsPerDay = slots,
          teachingDays = 5,
          classes = allClasses,
          teachers = allTeachers,
          subjects = allSubjects,
          lessonLines = allLines,
          crossClassUnits = units,
          teacherSubjects = allTeachers.map(t => t.id -> t.subjectIds.toSet).toMap,
          roomsOfKind = counts.toMap,
          totalRooms = allRooms.size
        ))
    }

  /** The rooms themselves, which only the solver needs — validation counts kinds. */
  def roomsOf(schoolId: UUID): IO[List[Room]] = rooms.findRooms(schoolId)
