package fin.domain

import java.time.OffsetDateTime
import java.util.UUID
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*

enum AccountType:
  case CHECKING, SAVINGS, INVESTMENT, IKE, IKZE, COMPANY

object AccountType:
  given Encoder[AccountType] = Encoder[String].contramap(_.toString)
  given Decoder[AccountType] = Decoder[String].emap(s =>
    scala.util.Try(AccountType.valueOf(s)).toEither.left.map(_.getMessage)
  )

enum AccountOwner:
  case SELF, WIFE, JOINT

object AccountOwner:
  given Encoder[AccountOwner] = Encoder[String].contramap(_.toString)
  given Decoder[AccountOwner] = Decoder[String].emap(s =>
    scala.util.Try(AccountOwner.valueOf(s)).toEither.left.map(_.getMessage)
  )

case class Account(
  id: UUID,
  name: String,
  accountType: AccountType,
  institution: String,
  currency: String,
  owner: AccountOwner,
  createdAt: OffsetDateTime
)

object Account:
  given Encoder[Account] = deriveEncoder
  given Decoder[Account] = deriveDecoder

case class CreateAccount(
  name: String,
  accountType: AccountType,
  institution: String,
  currency: String,
  owner: AccountOwner
)

object CreateAccount:
  given Decoder[CreateAccount] = deriveDecoder
