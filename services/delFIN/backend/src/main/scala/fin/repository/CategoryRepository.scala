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
      SELECT id, name, color, icon, parent_id, is_internal FROM categories ORDER BY name
    """.query[Category].to[List].transact(xa)

  def createCategory(cmd: CreateCategory): IO[Category] =
    sql"""
      INSERT INTO categories (name, color, icon, parent_id, is_internal)
      VALUES (${cmd.name}, ${cmd.color}, ${cmd.icon}, ${cmd.parentId}, ${cmd.isInternal})
      RETURNING id, name, color, icon, parent_id, is_internal
    """.query[Category].unique.transact(xa)

  def updateCategory(id: UUID, cmd: UpdateCategory): IO[Option[Category]] =
    sql"""
      UPDATE categories
      SET name = ${cmd.name}, color = ${cmd.color}, icon = ${cmd.icon}, parent_id = ${cmd.parentId},
          is_internal = ${cmd.isInternal}
      WHERE id = $id
      RETURNING id, name, color, icon, parent_id, is_internal
    """.query[Category].option.transact(xa)

  def hasChildren(id: UUID): IO[Boolean] =
    sql"SELECT COUNT(*) > 0 FROM categories WHERE parent_id = $id".query[Boolean].unique.transact(xa)

  // None means "no such category" — distinct from Some(false).
  def isTopLevel(id: UUID): IO[Option[Boolean]] =
    sql"SELECT parent_id IS NULL FROM categories WHERE id = $id".query[Boolean].option.transact(xa)

  // Categories with subcategories are pure rollup containers — a transaction
  // (or a rule, which just sets a transaction's category_id) can only ever be
  // assigned to a leaf/standalone category, never to something that itself
  // has children. Otherwise a parent's rolled-up total (its own transactions
  // + its children's) would double-count or become ambiguous.
  def assertAssignable(id: UUID): IO[Option[String]] =
    hasChildren(id).map { has =>
      if has then Some("This category has subcategories — pick one of its subcategories instead of the parent.")
      else None
    }

  // Capped at 2 levels (top-level categories + their direct children, no
  // grandchildren) — enough for every real case (Car/VW+Audi, Taxes/ZUS+VAT),
  // and it keeps this check simple: no cycle detection needed, no recursion.
  def validateParent(parentId: Option[UUID], selfId: Option[UUID]): IO[Option[String]] =
    parentId match
      case None => IO.pure(None)
      case Some(pid) if selfId.contains(pid) =>
        IO.pure(Some("A category can't be its own parent."))
      case Some(pid) =>
        for
          topOpt       <- isTopLevel(pid)
          selfHasKids  <- selfId.fold(IO.pure(false))(hasChildren)
        yield topOpt match
          case None        => Some("Parent category not found.")
          case Some(false) => Some("Can't nest under a category that already has a parent (max 2 levels).")
          case Some(true) if selfHasKids =>
            Some("This category has subcategories — a category with subcategories can't itself become one.")
          case _ => None

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
  // Priority defaults to 5 rather than the seeded rules' 10: a rule the user
  // asked for while correcting a transaction should be checked before the
  // generic built-in patterns, not after them.
  def createRuleFromCorrection(
    pattern:    String,
    categoryId: UUID,
    direction:  RuleDirection,
    matchType:  RuleMatchType = RuleMatchType.CONTAINS,
    priority:   Int = 5
  ): IO[Option[CategoryRule]] =
    sql"""
      SELECT COUNT(*) > 0 FROM category_rules
      WHERE UPPER(pattern) = UPPER($pattern) AND category_id = $categoryId AND direction = $direction
    """.query[Boolean].unique.transact(xa).flatMap {
      case true  => IO.pure(None)
      case false =>
        sql"""
          INSERT INTO category_rules (category_id, pattern, match_type, priority, direction)
          VALUES ($categoryId, $pattern, $matchType, $priority, $direction)
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
