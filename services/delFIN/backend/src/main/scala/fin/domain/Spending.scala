package fin.domain

import java.util.UUID
import io.circe.Encoder
import io.circe.generic.semiauto.*

case class CategorySpending(
  categoryId: UUID,
  categoryName: String,
  color: String,
  icon: Option[String],
  amount: BigDecimal
)

object CategorySpending:
  given Encoder[CategorySpending] = deriveEncoder

// Unlike CategorySpending, these amounts aren't a partition of totalSpent —
// a transaction can carry several tags at once, so it contributes its full
// amount to each of them independently (and untagged transactions, the
// common case, contribute to none).
case class TagSpending(
  tagId: UUID,
  tagName: String,
  color: String,
  icon: Option[String],
  amount: BigDecimal
)

object TagSpending:
  given Encoder[TagSpending] = deriveEncoder

case class MonthlySummary(
  month: String,
  totalSpent: BigDecimal,
  totalIncome: BigDecimal,
  netCashflow: BigDecimal,
  deltaVsPrevMonth: BigDecimal,
  spendingByCategory: List[CategorySpending],
  spendingByTag: List[TagSpending]
)

object MonthlySummary:
  given Encoder[MonthlySummary] = deriveEncoder

case class MonthlyTrend(
  month: String,
  totalSpent: BigDecimal,
  totalIncome: BigDecimal
)

object MonthlyTrend:
  given Encoder[MonthlyTrend] = deriveEncoder

case class ImportResult(imported: Int, skipped: Int, transfersDetected: Int)

object ImportResult:
  given Encoder[ImportResult] = deriveEncoder
