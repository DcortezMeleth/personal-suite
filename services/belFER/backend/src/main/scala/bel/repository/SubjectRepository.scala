package bel.repository

import bel.domain.*
import cats.effect.IO
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

class SubjectRepository(xa: Transactor[IO]):

  // The room kind is joined rather than fetched separately: a subject is never
  // displayed without knowing which specialist room it wants.
  private val select =
    fr"""
      SELECT s.id, s.code, s.name, s.optional, k.id, k.name, s.room_requirement_hard
      FROM subject s
      LEFT JOIN room_kind k ON k.id = s.required_room_kind_id
    """

  def findAll(schoolId: UUID): IO[List[Subject]] =
    (select ++ fr"WHERE s.school_id = $schoolId ORDER BY s.name")
      .query[Subject].to[List].transact(xa)

  def find(id: UUID): IO[Option[Subject]] =
    (select ++ fr"WHERE s.id = $id").query[Subject].option.transact(xa)

  def create(schoolId: UUID, input: SubjectInput): IO[Subject] =
    val insert = sql"""
      INSERT INTO subject (school_id, code, name, optional, required_room_kind_id, room_requirement_hard)
      VALUES ($schoolId, ${input.code.trim}, ${input.name.trim}, ${input.optional},
              ${input.requiredRoomKindId}, ${input.roomRequirementHard})
      RETURNING id
    """.query[UUID].unique
    insert.transact(xa).flatMap(id => find(id).map(_.get))

  def update(id: UUID, input: SubjectInput): IO[Option[Subject]] =
    val run = sql"""
      UPDATE subject SET
        code = ${input.code.trim},
        name = ${input.name.trim},
        optional = ${input.optional},
        required_room_kind_id = ${input.requiredRoomKindId},
        room_requirement_hard = ${input.roomRequirementHard}
      WHERE id = $id
    """.update.run
    run.transact(xa).flatMap(n => if n == 0 then IO.pure(None) else find(id))

  def delete(id: UUID): IO[Int] =
    sql"DELETE FROM subject WHERE id = $id".update.run.transact(xa)
