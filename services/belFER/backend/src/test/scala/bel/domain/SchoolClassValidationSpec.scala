package bel.domain

import org.scalatest.funsuite.AnyFunSuite

class SchoolClassValidationSpec extends AnyFunSuite:

  private val base = SchoolClassInput(
    year = 1,
    letter = "A",
    specialisation = Some("MAT-FIZ-INF"),
    homeroomTeacherId = None,
    studentCount = Some(30),
    girlCount = Some(14)
  )

  private val years = 4

  test("accepts a normal class") {
    assert(SchoolClassValidation.validate(base, years) == Right(()))
  }

  test("accepts a class with no counts and no profile") {
    assert(SchoolClassValidation.validate(
      base.copy(specialisation = None, studentCount = None, girlCount = None), years) == Right(()))
  }

  test("normalises the letter, so 'a' and 'A' are the same class") {
    assert(SchoolClassValidation.normaliseLetter(" a ") == "A")
  }

  test("rejects a year outside the school") {
    assert(SchoolClassValidation.validate(base.copy(year = 0), years).isLeft)
    assert(SchoolClassValidation.validate(base.copy(year = 5), years).isLeft)
  }

  test("rejects a blank or non-alphabetic letter") {
    assert(SchoolClassValidation.validate(base.copy(letter = "  "), years).isLeft)
    assert(SchoolClassValidation.validate(base.copy(letter = "2"), years).isLeft)
  }

  test("rejects more girls than students") {
    assert(SchoolClassValidation.validate(base.copy(studentCount = Some(10), girlCount = Some(11)), years).isLeft)
  }

  test("accepts a girls count with no total, since neither constrains anything") {
    assert(SchoolClassValidation.validate(base.copy(studentCount = None, girlCount = Some(14)), years) == Right(()))
  }

  // The real arkusz has no 1B and uneven year sizes, so generation names the
  // letters that exist rather than counting up from A.
  test("accepts generating a non-contiguous set of letters") {
    assert(SchoolClassValidation.validateGeneration(
      GenerateClasses(1, List("A", "C", "D", "E", "F", "G", "H")), years) == Right(()))
  }

  test("rejects generating with repeated letters, case-insensitively") {
    assert(SchoolClassValidation.validateGeneration(GenerateClasses(1, List("A", "a")), years).isLeft)
  }

  test("rejects generating nothing") {
    assert(SchoolClassValidation.validateGeneration(GenerateClasses(1, Nil), years).isLeft)
    assert(SchoolClassValidation.validateGeneration(GenerateClasses(1, List(" ")), years).isLeft)
  }
