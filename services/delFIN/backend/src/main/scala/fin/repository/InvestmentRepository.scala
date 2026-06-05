package fin.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import fin.domain.*
import fin.repository.DoobieMeta.given
import java.time.LocalDate
import java.util.UUID

class InvestmentRepository(xa: Transactor[IO]):

  // ── Instruments ──────────────────────────────────────────────────────────────

  def findInstrumentBySymbol(symbol: String): IO[Option[Instrument]] =
    sql"SELECT id, symbol, name, type, currency FROM instruments WHERE symbol = $symbol"
      .query[Instrument].option.transact(xa)

  def createInstrument(
    symbol: Option[String],
    name: String,
    iType: InstrumentType,
    currency: String
  ): IO[Instrument] =
    sql"""
      INSERT INTO instruments (symbol, name, type, currency)
      VALUES ($symbol, $name, $iType, $currency)
      RETURNING id, symbol, name, type, currency
    """.query[Instrument].unique.transact(xa)

  // ── Investment transactions ───────────────────────────────────────────────────

  def insertInvestmentTx(
    accountId: UUID,
    instrumentId: UUID,
    date: LocalDate,
    txType: InvestmentTxType,
    quantity: Option[BigDecimal],
    price: Option[BigDecimal],
    fees: BigDecimal,
    amountPln: BigDecimal,
    notes: Option[String]
  ): IO[InvestmentTransaction] =
    sql"""
      INSERT INTO investment_transactions
        (account_id, instrument_id, date, type, quantity, price, fees, amount_pln, notes)
      VALUES
        ($accountId, $instrumentId, $date, $txType, $quantity, $price, $fees, $amountPln, $notes)
      RETURNING id, account_id, instrument_id, date, type, quantity, price, fees, amount_pln, notes
    """.query[InvestmentTransaction].unique.transact(xa)

  def findTransactionsByAccount(accountId: UUID): IO[List[InvestmentTransaction]] =
    sql"""
      SELECT id, account_id, instrument_id, date, type, quantity, price, fees, amount_pln, notes
      FROM investment_transactions WHERE account_id = $accountId ORDER BY date DESC
    """.query[InvestmentTransaction].to[List].transact(xa)

  // ── Positions ────────────────────────────────────────────────────────────────

  def upsertPosition(
    accountId: UUID,
    instrumentId: UUID,
    quantity: BigDecimal,
    avgBuyPrice: Option[BigDecimal],
    currentPrice: Option[BigDecimal]
  ): IO[Unit] =
    sql"""
      INSERT INTO investment_positions (account_id, instrument_id, quantity, avg_buy_price, current_price)
      VALUES ($accountId, $instrumentId, $quantity, $avgBuyPrice, $currentPrice)
      ON CONFLICT (account_id, instrument_id) DO UPDATE
        SET quantity      = EXCLUDED.quantity,
            avg_buy_price = EXCLUDED.avg_buy_price,
            current_price = COALESCE(EXCLUDED.current_price, investment_positions.current_price),
            last_updated  = now()
    """.update.run.transact(xa).void

  def findPositions(ownerFilter: Option[AccountOwner]): IO[List[PositionDetail]] =
    val q = ownerFilter match
      case None =>
        sql"""
          SELECT a.id, a.name, i.id, i.symbol, i.name, i.type,
                 ip.quantity, ip.avg_buy_price, ip.current_price
          FROM investment_positions ip
          JOIN accounts a ON a.id = ip.account_id
          JOIN instruments i ON i.id = ip.instrument_id
          WHERE ip.quantity > 0
          ORDER BY a.name, i.name
        """
      case Some(owner) =>
        sql"""
          SELECT a.id, a.name, i.id, i.symbol, i.name, i.type,
                 ip.quantity, ip.avg_buy_price, ip.current_price
          FROM investment_positions ip
          JOIN accounts a ON a.id = ip.account_id
          JOIN instruments i ON i.id = ip.instrument_id
          WHERE ip.quantity > 0 AND a.owner = $owner
          ORDER BY a.name, i.name
        """
    q.query[(UUID, String, UUID, Option[String], String, InstrumentType,
             BigDecimal, Option[BigDecimal], Option[BigDecimal])]
      .to[List]
      .transact(xa)
      .map(_.map { case (acId, acName, instrId, sym, name, itype, qty, avgBuy, curPrice) =>
        val costBasis    = avgBuy.map(_ * qty)
        val currentValue = curPrice.map(_ * qty)
        val gainLoss     = for cv <- currentValue; cb <- costBasis yield cv - cb
        val gainLossPct  = for gl <- gainLoss; cb <- costBasis if cb != 0 yield (gl / cb) * 100
        PositionDetail(acId, acName, instrId, sym, name, itype,
          qty, avgBuy, curPrice, currentValue, costBasis, gainLoss, gainLossPct)
      })

  // ── Treasury bonds ───────────────────────────────────────────────────────────

  def createBond(cmd: CreateTreasuryBond): IO[TreasuryBond] =
    sql"""
      INSERT INTO treasury_bonds
        (account_id, series, nominal_value, quantity, purchase_date, maturity_date, interest_type, annual_rate_pct)
      VALUES
        (${cmd.accountId}, ${cmd.series}, ${cmd.nominalValue}, ${cmd.quantity},
         ${cmd.purchaseDate}, ${cmd.maturityDate}, ${cmd.interestType}, ${cmd.annualRatePct})
      RETURNING id, account_id, series, nominal_value, quantity, purchase_date, maturity_date, interest_type, annual_rate_pct
    """.query[TreasuryBond].unique.transact(xa)

  def findBonds(ownerFilter: Option[AccountOwner]): IO[List[(TreasuryBond, String)]] =
    val q = ownerFilter match
      case None =>
        sql"""
          SELECT tb.id, tb.account_id, tb.series, tb.nominal_value, tb.quantity,
                 tb.purchase_date, tb.maturity_date, tb.interest_type, tb.annual_rate_pct, a.name
          FROM treasury_bonds tb JOIN accounts a ON a.id = tb.account_id
          ORDER BY tb.purchase_date DESC
        """
      case Some(owner) =>
        sql"""
          SELECT tb.id, tb.account_id, tb.series, tb.nominal_value, tb.quantity,
                 tb.purchase_date, tb.maturity_date, tb.interest_type, tb.annual_rate_pct, a.name
          FROM treasury_bonds tb JOIN accounts a ON a.id = tb.account_id
          WHERE a.owner = $owner
          ORDER BY tb.purchase_date DESC
        """
    q.query[(TreasuryBond, String)].to[List].transact(xa)

  // ── Deposits ─────────────────────────────────────────────────────────────────

  def createDeposit(cmd: CreateDeposit): IO[Deposit] =
    sql"""
      INSERT INTO deposits (account_id, amount, currency, start_date, end_date, interest_rate, status)
      VALUES (${cmd.accountId}, ${cmd.amount}, ${cmd.currency}, ${cmd.startDate}, ${cmd.endDate},
              ${cmd.interestRate}, ${cmd.status})
      RETURNING id, account_id, amount, currency, start_date, end_date, interest_rate, status
    """.query[Deposit].unique.transact(xa)

  def findDeposits(ownerFilter: Option[AccountOwner]): IO[List[(Deposit, String)]] =
    val q = ownerFilter match
      case None =>
        sql"""
          SELECT d.id, d.account_id, d.amount, d.currency, d.start_date, d.end_date,
                 d.interest_rate, d.status, a.name
          FROM deposits d JOIN accounts a ON a.id = d.account_id
          ORDER BY d.start_date DESC
        """
      case Some(owner) =>
        sql"""
          SELECT d.id, d.account_id, d.amount, d.currency, d.start_date, d.end_date,
                 d.interest_rate, d.status, a.name
          FROM deposits d JOIN accounts a ON a.id = d.account_id
          WHERE a.owner = $owner
          ORDER BY d.start_date DESC
        """
    q.query[(Deposit, String)].to[List].transact(xa)

  // ── Inflation data ────────────────────────────────────────────────────────────

  def upsertInflation(year: Int, month: Int, cpiIndex: BigDecimal): IO[Unit] =
    sql"""
      INSERT INTO inflation_data (year, month, cpi_pct)
      VALUES ($year, $month, $cpiIndex)
      ON CONFLICT (year, month) DO UPDATE SET cpi_pct = EXCLUDED.cpi_pct
    """.update.run.transact(xa).void

  def findInflationData(months: Int): IO[List[InflationPoint]] =
    sql"""
      SELECT LPAD(year::text, 4, '0') || '-' || LPAD(month::text, 2, '0') AS ym, cpi_pct
      FROM inflation_data
      ORDER BY year DESC, month DESC
      LIMIT $months
    """.query[InflationPoint].to[List].transact(xa).map(_.reverse)
