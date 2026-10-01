package bel.db

import cats.effect.{IO, Resource}
import com.typesafe.config.Config
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import org.flywaydb.core.Flyway

object Database:

  def transactor(config: Config): Resource[IO, HikariTransactor[IO]] =
    val url      = config.getString("belfer.database.url")
    val user     = config.getString("belfer.database.user")
    val password = config.getString("belfer.database.password")
    val poolSize = config.getInt("belfer.database.pool-size")
    val schema   = config.getString("belfer.database.schema")
    for
      ec <- ExecutionContexts.fixedThreadPool[IO](poolSize)
      xa <- HikariTransactor.newHikariTransactor[IO](
              "org.postgresql.Driver",
              // The schema is applied to the connection rather than baked into
              // the configured URL, so that pointing belFER at its own
              // database is a one-setting change.
              withSchema(url, schema),
              user,
              password,
              ec
            )
    yield xa

  private[db] def withSchema(url: String, schema: String): String =
    val separator = if url.contains("?") then "&" else "?"
    s"$url${separator}currentSchema=$schema"

  // belFER owns a schema of its own inside the shared instance, and its own
  // migration location, so that it and delFIN can never apply each other's
  // migrations or share a version counter — each gets its own
  // flyway_schema_history inside its own schema. Flyway creates the schema if
  // it is missing, so there is no manual setup step.
  //
  // The location resolves because //db/belFER:migrations is listed as a
  // resource of belfer_lib, which puts it on the classpath under its
  // repo-relative path.
  def migrate(config: Config): IO[Unit] =
    IO {
      Flyway
        .configure()
        .dataSource(
          config.getString("belfer.database.url"),
          config.getString("belfer.database.user"),
          config.getString("belfer.database.password")
        )
        .schemas(config.getString("belfer.database.schema"))
        .defaultSchema(config.getString("belfer.database.schema"))
        .locations("classpath:db/belFER/migrations")
        .load()
        .migrate()
    }.void
