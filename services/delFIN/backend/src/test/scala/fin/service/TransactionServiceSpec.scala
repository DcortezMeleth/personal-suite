package fin.service

import org.scalatest.funsuite.AnyFunSuite
import fin.domain.*
import fin.repository.{CategoryRepository, TransactionRepository}
import doobie.Transactor
import cats.effect.IO
import java.time.LocalDate
import java.util.UUID

class TransactionServiceSpec extends AnyFunSuite:

  // matchCategory is pure and never touches the transactor — a null one is fine
  // for exercising just that method without wiring up a real database.
  private val service = TransactionService(
    TransactionRepository(null.asInstanceOf[Transactor[IO]]),
    CategoryRepository(null.asInstanceOf[Transactor[IO]])
  )

  private val groceriesId = UUID.randomUUID()

  private def pt(title: String, counterparty: Option[String] = None, rawDescription: String = "", amount: BigDecimal = BigDecimal("-10.00")) =
    ParsedTransaction(
      date           = LocalDate.of(2026, 1, 1),
      amount         = amount,
      currency       = "PLN",
      title          = title,
      counterparty   = counterparty,
      rawDescription = if rawDescription.nonEmpty then rawDescription else title
    )

  private def rule(pattern: String, matchType: RuleMatchType = RuleMatchType.CONTAINS, direction: RuleDirection = RuleDirection.ANY, priority: Int = 10) =
    CategoryRule(UUID.randomUUID(), groceriesId, pattern, matchType, priority, direction)

  test("matches a CONTAINS rule against raw_description (existing behaviour)") {
    val transaction = pt(title = "Some Title", rawDescription = "...BIEDRONKA 123...")
    assert(service.matchCategory(transaction, List(rule("BIEDRONKA"))) == Some(groceriesId))
  }

  test("matches a CONTAINS rule against title even when raw_description doesn't mention it") {
    // Simulates a card-fee line whose title was borrowed from the preceding purchase,
    // but whose own raw statement text never mentions the merchant.
    val transaction = pt(title = "Card fee — Cafe Example", rawDescription = "8090<00Pobranie oplaty<20Transakcja karta platnicza")
    assert(service.matchCategory(transaction, List(rule("Cafe Example"))) == Some(groceriesId))
  }

  test("matches a CONTAINS rule against counterparty even when title/raw_description don't mention it") {
    val transaction = pt(title = "Przelew do ZUS", counterparty = Some("ZUS"), rawDescription = "0300<00Przelew<27ZUS")
    assert(service.matchCategory(transaction, List(rule("ZUS"))) == Some(groceriesId))
  }

  test("returns None when no rule matches any of title/counterparty/raw_description") {
    val transaction = pt(title = "Unrelated Merchant")
    assert(service.matchCategory(transaction, List(rule("BIEDRONKA"))) == None)
  }

  test("EXACT match type requires a whole-field match, not a substring") {
    val transaction = pt(title = "ZUS")
    assert(service.matchCategory(transaction, List(rule("ZUS", RuleMatchType.EXACT))) == Some(groceriesId))
    val notExact = pt(title = "Przelew do ZUS")
    assert(service.matchCategory(notExact, List(rule("ZUS", RuleMatchType.EXACT))) == None)
  }

  test("REGEX match type is case-insensitive, matching the retroactive sweep's Postgres ~* semantics") {
    val transaction = pt(title = "lidl katowice")
    assert(service.matchCategory(transaction, List(rule("^LIDL", RuleMatchType.REGEX))) == Some(groceriesId))
  }

  test("when multiple rules match, the first by (priority, then list order) wins") {
    val transaction = pt(title = "STR Parka")
    val specific = rule("STR Parka", priority = 5)
    val generic  = rule("STR", priority = 10).copy(categoryId = UUID.randomUUID())
    // Caller is expected to pass rules pre-sorted by priority (as findAllRules does) —
    // matchCategory itself just takes the first match in list order.
    assert(service.matchCategory(transaction, List(specific, generic)) == Some(groceriesId))
  }

  test("a rule scoped to EXPENSE does not match an incoming (positive) transaction with the same pattern") {
    val incoming = pt(title = "ZUS", amount = BigDecimal("500.00"))
    assert(service.matchCategory(incoming, List(rule("ZUS", direction = RuleDirection.EXPENSE))) == None)
  }

  test("a rule scoped to EXPENSE matches an outgoing (negative) transaction") {
    val outgoing = pt(title = "ZUS", amount = BigDecimal("-500.00"))
    assert(service.matchCategory(outgoing, List(rule("ZUS", direction = RuleDirection.EXPENSE))) == Some(groceriesId))
  }

  test("a rule scoped to INCOME does not match an outgoing (negative) transaction with the same pattern") {
    val outgoing = pt(title = "ZUS", amount = BigDecimal("-500.00"))
    assert(service.matchCategory(outgoing, List(rule("ZUS", direction = RuleDirection.INCOME))) == None)
  }

  test("an ANY-direction rule matches regardless of amount sign") {
    assert(service.matchCategory(pt(title = "ZUS", amount = BigDecimal("500.00")), List(rule("ZUS"))) == Some(groceriesId))
    assert(service.matchCategory(pt(title = "ZUS", amount = BigDecimal("-500.00")), List(rule("ZUS"))) == Some(groceriesId))
  }
