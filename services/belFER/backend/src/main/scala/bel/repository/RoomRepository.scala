package bel.repository

import bel.domain.*
import cats.effect.IO
import cats.syntax.traverse.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

class RoomRepository(xa: Transactor[IO]):

  // ── Room kinds ────────────────────────────────────────────────────────────

  def findKinds(schoolId: UUID): IO[List[RoomKind]] =
    sql"SELECT id, name FROM room_kind WHERE school_id = $schoolId ORDER BY name"
      .query[RoomKind].to[List].transact(xa)

  def createKind(schoolId: UUID, cmd: CreateRoomKind): IO[RoomKind] =
    sql"""
      INSERT INTO room_kind (school_id, name) VALUES ($schoolId, ${cmd.name.trim})
      RETURNING id, name
    """.query[RoomKind].unique.transact(xa)

  def renameKind(id: UUID, cmd: CreateRoomKind): IO[Option[RoomKind]] =
    sql"""
      UPDATE room_kind SET name = ${cmd.name.trim} WHERE id = $id
      RETURNING id, name
    """.query[RoomKind].option.transact(xa)

  /**
   * Deletion cascades to room assignments, and nulls the reference from any
   * subject that required this kind — which is why a subject's hard room
   * requirement is validated on write rather than trusted thereafter.
   */
  def deleteKind(id: UUID): IO[Int] =
    sql"DELETE FROM room_kind WHERE id = $id".update.run.transact(xa)

  // ── Rooms ─────────────────────────────────────────────────────────────────

  private def kindsFor(roomIds: List[UUID]): ConnectionIO[Map[UUID, List[RoomKind]]] =
    roomIds match
      case Nil => doobie.free.connection.pure(Map.empty)
      case ids =>
        val nel = cats.data.NonEmptyList.fromListUnsafe(ids)
        (fr"""
          SELECT a.room_id, k.id, k.name
          FROM room_kind_assignment a
          JOIN room_kind k ON k.id = a.room_kind_id
          WHERE""" ++ Fragments.in(fr"a.room_id", nel) ++ fr"ORDER BY k.name")
          .query[(UUID, RoomKind)].to[List]
          .map(_.groupMap(_._1)(_._2))

  def findRooms(schoolId: UUID): IO[List[Room]] =
    val program =
      for
        bare <- sql"""
                  SELECT id, number, name, fits_whole_class FROM room
                  WHERE school_id = $schoolId ORDER BY number
                """.query[(UUID, String, Option[String], Boolean)].to[List]
        kinds <- kindsFor(bare.map(_._1))
      yield bare.map { case (id, number, name, fits) =>
        Room(id, number, name, fits, kinds.getOrElse(id, Nil))
      }
    program.transact(xa)

  private def replaceKinds(roomId: UUID, schoolId: UUID, kindIds: List[UUID]): ConnectionIO[Unit] =
    for
      _ <- sql"DELETE FROM room_kind_assignment WHERE room_id = $roomId".update.run
      _ <- kindIds.traverse(kindId =>
             sql"""INSERT INTO room_kind_assignment (room_id, room_kind_id, school_id)
                   VALUES ($roomId, $kindId, $schoolId)""".update.run)
    yield ()

  private def loadRoom(id: UUID): ConnectionIO[Option[Room]] =
    for
      bare <- sql"""SELECT id, number, name, fits_whole_class FROM room WHERE id = $id"""
                .query[(UUID, String, Option[String], Boolean)].option
      kinds <- kindsFor(bare.map(_._1).toList)
    yield bare.map { case (rid, number, name, fits) =>
      Room(rid, number, name, fits, kinds.getOrElse(rid, Nil))
    }

  def createRoom(schoolId: UUID, input: RoomInput): IO[Room] =
    val program =
      for
        id <- sql"""
                INSERT INTO room (school_id, number, name, fits_whole_class)
                VALUES ($schoolId, ${input.number.trim}, ${input.name.map(_.trim).filter(_.nonEmpty)},
                        ${input.fitsWholeClass})
                RETURNING id
              """.query[UUID].unique
        _     <- replaceKinds(id, schoolId, input.kindIds)
        saved <- loadRoom(id)
      yield saved.get
    program.transact(xa)

  def updateRoom(schoolId: UUID, id: UUID, input: RoomInput): IO[Option[Room]] =
    val program =
      for
        updated <- sql"""
                     UPDATE room SET
                       number = ${input.number.trim},
                       name = ${input.name.map(_.trim).filter(_.nonEmpty)},
                       fits_whole_class = ${input.fitsWholeClass}
                     WHERE id = $id
                   """.update.run
        _     <- if updated == 0 then doobie.free.connection.unit else replaceKinds(id, schoolId, input.kindIds)
        saved <- if updated == 0 then doobie.free.connection.pure(Option.empty[Room]) else loadRoom(id)
      yield saved
    program.transact(xa)

  /** How many rooms carry a given kind — the ceiling on how many groups of a
    * cross-class lesson can run at once, since they all run together. */
  def roomsOfKind(kindId: UUID): IO[Int] =
    sql"SELECT count(*) FROM room_kind_assignment WHERE room_kind_id = $kindId"
      .query[Int].unique.transact(xa)

  def deleteRoom(id: UUID): IO[Int] =
    sql"DELETE FROM room WHERE id = $id".update.run.transact(xa)
