package bel.importer

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.{Document, Element, NodeList}
import scala.util.Try

object ArkuszParser:

  /**
   * The file arrives by upload, so the parser is configured to refuse external
   * entities and DTDs. An arkusz has no legitimate use for either, and an XML
   * parser that resolves them will happily read files off the server for
   * whoever sends it one.
   */
  private def builder() =
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    factory.setXIncludeAware(false)
    factory.setExpandEntityReferences(false)
    factory.newDocumentBuilder()

  def parse(bytes: Array[Byte]): Either[String, ArkuszDocument] =
    Try(builder().parse(new ByteArrayInputStream(bytes))).toEither.left
      .map(e => s"Nie udało się odczytać pliku XML: ${e.getMessage}")
      .flatMap(read)

  private def read(doc: Document): Either[String, ArkuszDocument] =
    val root = doc.getDocumentElement
    if root == null || root.getTagName != "planOrg" then
      Left("To nie jest arkusz organizacyjny — oczekiwano elementu <planOrg>")
    else
      elements(doc, "placowka").headOption
        .toRight("Brak elementu <placowka> — nie wiadomo, której szkoły dotyczy plik")
        .map { placowka =>
          ArkuszDocument(
            school = ArkuszSchool(attr(placowka, "kod").getOrElse(""), attr(placowka, "nazwa").getOrElse("")),
            teachers = elements(doc, "nauczyciel").map(teacher),
            classes = elements(doc, "klasa").map(schoolClass),
            subjects = elements(doc, "przedmiot").map(subject),
            crossClassLessons = elements(doc, "zajecie").map(crossClass),
            assignments = elements(doc, "przydzial").map(assignment)
          )
        }

  // ── Element readers ─────────────────────────────────────────────────────────

  private def teacher(e: Element) = ArkuszTeacher(
    code = attr(e, "Kod").getOrElse(""),
    // The export carries a trailing space on some forenames.
    firstName = attr(e, "Imie").getOrElse("").trim,
    lastName = attr(e, "Nazwa").getOrElse("").trim,
    pensum = intAttr(e, "Pensum")
  )

  private def schoolClass(e: Element) = ArkuszClass(
    code = attr(e, "Kod").getOrElse(""),
    level = intAttr(e, "Poziom"),
    profile = attr(e, "Name").map(_.trim).filter(_.nonEmpty),
    shortName = attr(e, "Short").map(_.trim).filter(_.nonEmpty),
    studentCount = intAttr(e, "LiczbaUczniow"),
    girlCount = intAttr(e, "LiczbaDziewczyn"),
    homeroomTeacherCode = attr(e, "WychowawcaRef").map(_.trim).filter(_.nonEmpty)
  )

  private def subject(e: Element) = ArkuszSubject(
    code = attr(e, "Kod").getOrElse("").trim,
    name = attr(e, "Nazwa").getOrElse("").trim
  )

  private def crossClass(e: Element) = ArkuszCrossClassLesson(
    code = attr(e, "Kod").getOrElse("").trim,
    name = attr(e, "Nazwa").getOrElse("").trim
  )

  private def assignment(e: Element) = ArkuszAssignment(
    classCode = attr(e, "KlasaRef").map(_.trim).filter(_.nonEmpty),
    crossClassCode = attr(e, "ZajecieRef").map(_.trim).filter(_.nonEmpty),
    subjectCode = attr(e, "PrzedmiotRef").getOrElse("").trim,
    teacherCode = attr(e, "NauczycielRef").map(_.trim).filter(_.nonEmpty),
    hours = intAttr(e, "LiczbaGodzin").getOrElse(0),
    studentCount = intAttr(e, "LiczbaUczniow"),
    groupNumber = intAttr(e, "Grupa"),
    groupScheme = attr(e, "GrNazwa").map(_.trim).filter(_.nonEmpty),
    groupLabel = attr(e, "GrPodzial").map(_.trim).filter(_.nonEmpty),
    weekFrom = intAttr(e, "TydzienPocz").getOrElse(1),
    weekTo = intAttr(e, "TydzienKon").getOrElse(1)
  )

  // ── Plumbing ────────────────────────────────────────────────────────────────

  private def elements(doc: Document, tag: String): List[Element] =
    val nodes: NodeList = doc.getElementsByTagName(tag)
    (0 until nodes.getLength).toList.map(nodes.item(_).asInstanceOf[Element])

  private def attr(e: Element, name: String): Option[String] =
    if e.hasAttribute(name) then Some(e.getAttribute(name)) else None

  private def intAttr(e: Element, name: String): Option[Int] =
    attr(e, name).map(_.trim).filter(_.nonEmpty).flatMap(s => Try(s.toInt).toOption)
