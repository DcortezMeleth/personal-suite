package fin.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import fin.domain.*
import java.time.LocalDate
import java.util.UUID

class TransactionRepository(xa: Transactor[IO]):

  def insert(t: ParsedTransaction, accountId: UUID, categoryId: Option[UUID]): IO[Transaction] =
    sql"""
      INSERT INTO transactions
        (account_id, date, amount, currency, description, raw_description, category_id)
      VALUES
        ($accountId, ${t.date}, ${t.amount}, ${t.currency}, ${t.description}, ${t.rawDescription}, $categoryId)
      RETURNING id, account_id, date, amount, currency, description, raw_description,
                category_id, is_internal_transfer, transfer_peer_id, imported_at
    """.query[Transaction].unique.transact(xa)

  def existsDuplicate(accountId: UUID, date: LocalDate, amount: BigDecimal, rawDesc: String): IO[Boolean] =
    sql"""
      SELECT COUNT(*) > 0 FROM transactions
      WHERE account_id = $accountId AND date = $date
        AND amount = $amount AND raw_description = $rawDesc
    """.query[Boolean].unique.transact(xa)

  def findByMonth(year: Int, month: Int): IO[List[TransactionRow]] =
    sql"""
      SELECT t.id, t.account_id, a.name, t.date, t.amount, t.currency, t.description,
             t.category_id, c.name, c.color, t.is_internal_transfer
      FROM transactions t
      JOIN accounts a ON a.id = t.account_id
      LEFT JOIN categories c ON c.id = t.category_id
      WHERE EXTRACT(YEAR FROM t.date)  = $year
        AND EXTRACT(MONTH FROM t.date) = $month
      ORDER BY t.date DESC, t.imported_at DESC
    """.query[TransactionRow].to[List].transact(xa)

  def findTopByMonth(year: Int, month: Int, limit: Int): IO[List[TransactionRow]] =
    sql"""
      SELECT t.id, t.account_id, a.name, t.date, t.amount, t.currency, t.description,
             t.category_id, c.name, c.color, t.is_internal_transfer
      FROM transactions t
      JOIN accounts a ON a.id = t.account_id
      LEFT JOIN categories c ON c.id = t.category_id
      WHERE EXTRACT(YEAR FROM t.date)  = $year
        AND EXTRACT(MONTH FROM t.date) = $month
        AND NOT t.is_internal_transfer
        AND t.amount < 0
      ORDER BY t.amount ASC
      LIMIT $limit
    """.query[TransactionRow].to[List].transact(xa)

  def updateCategory(id: UUID, categoryId: UUID): IO[Int] =
    sql"UPDATE transactions SET category_id = $categoryId WHERE id = $id"
      .update.run.transact(xa)

  def detectAndLinkTransfers: IO[Int] =
    sql"""
      WITH pairs AS (
        SELECT t1.id AS id1, t2.id AS id2
        FROM transactions t1
        JOIN transactions t2 ON (
          t1.amount = -t2.amount
          AND t1.amount < 0
          AND ABS(t1.date - t2.date) <= 2
          AND t1.account_id != t2.account_id
          AND NOT t1.is_internal_transfer
          AND NOT t2.is_internal_transfer
          AND t1.id < t2.id
        )
      )
      UPDATE transactions t
      SET is_internal_transfer = true,
          transfer_peer_id = CASE WHEN t.id = p.id1 THEN p.id2 ELSE p.id1 END
      FROM pairs p
      WHERE t.id = p.id1 OR t.id = p.id2
    """.update.run.transact(xa)
