package fin.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import fin.domain.*
import java.time.{LocalDate, YearMonth}

class SpendingRepository(xa: Transactor[IO]):

  def spendingByCategory(ym: YearMonth): IO[List[CategorySpending]] =
    val start = ym.atDay(1)
    val end   = ym.atEndOfMonth()
    sql"""
      SELECT c.id, c.name, c.color, c.icon, COALESCE(SUM(-t.amount), 0) AS total
      FROM categories c
      JOIN transactions t ON t.category_id = c.id
      WHERE t.date BETWEEN $start AND $end
        AND t.amount < 0
        AND NOT t.is_internal_transfer
        AND NOT c.is_internal
      GROUP BY c.id, c.name, c.color, c.icon
      ORDER BY total DESC
    """.query[CategorySpending].to[List].transact(xa)

  def spendingByCategoryAll: IO[List[CategorySpending]] =
    sql"""
      SELECT c.id, c.name, c.color, c.icon, COALESCE(SUM(-t.amount), 0) AS total
      FROM categories c
      JOIN transactions t ON t.category_id = c.id
      WHERE t.amount < 0
        AND NOT t.is_internal_transfer
        AND NOT c.is_internal
      GROUP BY c.id, c.name, c.color, c.icon
      ORDER BY total DESC
    """.query[CategorySpending].to[List].transact(xa)

  // Rolls a child's spending into its parent's total — for each transaction,
  // resolve to its category's parent if it has one, else the category itself
  // (COALESCE(c.parent_id, c.id)). Correct as long as nesting stays capped at
  // 2 levels (enforced in CategoryRepository), since this only walks up one
  // hop; a true multi-level tree would need a recursive CTE instead.
  def spendingByCategoryRolledUp(ym: YearMonth): IO[List[CategorySpending]] =
    val start = ym.atDay(1)
    val end   = ym.atEndOfMonth()
    sql"""
      SELECT top.id, top.name, top.color, top.icon, COALESCE(SUM(-t.amount), 0) AS total
      FROM transactions t
      JOIN categories c ON c.id = t.category_id
      JOIN categories top ON top.id = COALESCE(c.parent_id, c.id)
      WHERE t.date BETWEEN $start AND $end
        AND t.amount < 0
        AND NOT t.is_internal_transfer
        AND NOT c.is_internal
      GROUP BY top.id, top.name, top.color, top.icon
      ORDER BY total DESC
    """.query[CategorySpending].to[List].transact(xa)

  def spendingByCategoryRolledUpAll: IO[List[CategorySpending]] =
    sql"""
      SELECT top.id, top.name, top.color, top.icon, COALESCE(SUM(-t.amount), 0) AS total
      FROM transactions t
      JOIN categories c ON c.id = t.category_id
      JOIN categories top ON top.id = COALESCE(c.parent_id, c.id)
      WHERE t.amount < 0
        AND NOT t.is_internal_transfer
        AND NOT c.is_internal
      GROUP BY top.id, top.name, top.color, top.icon
      ORDER BY total DESC
    """.query[CategorySpending].to[List].transact(xa)

  def spendingByTag(ym: YearMonth): IO[List[TagSpending]] =
    val start = ym.atDay(1)
    val end   = ym.atEndOfMonth()
    sql"""
      SELECT tg.id, tg.name, tg.color, tg.icon, COALESCE(SUM(-t.amount), 0) AS total
      FROM tags tg
      JOIN transaction_tags tt ON tt.tag_id = tg.id
      JOIN transactions t ON t.id = tt.transaction_id
      LEFT JOIN categories c ON c.id = t.category_id
      WHERE t.date BETWEEN $start AND $end
        AND t.amount < 0
        AND NOT t.is_internal_transfer
        AND NOT COALESCE(c.is_internal, false)
      GROUP BY tg.id, tg.name, tg.color, tg.icon
      ORDER BY total DESC
    """.query[TagSpending].to[List].transact(xa)

  def spendingByTagAll: IO[List[TagSpending]] =
    sql"""
      SELECT tg.id, tg.name, tg.color, tg.icon, COALESCE(SUM(-t.amount), 0) AS total
      FROM tags tg
      JOIN transaction_tags tt ON tt.tag_id = tg.id
      JOIN transactions t ON t.id = tt.transaction_id
      LEFT JOIN categories c ON c.id = t.category_id
      WHERE t.amount < 0
        AND NOT t.is_internal_transfer
        AND NOT COALESCE(c.is_internal, false)
      GROUP BY tg.id, tg.name, tg.color, tg.icon
      ORDER BY total DESC
    """.query[TagSpending].to[List].transact(xa)

  // Excludes both the auto-detected transfer-pairing flag AND any transaction
  // whose category is manually flagged is_internal (e.g. the "Internal"
  // category) — the two mechanisms are independent, either one excludes.
  private val notInternalCategory =
    fr"NOT COALESCE((SELECT is_internal FROM categories WHERE id = category_id), false)"

  def totalSpentAll: IO[BigDecimal] =
    (fr"""
      SELECT COALESCE(SUM(-amount), 0) FROM transactions
      WHERE amount < 0 AND NOT is_internal_transfer AND""" ++ notInternalCategory)
      .query[BigDecimal].unique.transact(xa)

  def totalIncomeAll: IO[BigDecimal] =
    (fr"""
      SELECT COALESCE(SUM(amount), 0) FROM transactions
      WHERE amount > 0 AND NOT is_internal_transfer AND""" ++ notInternalCategory)
      .query[BigDecimal].unique.transact(xa)

  def totalSpent(ym: YearMonth): IO[BigDecimal] =
    val start = ym.atDay(1)
    val end   = ym.atEndOfMonth()
    (fr"""
      SELECT COALESCE(SUM(-amount), 0) FROM transactions
      WHERE date BETWEEN $start AND $end AND amount < 0 AND NOT is_internal_transfer AND""" ++ notInternalCategory)
      .query[BigDecimal].unique.transact(xa)

  def totalIncome(ym: YearMonth): IO[BigDecimal] =
    val start = ym.atDay(1)
    val end   = ym.atEndOfMonth()
    (fr"""
      SELECT COALESCE(SUM(amount), 0) FROM transactions
      WHERE date BETWEEN $start AND $end AND amount > 0 AND NOT is_internal_transfer AND""" ++ notInternalCategory)
      .query[BigDecimal].unique.transact(xa)

  def monthlyTrend(startDate: LocalDate): IO[List[MonthlyTrend]] =
    (fr"""
      SELECT
        TO_CHAR(date, 'YYYY-MM') AS month,
        COALESCE(SUM(CASE WHEN amount < 0 THEN -amount ELSE 0 END), 0) AS spent,
        COALESCE(SUM(CASE WHEN amount > 0 THEN  amount ELSE 0 END), 0) AS income
      FROM transactions
      WHERE NOT is_internal_transfer AND date >= $startDate AND""" ++ notInternalCategory ++ fr"""
      GROUP BY month
      ORDER BY month ASC""")
      .query[MonthlyTrend].to[List].transact(xa)
