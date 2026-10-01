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
    val schema   = config.getString("delfin.database.schema")
    for
      ec <- ExecutionContexts.fixedThreadPool[IO](poolSize)
      xa <- HikariTransactor.newHikariTransactor[IO](
              "org.postgresql.Driver",
              // Applied to the connection rather than baked into the configured
              // URL, so pointing delFIN at a different database stays a
              // one-setting change.
              withSchema(url, schema),
              user,
              password,
              ec
            )
    yield xa

  private[db] def withSchema(url: String, schema: String): String =
    val separator = if url.contains("?") then "&" else "?"
    s"$url${separator}currentSchema=$schema"

  def migrate(config: Config): IO[Unit] =
    IO {
      Flyway
        .configure()
        .dataSource(
          config.getString("delfin.database.url"),
          config.getString("delfin.database.user"),
          config.getString("delfin.database.password")
        )
        .schemas(config.getString("delfin.database.schema"))
        .defaultSchema(config.getString("delfin.database.schema"))
        .locations("classpath:db/migrations")
        .load()
        .migrate()
    }.void
