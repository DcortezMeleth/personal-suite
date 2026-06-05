package fin.domain

import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*

// ── Enums ────────────────────────────────────────────────────────────────────

enum InstrumentType:
  case ETF, STOCK, TREASURY_BOND, DEPOSIT, FUND

object InstrumentType:
  given Encoder[InstrumentType] = Encoder[String].contramap(_.toString)
  given Decoder[InstrumentType] = Decoder[String].emap(s =>
    scala.util.Try(InstrumentType.valueOf(s)).toEither.left.map(_.getMessage)
  )

enum InvestmentTxType:
  case BUY, SELL, DIVIDEND, INTEREST, COUPON, MATURITY

object InvestmentTxType:
  given Encoder[InvestmentTxType] = Encoder[String].contramap(_.toString)
  given Decoder[InvestmentTxType] = Decoder[String].emap(s =>
    scala.util.Try(InvestmentTxType.valueOf(s)).toEither.left.map(_.getMessage)
  )

enum DepositStatus:
  case ACTIVE, MATURED, BROKEN

object DepositStatus:
  given Encoder[DepositStatus] = Encoder[String].contramap(_.toString)
  given Decoder[DepositStatus] = Decoder[String].emap(s =>
    scala.util.Try(DepositStatus.valueOf(s)).toEither.left.map(_.getMessage)
  )

// ── Core entities ─────────────────────────────────────────────────────────────

case class Instrument(
  id: UUID,
  symbol: Option[String],
  name: String,
  instrumentType: InstrumentType,
  currency: String
)

object Instrument:
  given Encoder[Instrument] = deriveEncoder
  given Decoder[Instrument] = deriveDecoder

case class InvestmentTransaction(
  id: UUID,
  accountId: UUID,
  instrumentId: UUID,
  date: LocalDate,
  txType: InvestmentTxType,
  quantity: Option[BigDecimal],
  price: Option[BigDecimal],
  fees: BigDecimal,
  amountPln: BigDecimal,
  notes: Option[String]
)

object InvestmentTransaction:
  given Encoder[InvestmentTransaction] = deriveEncoder
  given Decoder[InvestmentTransaction] = deriveDecoder

case class TreasuryBond(
  id: UUID,
  accountId: UUID,
  series: String,
  nominalValue: BigDecimal,
  quantity: Int,
  purchaseDate: LocalDate,
  maturityDate: LocalDate,
  interestType: String,
  annualRatePct: BigDecimal
)

object TreasuryBond:
  given Encoder[TreasuryBond] = deriveEncoder
  given Decoder[TreasuryBond] = deriveDecoder

case class Deposit(
  id: UUID,
  accountId: UUID,
  amount: BigDecimal,
  currency: String,
  startDate: LocalDate,
  endDate: LocalDate,
  interestRate: BigDecimal,
  status: DepositStatus
)

object Deposit:
  given Encoder[Deposit] = deriveEncoder
  given Decoder[Deposit] = deriveDecoder

// ── Create commands ───────────────────────────────────────────────────────────

case class CreateTreasuryBond(
  accountId: UUID,
  series: String,
  nominalValue: BigDecimal,
  quantity: Int,
  purchaseDate: LocalDate,
  maturityDate: LocalDate,
  interestType: String,
  annualRatePct: BigDecimal
)

object CreateTreasuryBond:
  given Decoder[CreateTreasuryBond] = deriveDecoder

case class CreateDeposit(
  accountId: UUID,
  amount: BigDecimal,
  currency: String,
  startDate: LocalDate,
  endDate: LocalDate,
  interestRate: BigDecimal,
  status: DepositStatus
)

object CreateDeposit:
  given Decoder[CreateDeposit] = deriveDecoder

case class CreateInvestmentTransaction(
  accountId: UUID,
  instrumentSymbol: Option[String],
  instrumentName: String,
  instrumentType: InstrumentType,
  currency: String,
  date: LocalDate,
  txType: InvestmentTxType,
  quantity: Option[BigDecimal],
  price: Option[BigDecimal],
  fees: BigDecimal,
  amountPln: BigDecimal,
  notes: Option[String]
)

object CreateInvestmentTransaction:
  given Decoder[CreateInvestmentTransaction] = deriveDecoder

// ── View / response types ─────────────────────────────────────────────────────

case class PositionDetail(
  accountId: UUID,
  accountName: String,
  instrumentId: UUID,
  symbol: Option[String],
  name: String,
  instrumentType: InstrumentType,
  quantity: BigDecimal,
  avgBuyPrice: Option[BigDecimal],
  currentPrice: Option[BigDecimal],
  currentValue: Option[BigDecimal],
  costBasis: Option[BigDecimal],
  gainLoss: Option[BigDecimal],
  gainLossPct: Option[BigDecimal]
)

object PositionDetail:
  given Encoder[PositionDetail] = deriveEncoder

case class BondWithValue(
  bond: TreasuryBond,
  accountName: String,
  invested: BigDecimal,
  accruedInterest: BigDecimal,
  currentValue: BigDecimal,
  gainLossPct: BigDecimal
)

object BondWithValue:
  given Encoder[BondWithValue] = deriveEncoder

case class DepositWithValue(
  deposit: Deposit,
  accountName: String,
  accruedInterest: BigDecimal,
  currentValue: BigDecimal,
  gainLossPct: BigDecimal
)

object DepositWithValue:
  given Encoder[DepositWithValue] = deriveEncoder

case class TypeAllocation(
  typeName: String,
  currentValue: BigDecimal,
  invested: BigDecimal,
  pct: BigDecimal
)

object TypeAllocation:
  given Encoder[TypeAllocation] = deriveEncoder

case class PortfolioSummary(
  owner: String,
  totalCurrentValue: BigDecimal,
  totalInvested: BigDecimal,
  totalGainLoss: BigDecimal,
  totalGainLossPct: BigDecimal,
  byType: List[TypeAllocation]
)

object PortfolioSummary:
  given Encoder[PortfolioSummary] = deriveEncoder

case class InflationPoint(month: String, cpiIndex: BigDecimal)

object InflationPoint:
  given Encoder[InflationPoint] = deriveEncoder
