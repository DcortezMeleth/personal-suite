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

case class CategoryRule(
  id: UUID,
  categoryId: UUID,
  pattern: String,
  matchType: RuleMatchType,
  priority: Int
)

object CategoryRule:
  given Encoder[CategoryRule] = deriveEncoder
  given Decoder[CategoryRule] = deriveDecoder

case class CreateCategoryRule(
  categoryId: UUID,
  pattern: String,
  matchType: RuleMatchType,
  priority: Int
)

object CreateCategoryRule:
  given Decoder[CreateCategoryRule] = deriveDecoder
