package fin.api

import cats.effect.IO
import cats.syntax.traverse.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import fin.domain.*
import fin.repository.{CategoryRepository, TagRepository, TransactionRepository}
import java.time.LocalDate
import java.util.UUID

object MonthParam     extends QueryParamDecoderMatcher[Int]("month")
object YearParam      extends QueryParamDecoderMatcher[Int]("year")
object LimitParam     extends OptionalQueryParamDecoderMatcher[Int]("limit")
object DateFromParam  extends OptionalQueryParamDecoderMatcher[String]("dateFrom")
object DateToParam    extends OptionalQueryParamDecoderMatcher[String]("dateTo")
object CategoryParam  extends OptionalQueryParamDecoderMatcher[String]("categoryId")
object SearchParam    extends OptionalQueryParamDecoderMatcher[String]("search")
object MinAmountParam extends OptionalQueryParamDecoderMatcher[String]("minAmount")
object MaxAmountParam extends OptionalQueryParamDecoderMatcher[String]("maxAmount")
object SortByParam    extends OptionalQueryParamDecoderMatcher[String]("sortBy")
object SortDirParam   extends OptionalQueryParamDecoderMatcher[String]("sortDir")
object PageParam      extends OptionalQueryParamDecoderMatcher[Int]("page")
object PageSizeParam  extends OptionalQueryParamDecoderMatcher[Int]("pageSize")

class TransactionRoutes(repo: TransactionRepository, categoryRepo: CategoryRepository, tagRepo: TagRepository):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "transactions" / "search"
        :? DateFromParam(dateFromOpt) +& DateToParam(dateToOpt) +& CategoryParam(categoryIdOpt)
        +& SearchParam(searchOpt) +& MinAmountParam(minAmountOpt) +& MaxAmountParam(maxAmountOpt)
        +& SortByParam(sortByOpt) +& SortDirParam(sortDirOpt) +& PageParam(pageOpt) +& PageSizeParam(pageSizeOpt) =>
      scala.util.Try {
        val dateFrom   = dateFromOpt.map(LocalDate.parse)
        val dateTo     = dateToOpt.map(LocalDate.parse)
        val categoryId = categoryIdOpt.map(UUID.fromString)
        val minAmount  = minAmountOpt.map(BigDecimal(_))
        val maxAmount  = maxAmountOpt.map(BigDecimal(_))
        (dateFrom, dateTo, categoryId, minAmount, maxAmount)
      } match
        case scala.util.Failure(e) =>
          BadRequest(s"""{"error":"Invalid query parameter: ${e.getMessage}"}""")
        case scala.util.Success((dateFrom, dateTo, categoryId, minAmount, maxAmount)) =>
          val sortBy   = if sortByOpt.contains("amount") then "amount" else "date"
          val sortDir  = if sortDirOpt.contains("asc") then "asc" else "desc"
          val page     = math.max(0, pageOpt.getOrElse(0))
          val pageSize = math.min(500, math.max(1, pageSizeOpt.getOrElse(50)))
          repo.search(dateFrom, dateTo, categoryId, searchOpt, minAmount, maxAmount, sortBy, sortDir, page, pageSize)
            .flatMap { result =>
              tagRepo.findTagsForTransactions(result.items.map(_.id)).flatMap { tagsByTx =>
                val enriched = result.items.map(row => TransactionRowWithTags(row, tagsByTx.getOrElse(row.id, Nil)))
                Ok(TransactionSearchResultWithTags(enriched, result.total).asJson)
              }
            }

    case GET -> Root / "transactions" / "top" / "all-time" :? LimitParam(limitOpt) =>
      repo.findTopAll(limitOpt.getOrElse(10)).flatMap(list => Ok(list.asJson))

    case GET -> Root / "transactions" :? YearParam(year) +& MonthParam(month) =>
      repo.findByMonth(year, month).flatMap(list => Ok(list.asJson))

    case GET -> Root / "transactions" / "top" :? YearParam(year) +& MonthParam(month) +& LimitParam(limitOpt) =>
      repo.findTopByMonth(year, month, limitOpt.getOrElse(10))
        .flatMap(list => Ok(list.asJson))

    // Sets the category for THIS transaction only — no side effects. A correction
    // might be a one-off exception (Lidl while on holiday shouldn't turn every
    // Lidl trip into "Holidays"), so nothing is generalised into a rule unless
    // the user explicitly asks for that via POST .../category-rule below.
    case req @ PATCH -> Root / "transactions" / UUIDVar(id) / "category" =>
      req.as[SetCategory].flatMap { cmd =>
        repo.updateCategory(id, cmd.categoryId).flatMap(_ => Ok("""{"ok":true}"""))
      }

    // Free-text per-transaction annotation, independent of title/counterparty
    // and never touched by the derived-fields backfill — see SetNotes.
    case req @ PATCH -> Root / "transactions" / UUIDVar(id) / "notes" =>
      req.as[SetNotes].flatMap { cmd =>
        repo.updateNotes(id, cmd.notes).flatMap(_ => Ok("""{"ok":true}"""))
      }

    // Bulk-assigns one tag to a set of transactions at once — the intended flow
    // is filter the list down to a trip's date range, select the rows, then tag
    // all of them in one go, rather than tagging one at a time.
    case req @ POST -> Root / "transactions" / "tags" / "bulk-assign" =>
      req.as[BulkAssignTag].flatMap { cmd =>
        repo.bulkAssignTag(cmd.transactionIds, cmd.tagId).flatMap(n => Ok(Json.obj("assigned" -> Json.fromInt(n))))
      }

    case DELETE -> Root / "transactions" / UUIDVar(id) / "tags" / UUIDVar(tagId) =>
      repo.removeTag(id, tagId).flatMap(_ => NoContent())

    // Explicit, separate action: generalise this transaction's (counterparty or
    // title, category) pairing into a standing rule. `scope` decides whether/how
    // far it reaches into already-imported transactions. Only happens when asked.
    // The rule defaults to the transaction's own cash-flow direction — an outgoing
    // payment to "ZUS" shouldn't silently also catch an incoming refund from "ZUS".
    case req @ POST -> Root / "transactions" / UUIDVar(id) / "category-rule" =>
      req.as[CreateRuleFromTransaction].flatMap { cmd =>
        for
          seedOpt  <- repo.findRulePatternSeed(id)
          ruleOpt  <- seedOpt.traverse { case (seed, amount) =>
                        val direction = if amount > 0 then RuleDirection.INCOME else RuleDirection.EXPENSE
                        categoryRepo.createRuleFromCorrection(seed, cmd.categoryId, direction)
                      }.map(_.flatten)
          affected <- ruleOpt.traverse(categoryRepo.recategoriseByRule(_, cmd.scope)).map(_.getOrElse(0))
          resp     <- Ok(
                        Json.obj(
                          "ruleCreated" -> Json.fromBoolean(ruleOpt.isDefined),
                          "rulePattern" -> ruleOpt.map(r => Json.fromString(r.pattern)).getOrElse(Json.Null),
                          "affected"    -> Json.fromInt(affected)
                        )
                      )
        yield resp
      }
  }
