package bel.repository

import bel.domain.*
import cats.effect.IO
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

class SchoolRepository(xa: Transactor[IO]):

  // Column order is the constructor order of School, through SchedulingSettings
  // and then Optimisation: doobie reads them positionally, so a column moved
  // here without moving there compiles and then quietly reads the wrong value.
  private val columns =
    fr"""id, name, years, max_consecutive_teacher_gaps,
         default_teacher_max_working_days, default_teacher_max_lessons_per_day,
         max_teacher_gaps_per_week, min_class_hours_daily, max_class_hours_daily,
         class_day_weight, home_room_weight, soft_room_weight,
         created_at"""

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

  /**
   * The school's own settings, and deliberately not what it optimises for.
   *
   * Those have their own screen and their own call below. Writing them here
   * too would mean the Szkoła page silently clearing every weight the
   * Konfiguracja page had set, because it does not know they exist and sends
   * nothing for them — which is exactly what it did once.
   */
  def update(id: UUID, cmd: UpdateSchool): IO[Option[School]] =
    (fr"""
      UPDATE school SET
        name                                = ${cmd.name.trim},
        years                               = ${cmd.years},
        max_consecutive_teacher_gaps        = ${cmd.settings.maxConsecutiveTeacherGaps},
        default_teacher_max_working_days    = ${cmd.settings.defaultTeacherMaxWorkingDays},
        default_teacher_max_lessons_per_day = ${cmd.settings.defaultTeacherMaxLessonsPerDay}
      WHERE id = $id
      RETURNING""" ++ columns)
      .query[School].option.transact(xa)

  /** What a good week is, as distinct from a legal one — ADR 0005. */
  def updateOptimisation(id: UUID, tuning: Optimisation): IO[Option[School]] =
    (fr"""
      UPDATE school SET
        max_teacher_gaps_per_week = ${tuning.maxTeacherGapsPerWeek},
        min_class_hours_daily     = ${tuning.minClassHoursDaily},
        max_class_hours_daily     = ${tuning.maxClassHoursDaily},
        class_day_weight          = ${tuning.classDayWeight},
        home_room_weight          = ${tuning.homeRoomWeight},
        soft_room_weight          = ${tuning.softRoomWeight}
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
