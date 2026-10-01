package bel.db

import cats.effect.{IO, Resource}
import com.typesafe.config.Config
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import org.flywaydb.core.Flyway

object Database:

  private val Schema = "belfer"

  def transactor(config: Config): Resource[IO, HikariTransactor[IO]] =
    val url      = config.getString("belfer.database.url")
    val user     = config.getString("belfer.database.user")
    val password = config.getString("belfer.database.password")
    val poolSize = config.getInt("belfer.database.pool-size")
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
        .schemas(Schema)
        .defaultSchema(Schema)
        .locations("classpath:db/belFER/migrations")
        .load()
        .migrate()
    }.void
