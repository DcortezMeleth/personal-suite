package fin.service

import cats.effect.IO
import fin.domain.*
import fin.repository.*
import java.time.YearMonth

class SpendingService(spendingRepo: SpendingRepository):

  def summary(ym: YearMonth): IO[MonthlySummary] =
    val prev = ym.minusMonths(1)
    for
      byCategory   <- spendingRepo.spendingByCategory(ym)
      totalSpent   <- spendingRepo.totalSpent(ym)
      totalIncome  <- spendingRepo.totalIncome(ym)
      prevSpent    <- spendingRepo.totalSpent(prev)
    yield MonthlySummary(
      month                = ym.toString,
      totalSpent           = totalSpent,
      totalIncome          = totalIncome,
      netCashflow          = totalIncome - totalSpent,
      deltaVsPrevMonth     = totalSpent - prevSpent,
      spendingByCategory   = byCategory
    )

  def summaryAllTime: IO[MonthlySummary] =
    for
      byCategory  <- spendingRepo.spendingByCategoryAll
      totalSpent  <- spendingRepo.totalSpentAll
      totalIncome <- spendingRepo.totalIncomeAll
    yield MonthlySummary(
      month              = "ALL",
      totalSpent         = totalSpent,
      totalIncome        = totalIncome,
      netCashflow        = totalIncome - totalSpent,
      deltaVsPrevMonth   = 0,
      spendingByCategory = byCategory
    )

  def trend(months: Int): IO[List[MonthlyTrend]] =
    val startDate = YearMonth.now().minusMonths(months.toLong).atDay(1)
    spendingRepo.monthlyTrend(startDate)
