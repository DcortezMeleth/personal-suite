package fin.api

import cats.effect.IO
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import fin.domain.*
import fin.repository.TagRepository

class TagRoutes(repo: TagRepository):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "tags" =>
      repo.findAll.flatMap(list => Ok(list.asJson))

    case req @ POST -> Root / "tags" =>
      req.as[CreateTag].flatMap(cmd => repo.createTag(cmd).flatMap(tag => Created(tag.asJson)))

    case req @ PUT -> Root / "tags" / UUIDVar(id) =>
      req.as[UpdateTag].flatMap { cmd =>
        repo.updateTag(id, cmd).flatMap {
          case Some(tag) => Ok(tag.asJson)
          case None      => NotFound(s"""{"error":"No tag with id $id"}""")
        }
      }

    case DELETE -> Root / "tags" / UUIDVar(id) =>
      repo.deleteTag(id).flatMap(_ => NoContent())
  }
