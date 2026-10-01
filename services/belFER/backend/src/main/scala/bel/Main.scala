package bel

import bel.api.*
import bel.db.Database
import cats.effect.*
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import com.typesafe.config.ConfigFactory
import org.http4s.HttpRoutes
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router

object Main extends IOApp.Simple:

  override def run: IO[Unit] =
    val config     = ConfigFactory.load()
    val hostStr    = config.getString("belfer.server.host")
    val portInt    = config.getInt("belfer.server.port")
    val serverHost = Host.fromString(hostStr).getOrElse(Host.fromString("0.0.0.0").get)
    val serverPort = Port.fromInt(portInt).getOrElse(Port.fromInt(8081).get)

    Database.migrate(config) >>
      Database.transactor(config).use { _ =>
        // Routes are wired in here as they arrive; Phase 1 fills this in. The
        // transactor is already opened so a bad database config fails at
        // startup rather than on the first request.
        val apiRoutes = HttpRoutes.empty[IO]

        EmberServerBuilder
          .default[IO]
          .withHost(serverHost)
          .withPort(serverPort)
          .withHttpApp(
            Router(
              "/"    -> HealthRoutes.routes,
              "/api" -> apiRoutes
            ).orNotFound
          )
          .build
          .useForever
      }
