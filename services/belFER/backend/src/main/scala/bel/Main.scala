package bel

import bel.api.*
import bel.db.Database
import bel.repository.SchoolRepository
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
      Database.transactor(config).use { xa =>
        val schoolRepo = SchoolRepository(xa)

        val apiRoutes = SchoolRoutes(schoolRepo).routes

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
