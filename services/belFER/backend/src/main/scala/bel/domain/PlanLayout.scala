package bel.domain

import java.util.UUID

/**
 * A finished plan, with every identifier turned into the name it stands for.
 *
 * A placement says "this lesson, that hour" and nothing else: who it occupies,
 * who teaches it and what it is called all live back in the school. Turning
 * one into the other is the step between a stored plan and any way of reading
 * it, and there are three kinds of placement to turn — a lesson line, one
 * group of a cross-class unit, and an hour of individual tuition — each
 * reaching for a different part of the snapshot.
 *
 * It was written twice: once for the screens and once for the single-file
 * sheet the solver prints. Two expressions of one mapping is the arrangement
 * ADR 0004 warns about for rules, and it holds here too — the second copy had
 * already drifted on what a class is told about tuition. So it is written
 * once, and the only thing the two callers disagree about is how to write a
 * teacher's name, which is a parameter.
 */
object PlanLayout:

  /** One lesson, at one hour, with everyone it concerns. */
  case class Lesson(
    day: Int,
    position: Int,
    length: Int,
    subject: String,
    teachers: List[String],
    room: Option[String],
    /** Classes this occupies. Empty for individual tuition. */
    classes: List[String],
    /** 1 or 2 when only one half of a class is in it. */
    half: Option[Int],
    /** One student taught alone, shown on the teacher's plan only. */
    individual: Boolean
  )

  val DayNames =
    List("Poniedziałek", "Wtorek", "Środa", "Czwartek", "Piątek", "Sobota", "Niedziela")

  /** How a teacher is named on a screen, where there is room to be unambiguous. */
  def fullName(t: Teacher): String = s"${t.lastName} ${t.firstName} (${t.code})"

  /** How a teacher is named in a grid cell, where there is not. */
  def shortName(t: Teacher): String = t.code

  /**
   * @param naming how to write a teacher, which is all the two readers of a
   *     plan differ about
   */
  def of(s: SchoolSnapshot, placements: List[Placement], rooms: List[Room],
         naming: Teacher => String = fullName): List[Lesson] =
    val subjectNames = s.subjects.map(x => x.id -> x.name).toMap
    val teacherNames = s.teachers.map(t => t.id -> naming(t)).toMap
    val classNames = s.classes.map(c => c.id -> c.name).toMap
    val roomNames = rooms.map(r => r.id -> r.number).toMap
    val byLine = s.lessonLines.map(l => l.id -> l).toMap
    val byGroup = s.crossClassUnits.flatMap(u => u.groups.map(g => g.id -> (u, g))).toMap
    val byTuition = s.individualPlans.flatMap(p => p.lessons.map(l => l.id -> (p, l))).toMap

    placements.flatMap { p =>
      val room = p.roomId.flatMap(roomNames.get)

      p.lessonLineId.flatMap(byLine.get).map { line =>
        Lesson(
          day = p.dayOfWeek - 1, position = p.position - 1,
          length = line.blocks.lift(p.blockIndex).getOrElse(1),
          subject = subjectNames.getOrElse(line.subjectId, "?"),
          teachers = (line.teacherId.toList ++ line.supportTeacherId.toList).flatMap(teacherNames.get),
          room = room,
          classes = classNames.get(line.classId).toList,
          half = line.audience match
            case LessonAudience.GROUP_1 => Some(1)
            case LessonAudience.GROUP_2 => Some(2)
            case _                      => None,
          individual = false)
      }.orElse {
        p.crossClassGroupId.flatMap(byGroup.get).map { (unit, group) =>
          Lesson(
            day = p.dayOfWeek - 1, position = p.position - 1,
            length = unit.blocks.lift(p.blockIndex).getOrElse(1),
            subject = subjectNames.getOrElse(unit.subjectId, "?"),
            teachers = group.teacherId.toList.flatMap(teacherNames.get),
            room = room,
            classes = group.classIds.flatMap(classNames.get).sorted,
            half = None,
            individual = false)
        }
      }.orElse {
        p.individualLessonId.flatMap(byTuition.get).map { (plan, lesson) =>
          Lesson(
            day = p.dayOfWeek - 1, position = p.position - 1,
            length = lesson.blocks.lift(p.blockIndex).getOrElse(1),
            subject = subjectNames.getOrElse(lesson.subjectId, "?"),
            teachers = lesson.teacherId.toList.flatMap(teacherNames.get),
            room = room,
            // The class is not in school for this, so it names no class: one
            // student is, and which student is not ours to say.
            classes = Nil,
            half = None,
            individual = true)
        }
      }
    }
