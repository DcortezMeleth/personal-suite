package bel.db

import org.scalatest.funsuite.AnyFunSuite

// Both of these check Bazel wiring rather than logic. They exist because the
// failure they catch is silent: Flyway treats a missing location as "no
// migrations" and starts the server against an empty database, and a test
// reading a missing fixture only fails much later and far less clearly.
class ClasspathWiringSpec extends AnyFunSuite:

  private def resourceExists(path: String): Boolean =
    Option(getClass.getResourceAsStream(path)).map(_.close()).isDefined

  test("migrations are on the classpath at the location Flyway is configured to read") {
    assert(
      resourceExists("/db/belFER/migrations/V1__initial_schema.sql"),
      "//db/belFER:migrations is not reaching the classpath — Flyway would " +
        "silently find no migrations and leave the database empty"
    )
  }

  test("the arkusz fixture is on the classpath for the importer's tests") {
    assert(
      resourceExists("/services/belFER/testdata/arkusz/full-semester.xml"),
      "//services/belFER/testdata:testdata is not reaching the test classpath"
    )
  }

class SchemaUrlSpec extends AnyFunSuite:

  test("adds the schema to a url that has no query string") {
    assert(
      Database.withSchema("jdbc:postgresql://localhost:5432/delfin", "belfer") ==
        "jdbc:postgresql://localhost:5432/delfin?currentSchema=belfer"
    )
  }

  test("appends to a url that already carries parameters, rather than replacing them") {
    assert(
      Database.withSchema("jdbc:postgresql://db:5432/belfer?ssl=true", "public") ==
        "jdbc:postgresql://db:5432/belfer?ssl=true&currentSchema=public"
    )
  }
