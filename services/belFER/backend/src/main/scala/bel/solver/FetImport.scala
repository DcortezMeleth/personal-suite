package bel.solver

import bel.domain.*
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * FET's answer, read back into our own terms.
 *
 * What comes out of FET is spare: an activity number, a day name, an hour, and
 * a room. Everything that says what the activity *was* stayed behind in the
 * file we sent, which is why the export keeps a record of what each number
 * means. Here the two are put back together.
 *
 * Nothing here trusts the result. A timetable that comes back is checked
 * against our own statement of the rules before anybody is shown it, and the
 * checking lives apart from this on purpose: a fault in the translation should
 * produce a timetable that fails the check, not one that passes it because the
 * same mistake was made twice.
 */
object FetImport:

  /** Where one lesson ended up. */
  case class Placed(what: LessonRef, day: Int, position: Int, length: Int,
                    /** The room FET put it in, by the name we gave the room. */
                    room: Option[String])

  case class Result(placed: List[Placed], unplaced: Int):
    def complete: Boolean = unplaced == 0

  /**
   * @param days the day names written into the file, in order, because FET
   *     answers with the names it was given rather than with indices
   */
  def apply(xml: String, means: Map[Int, LessonRef],
            days: List[String]): Result =
    val byName = days.zipWithIndex.toMap
    val factory = DocumentBuilderFactory.newInstance()
    // Nothing in a timetable needs a DTD, and a parser that will fetch one is
    // a parser that will fetch anything it is pointed at.
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    val doc = factory.newDocumentBuilder()
      .parse(java.io.ByteArrayInputStream(xml.getBytes("UTF-8")))

    def child(e: Element, name: String): Option[String] =
      val found = e.getElementsByTagName(name)
      Option.when(found.getLength > 0)(found.item(0).getTextContent.trim)

    val nodes = doc.getElementsByTagName("Activity")
    val placed = (0 until nodes.getLength).toList.flatMap { i =>
      val e = nodes.item(i).asInstanceOf[Element]
      for
        id   <- child(e, "Id").flatMap(_.toIntOption)
        what <- means.get(id)
        day  <- child(e, "Day").flatMap(byName.get)
        hour <- child(e, "Hour").flatMap(_.toIntOption)
      yield Placed(what, day, hour - 1, 1, child(e, "Room").filter(_.nonEmpty))
    }

    // Anything we asked about and did not get an hour for. FET will not
    // normally return a half-finished timetable, but "it said no" and "it said
    // nothing about this one" are different answers and worth telling apart.
    Result(placed, means.size - placed.size)

  /**
   * The placements, expanded into what the rules need to know about them.
   *
   * A placement says only "this lesson, that hour". Who it occupies, who
   * teaches it and whether it may sit in the middle of a day all live in the
   * school rather than in the answer, so they are looked up here.
   */
  def expand(s: SchoolSnapshot, placed: List[Placed],
             rooms: List[Room] = Nil): List[PlanCheck.Scheduled] =
    val byNumber = rooms.map(r => r.number -> r.id).toMap
    val tuitionKind = rooms.flatMap(_.kinds)
      .find(_.name.equalsIgnoreCase(IndividualTuition.RoomKindName)).map(_.id)
    val lines = s.lessonLines.map(l => l.id -> l).toMap
    val units = s.crossClassUnits.map(u => u.id -> u).toMap
    val optional = s.subjects.filter(_.optional).map(_.id).toSet

    val tuition = s.individualPlans.flatMap(_.lessons).map(l => l.id -> l).toMap

    val individual = placed.collect {
      case Placed(LessonRef.Individual(lessonId, block), day, from, _, where)
        if tuition.contains(lessonId) =>
        val lesson = tuition(lessonId)
        PlanCheck.Scheduled(
          // The student is taught alone and is not in the class while it
          // happens, so no class is busy. The teacher is, which is the point.
          classIds = Set.empty,
          audience = LessonAudience.WHOLE_CLASS,
          subjectId = lesson.subjectId,
          teacherIds = lesson.teacherId.toSet,
          day = day, from = from,
          length = lesson.blocks.lift(block).getOrElse(1),
          optional = false,
          roomId = where.flatMap(byNumber.get),
          needsKind = tuitionKind)
    }

    val ordinary = placed.collect {
      case Placed(LessonRef.Line(lineId, block), day, from, _, where)
        if lines.contains(lineId) =>
        val l = lines(lineId)
        PlanCheck.Scheduled(
          classIds = Set(l.classId),
          audience = l.audience,
          subjectId = l.subjectId,
          teacherIds = (l.teacherId.toList ++ l.supportTeacherId.toList).toSet,
          day = day, from = from,
          length = l.blocks.lift(block).getOrElse(1),
          optional = optional.contains(l.subjectId),
          roomId = where.flatMap(byNumber.get),
          needsKind = s.subjects.find(_.id == l.subjectId)
            .flatMap(_.requiredRoomKind).map(_.id))
    }

    // A cross-class block is one hour for the classes and several lessons for
    // the teachers, and the two have to be counted apart. Letting each group
    // claim its classes makes a class whose students are spread over three
    // groups look booked three times over — which is the same mistake the
    // export made, and is why the check is written separately from it.
    val crossPlacements = placed.collect {
      case q @ Placed(ref: LessonRef.CrossClassGroupRef, _, _, _, _) => (ref, q)
    }

    val teaching = crossPlacements.flatMap { (ref, q) =>
      for
        unit <- units.get(ref.unitId)
        group <- unit.groups.find(_.id == ref.groupId)
      yield PlanCheck.Scheduled(
        classIds = Set.empty,
        audience = LessonAudience.WHOLE_CLASS,
        subjectId = unit.subjectId,
        teacherIds = group.teacherId.toSet,
        day = q.day, from = q.position,
        length = unit.blocks.lift(ref.block).getOrElse(1),
        optional = optional.contains(unit.subjectId),
        roomId = q.room.flatMap(byNumber.get),
        needsKind = unit.requiredRoomKindId)
    }

    val occupancy = crossPlacements
      .groupBy((ref, _) => (ref.unitId, ref.block))
      .toList.flatMap { case ((unitId, block), group) =>
        val (_, first) = group.head
        units.get(unitId).map { unit =>
          PlanCheck.Scheduled(
            classIds = unit.classIds.toSet,
            audience = LessonAudience.WHOLE_CLASS,
            subjectId = unit.subjectId,
            teacherIds = Set.empty,
            day = first.day, from = first.position,
            length = unit.blocks.lift(block).getOrElse(1),
            optional = optional.contains(unit.subjectId),
            derived = true)
        }
      }

    ordinary ++ individual ++ teaching ++ occupancy
