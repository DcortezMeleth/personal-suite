package bel.repository

import bel.domain.*
import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.traverse.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

class TeacherRepository(xa: Transactor[IO]):

  private type Bare = (UUID, String, String, String, Option[UUID], Option[Int], Option[Int], Option[Int])

  private def subjectsFor(ids: List[UUID]): ConnectionIO[Map[UUID, List[UUID]]] =
    ids match
      case Nil => doobie.free.connection.pure(Map.empty)
      case xs =>
        val nel = NonEmptyList.fromListUnsafe(xs)
        (fr"SELECT teacher_id, subject_id FROM teacher_subject WHERE" ++
          Fragments.in(fr"teacher_id", nel))
          .query[(UUID, UUID)].to[List].map(_.groupMap(_._1)(_._2))

  private def blocksFor(ids: List[UUID]): ConnectionIO[Map[UUID, List[UnavailabilityBlock]]] =
    ids match
      case Nil => doobie.free.connection.pure(Map.empty)
      case xs =>
        val nel = NonEmptyList.fromListUnsafe(xs)
        (fr"""SELECT teacher_id, day_of_week, from_position, to_position
              FROM teacher_unavailability WHERE""" ++ Fragments.in(fr"teacher_id", nel) ++
          fr"ORDER BY day_of_week, from_position")
          .query[(UUID, UnavailabilityBlock)].to[List].map(_.groupMap(_._1)(_._2))

  private def assemble(bare: List[Bare]): ConnectionIO[List[Teacher]] =
    val ids = bare.map(_._1)
    for
      subjects <- subjectsFor(ids)
      blocks   <- blocksFor(ids)
    yield bare.map { case (id, code, first, last, room, days, lessons, pensum) =>
      Teacher(
        id, code, first, last, room,
        subjects.getOrElse(id, Nil),
        days, lessons, pensum,
        blocks.getOrElse(id, Nil)
      )
    }

  private val selectBare =
    fr"""SELECT id, code, first_name, last_name, home_room_id,
                max_working_days, max_lessons_per_day, pensum
         FROM teacher"""

  def findAll(schoolId: UUID): IO[List[Teacher]] =
    val program =
      for
        bare     <- (selectBare ++ fr"WHERE school_id = $schoolId ORDER BY last_name, first_name")
                      .query[Bare].to[List]
        teachers <- assemble(bare)
      yield teachers
    program.transact(xa)

  private def load(id: UUID): ConnectionIO[Option[Teacher]] =
    for
      bare     <- (selectBare ++ fr"WHERE id = $id").query[Bare].to[List]
      teachers <- assemble(bare)
    yield teachers.headOption

  private def replaceChildren(id: UUID, schoolId: UUID, input: TeacherInput): ConnectionIO[Unit] =
    for
      _ <- sql"DELETE FROM teacher_subject WHERE teacher_id = $id".update.run
      _ <- input.subjectIds.traverse(subjectId =>
             sql"""INSERT INTO teacher_subject (teacher_id, subject_id, school_id)
                   VALUES ($id, $subjectId, $schoolId)""".update.run)
      _ <- sql"DELETE FROM teacher_unavailability WHERE teacher_id = $id".update.run
      _ <- input.unavailability.traverse(block =>
             sql"""INSERT INTO teacher_unavailability (teacher_id, day_of_week, from_position, to_position)
                   VALUES ($id, ${block.dayOfWeek}, ${block.fromPosition}, ${block.toPosition})""".update.run)
    yield ()

  def create(schoolId: UUID, input: TeacherInput): IO[Teacher] =
    val program =
      for
        id <- sql"""
                INSERT INTO teacher (school_id, code, first_name, last_name, home_room_id,
                                     max_working_days, max_lessons_per_day, pensum)
                VALUES ($schoolId, ${input.code.trim}, ${input.firstName.trim}, ${input.lastName.trim},
                        ${input.homeRoomId}, ${input.maxWorkingDays}, ${input.maxLessonsPerDay},
                        ${input.pensum})
                RETURNING id
              """.query[UUID].unique
        _     <- replaceChildren(id, schoolId, input)
        saved <- load(id)
      yield saved.get
    program.transact(xa)

  def update(schoolId: UUID, id: UUID, input: TeacherInput): IO[Option[Teacher]] =
    val program =
      for
        updated <- sql"""
                     UPDATE teacher SET
                       code = ${input.code.trim},
                       first_name = ${input.firstName.trim},
                       last_name = ${input.lastName.trim},
                       home_room_id = ${input.homeRoomId},
                       max_working_days = ${input.maxWorkingDays},
                       max_lessons_per_day = ${input.maxLessonsPerDay},
                       pensum = ${input.pensum}
                     WHERE id = $id
                   """.update.run
        _     <- if updated == 0 then doobie.free.connection.unit else replaceChildren(id, schoolId, input)
        saved <- if updated == 0 then doobie.free.connection.pure(Option.empty[Teacher]) else load(id)
      yield saved
    program.transact(xa)

  def delete(id: UUID): IO[Int] =
    sql"DELETE FROM teacher WHERE id = $id".update.run.transact(xa)
