package fin.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import fin.domain.*
import fin.repository.DoobieMeta.given
import java.util.UUID

class AccountRepository(xa: Transactor[IO]):

  def findAll: IO[List[Account]] =
    sql"""
      SELECT id, name, type, institution, currency, owner, created_at
      FROM accounts ORDER BY name
    """.query[Account].to[List].transact(xa)

  def findById(id: UUID): IO[Option[Account]] =
    sql"""
      SELECT id, name, type, institution, currency, owner, created_at
      FROM accounts WHERE id = $id
    """.query[Account].option.transact(xa)

  def create(cmd: CreateAccount): IO[Account] =
    sql"""
      INSERT INTO accounts (name, type, institution, currency, owner)
      VALUES (${cmd.name}, ${cmd.accountType}, ${cmd.institution}, ${cmd.currency}, ${cmd.owner})
      RETURNING id, name, type, institution, currency, owner, created_at
    """.query[Account].unique.transact(xa)
