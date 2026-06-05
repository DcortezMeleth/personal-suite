package fin.domain

import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*

case class Transaction(
  id: UUID,
  accountId: UUID,
  date: LocalDate,
  amount: BigDecimal,
  currency: String,
  description: String,
  rawDescription: String,
  categoryId: Option[UUID],
  isInternalTransfer: Boolean,
  transferPeerId: Option[UUID],
  importedAt: OffsetDateTime
)

object Transaction:
  given Encoder[Transaction] = deriveEncoder
  given Decoder[Transaction] = deriveDecoder

case class TransactionRow(
  id: UUID,
  accountId: UUID,
  accountName: String,
  date: LocalDate,
  amount: BigDecimal,
  currency: String,
  description: String,
  categoryId: Option[UUID],
  categoryName: Option[String],
  categoryColor: Option[String],
  isInternalTransfer: Boolean
)

object TransactionRow:
  given Encoder[TransactionRow] = deriveEncoder

case class ParsedTransaction(
  date: LocalDate,
  amount: BigDecimal,
  currency: String,
  description: String,
  rawDescription: String
)

case class SetCategory(categoryId: UUID)
object SetCategory:
  given Decoder[SetCategory] = deriveDecoder
