package fin.domain

import java.util.UUID
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*

enum RuleMatchType:
  case CONTAINS, REGEX, EXACT

object RuleMatchType:
  given Encoder[RuleMatchType] = Encoder[String].contramap(_.toString)
  given Decoder[RuleMatchType] = Decoder[String].emap(s =>
    scala.util.Try(RuleMatchType.valueOf(s)).toEither.left.map(_.getMessage)
  )

/** How far a (re-)applied category rule should reach into already-imported data. */
enum RecategorizeScope:
  case NONE, UNCATEGORIZED_ONLY, ALL

object RecategorizeScope:
  given Decoder[RecategorizeScope] = Decoder[String].emap(s =>
    scala.util.Try(RecategorizeScope.valueOf(s)).toEither.left.map(_.getMessage)
  )

/** Restricts a rule to one cash-flow direction. The same counterparty can mean
  * different things depending on which way money moved (e.g. a merchant refund
  * arriving vs. paying that merchant), so a rule shouldn't have to apply to both
  * by default.
  */
enum RuleDirection:
  case ANY, INCOME, EXPENSE

object RuleDirection:
  given Encoder[RuleDirection] = Encoder[String].contramap(_.toString)
  given Decoder[RuleDirection] = Decoder[String].emap(s =>
    scala.util.Try(RuleDirection.valueOf(s)).toEither.left.map(_.getMessage)
  )

case class Category(
  id: UUID,
  name: String,
  color: String,
  icon: Option[String],
  parentId: Option[UUID]
)

object Category:
  given Encoder[Category] = deriveEncoder
  given Decoder[Category] = deriveDecoder

case class CreateCategory(
  name: String,
  color: String,
  icon: Option[String],
  parentId: Option[UUID]
)

object CreateCategory:
  given Decoder[CreateCategory] = deriveDecoder

case class UpdateCategory(
  name: String,
  color: String,
  icon: Option[String],
  parentId: Option[UUID]
)

object UpdateCategory:
  given Decoder[UpdateCategory] = deriveDecoder

case class CategoryRule(
  id: UUID,
  categoryId: UUID,
  pattern: String,
  matchType: RuleMatchType,
  priority: Int,
  direction: RuleDirection
)

object CategoryRule:
  given Encoder[CategoryRule] = deriveEncoder
  given Decoder[CategoryRule] = deriveDecoder

case class CreateCategoryRule(
  categoryId: UUID,
  pattern: String,
  matchType: RuleMatchType,
  priority: Int,
  direction: RuleDirection
)

object CreateCategoryRule:
  given Decoder[CreateCategoryRule] = deriveDecoder

case class UpdateCategoryRule(
  categoryId: UUID,
  pattern: String,
  matchType: RuleMatchType,
  priority: Int,
  direction: RuleDirection
)

object UpdateCategoryRule:
  given Decoder[UpdateCategoryRule] = deriveDecoder
