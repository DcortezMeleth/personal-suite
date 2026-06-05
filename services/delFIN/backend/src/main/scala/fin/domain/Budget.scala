package fin.domain

import java.util.UUID
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*

case class Budget(
  id: UUID,
  categoryId: UUID,
  monthlyLimit: BigDecimal,
  alertThresholdPct: Int
)

object Budget:
  given Encoder[Budget] = deriveEncoder
  given Decoder[Budget] = deriveDecoder

case class CreateBudget(
  categoryId: UUID,
  monthlyLimit: BigDecimal,
  alertThresholdPct: Int
)

object CreateBudget:
  given Decoder[CreateBudget] = deriveDecoder

case class BudgetStatus(
  categoryId: UUID,
  categoryName: String,
  monthlyLimit: BigDecimal,
  alertThresholdPct: Int,
  spent: BigDecimal,
  pct: Int,
  isWarning: Boolean,
  isBreached: Boolean
)

object BudgetStatus:
  given Encoder[BudgetStatus] = deriveEncoder
