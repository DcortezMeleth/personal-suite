package bel.repository

import bel.domain.*
import bel.repository.DoobieMeta.given
import cats.effect.IO
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

class LessonLineRepository(xa: Transactor[IO]):

  private val select =
    fr"""SELECT id, class_id, subject_id, audience, kind, teacher_id, support_teacher_id, blocks
         FROM lesson_line"""

  def findForSchool(schoolId: UUID): IO[List[LessonLine]] =
    (select ++ fr"WHERE school_id = $schoolId").query[LessonLine].to[List].transact(xa)

  def findForClass(classId: UUID): IO[List[LessonLine]] =
    (select ++ fr"WHERE class_id = $classId ORDER BY subject_id, audience, kind")
      .query[LessonLine].to[List].transact(xa)

  private def load(id: UUID): ConnectionIO[Option[LessonLine]] =
    (select ++ fr"WHERE id = $id").query[LessonLine].option

  def create(schoolId: UUID, input: LessonLineInput): IO[LessonLine] =
    sql"""
      INSERT INTO lesson_line
        (school_id, class_id, subject_id, audience, kind, teacher_id, support_teacher_id, blocks)
      VALUES ($schoolId, ${input.classId}, ${input.subjectId}, ${input.audience}, ${input.kind},
              ${input.teacherId}, ${input.supportTeacherId}, ${input.blocks})
      RETURNING id
    """.query[UUID].unique.flatMap(id => load(id).map(_.get)).transact(xa)

  def update(id: UUID, input: LessonLineInput): IO[Option[LessonLine]] =
    sql"""
      UPDATE lesson_line SET
        subject_id = ${input.subjectId},
        audience = ${input.audience},
        kind = ${input.kind},
        teacher_id = ${input.teacherId},
        support_teacher_id = ${input.supportTeacherId},
        blocks = ${input.blocks}
      WHERE id = $id
    """.update.run.flatMap(n => if n == 0 then doobie.free.connection.pure(Option.empty) else load(id)).transact(xa)

  def delete(id: UUID): IO[Int] =
    sql"DELETE FROM lesson_line WHERE id = $id".update.run.transact(xa)
