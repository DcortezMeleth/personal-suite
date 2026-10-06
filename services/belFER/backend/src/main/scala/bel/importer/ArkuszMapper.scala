package bel.importer

import bel.domain.{LessonAudience, LessonKind}

/**
 * Turns an arkusz into what belFER would store.
 *
 * Everything the file says that belFER cannot represent is reported rather than
 * dropped quietly. That is the point of running this against the real export:
 * the notes are the findings.
 */
object ArkuszMapper:

  /** The arkusz covers a whole year; a plan covers one stretch of it. */
  case class WeekRange(from: Int, to: Int)

  /**
   * @param splitScheme the GrNazwa that denotes the class's own two-way split.
   *   Any other named scheme is an independent grouping — the school's WF rows
   *   carry up to six groups in one class, and they are not the same halves as
   *   the language split, so treating them as GROUP_1/GROUP_2 would tell the
   *   solver two unrelated halves were the same students.
   */
  case class Options(weeks: WeekRange, splitScheme: String = "grupy")

  // Extended hours are coded as their own subject ("r_matematyka"), which the
  // school says is wrong — it is one subject with extra hours.
  private val ExtensionPrefix = "r_"

  // A class code of more than year+letter is not a class: it is one student's
  // individual-teaching plan, identified by their initials.
  private val RealClass = """^(\d)([A-ZĄĆĘŁŃÓŚŹŻ])$""".r

  private val OptionalSubjectHints = Set("religia", "etyka")

  def map(doc: ArkuszDocument, weeks: WeekRange): ImportPlan =
    map(doc, Options(weeks))

  def map(doc: ArkuszDocument, options: Options): ImportPlan =
    val weeks = options.weeks
    val notes = List.newBuilder[ImportNote]
    def warn(message: String): Unit = notes += ImportNote(NoteLevel.Warning, message)
    def info(message: String): Unit = notes += ImportNote(NoteLevel.Info, message)

    // ── Classes ───────────────────────────────────────────────────────────────
    val (realClasses, individual) = doc.classes.partition(c => RealClass.matches(c.code))
    if individual.nonEmpty then
      info(s"Pominięto ${individual.size} planów nauczania indywidualnego — " +
        "belFER nie obsługuje ich jeszcze")

    val classes = realClasses.map { c =>
      val RealClass(year, letter) = c.code: @unchecked
      PlannedClass(c.code, year.toInt, letter, c.profile, c.homeroomTeacherCode,
        c.studentCount, c.girlCount)
    }
    val knownClasses = classes.map(_.code).toSet

    // ── Subjects ──────────────────────────────────────────────────────────────
    val baseCodes = doc.subjects.map(_.code).toSet
    val byName = doc.subjects.map(s => normaliseName(s.name) -> s.code).toMap

    // The code usually strips cleanly, but not always: r_angielski's base is
    // j.angielski and r_informat.'s is informatyka. Falling back to the name
    // with the "rozszerzony" adjective removed catches those.
    def baseOf(code: String): String =
      if !code.startsWith(ExtensionPrefix) then code
      else
        val stripped = code.drop(ExtensionPrefix.length)
        if baseCodes.contains(stripped) then stripped
        else
          doc.subjects
            .find(_.code == code)
            .flatMap(s => byName.get(normaliseName(withoutExtendedAdjective(s.name))))
            .getOrElse(code)

    val merged = doc.subjects.filter(s => baseOf(s.code) != s.code)
    if merged.nonEmpty then
      info(s"Połączono ${merged.size} przedmiotów rozszerzonych z ich podstawą " +
        "— to jeden przedmiot z dodatkowymi godzinami")
    val unmatched = doc.subjects.filter(s => s.code.startsWith(ExtensionPrefix) && baseOf(s.code) == s.code)
    if unmatched.nonEmpty then
      warn(s"Nie znaleziono podstawy dla: ${unmatched.map(_.code).mkString(", ")} " +
        "— zostaną osobnymi przedmiotami")

    val subjects = doc.subjects
      .filterNot(s => merged.exists(_.code == s.code))
      .map(s => PlannedSubject(s.code, s.name,
        optional = OptionalSubjectHints.exists(h => s.code.toLowerCase.contains(h))))

    // ── Which rows apply to the chosen weeks ──────────────────────────────────
    val inRange = doc.assignments.filter(_.overlaps(weeks.from, weeks.to))
    val dropped = doc.assignments.size - inRange.size
    if dropped > 0 then
      info(s"Pominięto $dropped przydziałów spoza tygodni ${weeks.from}–${weeks.to}")

    // ── Ordinary in-class allocations ─────────────────────────────────────────
    val ordinary = inRange.filter(_.isOrdinary).filter(a => a.classCode.exists(knownClasses.contains))

    // Rows under a scheme other than the class's own split are an independent
    // grouping — they become units of their own below, not lines.
    val (independent, classOwn) = ordinary.partition(isIndependentGrouping(_, options.splitScheme))

    val lessonLines = classOwn.flatMap { a =>
      audienceOf(a) match
        case Left(reason) =>
          warn(s"${a.classCode.getOrElse("?")} / ${a.subjectCode}: $reason")
          None
        case Right(audience) =>
          Some(PlannedLessonLine(
            classCode = a.classCode.get,
            subjectCode = baseOf(a.subjectCode),
            audience = audience,
            kind = if a.subjectCode.startsWith(ExtensionPrefix) && baseOf(a.subjectCode) != a.subjectCode
                   then LessonKind.EXTENSION else LessonKind.BASE,
            teacherCode = a.teacherCode,
            // The arkusz states a count, never a shape. Singles are the only
            // honest default; the school sets doubles where it wants them.
            blocks = List.fill(a.hours.max(1))(1)
          ))
    }

    // The arkusz can state the same class, subject, audience and kind more than
    // once — most often because one stretch of weeks is listed separately from
    // another within the imported range. They are one allocation here, so the
    // hours join. Collapsing here rather than on the way to the database keeps
    // the preview honest about how many rows will exist.
    val collapsedLines = lessonLines
      .groupBy(l => (l.classCode, l.subjectCode, l.audience, l.kind))
      .toList
      .sortBy((key, _) => (key._1, key._2, key._3.toString, key._4.toString))
      .map { case ((classCode, subjectCode, audience, kind), group) =>
        PlannedLessonLine(classCode, subjectCode, audience, kind,
          group.flatMap(_.teacherCode).headOption, group.flatMap(_.blocks))
      }
    val joined = lessonLines.size - collapsedLines.size
    if joined > 0 then
      info(s"Połączono $joined przydziałów, które arkusz podaje osobno dla różnych " +
        "zakresów tygodni")

    if lessonLines.exists(_.blocks.sizeIs > 1) then
      info("Godziny zapisano jako pojedyncze lekcje — arkusz nie podaje układu bloków, " +
        "więc bloki dwugodzinne trzeba ustawić ręcznie")

    val unassigned = ordinary.count(_.teacherCode.isEmpty)
    if unassigned > 0 then info(s"$unassigned przydziałów nie ma jeszcze nauczyciela")

    if independent.nonEmpty then
      val schemes = independent.flatMap(_.groupScheme).distinct.sorted
      info(s"${independent.size} przydziałów w podziale „${schemes.mkString("”, „")}” " +
        "potraktowano jako zajęcia międzyoddziałowe — to inny podział niż grupy oddziału")

    // ── Cross-class lessons ───────────────────────────────────────────────────
    val crossClassUnits = buildUnits(doc, inRange, independent, knownClasses, baseOf, warn)

    ImportPlan(
      schoolName = doc.school.name,
      teachers = doc.teachers.map(t => PlannedTeacher(t.code, t.firstName, t.lastName, t.pensum)),
      subjects = subjects,
      classes = classes,
      lessonLines = collapsedLines,
      crossClassUnits = crossClassUnits,
      notes = notes.result()
    )

  /**
   * belFER splits a class into two halves. The arkusz mostly agrees, but its
   * own WF rows sometimes carry three or four groups within one class — which
   * the model cannot hold, so those are reported rather than squeezed in.
   */
  private def normaliseName(name: String): String =
    name.trim.toLowerCase.replaceAll("\\s+", " ")

  private def withoutExtendedAdjective(name: String): String =
    name.replaceAll("(?i)\\s+rozszerzon[ayे]?\\w*", "").trim

  /** A named grouping that is not the class's own two-way split. */
  private def isIndependentGrouping(a: ArkuszAssignment, splitScheme: String): Boolean =
    a.groupNumber.exists(_ > 0) &&
      a.groupScheme.exists(scheme =>
        !scheme.equalsIgnoreCase(splitScheme) && !isOptionalScheme(scheme))

  // Religia and etyka occupy the whole class's slot even though only some
  // students attend, which is why they are placed first or last rather than
  // modelled as a group.
  private def isOptionalScheme(scheme: String): Boolean =
    OptionalSubjectHints.exists(hint => scheme.toLowerCase.contains(hint))

  private def audienceOf(a: ArkuszAssignment): Either[String, LessonAudience] =
    a.groupNumber match
      case None | Some(0) => Right(LessonAudience.WHOLE_CLASS)
      case Some(1)        => Right(LessonAudience.GROUP_1)
      case Some(2)        => Right(LessonAudience.GROUP_2)
      case Some(n)        =>
        Left(s"podział na $n grup w jednym oddziale — belFER obsługuje dwie")

  /**
   * A candidate group, however the arkusz happened to state it.
   *
   * A cross-class lesson and an independent grouping inside one class are the
   * same thing seen from different ends: a set of students taught together,
   * drawn from one or more classes. Treating them alike is what makes the next
   * step possible.
   */
  private case class GroupCandidate(
    key: String,
    subject: String,
    label: String,
    teacherCode: Option[String],
    classCodes: List[String],
    hours: Int
  )

  /**
   * Groups that share a class must run at the same time, because a class
   * cannot be in two places — so units are the connected components of "shares
   * a class with", computed within a subject so religia and WF never merge.
   *
   * This is what joins a class's in-class WF half to the half it sends to
   * another class: in the real arkusz most classes teach their girls alone and
   * merge their boys, and scheduling those two apart would leave half the class
   * idle while the other half had a lesson.
   */
  private def buildUnits(
    doc: ArkuszDocument,
    inRange: List[ArkuszAssignment],
    independent: List[ArkuszAssignment],
    knownClasses: Set[String],
    baseOf: String => String,
    warn: String => Unit
  ): List[PlannedCrossClassUnit] =
    val names = doc.crossClassLessons.map(l => l.code -> l.name).toMap

    val membership = inRange
      .filter(_.isCrossClassMembership)
      .groupMap(_.crossClassCode.get)(_.classCode.get)
      .view.mapValues(_.distinct.filter(knownClasses.contains)).toMap

    val fromCrossClass = inRange.filter(_.isCrossClassGroup).map { row =>
      val code = row.crossClassCode.get
      GroupCandidate(
        key = code,
        subject = baseOf(row.subjectCode),
        // The zajecie's Nazwa is just the subject name, so every WF group would
        // read "Wychowanie fizyczne". Its Kod — 4DE, 2CD — is what the school
        // uses to tell them apart, and it says which classes are involved.
        label = code,
        teacherCode = row.teacherCode,
        classCodes = membership.getOrElse(code, Nil),
        hours = row.hours
      )
    }

    val fromIndependent = independent.zipWithIndex.map { (row, i) =>
      // Qualified by class: one unit can hold 3A's girls and 3F's girls, and
      // both rows call themselves DZIEWCZĘTA.
      val within = row.groupLabel.getOrElse(s"grupa ${row.groupNumber.getOrElse(0)}")
      GroupCandidate(
        key = s"wewn-$i",
        subject = baseOf(row.subjectCode),
        label = s"${row.classCode.getOrElse("?")} $within",
        teacherCode = row.teacherCode,
        classCodes = row.classCode.toList,
        hours = row.hours
      )
    }

    val candidates = fromCrossClass ++ fromIndependent

    connectedBySharedClass(candidates).map { component =>
      val hours = component.map(_.hours).distinct
      if hours.sizeIs > 1 then
        warn(s"${component.head.label}: grupy mają różną liczbę godzin " +
          s"(${hours.sorted.mkString(", ")}), a odbywają się jednocześnie")

      val classes = component.flatMap(_.classCodes).distinct.sorted
      PlannedCrossClassUnit(
        subjectCode = component.head.subject,
        name = s"${subjectLabel(doc, component.head.subject)} — ${classes.mkString(", ")}",
        blocks = List.fill(hours.min.max(1))(1),
        groups = component.map(c =>
          PlannedCrossClassGroup(c.label, c.teacherCode, c.classCodes))
      )
    }

  private def subjectLabel(doc: ArkuszDocument, code: String): String =
    doc.subjects.find(_.code == code).map(_.name).getOrElse(code)

  /** Groups joined wherever they share a class, kept within a subject. */
  private def connectedBySharedClass(candidates: List[GroupCandidate]): List[List[GroupCandidate]] =
    candidates.groupBy(_.subject).toList.sortBy(_._1).flatMap { (_, sameSubject) =>
      var components: List[(List[GroupCandidate], Set[String])] = Nil

      for candidate <- sameSubject do
        val classes = candidate.classCodes.toSet
        val (touching, rest) =
          components.partition((_, classSet) => classes.exists(classSet.contains))
        val mergedGroups = touching.flatMap(_._1) :+ candidate
        val mergedClasses = touching.map(_._2).foldLeft(classes)(_ ++ _)
        components = (mergedGroups, mergedClasses) :: rest

      components.map(_._1).reverse
    }
