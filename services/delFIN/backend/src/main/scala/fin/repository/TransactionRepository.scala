package fin.repository

import cats.effect.IO
import cats.syntax.traverse.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import fin.domain.*
import fin.importer.DescriptionFormatter
import java.time.LocalDate
import java.util.UUID

class TransactionRepository(xa: Transactor[IO]):

  def insert(t: ParsedTransaction, accountId: UUID, categoryId: Option[UUID]): IO[Transaction] =
    sql"""
      INSERT INTO transactions
        (account_id, date, amount, currency, title, counterparty, raw_description, category_id)
      VALUES
        ($accountId, ${t.date}, ${t.amount}, ${t.currency}, ${t.title}, ${t.counterparty}, ${t.rawDescription}, $categoryId)
      RETURNING id, account_id, date, amount, currency, title, counterparty, raw_description,
                category_id, is_internal_transfer, transfer_peer_id, imported_at, notes
    """.query[Transaction].unique.transact(xa)

  def existsDuplicate(accountId: UUID, date: LocalDate, amount: BigDecimal, rawDesc: String): IO[Boolean] =
    sql"""
      SELECT COUNT(*) > 0 FROM transactions
      WHERE account_id = $accountId AND date = $date
        AND amount = $amount AND raw_description = $rawDesc
    """.query[Boolean].unique.transact(xa)

  def findByMonth(year: Int, month: Int): IO[List[TransactionRow]] =
    sql"""
      SELECT t.id, t.account_id, a.name, t.date, t.amount, t.currency, t.title, t.counterparty, t.notes,
             t.category_id, c.name, c.color, c.icon, t.is_internal_transfer
      FROM transactions t
      JOIN accounts a ON a.id = t.account_id
      LEFT JOIN categories c ON c.id = t.category_id
      WHERE EXTRACT(YEAR FROM t.date)  = $year
        AND EXTRACT(MONTH FROM t.date) = $month
      ORDER BY t.date DESC, t.imported_at DESC
    """.query[TransactionRow].to[List].transact(xa)

  def findTopByMonth(year: Int, month: Int, limit: Int): IO[List[TransactionRow]] =
    sql"""
      SELECT t.id, t.account_id, a.name, t.date, t.amount, t.currency, t.title, t.counterparty, t.notes,
             t.category_id, c.name, c.color, c.icon, t.is_internal_transfer
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

  def findTopAll(limit: Int): IO[List[TransactionRow]] =
    sql"""
      SELECT t.id, t.account_id, a.name, t.date, t.amount, t.currency, t.title, t.counterparty, t.notes,
             t.category_id, c.name, c.color, c.icon, t.is_internal_transfer
      FROM transactions t
      JOIN accounts a ON a.id = t.account_id
      LEFT JOIN categories c ON c.id = t.category_id
      WHERE NOT t.is_internal_transfer
        AND t.amount < 0
      ORDER BY t.amount ASC
      LIMIT $limit
    """.query[TransactionRow].to[List].transact(xa)

  // sortBy/sortDir are expected to already be validated against a fixed whitelist
  // by the route layer — interpolated via Fragment.const, never as bind parameters.
  def search(
    dateFrom:   Option[LocalDate],
    dateTo:     Option[LocalDate],
    categoryId: Option[UUID],
    search:     Option[String],
    minAmount:  Option[BigDecimal],
    maxAmount:  Option[BigDecimal],
    sortBy:     String,
    sortDir:    String,
    page:       Int,
    pageSize:   Int
  ): IO[TransactionSearchResult] =
    val filters = Fragments.whereAndOpt(
      dateFrom.map(d => fr"t.date >= $d"),
      dateTo.map(d => fr"t.date <= $d"),
      categoryId.map(c => fr"t.category_id = $c"),
      search.map(s => fr"""(t.title ILIKE ${"%" + s + "%"}
                            OR t.counterparty ILIKE ${"%" + s + "%"}
                            OR t.raw_description ILIKE ${"%" + s + "%"}
                            OR t.notes ILIKE ${"%" + s + "%"})"""),
      minAmount.map(a => fr"t.amount >= $a"),
      maxAmount.map(a => fr"t.amount <= $a")
    )

    val orderBy = (sortBy, sortDir) match
      case ("amount", "asc") => Fragment.const("ORDER BY t.amount ASC, t.id")
      case ("amount", _)     => Fragment.const("ORDER BY t.amount DESC, t.id")
      case (_, "asc")        => Fragment.const("ORDER BY t.date ASC, t.id")
      case _                 => Fragment.const("ORDER BY t.date DESC, t.id")

    val fromClause = fr"""
      FROM transactions t
      JOIN accounts a ON a.id = t.account_id
      LEFT JOIN categories c ON c.id = t.category_id
    """

    val selectFr =
      fr"""
        SELECT t.id, t.account_id, a.name, t.date, t.amount, t.currency, t.title, t.counterparty, t.notes,
               t.category_id, c.name, c.color, c.icon, t.is_internal_transfer
      """ ++ fromClause ++ filters ++ orderBy ++ fr"LIMIT $pageSize OFFSET ${page * pageSize}"

    val countFr = fr"SELECT COUNT(*)" ++ fromClause ++ filters

    for
      items <- selectFr.query[TransactionRow].to[List].transact(xa)
      total <- countFr.query[Long].unique.transact(xa)
    yield TransactionSearchResult(items, total)

  // Regenerates `title` and `counterparty` from the durable `raw_description`
  // using the current DescriptionFormatter rules. Safe to re-run any time that
  // formatting logic changes — no re-import needed, since raw_description already
  // holds the full original data. Ordered per-account by (date, imported_at) and
  // run through the same threadFeeInfo pass Mt940Parser uses, so already-imported
  // card-fee rows also pick up their preceding purchase's title and counterparty —
  // that fix isn't limited to future imports.
  def backfillDerivedFields: IO[Int] =
    sql"SELECT id, account_id, raw_description FROM transactions ORDER BY account_id, date, imported_at"
      .query[(UUID, UUID, String)].to[List].transact(xa)
      .flatMap { rows =>
        val updates = rows.groupBy(_._2).values.flatMap { group =>
          val rawInfo  = group.map((_, _, raw) => (DescriptionFormatter.extractTitle(raw), DescriptionFormatter.extractCounterparty(raw)))
          val threaded = DescriptionFormatter.threadFeeInfo(rawInfo)
          group.zip(threaded).map { case ((id, _, _), (title, counterparty)) => (id, title, counterparty) }
        }.toList
        updates.traverse { case (id, title, counterparty) =>
          sql"UPDATE transactions SET title = $title, counterparty = $counterparty WHERE id = $id".update.run
        }.transact(xa)
      }
      .map(_.sum)

  def updateCategory(id: UUID, categoryId: UUID): IO[Int] =
    sql"UPDATE transactions SET category_id = $categoryId WHERE id = $id"
      .update.run.transact(xa)

  def updateNotes(id: UUID, notes: Option[String]): IO[Int] =
    sql"UPDATE transactions SET notes = $notes WHERE id = $id"
      .update.run.transact(xa)

  // ON CONFLICT DO NOTHING: bulk-assigning a tag that's already on some of
  // the selected transactions (e.g. re-running over an overlapping date
  // range) is a no-op for those rows, not an error.
  def bulkAssignTag(transactionIds: List[UUID], tagId: UUID): IO[Int] =
    transactionIds.traverse { txId =>
      sql"""
        INSERT INTO transaction_tags (transaction_id, tag_id) VALUES ($txId, $tagId)
        ON CONFLICT DO NOTHING
      """.update.run
    }.transact(xa).map(_.sum)

  def removeTag(transactionId: UUID, tagId: UUID): IO[Int] =
    sql"DELETE FROM transaction_tags WHERE transaction_id = $transactionId AND tag_id = $tagId"
      .update.run.transact(xa)

  /** The best available label for a transaction to seed an auto-generated
    * category rule from (counterparty if present, else title), plus its own
    * amount so the rule can default to that same cash-flow direction.
    */
  def findRulePatternSeed(id: UUID): IO[Option[(String, BigDecimal)]] =
    sql"SELECT COALESCE(counterparty, title), amount FROM transactions WHERE id = $id"
      .query[(String, BigDecimal)].option.transact(xa)

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
