package bel.repository

import bel.domain.*
import cats.effect.IO
import cats.syntax.traverse.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

class SchoolClassRepository(xa: Transactor[IO]):

  private val select =
    fr"""SELECT id, year, letter, specialisation, homeroom_teacher_id, student_count, girl_count
         FROM school_class"""

  def findAll(schoolId: UUID): IO[List[SchoolClass]] =
    (select ++ fr"WHERE school_id = $schoolId ORDER BY year, letter")
      .query[SchoolClass].to[List].transact(xa)

  def find(id: UUID): IO[Option[SchoolClass]] =
    (select ++ fr"WHERE id = $id").query[SchoolClass].option.transact(xa)

  private def insert(schoolId: UUID, input: SchoolClassInput): ConnectionIO[UUID] =
    sql"""
      INSERT INTO school_class
        (school_id, year, letter, specialisation, homeroom_teacher_id, student_count, girl_count)
      VALUES ($schoolId, ${input.year}, ${SchoolClassValidation.normaliseLetter(input.letter)},
              ${input.specialisation.map(_.trim).filter(_.nonEmpty)}, ${input.homeroomTeacherId},
              ${input.studentCount}, ${input.girlCount})
      RETURNING id
    """.query[UUID].unique

  def create(schoolId: UUID, input: SchoolClassInput): IO[SchoolClass] =
    insert(schoolId, input).transact(xa).flatMap(id => find(id).map(_.get))

  def update(id: UUID, input: SchoolClassInput): IO[Option[SchoolClass]] =
    sql"""
      UPDATE school_class SET
        year = ${input.year},
        letter = ${SchoolClassValidation.normaliseLetter(input.letter)},
        specialisation = ${input.specialisation.map(_.trim).filter(_.nonEmpty)},
        homeroom_teacher_id = ${input.homeroomTeacherId},
        student_count = ${input.studentCount},
        girl_count = ${input.girlCount}
      WHERE id = $id
    """.update.run.transact(xa).flatMap(n => if n == 0 then IO.pure(None) else find(id))

  def delete(id: UUID): IO[Int] =
    sql"DELETE FROM school_class WHERE id = $id".update.run.transact(xa)

  /**
   * Creates the letters that are missing and leaves the rest alone, so running
   * it twice is harmless and adding one more class later does not mean editing
   * around the ones that already exist.
   */
  def generate(schoolId: UUID, cmd: GenerateClasses): IO[List[SchoolClass]] =
    val wanted = cmd.letters.map(SchoolClassValidation.normaliseLetter).filter(_.nonEmpty).distinct
    val program =
      for
        existing <- sql"""SELECT letter FROM school_class
                          WHERE school_id = $schoolId AND year = ${cmd.year}"""
                      .query[String].to[List]
        missing   = wanted.filterNot(existing.contains)
        _        <- missing.traverse(letter =>
                      insert(schoolId, SchoolClassInput(cmd.year, letter, None, None, None, None)))
      yield ()
    program.transact(xa) >> findAll(schoolId)
