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
  title: String,
  counterparty: Option[String],
  rawDescription: String,
  categoryId: Option[UUID],
  isInternalTransfer: Boolean,
  transferPeerId: Option[UUID],
  importedAt: OffsetDateTime,
  notes: Option[String]
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
  title: String,
  counterparty: Option[String],
  notes: Option[String],
  categoryId: Option[UUID],
  categoryName: Option[String],
  categoryColor: Option[String],
  isInternalTransfer: Boolean
)

object TransactionRow:
  given Encoder[TransactionRow] = deriveEncoder

case class TransactionSearchResult(items: List[TransactionRow], total: Long)

object TransactionSearchResult:
  given Encoder[TransactionSearchResult] = deriveEncoder

case class ParsedTransaction(
  date: LocalDate,
  amount: BigDecimal,
  currency: String,
  title: String,
  counterparty: Option[String],
  rawDescription: String
)

case class SetCategory(categoryId: UUID)
object SetCategory:
  given Decoder[SetCategory] = deriveDecoder

case class SetNotes(notes: Option[String])
object SetNotes:
  given Decoder[SetNotes] = deriveDecoder

case class CreateRuleFromTransaction(categoryId: UUID, scope: RecategorizeScope)
object CreateRuleFromTransaction:
  given Decoder[CreateRuleFromTransaction] = deriveDecoder
