package fin.api

import cats.effect.IO
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import fin.domain.*
import fin.repository.CategoryRepository
import java.util.UUID

case class ReapplyRequest(scope: RecategorizeScope)
object ReapplyRequest:
  given io.circe.Decoder[ReapplyRequest] = io.circe.generic.semiauto.deriveDecoder

class CategoryRoutes(repo: CategoryRepository):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "categories" =>
      repo.findAll.flatMap(list => Ok(list.asJson))

    case GET -> Root / "category-rules" =>
      repo.findAllRules.flatMap(list => Ok(list.asJson))

    // Creating/editing a rule never touches existing transactions by itself —
    // that's a separate, explicit action via POST .../reapply below, so nothing
    // gets silently mass-recategorised as a side effect of a rule edit.
    case req @ POST -> Root / "category-rules" =>
      req.as[CreateCategoryRule].flatMap(cmd => repo.createRule(cmd).flatMap(rule => Created(rule.asJson)))

    case req @ PUT -> Root / "category-rules" / UUIDVar(id) =>
      req.as[UpdateCategoryRule].flatMap { cmd =>
        repo.updateRule(id, cmd).flatMap {
          case Some(rule) => Ok(rule.asJson)
          case None       => NotFound(s"""{"error":"No rule with id $id"}""")
        }
      }

    case DELETE -> Root / "category-rules" / UUIDVar(id) =>
      repo.deleteRule(id).flatMap(_ => NoContent())

    // Explicit, on-demand sweep of an existing rule (manually created or
    // auto-generated from a correction) over already-imported transactions.
    case req @ POST -> Root / "category-rules" / UUIDVar(id) / "reapply" =>
      req.as[ReapplyRequest].flatMap { cmd =>
        repo.findAllRules.flatMap { rules =>
          rules.find(_.id == id) match
            case None => NotFound(s"""{"error":"No rule with id $id"}""")
            case Some(rule) =>
              repo.recategoriseByRule(rule, cmd.scope).flatMap(affected => Ok(io.circe.Json.obj("affected" -> io.circe.Json.fromInt(affected))))
        }
      }
  }
