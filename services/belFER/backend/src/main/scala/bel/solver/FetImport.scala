package bel.solver

import bel.domain.*
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import scala.jdk.CollectionConverters.*

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
  case class Placed(what: SolverInput.LessonRef, day: Int, position: Int, length: Int)

  case class Result(placed: List[Placed], unplaced: Int):
    def complete: Boolean = unplaced == 0

  /**
   * @param days the day names written into the file, in order, because FET
   *     answers with the names it was given rather than with indices
   */
  def apply(xml: String, means: Map[Int, SolverInput.LessonRef],
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
      yield Placed(what, day, hour - 1, 1)
    }

    // Anything we asked about and did not get an hour for. FET will not
    // normally return a half-finished timetable, but "it said no" and "it said
    // nothing about this one" are different answers and worth telling apart.
    Result(placed, means.size - placed.size)
