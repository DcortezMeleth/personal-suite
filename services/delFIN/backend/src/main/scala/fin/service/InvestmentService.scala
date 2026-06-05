package fin.service

import cats.effect.IO
import cats.syntax.traverse.*
import fin.domain.*
import fin.importer.XtbCsvParser
import fin.repository.*
import java.time.LocalDate
import java.util.UUID

class InvestmentService(
  investRepo:  InvestmentRepository,
  accountRepo: AccountRepository
):

  def importXtb(rows: List[fin.importer.XtbRow], accountId: UUID): IO[ImportResult] =
    rows.traverse { row =>
      for
        instrument <- investRepo.findInstrumentBySymbol(row.symbol).flatMap {
                        case Some(i) => IO.pure(i)
                        case None    =>
                          investRepo.createInstrument(
                            Some(row.symbol), row.name, InstrumentType.STOCK, "PLN"
                          )
                      }
        _          <- investRepo.insertInvestmentTx(
                        accountId, instrument.id, row.date, row.txType,
                        row.quantity, row.openPrice, row.commission,
                        amountPln(row), row.symbol.some
                      )
      yield ()
    } >> recomputePositions(accountId) >> IO.pure(ImportResult(rows.size, 0, 0))

  def positions(owner: Option[AccountOwner]): IO[List[PositionDetail]] =
    investRepo.findPositions(owner)

  def bondsWithValue(owner: Option[AccountOwner]): IO[List[BondWithValue]] =
    investRepo.findBonds(owner).map(_.map { case (bond, accName) =>
      val invested    = bond.nominalValue * bond.quantity
      val yearsHeld   = daysBetween(bond.purchaseDate, LocalDate.now().min(bond.maturityDate)) / 365.25
      val accrued     = invested * (bond.annualRatePct / 100) * BigDecimal(yearsHeld)
      val currentVal  = invested + accrued
      val gainPct     = if invested > 0 then (accrued / invested) * 100 else BigDecimal(0)
      BondWithValue(bond, accName, invested, accrued, currentVal, gainPct)
    })

  def depositsWithValue(owner: Option[AccountOwner]): IO[List[DepositWithValue]] =
    investRepo.findDeposits(owner).map(_.map { case (dep, accName) =>
      val effectiveEnd = if dep.status == DepositStatus.ACTIVE then
                           dep.endDate.min(LocalDate.now())
                         else dep.endDate
      val yearsHeld    = daysBetween(dep.startDate, effectiveEnd) / 365.25
      val accrued      = dep.amount * (dep.interestRate / 100) * BigDecimal(yearsHeld)
      val currentVal   = dep.amount + accrued
      val gainPct      = if dep.amount > 0 then (accrued / dep.amount) * 100 else BigDecimal(0)
      DepositWithValue(dep, accName, accrued, currentVal, gainPct)
    })

  def portfolioSummary(owner: Option[AccountOwner]): IO[PortfolioSummary] =
    for
      positions <- investRepo.findPositions(owner)
      bonds     <- bondsWithValue(owner)
      deposits  <- depositsWithValue(owner)
    yield
      val posInvested = positions.flatMap(_.costBasis).sum
      posInvested match
        case _ =>
          val posCurrent  = positions.flatMap(_.currentValue).sum
          val bondInvest  = bonds.map(_.invested).sum
          val bondCurrent = bonds.map(_.currentValue).sum
          val depInvest   = deposits.map(_.deposit.amount).sum
          val depCurrent  = deposits.map(_.currentValue).sum

          val totalInvested = posInvested + bondInvest + depInvest
          val totalCurrent  = posCurrent  + bondCurrent + depCurrent
          val gainLoss      = totalCurrent - totalInvested
          val gainLossPct   = if totalInvested > 0 then (gainLoss / totalInvested) * 100 else BigDecimal(0)

          val byType = List(
            mkAlloc("Stocks/ETFs/Funds", posCurrent,  posInvested,  totalCurrent),
            mkAlloc("Treasury Bonds",    bondCurrent, bondInvest,   totalCurrent),
            mkAlloc("Deposits",          depCurrent,  depInvest,    totalCurrent)
          ).filter(_.currentValue > 0)

          PortfolioSummary(
            owner           = owner.map(_.toString).getOrElse("ALL"),
            totalCurrentValue = totalCurrent,
            totalInvested   = totalInvested,
            totalGainLoss   = gainLoss,
            totalGainLossPct = gainLossPct,
            byType          = byType
          )

  private def mkAlloc(name: String, current: BigDecimal, invested: BigDecimal, total: BigDecimal) =
    TypeAllocation(name, current, invested, if total > 0 then (current / total) * 100 else BigDecimal(0))

  private def daysBetween(from: LocalDate, to: LocalDate): Double =
    math.max(0.0, java.time.temporal.ChronoUnit.DAYS.between(from, to).toDouble)

  private def amountPln(row: fin.importer.XtbRow): BigDecimal =
    row.txType match
      case InvestmentTxType.BUY  =>
        -(row.quantity.getOrElse(BigDecimal(0)) * row.openPrice.getOrElse(BigDecimal(0)) + row.commission)
      case InvestmentTxType.SELL =>
        row.quantity.getOrElse(BigDecimal(0)) * row.closePrice.orElse(row.openPrice).getOrElse(BigDecimal(0)) - row.commission + row.profitPln
      case _ => row.profitPln

  private def recomputePositions(accountId: UUID): IO[Unit] =
    investRepo.findTransactionsByAccount(accountId).flatMap { txs =>
      val byInstrument = txs.groupBy(_.instrumentId)
      byInstrument.toList.traverse { case (instrId, instrTxs) =>
        val buys    = instrTxs.filter(_.txType == InvestmentTxType.BUY)
        val sells   = instrTxs.filter(_.txType == InvestmentTxType.SELL)
        val boughtQty = buys.flatMap(_.quantity).sum
        val soldQty   = sells.flatMap(_.quantity).sum
        val netQty    = boughtQty - soldQty
        val totalCost = buys.flatMap(t => t.quantity.flatMap(q => t.price.map(p => q * p))).sum
        val avgBuy    = if boughtQty > 0 then Some(totalCost / boughtQty) else None
        investRepo.upsertPosition(accountId, instrId, netQty, avgBuy, None)
      }.void
    }

extension (s: String) def some: Option[String] = Some(s)
extension (ld: LocalDate) def min(other: LocalDate): LocalDate = if ld.isBefore(other) then ld else other
