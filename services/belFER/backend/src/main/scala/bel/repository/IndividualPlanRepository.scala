package bel.repository

import bel.domain.*
import cats.effect.IO
import cats.syntax.traverse.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

class IndividualPlanRepository(xa: Transactor[IO]):

  def findAll(schoolId: UUID): IO[List[IndividualPlan]] =
    val plans =
      sql"""
        SELECT p.id, p.class_id, p.ordinal, p.kind, p.half
        FROM individual_plan p
        WHERE p.school_id = $schoolId
        ORDER BY p.ordinal
      """.query[(UUID, UUID, Int, Option[String], Option[Int])].to[List]

    val lessons =
      sql"""
        SELECT l.plan_id, l.id, l.subject_id, l.teacher_id, l.blocks
        FROM individual_lesson l
          JOIN individual_plan p ON p.id = l.plan_id
        WHERE p.school_id = $schoolId
      """.query[(UUID, UUID, UUID, Option[UUID], List[Int])].to[List]

    (for
      ps <- plans
      ls <- lessons
    yield
      val byPlan = ls.groupBy(_._1)
      ps.map { (id, classId, ordinal, kind, half) =>
        IndividualPlan(id, classId, ordinal,
          byPlan.getOrElse(id, Nil).map((_, lid, subject, teacher, blocks) =>
            IndividualLesson(lid, subject, teacher, blocks)),
          kind.flatMap(TuitionKind.parse), half)
      }
    ).transact(xa)

  /** Sets what the arkusz does not say. False when no such plan is in this school. */
  def setKind(schoolId: UUID, planId: UUID, settings: TuitionSettings): IO[Boolean] =
    sql"""
      UPDATE individual_plan
      SET kind = ${settings.kind.map(_.toString)}, half = ${settings.half}
      WHERE id = $planId AND school_id = $schoolId
    """.update.run.map(_ == 1).transact(xa)

  /** Replaces the school's individual tuition wholesale, as an import does. */
  def replaceAll(schoolId: UUID, plans: List[IndividualPlan]): IO[Int] =
    val tx = for
      _ <- sql"DELETE FROM individual_plan WHERE school_id = $schoolId".update.run
      written <- plans.traverse { plan =>
        for
          id <- sql"""
                  INSERT INTO individual_plan (school_id, class_id, ordinal)
                  VALUES ($schoolId, ${plan.classId}, ${plan.ordinal})
                """.update.withUniqueGeneratedKeys[UUID]("id")
          _ <- Update[(UUID, UUID, Option[UUID], List[Int])](
                 """INSERT INTO individual_lesson (plan_id, subject_id, teacher_id, blocks)
                    VALUES (?, ?, ?, ?)"""
               ).updateMany(plan.lessons.map(l => (id, l.subjectId, l.teacherId, l.blocks)))
        yield plan.lessons.size
      }
    yield written.sum
    tx.transact(xa)
