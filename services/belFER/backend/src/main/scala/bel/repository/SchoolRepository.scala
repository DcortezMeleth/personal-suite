package bel.repository

import bel.domain.*
import cats.effect.IO
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

class SchoolRepository(xa: Transactor[IO]):

  private val columns =
    fr"""id, name, years, allow_class_gaps, max_consecutive_teacher_gaps,
         default_teacher_max_working_days, default_teacher_max_lessons_per_day, created_at"""

  def findAll: IO[List[School]] =
    (fr"SELECT" ++ columns ++ fr"FROM school ORDER BY name")
      .query[School].to[List].transact(xa)

  def find(id: UUID): IO[Option[School]] =
    (fr"SELECT" ++ columns ++ fr"FROM school WHERE id = $id")
      .query[School].option.transact(xa)

  def create(cmd: CreateSchool): IO[School] =
    (fr"""
      INSERT INTO school (name, years) VALUES (${cmd.name.trim}, ${cmd.years})
      RETURNING""" ++ columns)
      .query[School].unique.transact(xa)

  def update(id: UUID, cmd: UpdateSchool): IO[Option[School]] =
    (fr"""
      UPDATE school SET
        name                                = ${cmd.name.trim},
        years                               = ${cmd.years},
        allow_class_gaps                    = ${cmd.settings.allowClassGaps},
        max_consecutive_teacher_gaps        = ${cmd.settings.maxConsecutiveTeacherGaps},
        default_teacher_max_working_days    = ${cmd.settings.defaultTeacherMaxWorkingDays},
        default_teacher_max_lessons_per_day = ${cmd.settings.defaultTeacherMaxLessonsPerDay}
      WHERE id = $id
      RETURNING""" ++ columns)
      .query[School].option.transact(xa)

  /** How many lessons the bell schedule has. Several validations bound
    * themselves by it: a blocked hour, or a block, past the end of the day
    * cannot be placed. */
  def slotCount(schoolId: UUID): IO[Int] =
    sql"SELECT count(*) FROM time_slot WHERE school_id = $schoolId"
      .query[Int].unique.transact(xa)

  def findTimeSlots(schoolId: UUID): IO[List[TimeSlot]] =
    sql"""
      SELECT id, position, starts_at, ends_at FROM time_slot
      WHERE school_id = $schoolId ORDER BY position
    """.query[TimeSlot].to[List].transact(xa)

  /**
   * Replaces the whole bell schedule in one transaction. Editing slot by slot
   * would leave the schedule briefly non-contiguous, which is exactly the state
   * the validation exists to prevent.
   */
  def replaceTimeSlots(schoolId: UUID, slots: List[TimeSlotInput]): IO[List[TimeSlot]] =
    val delete = sql"DELETE FROM time_slot WHERE school_id = $schoolId".update.run
    val insert = Update[(UUID, Int, java.time.LocalTime, java.time.LocalTime)](
      "INSERT INTO time_slot (school_id, position, starts_at, ends_at) VALUES (?, ?, ?, ?)"
    ).updateMany(slots.map(s => (schoolId, s.position, s.startsAt, s.endsAt)))

    val select = sql"""
      SELECT id, position, starts_at, ends_at FROM time_slot
      WHERE school_id = $schoolId ORDER BY position
    """.query[TimeSlot].to[List]

    val replace =
      for
        _     <- delete
        _     <- insert
        saved <- select
      yield saved

    replace.transact(xa)
