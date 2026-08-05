package fin.service

import cats.effect.IO
import cats.syntax.traverse.*
import fin.domain.*
import fin.repository.*
import java.util.UUID

class TransactionService(
  txRepo:  TransactionRepository,
  catRepo: CategoryRepository
):

  def importTransactions(parsed: List[ParsedTransaction], accountId: UUID): IO[ImportResult] =
    for
      rules   <- catRepo.findAllRules
      results <- parsed.traverse { pt =>
                   txRepo
                     .existsDuplicate(accountId, pt.date, pt.amount, pt.rawDescription)
                     .flatMap {
                       case true  => IO.pure(false)
                       case false =>
                         val catId = matchCategory(pt, rules)
                         txRepo.insert(pt, accountId, catId).as(true)
                     }
                 }
      imported   = results.count(identity)
      transferred <- txRepo.detectAndLinkTransfers
    yield ImportResult(imported, results.length - imported, transferred)

  // Matches against title/counterparty as well as the raw dump — a rule created
  // from a transaction's (borrowed) title must also catch that same transaction,
  // even when the raw statement text itself never mentions the merchant (e.g.
  // card-fee lines, which inherit their title from the preceding purchase).
  private[service] def matchCategory(pt: ParsedTransaction, rules: List[CategoryRule]): Option[UUID] =
    val fields = List(pt.title, pt.counterparty.getOrElse(""), pt.rawDescription)
    rules.find { r =>
      directionMatches(r.direction, pt.amount) && (r.matchType match
        case RuleMatchType.CONTAINS =>
          fields.exists(_.toUpperCase.contains(r.pattern.toUpperCase))
        case RuleMatchType.EXACT =>
          fields.exists(_.equalsIgnoreCase(r.pattern))
        case RuleMatchType.REGEX =>
          // Case-insensitive to match the retroactive sweep's Postgres `~*` semantics.
          fields.exists(f => scala.util.Try(s"(?i)${r.pattern}".r.findFirstIn(f).isDefined).getOrElse(false)))
    }.map(_.categoryId)

  private def directionMatches(direction: RuleDirection, amount: BigDecimal): Boolean =
    direction match
      case RuleDirection.ANY     => true
      case RuleDirection.INCOME  => amount > 0
      case RuleDirection.EXPENSE => amount < 0
