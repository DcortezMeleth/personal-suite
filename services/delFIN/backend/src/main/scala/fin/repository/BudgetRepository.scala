package fin.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import fin.domain.*
import java.time.{LocalDate, YearMonth}
import java.util.UUID

class BudgetRepository(xa: Transactor[IO]):

  def findAll: IO[List[Budget]] =
    sql"""
      SELECT id, category_id, monthly_limit, alert_threshold_pct FROM budgets
    """.query[Budget].to[List].transact(xa)

  def upsert(cmd: CreateBudget): IO[Budget] =
    sql"""
      INSERT INTO budgets (category_id, monthly_limit, alert_threshold_pct)
      VALUES (${cmd.categoryId}, ${cmd.monthlyLimit}, ${cmd.alertThresholdPct})
      ON CONFLICT (category_id) DO UPDATE
        SET monthly_limit       = EXCLUDED.monthly_limit,
            alert_threshold_pct = EXCLUDED.alert_threshold_pct
      RETURNING id, category_id, monthly_limit, alert_threshold_pct
    """.query[Budget].unique.transact(xa)

  def delete(id: UUID): IO[Int] =
    sql"DELETE FROM budgets WHERE id = $id".update.run.transact(xa)

  def statusForMonth(ym: YearMonth): IO[List[BudgetStatus]] =
    val start = ym.atDay(1)
    val end   = ym.atEndOfMonth()
    sql"""
      SELECT b.category_id, c.name, b.monthly_limit, b.alert_threshold_pct,
             COALESCE(SUM(-t.amount), 0) AS spent
      FROM budgets b
      JOIN categories c ON c.id = b.category_id
      LEFT JOIN transactions t
        ON  t.category_id = b.category_id
        AND t.date BETWEEN $start AND $end
        AND t.amount < 0
        AND NOT t.is_internal_transfer
      GROUP BY b.category_id, c.name, b.monthly_limit, b.alert_threshold_pct
    """.query[(UUID, String, BigDecimal, Int, BigDecimal)]
      .to[List]
      .transact(xa)
      .map(_.map { case (catId, catName, limit, threshold, spent) =>
        val pct = if limit > 0 then ((spent / limit) * 100).toInt else 0
        BudgetStatus(catId, catName, limit, threshold, spent, pct,
          isWarning = pct >= threshold,
          isBreached = pct >= 100
        )
      })
