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
                         val catId = matchCategory(pt.rawDescription, rules)
                         txRepo.insert(pt, accountId, catId).as(true)
                     }
                 }
      imported   = results.count(identity)
      transferred <- txRepo.detectAndLinkTransfers
    yield ImportResult(imported, results.length - imported, transferred)

  private def matchCategory(rawDesc: String, rules: List[CategoryRule]): Option[UUID] =
    rules.find { r =>
      r.matchType match
        case RuleMatchType.CONTAINS => rawDesc.toUpperCase.contains(r.pattern.toUpperCase)
        case RuleMatchType.EXACT    => rawDesc.equalsIgnoreCase(r.pattern)
        case RuleMatchType.REGEX    =>
          scala.util.Try(r.pattern.r.findFirstIn(rawDesc).isDefined).getOrElse(false)
    }.map(_.categoryId)
