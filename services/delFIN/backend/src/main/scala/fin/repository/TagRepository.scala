package fin.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import fin.domain.*
import java.util.UUID

class TagRepository(xa: Transactor[IO]):

  def findAll: IO[List[Tag]] =
    sql"SELECT id, name, color FROM tags ORDER BY name".query[Tag].to[List].transact(xa)

  def createTag(cmd: CreateTag): IO[Tag] =
    sql"""
      INSERT INTO tags (name, color) VALUES (${cmd.name}, ${cmd.color})
      RETURNING id, name, color
    """.query[Tag].unique.transact(xa)

  def updateTag(id: UUID, cmd: UpdateTag): IO[Option[Tag]] =
    sql"""
      UPDATE tags SET name = ${cmd.name}, color = ${cmd.color}
      WHERE id = $id
      RETURNING id, name, color
    """.query[Tag].option.transact(xa)

  // ON DELETE CASCADE on transaction_tags handles untagging — no conflict
  // check needed, unlike CategoryRepository.deleteCategory.
  def deleteTag(id: UUID): IO[Int] =
    sql"DELETE FROM tags WHERE id = $id".update.run.transact(xa)
