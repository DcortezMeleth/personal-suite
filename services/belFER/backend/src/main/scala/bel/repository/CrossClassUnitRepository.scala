package bel.repository

import bel.domain.*
import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.traverse.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import java.util.UUID

class CrossClassUnitRepository(xa: Transactor[IO]):

  private def groupsFor(unitIds: List[UUID]): ConnectionIO[Map[UUID, List[CrossClassGroup]]] =
    unitIds match
      case Nil => doobie.free.connection.pure(Map.empty)
      case ids =>
        val nel = NonEmptyList.fromListUnsafe(ids)
        for
          bare <- (fr"SELECT id, unit_id, label, teacher_id FROM cross_class_group WHERE" ++
                    Fragments.in(fr"unit_id", nel) ++ fr"ORDER BY label")
                    .query[(UUID, UUID, String, Option[UUID])].to[List]
          classes <- classesFor(bare.map(_._1))
        yield bare
          .map((id, unitId, label, teacher) =>
            unitId -> CrossClassGroup(id, label, teacher, classes.getOrElse(id, Nil)))
          .groupMap(_._1)(_._2)

  private def classesFor(groupIds: List[UUID]): ConnectionIO[Map[UUID, List[UUID]]] =
    groupIds match
      case Nil => doobie.free.connection.pure(Map.empty)
      case ids =>
        val nel = NonEmptyList.fromListUnsafe(ids)
        (fr"SELECT group_id, class_id FROM cross_class_group_class WHERE" ++ Fragments.in(fr"group_id", nel))
          .query[(UUID, UUID)].to[List].map(_.groupMap(_._1)(_._2))

  private val selectUnits =
    fr"SELECT id, subject_id, name, required_room_kind_id, blocks FROM cross_class_unit"

  private type BareUnit = (UUID, UUID, String, Option[UUID], List[Int])

  private def assemble(bare: List[BareUnit]): ConnectionIO[List[CrossClassUnit]] =
    groupsFor(bare.map(_._1)).map { groups =>
      bare.map((id, subject, name, kind, blocks) =>
        CrossClassUnit(id, subject, name, kind, blocks, groups.getOrElse(id, Nil)))
    }

  def findAll(schoolId: UUID): IO[List[CrossClassUnit]] =
    val program =
      for
        bare  <- (selectUnits ++ fr"WHERE school_id = $schoolId ORDER BY name").query[BareUnit].to[List]
        units <- assemble(bare)
      yield units
    program.transact(xa)

  private def load(id: UUID): ConnectionIO[Option[CrossClassUnit]] =
    for
      bare  <- (selectUnits ++ fr"WHERE id = $id").query[BareUnit].to[List]
      units <- assemble(bare)
    yield units.headOption

  private def replaceGroups(unitId: UUID, schoolId: UUID, groups: List[CrossClassGroupInput]): ConnectionIO[Unit] =
    for
      _ <- sql"DELETE FROM cross_class_group WHERE unit_id = $unitId".update.run
      _ <- groups.traverse { group =>
             for
               groupId <- sql"""
                            INSERT INTO cross_class_group (unit_id, school_id, label, teacher_id)
                            VALUES ($unitId, $schoolId, ${group.label.trim}, ${group.teacherId})
                            RETURNING id
                          """.query[UUID].unique
               _       <- group.classIds.distinct.traverse(classId =>
                            sql"""INSERT INTO cross_class_group_class (group_id, class_id, school_id)
                                  VALUES ($groupId, $classId, $schoolId)""".update.run)
             yield ()
           }
    yield ()

  def create(schoolId: UUID, input: CrossClassUnitInput): IO[CrossClassUnit] =
    val program =
      for
        id <- sql"""
                INSERT INTO cross_class_unit (school_id, subject_id, name, required_room_kind_id, blocks)
                VALUES ($schoolId, ${input.subjectId}, ${input.name.trim},
                        ${input.requiredRoomKindId}, ${input.blocks})
                RETURNING id
              """.query[UUID].unique
        _     <- replaceGroups(id, schoolId, input.groups)
        saved <- load(id)
      yield saved.get
    program.transact(xa)

  def update(schoolId: UUID, id: UUID, input: CrossClassUnitInput): IO[Option[CrossClassUnit]] =
    val program =
      for
        updated <- sql"""
                     UPDATE cross_class_unit SET
                       subject_id = ${input.subjectId},
                       name = ${input.name.trim},
                       required_room_kind_id = ${input.requiredRoomKindId},
                       blocks = ${input.blocks}
                     WHERE id = $id
                   """.update.run
        _     <- if updated == 0 then doobie.free.connection.unit
                 else replaceGroups(id, schoolId, input.groups)
        saved <- if updated == 0 then doobie.free.connection.pure(Option.empty[CrossClassUnit]) else load(id)
      yield saved
    program.transact(xa)

  def delete(id: UUID): IO[Int] =
    sql"DELETE FROM cross_class_unit WHERE id = $id".update.run.transact(xa)
