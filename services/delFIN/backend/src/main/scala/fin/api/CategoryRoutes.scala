package fin.api

import cats.effect.IO
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import fin.domain.*
import fin.repository.CategoryRepository
import java.util.UUID

class CategoryRoutes(repo: CategoryRepository):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "categories" =>
      repo.findAll.flatMap(list => Ok(list.asJson))

    case GET -> Root / "category-rules" =>
      repo.findAllRules.flatMap(list => Ok(list.asJson))

    case req @ POST -> Root / "category-rules" =>
      req.as[CreateCategoryRule].flatMap { cmd =>
        repo.createRule(cmd).flatMap { rule =>
          repo.recategoriseByRule(rule).flatMap(_ => Created(rule.asJson))
        }
      }

    case DELETE -> Root / "category-rules" / UUIDVar(id) =>
      repo.deleteRule(id).flatMap(_ => NoContent())
  }
