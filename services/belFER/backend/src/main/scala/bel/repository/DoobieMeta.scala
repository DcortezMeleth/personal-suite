package bel.repository

import bel.domain.{LessonAudience, LessonKind}
import doobie.*
import doobie.postgres.implicits.*

/**
 * Postgres enum types, mapped centrally so every repository reads them the
 * same way. Mirrors delFIN's `fin.repository.DoobieMeta`.
 */
object DoobieMeta:

  given Meta[LessonAudience] =
    pgEnumStringOpt("lesson_audience", LessonAudience.parse, _.toString)

  given Meta[LessonKind] =
    pgEnumStringOpt("lesson_kind", LessonKind.parse, _.toString)
