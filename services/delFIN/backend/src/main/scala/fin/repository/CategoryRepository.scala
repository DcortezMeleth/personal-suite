package fin.repository

import cats.effect.IO
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import fin.domain.*
import fin.repository.DoobieMeta.given
import java.util.UUID

class CategoryRepository(xa: Transactor[IO]):

  def findAll: IO[List[Category]] =
    sql"""
      SELECT id, name, color, icon, parent_id FROM categories ORDER BY name
    """.query[Category].to[List].transact(xa)

  def createCategory(cmd: CreateCategory): IO[Category] =
    sql"""
      INSERT INTO categories (name, color, icon, parent_id)
      VALUES (${cmd.name}, ${cmd.color}, ${cmd.icon}, ${cmd.parentId})
      RETURNING id, name, color, icon, parent_id
    """.query[Category].unique.transact(xa)

  def updateCategory(id: UUID, cmd: UpdateCategory): IO[Option[Category]] =
    sql"""
      UPDATE categories
      SET name = ${cmd.name}, color = ${cmd.color}, icon = ${cmd.icon}, parent_id = ${cmd.parentId}
      WHERE id = $id
      RETURNING id, name, color, icon, parent_id
    """.query[Category].option.transact(xa)

  // No ON DELETE CASCADE/SET NULL on category_id anywhere on purpose — deleting
  // a category that's still in use (transactions, rules, budgets, or as a
  // parent) should fail loudly rather than silently orphan/cascade data.
  def deleteCategory(id: UUID): IO[Either[String, Unit]] =
    sql"DELETE FROM categories WHERE id = $id".update.run.transact(xa).attempt.map {
      case Left(e: org.postgresql.util.PSQLException) if e.getSQLState == "23503" =>
        Left("This category is still in use (transactions, rules, budgets, or a subcategory) — reassign or remove those first.")
      case Left(e) => throw e
      case Right(_) => Right(())
    }

  def findAllRules: IO[List[CategoryRule]] =
    sql"""
      SELECT id, category_id, pattern, match_type, priority, direction
      FROM category_rules
      ORDER BY priority ASC, created_at ASC
    """.query[CategoryRule].to[List].transact(xa)

  def createRule(cmd: CreateCategoryRule): IO[CategoryRule] =
    sql"""
      INSERT INTO category_rules (category_id, pattern, match_type, priority, direction)
      VALUES (${cmd.categoryId}, ${cmd.pattern}, ${cmd.matchType}, ${cmd.priority}, ${cmd.direction})
      RETURNING id, category_id, pattern, match_type, priority, direction
    """.query[CategoryRule].unique.transact(xa)

  def updateRule(id: UUID, cmd: UpdateCategoryRule): IO[Option[CategoryRule]] =
    sql"""
      UPDATE category_rules
      SET category_id = ${cmd.categoryId}, pattern = ${cmd.pattern}, match_type = ${cmd.matchType},
          priority = ${cmd.priority}, direction = ${cmd.direction}
      WHERE id = $id
      RETURNING id, category_id, pattern, match_type, priority, direction
    """.query[CategoryRule].option.transact(xa)

  def deleteRule(id: UUID): IO[Int] =
    sql"DELETE FROM category_rules WHERE id = $id".update.run.transact(xa)

  // Auto-generates a CONTAINS rule from a manual category correction, so future
  // imports of the same counterparty/title are categorised without help. Rules
  // born this way get top priority (5) — they reflect explicit user intent, so
  // they should win over generic seeded/manual patterns. Scoped to the corrected
  // transaction's own direction by default: correcting an outgoing payment to
  // "ZUS" shouldn't silently also apply to an incoming refund from "ZUS" — those
  // can be different things despite sharing a counterparty. Skips creation if an
  // identical (pattern, category, direction) rule already exists, to avoid
  // duplicate spam when the same correction is made more than once.
  def createRuleFromCorrection(pattern: String, categoryId: UUID, direction: RuleDirection): IO[Option[CategoryRule]] =
    sql"""
      SELECT COUNT(*) > 0 FROM category_rules
      WHERE UPPER(pattern) = UPPER($pattern) AND category_id = $categoryId AND direction = $direction
    """.query[Boolean].unique.transact(xa).flatMap {
      case true  => IO.pure(None)
      case false =>
        sql"""
          INSERT INTO category_rules (category_id, pattern, match_type, priority, direction)
          VALUES ($categoryId, $pattern, ${RuleMatchType.CONTAINS}, 5, $direction)
          RETURNING id, category_id, pattern, match_type, priority, direction
        """.query[CategoryRule].unique.transact(xa).map(Some(_))
    }

  // scope controls which existing transactions a (re-)applied rule may touch:
  // NONE just creates/keeps the rule for future imports, UNCATEGORIZED_ONLY fills
  // in blanks without overwriting prior corrections, ALL re-checks everything
  // matching (including already-categorised transactions).
  def recategoriseByRule(rule: CategoryRule, scope: RecategorizeScope): IO[Int] =
    if scope == RecategorizeScope.NONE then IO.pure(0)
    else
      // Matches title/counterparty as well as raw_description — a rule seeded from
      // a (possibly borrowed) title must also catch transactions whose raw text
      // never mentions the merchant, e.g. card-fee lines.
      val matchFr = rule.matchType match
        case RuleMatchType.CONTAINS =>
          val needle = "%" + rule.pattern + "%"
          fr"""(UPPER(title) LIKE UPPER($needle)
               OR UPPER(COALESCE(counterparty, '')) LIKE UPPER($needle)
               OR UPPER(raw_description) LIKE UPPER($needle))"""
        case RuleMatchType.EXACT =>
          fr"""(UPPER(title) = UPPER(${rule.pattern})
               OR UPPER(COALESCE(counterparty, '')) = UPPER(${rule.pattern})
               OR UPPER(raw_description) = UPPER(${rule.pattern}))"""
        case RuleMatchType.REGEX =>
          fr"""(title ~* ${rule.pattern}
               OR COALESCE(counterparty, '') ~* ${rule.pattern}
               OR raw_description ~* ${rule.pattern})"""

      val scopeFr = scope match
        case RecategorizeScope.ALL              => fr""
        case RecategorizeScope.UNCATEGORIZED_ONLY => fr"AND category_id IS NULL"
        case RecategorizeScope.NONE              => fr"AND FALSE" // unreachable, guarded above

      val directionFr = rule.direction match
        case RuleDirection.ANY     => fr""
        case RuleDirection.INCOME  => fr"AND amount > 0"
        case RuleDirection.EXPENSE => fr"AND amount < 0"

      (fr"UPDATE transactions SET category_id = ${rule.categoryId} WHERE" ++ matchFr ++ directionFr ++ scopeFr)
        .update.run.transact(xa)
