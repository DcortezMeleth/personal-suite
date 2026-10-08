package bel.repository

import bel.domain.{LessonAudience, LessonKind}
import bel.importer.*
import bel.repository.DoobieMeta.given
import cats.effect.IO
import cats.syntax.apply.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

/**
 * Writes an import plan.
 *
 * The whole thing is one transaction. A half-applied arkusz — teachers without
 * their allocations, classes without their teachers — would be worse than no
 * import at all, because it looks like data rather than like a failure.
 */
class ImportRepository(xa: Transactor[IO]):

  case class Applied(
    teachers: Int,
    subjects: Int,
    classes: Int,
    lessonLines: Int,
    crossClassUnits: Int
  )

  /** What the import would overwrite, so the user can be asked first. */
  def existingCounts(schoolId: UUID): IO[Applied] =
    val count = (table: String) =>
      Fragment.const(s"SELECT count(*) FROM $table WHERE school_id = ?")
    (for
      teachers <- sql"SELECT count(*) FROM teacher WHERE school_id = $schoolId".query[Int].unique
      subjects <- sql"SELECT count(*) FROM subject WHERE school_id = $schoolId".query[Int].unique
      classes  <- sql"SELECT count(*) FROM school_class WHERE school_id = $schoolId".query[Int].unique
      lines    <- sql"SELECT count(*) FROM lesson_line WHERE school_id = $schoolId".query[Int].unique
      units    <- sql"SELECT count(*) FROM cross_class_unit WHERE school_id = $schoolId".query[Int].unique
    yield Applied(teachers, subjects, classes, lines, units)).transact(xa)

  def apply(schoolId: UUID, plan: ImportPlan): IO[Applied] =
    val program =
      for
        _        <- clear(schoolId)
        teachers <- insertTeachers(schoolId, plan.teachers)
        subjects <- insertSubjects(schoolId, plan.subjects)
        classes  <- insertClasses(schoolId, plan.classes, teachers)
        lines    <- insertLessonLines(schoolId, plan.lessonLines, classes, subjects, teachers)
        units    <- insertUnits(schoolId, plan.crossClassUnits, classes, subjects, teachers)
        _        <- deriveTeacherSubjects(schoolId, plan, classes, subjects, teachers)
      yield Applied(teachers.size, subjects.size, classes.size, lines, units)
    program.transact(xa)

  /**
   * Rooms and room kinds are deliberately left alone: the arkusz does not
   * mention them, so an import would otherwise silently discard the room list
   * someone had entered by hand.
   */
  private def clear(schoolId: UUID): ConnectionIO[Unit] =
    for
      _ <- sql"DELETE FROM lesson_line WHERE school_id = $schoolId".update.run
      _ <- sql"DELETE FROM cross_class_unit WHERE school_id = $schoolId".update.run
      _ <- sql"DELETE FROM school_class WHERE school_id = $schoolId".update.run
      _ <- sql"DELETE FROM teacher WHERE school_id = $schoolId".update.run
      _ <- sql"DELETE FROM subject WHERE school_id = $schoolId".update.run
    yield ()

  private def insertTeachers(
    schoolId: UUID,
    teachers: List[PlannedTeacher]
  ): ConnectionIO[Map[String, UUID]] =
    teachers
      .traverse { t =>
        sql"""
          INSERT INTO teacher (school_id, code, first_name, last_name, pensum)
          VALUES ($schoolId, ${t.code}, ${t.firstName}, ${t.lastName}, ${t.pensum})
          RETURNING id
        """.query[UUID].unique.map(t.code -> _)
      }
      .map(_.toMap)

  private def insertSubjects(
    schoolId: UUID,
    subjects: List[PlannedSubject]
  ): ConnectionIO[Map[String, UUID]] =
    subjects
      .traverse { s =>
        sql"""
          INSERT INTO subject (school_id, code, name, optional)
          VALUES ($schoolId, ${s.code}, ${s.name}, ${s.optional})
          RETURNING id
        """.query[UUID].unique.map(s.code -> _)
      }
      .map(_.toMap)

  private def insertClasses(
    schoolId: UUID,
    classes: List[PlannedClass],
    teachers: Map[String, UUID]
  ): ConnectionIO[Map[String, UUID]] =
    classes
      .traverse { c =>
        sql"""
          INSERT INTO school_class
            (school_id, year, letter, specialisation, homeroom_teacher_id, student_count, girl_count)
          VALUES ($schoolId, ${c.year}, ${c.letter}, ${c.profile},
                  ${c.homeroomTeacherCode.flatMap(teachers.get)}, ${c.studentCount}, ${c.girlCount})
          RETURNING id
        """.query[UUID].unique.map(c.code -> _)
      }
      .map(_.toMap)

  private def insertLessonLines(
    schoolId: UUID,
    lines: List[PlannedLessonLine],
    classes: Map[String, UUID],
    subjects: Map[String, UUID],
    teachers: Map[String, UUID]
  ): ConnectionIO[Int] =
    lines
      .traverse { l =>
        sql"""
          INSERT INTO lesson_line
            (school_id, class_id, subject_id, audience, kind, teacher_id, blocks)
          VALUES ($schoolId, ${classes(l.classCode)}, ${subjects(l.subjectCode)},
                  ${l.audience}, ${l.kind}, ${l.teacherCode.flatMap(teachers.get)}, ${l.blocks})
        """.update.run
      }
      .map(_.sum)

  /**
   * The arkusz never says what a teacher teaches, only what they have been
   * allocated — which amounts to the same thing and is the only source there
   * is. Without this every teacher would import with no subjects at all, and
   * the check for "allocated a subject they do not teach" would flag the whole
   * school.
   */
  private def deriveTeacherSubjects(
    schoolId: UUID,
    plan: ImportPlan,
    classes: Map[String, UUID],
    subjects: Map[String, UUID],
    teachers: Map[String, UUID]
  ): ConnectionIO[Unit] =
    val fromLines = plan.lessonLines.flatMap(l => l.teacherCode.map(_ -> l.subjectCode))
    val fromUnits = plan.crossClassUnits.flatMap(u =>
      u.groups.flatMap(_.teacherCode).map(_ -> u.subjectCode))

    (fromLines ++ fromUnits).distinct
      .flatMap((teacherCode, subjectCode) =>
        (teachers.get(teacherCode), subjects.get(subjectCode)).tupled)
      .traverse((teacherId, subjectId) =>
        sql"""INSERT INTO teacher_subject (teacher_id, subject_id, school_id)
              VALUES ($teacherId, $subjectId, $schoolId)""".update.run)
      .void

  private def insertUnits(
    schoolId: UUID,
    units: List[PlannedCrossClassUnit],
    classes: Map[String, UUID],
    subjects: Map[String, UUID],
    teachers: Map[String, UUID]
  ): ConnectionIO[Int] =
    units
      .traverse { unit =>
        for
          unitId <- sql"""
                      INSERT INTO cross_class_unit (school_id, subject_id, name, blocks)
                      VALUES ($schoolId, ${subjects(unit.subjectCode)}, ${unit.name}, ${unit.blocks})
                      RETURNING id
                    """.query[UUID].unique
          _ <- unit.groups.traverse { group =>
                 for
                   groupId <- sql"""
                                INSERT INTO cross_class_group (unit_id, school_id, label, teacher_id)
                                VALUES ($unitId, $schoolId, ${group.label},
                                        ${group.teacherCode.flatMap(teachers.get)})
                                RETURNING id
                              """.query[UUID].unique
                   _ <- group.classCodes.flatMap(classes.get).distinct.traverse(classId =>
                          sql"""INSERT INTO cross_class_group_class (group_id, class_id, school_id)
                                VALUES ($groupId, $classId, $schoolId)""".update.run)
                 yield ()
               }
        yield 1
      }
      .map(_.sum)
