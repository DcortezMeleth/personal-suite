package fin.domain

import org.scalatest.funsuite.AnyFunSuite
import java.util.UUID

class CategoryFilterSpec extends AnyFunSuite:

  test("an absent categoryId means no category filtering at all") {
    assert(CategoryFilter.fromParam(None) == CategoryFilter.All)
  }

  // The <select>'s "All" option has an empty value, and an empty query param
  // reaches the route as Some("") rather than None.
  test("an empty categoryId is treated the same as an absent one") {
    assert(CategoryFilter.fromParam(Some("")) == CategoryFilter.All)
  }

  test("the 'none' token selects only transactions without a category") {
    assert(CategoryFilter.fromParam(Some("none")) == CategoryFilter.Uncategorized)
  }

  test("the 'any' token excludes transactions without a category") {
    assert(CategoryFilter.fromParam(Some("any")) == CategoryFilter.Categorized)
  }

  test("anything else is read as a single category id") {
    val id = UUID.randomUUID()
    assert(CategoryFilter.fromParam(Some(id.toString)) == CategoryFilter.One(id))
  }

  // Surfaces as a 400 from the search route, which wraps parsing in a Try —
  // the sentinels must not turn a typo into a silently unfiltered query.
  test("a malformed category id is rejected rather than ignored") {
    assertThrows[IllegalArgumentException](CategoryFilter.fromParam(Some("not-a-uuid")))
  }
