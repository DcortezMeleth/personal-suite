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

  def findAllRules: IO[List[CategoryRule]] =
    sql"""
      SELECT id, category_id, pattern, match_type, priority
      FROM category_rules
      ORDER BY priority ASC, created_at ASC
    """.query[CategoryRule].to[List].transact(xa)

  def createRule(cmd: CreateCategoryRule): IO[CategoryRule] =
    sql"""
      INSERT INTO category_rules (category_id, pattern, match_type, priority)
      VALUES (${cmd.categoryId}, ${cmd.pattern}, ${cmd.matchType}, ${cmd.priority})
      RETURNING id, category_id, pattern, match_type, priority
    """.query[CategoryRule].unique.transact(xa)

  def deleteRule(id: UUID): IO[Int] =
    sql"DELETE FROM category_rules WHERE id = $id".update.run.transact(xa)

  def recategoriseByRule(rule: CategoryRule): IO[Int] =
    rule.matchType match
      case RuleMatchType.CONTAINS =>
        sql"""
          UPDATE transactions SET category_id = ${rule.categoryId}
          WHERE UPPER(raw_description) LIKE UPPER(${"%" + rule.pattern + "%"})
        """.update.run.transact(xa)
      case RuleMatchType.EXACT =>
        sql"""
          UPDATE transactions SET category_id = ${rule.categoryId}
          WHERE UPPER(raw_description) = UPPER(${rule.pattern})
        """.update.run.transact(xa)
      case RuleMatchType.REGEX =>
        sql"""
          UPDATE transactions SET category_id = ${rule.categoryId}
          WHERE raw_description ~* ${rule.pattern}
        """.update.run.transact(xa)
