package bel.domain

/**
 * A saved plan, written out as a page somebody can read.
 *
 * The application shows plans properly now, so this is no longer the only way
 * to look at one. What it still is, is the way to look at one without a
 * server or a database: `--sheet=` on a solver run writes a file that opens
 * from a file manager, which is what you want when the question is whether
 * the week that just came back resembles a timetable at all.
 *
 * Deliberately plain, and deliberately not its own idea of what a plan is: it
 * renders the same PlanLayout the screens do, asking only for shorter teacher
 * names. It used to resolve placements itself, which is how it came to differ
 * from the screens about what a class is told about tuition.
 */
object PlanSheet:

  /**
   * Who a lesson belongs to, for the two sets of sheets this prints.
   *
   * A cross-class block belongs to every class sending students to it;
   * tuition belongs to no class, because the student is not in one while it
   * happens — only to the teacher, which is the whole reason for carrying it.
   */
  private def grouped(lessons: List[PlanLayout.Lesson]) =
    val byClass = lessons.flatMap(l => l.classes.map(_ -> l)).groupMap(_._1)(_._2)
    val byTeacher = lessons.flatMap(l => l.teachers.map(_ -> l)).groupMap(_._1)(_._2)
    (byClass, byTeacher)

  def apply(s: SchoolSnapshot, plan: Plan, placements: List[Placement],
            rooms: List[Room]): String =
    // The same resolving the screens do, asking only for shorter teacher
    // names: a grid cell has no room for "Kowalski Jan (AN)".
    val lessons = PlanLayout.of(s, placements, rooms, PlanLayout.shortName)
    val (byClass, byTeacher) = grouped(lessons)

    val b = StringBuilder()
    b ++= s"""<!doctype html><html lang="pl"><head><meta charset="utf-8">
<title>Plan lekcji</title>
<style>
 body { font-family: system-ui, sans-serif; margin: 2rem; color: #1a1a1a; }
 h1 { font-size: 1.4rem; } h2 { font-size: 1.1rem; margin-top: 2.5rem; }
 table { border-collapse: collapse; margin: .5rem 0 1.5rem; font-size: .82rem; }
 th, td { border: 1px solid #ccc; padding: .25rem .4rem; vertical-align: top;
          min-width: 8.5rem; height: 2.1rem; }
 th { background: #f2f2f2; font-weight: 600; }
 td.hour { background: #fafafa; text-align: center; min-width: 2rem; font-weight: 600; }
 .sub { font-weight: 600; } .meta { color: #666; font-size: .75rem; }
 .g1 { border-left: 3px solid #2a7; } .g2 { border-left: 3px solid #74c; }
 nav { position: sticky; top: 0; background: #fff; padding: .5rem 0;
       border-bottom: 1px solid #ddd; margin-bottom: 1rem; }
 nav a { margin-right: .6rem; font-size: .8rem; }
 .summary { color: #555; font-size: .85rem; }
</style></head><body>
<h1>${esc(plan.name)}</h1>
<p class="summary">${placements.size} zajęć &middot; ${plan.madeBy} &middot;
  reguły: ${plan.rulesKept.getOrElse(0)}/${plan.rulesTotal.getOrElse(0)}</p>
"""

    b ++= "<nav><strong>Oddziały:</strong> "
    for cls <- s.classes.sortBy(_.name) do
      b ++= s"""<a href="#c-${esc(cls.name)}">${esc(cls.name)}</a>"""
    b ++= "</nav>\n"

    b ++= "<h2>Plany oddziałów</h2>\n"
    for cls <- s.classes.sortBy(_.name) do
      b ++= s"""<h3 id="c-${esc(cls.name)}">${esc(cls.name)}</h3>\n"""
      b ++= grid(byClass.getOrElse(cls.name, Nil), s.slotsPerDay, s.teachingDays,
        showTeacher = true)

    b ++= "<h2>Plany nauczycieli</h2>\n"
    for code <- byTeacher.keys.toList.sorted do
      b ++= s"<h3>${esc(code)}</h3>\n"
      b ++= grid(byTeacher(code), s.slotsPerDay, s.teachingDays, showTeacher = false)

    b ++= "</body></html>\n"
    b.result()

  /** One week as a table: hours down the side, days across. */
  private def grid(lessons: List[PlanLayout.Lesson], slots: Int, days: Int,
                   showTeacher: Boolean): String =
    val at = lessons.groupBy(e => (e.day, e.position))
    val b = StringBuilder()
    b ++= "<table><tr><th></th>"
    for d <- 0 until days do b ++= s"<th>${PlanLayout.DayNames(d)}</th>"
    b ++= "</tr>\n"
    for p <- 0 until slots do
      b ++= s"""<tr><td class="hour">${p + 1}</td>"""
      for d <- 0 until days do
        val here = at.getOrElse((d, p), Nil).sortBy(_.half.getOrElse(0))
        val klass = here.flatMap(_.half).distinct match
          case List(1) => " class=\"g1\""
          case List(2) => " class=\"g2\""
          case _       => ""
        b ++= s"<td$klass>"
        for e <- here do
          val half = e.half.fold("")(h => s"/$h")
          val name = if e.individual then s"${e.subject} (ind.)" else e.subject
          val meta = List(if showTeacher then e.teachers.mkString(", ") else "",
            e.room.getOrElse("")).filter(_.nonEmpty).mkString(" &middot; ")
          b ++= s"""<div><span class="sub">${esc(name)}$half</span>"""
          if meta.nonEmpty then b ++= s"""<br><span class="meta">$meta</span>"""
          b ++= "</div>"
        b ++= "</td>"
      b ++= "</tr>\n"
    b ++= "</table>\n"
    b.result()

  private def esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
