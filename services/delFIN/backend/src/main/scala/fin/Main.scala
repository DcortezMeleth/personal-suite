package fin

import cats.effect.*
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import com.typesafe.config.ConfigFactory
import fin.api.HealthRoutes
import org.http4s.server.Router
import org.http4s.ember.server.EmberServerBuilder

object Main extends IOApp.Simple:

  override def run: IO[Unit] =
    val config      = ConfigFactory.load()
    val hostStr     = config.getString("delfin.server.host")
    val portInt     = config.getInt("delfin.server.port")
    val serverHost  = Host.fromString(hostStr).getOrElse(Host.fromString("0.0.0.0").get)
    val serverPort  = Port.fromInt(portInt).getOrElse(Port.fromInt(8080).get)

    EmberServerBuilder
      .default[IO]
      .withHost(serverHost)
      .withPort(serverPort)
      .withHttpApp(Router("/" -> HealthRoutes.routes).orNotFound)
      .build
      .useForever
