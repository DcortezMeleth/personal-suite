package bel.repository

import bel.domain.*
import cats.effect.IO
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

class PlanRepository(xa: Transactor[IO]):

  private val columns =
    fr"id, school_id, name, created_at, made_by, took_millis, rules_kept, rules_total"

  def list(schoolId: UUID): IO[List[Plan]] =
    (fr"SELECT" ++ columns ++
      fr"FROM plan WHERE school_id = $schoolId ORDER BY created_at DESC")
      .query[Plan].to[List].transact(xa)

  def find(id: UUID): IO[Option[Plan]] =
    (fr"SELECT" ++ columns ++ fr"FROM plan WHERE id = $id")
      .query[Plan].option.transact(xa)

  def placements(planId: UUID): IO[List[Placement]] =
    sql"""
      SELECT lesson_line_id, cross_class_group_id, individual_lesson_id, block_index,
             day_of_week, position, room_id
      FROM plan_placement WHERE plan_id = $planId
      ORDER BY day_of_week, position
    """.query[Placement].to[List].transact(xa)

  /**
   * A plan and everything in it, or neither.
   *
   * One transaction on purpose: a timetable missing a hundred of its lessons
   * is worse than no timetable, because it looks like one.
   */
  def save(schoolId: UUID, plan: NewPlan): IO[Plan] =
    val insert =
      sql"""
        INSERT INTO plan (school_id, name, made_by, took_millis, rules_kept, rules_total)
        VALUES ($schoolId, ${plan.name.trim}, ${plan.madeBy},
                ${plan.tookMillis}, ${plan.rulesKept}, ${plan.rulesTotal})
      """.update.withUniqueGeneratedKeys[UUID]("id")

    val tx = for
      id <- insert
      _  <- Update[(UUID, Placement)](
              """INSERT INTO plan_placement
                   (plan_id, lesson_line_id, cross_class_group_id, individual_lesson_id,
                    block_index, day_of_week, position, room_id)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?)"""
            ).updateMany(plan.placements.map(id -> _))
      saved <- (fr"SELECT" ++ columns ++ fr"FROM plan WHERE id = $id").query[Plan].unique
    yield saved
    tx.transact(xa)

  /** Which plan the school is actually running. */
  def current(schoolId: UUID): IO[Option[Plan]] =
    (fr"SELECT" ++ columns ++ fr"""
       FROM plan JOIN plan_current ON plan_current.plan_id = plan.id
       WHERE plan.school_id = $schoolId""")
      .query[Plan].option.transact(xa)

  def makeCurrent(schoolId: UUID, planId: UUID): IO[Unit] =
    sql"""
      INSERT INTO plan_current (school_id, plan_id) VALUES ($schoolId, $planId)
      ON CONFLICT (school_id) DO UPDATE SET plan_id = EXCLUDED.plan_id
    """.update.run.transact(xa).void

  def delete(id: UUID): IO[Boolean] =
    sql"DELETE FROM plan WHERE id = $id".update.run.transact(xa).map(_ > 0)
