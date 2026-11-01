package bel.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

/**
 * How many of a class attend each optional subject.
 *
 * Small enough to be loaded whole and keyed by the pair, because every caller
 * wants the same thing: given a class and a subject, how many sit in the
 * lesson. The rest of the class is the number that matters — those are the
 * pupils who must not be left waiting — but it is derived rather than stored,
 * so that a class whose size is corrected does not leave a stale remainder
 * behind it.
 */
class OptionalAttendanceRepository(xa: Transactor[IO]):

  def forSchool(schoolId: UUID): IO[Map[(UUID, UUID), Int]] =
    sql"""
      SELECT a.class_id, a.subject_id, a.attending
      FROM optional_subject_attendance a
      JOIN school_class c ON c.id = a.class_id
      WHERE c.school_id = $schoolId
    """.query[(UUID, UUID, Int)].to[List].transact(xa)
      .map(_.map((cls, subject, n) => (cls, subject) -> n).toMap)

  /**
   * Set one figure, or clear it.
   *
   * Clearing is a delete rather than a zero: nobody attending and nobody
   * having said are different answers, and only the second should leave the
   * solver to fall back on the blunter rule.
   */
  def set(classId: UUID, subjectId: UUID, attending: Option[Int]): IO[Unit] =
    attending match
      case None =>
        sql"""DELETE FROM optional_subject_attendance
              WHERE class_id = $classId AND subject_id = $subjectId"""
          .update.run.transact(xa).void
      case Some(n) =>
        sql"""
          INSERT INTO optional_subject_attendance (class_id, subject_id, attending)
          VALUES ($classId, $subjectId, ${n.max(0)})
          ON CONFLICT (class_id, subject_id)
          DO UPDATE SET attending = EXCLUDED.attending
        """.update.run.transact(xa).void
