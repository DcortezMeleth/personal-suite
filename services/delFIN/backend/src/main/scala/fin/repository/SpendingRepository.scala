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
      GROUP BY c.id, c.name, c.color, c.icon
      ORDER BY total DESC
    """.query[CategorySpending].to[List].transact(xa)

  def totalSpentAll: IO[BigDecimal] =
    sql"""
      SELECT COALESCE(SUM(-amount), 0) FROM transactions
      WHERE amount < 0 AND NOT is_internal_transfer
    """.query[BigDecimal].unique.transact(xa)

  def totalIncomeAll: IO[BigDecimal] =
    sql"""
      SELECT COALESCE(SUM(amount), 0) FROM transactions
      WHERE amount > 0 AND NOT is_internal_transfer
    """.query[BigDecimal].unique.transact(xa)

  def totalSpent(ym: YearMonth): IO[BigDecimal] =
    val start = ym.atDay(1)
    val end   = ym.atEndOfMonth()
    sql"""
      SELECT COALESCE(SUM(-amount), 0) FROM transactions
      WHERE date BETWEEN $start AND $end AND amount < 0 AND NOT is_internal_transfer
    """.query[BigDecimal].unique.transact(xa)

  def totalIncome(ym: YearMonth): IO[BigDecimal] =
    val start = ym.atDay(1)
    val end   = ym.atEndOfMonth()
    sql"""
      SELECT COALESCE(SUM(amount), 0) FROM transactions
      WHERE date BETWEEN $start AND $end AND amount > 0 AND NOT is_internal_transfer
    """.query[BigDecimal].unique.transact(xa)

  def monthlyTrend(startDate: LocalDate): IO[List[MonthlyTrend]] =
    sql"""
      SELECT
        TO_CHAR(date, 'YYYY-MM') AS month,
        COALESCE(SUM(CASE WHEN amount < 0 THEN -amount ELSE 0 END), 0) AS spent,
        COALESCE(SUM(CASE WHEN amount > 0 THEN  amount ELSE 0 END), 0) AS income
      FROM transactions
      WHERE NOT is_internal_transfer AND date >= $startDate
      GROUP BY month
      ORDER BY month ASC
    """.query[MonthlyTrend].to[List].transact(xa)
