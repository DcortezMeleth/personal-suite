package fin.domain

import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto.*
import io.circe.syntax.*

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
  categoryIcon: Option[String],
  isInternalTransfer: Boolean
)

object TransactionRow:
  given Encoder[TransactionRow] = deriveEncoder

case class TransactionSearchResult(items: List[TransactionRow], total: Long)

object TransactionSearchResult:
  given Encoder[TransactionSearchResult] = deriveEncoder

// Tags come from a separate many-to-many join, not a plain SQL column, so
// they can't just be another field on TransactionRow (doobie derives Read
// positionally from SELECT columns). Reuses TransactionRow's own encoder via
// deepMerge instead of repeating every field.
case class TransactionRowWithTags(row: TransactionRow, tags: List[Tag])

object TransactionRowWithTags:
  given Encoder[TransactionRowWithTags] = Encoder.instance { rt =>
    rt.row.asJson.deepMerge(Json.obj("tags" -> rt.tags.asJson))
  }

case class TransactionSearchResultWithTags(items: List[TransactionRowWithTags], total: Long)

object TransactionSearchResultWithTags:
  given Encoder[TransactionSearchResultWithTags] = deriveEncoder

case class BulkAssignTag(transactionIds: List[UUID], tagId: UUID)
object BulkAssignTag:
  given Decoder[BulkAssignTag] = deriveDecoder

case class AssignTag(tagId: UUID)
object AssignTag:
  given Decoder[AssignTag] = deriveDecoder

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
