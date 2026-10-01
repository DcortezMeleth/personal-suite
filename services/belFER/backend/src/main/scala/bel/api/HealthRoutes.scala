package bel.api

import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*

object HealthRoutes:
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "health" =>
      Ok("""{"status":"ok","service":"belFER"}""")
  }
