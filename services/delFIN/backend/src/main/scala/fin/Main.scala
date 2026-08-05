package fin

import cats.effect.*
import cats.syntax.semigroupk.*
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import com.typesafe.config.ConfigFactory
import fin.api.*
import fin.db.Database
import fin.repository.*
import fin.service.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.server.Router
import org.http4s.ember.server.EmberServerBuilder

object Main extends IOApp.Simple:

  override def run: IO[Unit] =
    val config     = ConfigFactory.load()
    val hostStr    = config.getString("delfin.server.host")
    val portInt    = config.getInt("delfin.server.port")
    val serverHost = Host.fromString(hostStr).getOrElse(Host.fromString("0.0.0.0").get)
    val serverPort = Port.fromInt(portInt).getOrElse(Port.fromInt(8080).get)

    Database.migrate(config) >>
      (for
        xa         <- Database.transactor(config)
        httpClient <- EmberClientBuilder.default[IO].build
      yield (xa, httpClient)).use { case (xa, httpClient) =>
        val accountRepo  = AccountRepository(xa)
        val categoryRepo = CategoryRepository(xa)
        val txRepo       = TransactionRepository(xa)
        val budgetRepo   = BudgetRepository(xa)
        val spendingRepo = SpendingRepository(xa)
        val investRepo   = InvestmentRepository(xa)

        val txService       = TransactionService(txRepo, categoryRepo)
        val spendingService = SpendingService(spendingRepo)
        val investService   = InvestmentService(investRepo, accountRepo)
        val inflationSvc    = InflationService(httpClient, investRepo)

        val apiRoutes =
          AccountRoutes(accountRepo).routes                              <+>
          ImportRoutes(accountRepo, txService, investService).routes     <+>
          TransactionRoutes(txRepo, categoryRepo).routes                 <+>
          CategoryRoutes(categoryRepo).routes                            <+>
          BudgetRoutes(budgetRepo).routes                                <+>
          SpendingRoutes(spendingService).routes                         <+>
          InvestmentRoutes(investRepo, investService, inflationSvc).routes <+>
          AdminRoutes(txRepo).routes

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
