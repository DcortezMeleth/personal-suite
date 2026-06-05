package fin.db

import cats.effect.{IO, Resource}
import com.typesafe.config.Config
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import org.flywaydb.core.Flyway

object Database:

  def transactor(config: Config): Resource[IO, HikariTransactor[IO]] =
    val url      = config.getString("delfin.database.url")
    val user     = config.getString("delfin.database.user")
    val password = config.getString("delfin.database.password")
    val poolSize = config.getInt("delfin.database.pool-size")
    for
      ec <- ExecutionContexts.fixedThreadPool[IO](poolSize)
      xa <- HikariTransactor.newHikariTransactor[IO](
              "org.postgresql.Driver",
              url,
              user,
              password,
              ec
            )
    yield xa

  def migrate(config: Config): IO[Unit] =
    IO {
      Flyway
        .configure()
        .dataSource(
          config.getString("delfin.database.url"),
          config.getString("delfin.database.user"),
          config.getString("delfin.database.password")
        )
        .locations("classpath:db/migrations")
        .load()
        .migrate()
    }.void
