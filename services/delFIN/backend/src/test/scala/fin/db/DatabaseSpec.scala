package fin.db

import org.scalatest.funsuite.AnyFunSuite

class DatabaseSpec extends AnyFunSuite:

  test("adds the schema to a url that has no query string") {
    assert(
      Database.withSchema("jdbc:postgresql://localhost:5432/delfin", "delfin") ==
        "jdbc:postgresql://localhost:5432/delfin?currentSchema=delfin"
    )
  }

  test("appends to a url that already carries parameters, rather than replacing them") {
    assert(
      Database.withSchema("jdbc:postgresql://db:5432/delfin?ssl=true", "delfin") ==
        "jdbc:postgresql://db:5432/delfin?ssl=true&currentSchema=delfin"
    )
  }
