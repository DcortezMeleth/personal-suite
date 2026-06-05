package fin.repository

import doobie.util.meta.Meta
import doobie.postgres.implicits.*
import fin.domain.*

object DoobieMeta:
  given Meta[AccountType] =
    pgEnumStringOpt("account_type", s => scala.util.Try(AccountType.valueOf(s)).toOption, _.toString)

  given Meta[AccountOwner] =
    pgEnumStringOpt("account_owner", s => scala.util.Try(AccountOwner.valueOf(s)).toOption, _.toString)

  given Meta[RuleMatchType] =
    pgEnumStringOpt(
      "rule_match_type",
      s => scala.util.Try(RuleMatchType.valueOf(s)).toOption,
      _.toString
    )
