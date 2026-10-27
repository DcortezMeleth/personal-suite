package bel.domain

import java.util.UUID

/**
 * A saved plan, written out as a page somebody can read.
 *
 * Phase 4 builds these views properly, in the application, where they can be
 * clicked through and edited. This is the version that exists before that: one
 * file, no server, openable from a file manager — enough for the planner to
 * look at a generated week and say whether it resembles a timetable.
 *
 * Deliberately plain. It is a thing to check a plan with, not a thing to hand
 * to a school, and the moment it starts growing features it should be replaced
 * by the real views instead.
 */
object PlanSheet:

  private val Days = List("Poniedziałek", "Wtorek", "Środa", "Czwartek", "Piątek")

  /** One lesson in one cell. */
  private case class Entry(
    day: Int, position: Int, length: Int,
    subject: String, teacher: String, room: String,
    /** Which half, when a lesson is only for one of them. */
    half: Option[Int]
  )

  def apply(s: SchoolSnapshot, plan: Plan, placements: List[Placement],
            rooms: List[Room]): String =
    val subjects = s.subjects.map(x => x.id -> x.name).toMap
    val teachers = s.teachers.map(t => t.id -> t.code).toMap
    val classes = s.classes.map(c => c.id -> c.name).toMap
    val roomNames = rooms.map(r => r.id -> r.number).toMap
    val lines = s.lessonLines.map(l => l.id -> l).toMap
    val groups = s.crossClassUnits.flatMap(u => u.groups.map(g => g.id -> (u, g))).toMap
    val tuition = s.individualPlans.flatMap(p => p.lessons.map(l => l.id -> (p, l))).toMap

    // Every placement, told who it belongs to. A cross-class block belongs to
    // every class sending students to it; tuition belongs to the class the
    // student is in, though the class itself is not busy.
    val byClass = scala.collection.mutable.Map.empty[UUID, List[Entry]].withDefaultValue(Nil)
    val byTeacher = scala.collection.mutable.Map.empty[String, List[Entry]].withDefaultValue(Nil)

    def add(classIds: List[UUID], who: Option[String], e: Entry): Unit =
      for id <- classIds do byClass(id) = e :: byClass(id)
      for code <- who do byTeacher(code) = e :: byTeacher(code)

    for p <- placements do
      val room = p.roomId.flatMap(roomNames.get).getOrElse("")
      p.lessonLineId.flatMap(lines.get) match
        case Some(line) =>
          val staff = (line.teacherId.toList ++ line.supportTeacherId.toList).flatMap(teachers.get)
          val e = Entry(p.dayOfWeek - 1, p.position - 1,
            line.blocks.lift(p.blockIndex).getOrElse(1),
            subjects.getOrElse(line.subjectId, "?"), staff.mkString(", "), room,
            line.audience match
              case LessonAudience.GROUP_1 => Some(1)
              case LessonAudience.GROUP_2 => Some(2)
              case _                      => None)
          add(List(line.classId), staff.headOption, e)
          for extra <- staff.drop(1) do byTeacher(extra) = e :: byTeacher(extra)
        case None =>
          p.crossClassGroupId.flatMap(groups.get) match
            case Some((unit, group)) =>
              val who = group.teacherId.flatMap(teachers.get)
              val e = Entry(p.dayOfWeek - 1, p.position - 1,
                unit.blocks.lift(p.blockIndex).getOrElse(1),
                subjects.getOrElse(unit.subjectId, "?"), who.getOrElse(""), room, None)
              add(group.classIds, who, e)
            case None =>
              for (ip, lesson) <- p.individualLessonId.flatMap(tuition.get) do
                val who = lesson.teacherId.flatMap(teachers.get)
                val e = Entry(p.dayOfWeek - 1, p.position - 1,
                  lesson.blocks.lift(p.blockIndex).getOrElse(1),
                  subjects.getOrElse(lesson.subjectId, "?") + " (ind.)",
                  who.getOrElse(""), room, None)
                // The class is not busy, so this appears on the teacher's
                // sheet and is listed against the class only for reference.
                for code <- who do byTeacher(code) = e :: byTeacher(code)

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
      b ++= grid(byClass(cls.id), s.slotsPerDay, s.teachingDays, showTeacher = true)

    b ++= "<h2>Plany nauczycieli</h2>\n"
    for code <- byTeacher.keys.toList.sorted do
      b ++= s"<h3>${esc(code)}</h3>\n"
      b ++= grid(byTeacher(code), s.slotsPerDay, s.teachingDays, showTeacher = false)

    b ++= "</body></html>\n"
    b.result()

  /** One week as a table: hours down the side, days across. */
  private def grid(entries: List[Entry], slots: Int, days: Int, showTeacher: Boolean): String =
    val at = entries.groupBy(e => (e.day, e.position))
    val b = StringBuilder()
    b ++= "<table><tr><th></th>"
    for d <- 0 until days do b ++= s"<th>${Days(d)}</th>"
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
          val meta = List(if showTeacher then e.teacher else "", e.room)
            .filter(_.nonEmpty).mkString(" &middot; ")
          b ++= s"""<div><span class="sub">${esc(e.subject)}$half</span>"""
          if meta.nonEmpty then b ++= s"""<br><span class="meta">$meta</span>"""
          b ++= "</div>"
        b ++= "</td>"
      b ++= "</tr>\n"
    b ++= "</table>\n"
    b.result()

  private def esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
