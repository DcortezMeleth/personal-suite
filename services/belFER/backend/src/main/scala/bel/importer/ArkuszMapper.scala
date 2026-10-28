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

  /**
   * A pseudo-class: a real class's code with a student's initials after it.
   * Only the class part is kept — the initials identify a child.
   */
  private val IndividualClass = """^(\d[A-ZĄĆĘŁŃÓŚŹŻ])[A-Za-zĄĆĘŁŃÓŚŹŻąćęłńóśźż]+$""".r

  private val OptionalSubjectHints = Set("religia", "etyka")

  /**
   * Days a week a class is in school. The arkusz does not say, and the school
   * setting is not available here, so five is assumed — which it is for every
   * Polish liceum and is the number the rest of belFER defaults to.
   */
  private val TeachingDays = 5

  def map(doc: ArkuszDocument, weeks: WeekRange): ImportPlan =
    map(doc, Options(weeks))

  def map(doc: ArkuszDocument, options: Options): ImportPlan =
    val weeks = options.weeks
    val notes = List.newBuilder[ImportNote]
    def warn(message: String): Unit = notes += ImportNote(NoteLevel.Warning, message)
    def info(message: String): Unit = notes += ImportNote(NoteLevel.Info, message)

    // ── Classes ───────────────────────────────────────────────────────────────
    val (realClasses, individual) = doc.classes.partition(c => RealClass.matches(c.code))

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
    val addressed = inRange.filter(_.isOrdinary).filter(a => a.classCode.exists(knownClasses.contains))
    // An allocation of no hours is not an allocation. `max(1)` below would
    // round it up to one and hand the class an hour the file never gave it.
    val (ordinary, empty) = addressed.partition(_.hours > 0)
    if empty.nonEmpty then
      info(s"Pominięto ${empty.size} przydziałów z zerową liczbą godzin")

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
            blocks = List.fill(a.hours.max(1))(1),
            weekFrom = a.weekFrom,
            weekTo = a.weekTo
          ))
    }

    // Where one allocation is stated for several stretches of weeks, those are
    // alternatives in time, not parts of a sum: "historia 2h in weeks 1-12,
    // 1h in weeks 12-19" means two hours a week and then one, never three.
    // Adding them would have the solver place hours the class never has.
    //
    // A plan describes one week, so the question asked of each class-subject is
    // "what does this look like in a typical week of the chosen stretch" — and
    // the answer is whatever is running in the middle of it. Choosing per
    // teacher instead would re-create the sum wherever a teacher changes
    // hands: the file has 4D's ZRZU passing from one teacher at weeks 1-19 to
    // another at 19-30, and keeping both gives the class an hour it never has.
    val sampleWeek = weeks.from + (weeks.to - weeks.from) / 2

    val collapsedLines = lessonLines
      .groupBy(l => (l.classCode, l.subjectCode, l.audience, l.kind))
      .toList
      .sortBy((key, _) => (key._1, key._2, key._3.toString, key._4.toString))
      .flatMap { case (_, group) =>
        val running = group.filter(l => l.weekFrom <= sampleWeek && l.weekTo >= sampleWeek)
        // Nothing covers the middle when every window sits at one end of the
        // stretch; the longest-running one is then the best available answer.
        val chosen = if running.nonEmpty then running else List(group.maxBy(overlapWeeks(_, weeks)))
        // Several teachers at once is real — co-teaching, or a subject split
        // between them — so they are kept. One teacher stated twice is not.
        chosen
          .groupBy(_.teacherCode)
          .toList
          .sortBy((teacher, _) => teacher.getOrElse(""))
          .map((_, same) => same.maxBy(overlapWeeks(_, weeks)))
      }

    val varying = lessonLines
      .groupBy(l => (l.classCode, l.subjectCode, l.audience, l.kind, l.teacherCode))
      .count((_, group) => group.map(_.blocks.sum).distinct.sizeIs > 1)
    if varying > 0 then
      warn(s"W $varying przydziałach liczba godzin zmienia się w trakcie wybranego okresu — " +
        "wzięto wariant obowiązujący w jego środku. Rozważ zaimportowanie węższego " +
        "zakresu tygodni, jeśli ta zmiana ma być w planie")

    // Both halves of a split under one teacher. They cannot be taught at the
    // same time, so the rest of the class is not in school for them — which is
    // schedulable, by putting the lesson at an end of the day, but it is not
    // what the school wants and it is better said at import than discovered
    // when the plan comes out with half a class arriving late.
    // ── Hours that will not fit as singles ────────────────────────────────
    //
    // The arkusz states a count and never a shape, and singles are the honest
    // default — but a class sees a subject at most once a day, so six hours of
    // maths cannot be six separate lessons in a five-day week. Where that
    // happens the double is forced rather than preferred, and pairing just
    // enough of them is the smallest change that makes the week possible.
    //
    // Left alone otherwise: the school decides where it wants doubles, and
    // guessing on its behalf is how a timetable stops looking like the one
    // they asked for.
    val fitted =
      collapsedLines
        .groupBy(l => (l.classCode, l.subjectCode, l.audience))
        .toList
        .sortBy((key, _) => (key._1, key._2, key._3.toString))
        .map { case (key, group) =>
          var blocks = group.map(_.blocks)
          def total = blocks.map(_.size).sum
          val before = total
          while total > TeachingDays && blocks.exists(_.count(_ == 1) >= 2) do
            val index = blocks.indices.maxBy(i => blocks(i).count(_ == 1))
            val singles = blocks(index).count(_ == 1)
            blocks = blocks.updated(index,
              (blocks(index).filter(_ != 1) ++ List.fill(singles / 2)(2)
                ++ List.fill(singles % 2)(1)).sorted)
          (key, before > total, group.zip(blocks).map((line, shape) => line.copy(blocks = shape)))
        }

    val fittedLines = fitted.flatMap(_._3)
    val reshaped = fitted.filter(_._2).map(_._1)
    if reshaped.nonEmpty then
      warn(s"W ${reshaped.size} przedmiotach godziny nie zmieściłyby się w tygodniu jako " +
        "pojedyncze lekcje — połączono część w bloki dwugodzinne: " +
        reshaped.take(6).map((cls, subject, _) => s"$cls ${subjectLabel(doc, subject)}")
          .mkString(", ") + (if reshaped.sizeIs > 6 then ", …" else ""))

    val sharedHalves = fittedLines
      .filter(_.audience != LessonAudience.WHOLE_CLASS)
      .groupBy(l => (l.classCode, l.subjectCode))
      .count { (_, group) =>
        val first = group.filter(_.audience == LessonAudience.GROUP_1).flatMap(_.teacherCode).toSet
        val second = group.filter(_.audience == LessonAudience.GROUP_2).flatMap(_.teacherCode).toSet
        first.intersect(second).nonEmpty
      }
    if sharedHalves > 0 then
      warn(s"W $sharedHalves przedmiotach obie grupy prowadzi ta sama osoba — nie mogą " +
        "odbywać się jednocześnie, więc zostaną zaplanowane na początku lub końcu dnia, " +
        "a druga połowa klasy przyjdzie później albo wyjdzie wcześniej")

    val multiTeacher = fittedLines
      .groupBy(l => (l.classCode, l.subjectCode))
      .count((_, group) => group.map(_.teacherCode).distinct.sizeIs > 1)
    if multiTeacher > 0 then
      info(s"$multiTeacher par oddział–przedmiot ma więcej niż jednego nauczyciela — " +
        "zachowano je jako osobne przydziały")

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

    // ── Nauczanie indywidualne ────────────────────────────────────────────
    //
    // One student taught alone, carried in the arkusz as a pseudo-class whose
    // code is that student's class followed by their initials. Neither the
    // initials nor the name comes across — a named minor beside their
    // provision is the most sensitive thing in the file — but the hours do,
    // because they are taught by the people who teach the classes and a plan
    // that does not know about them will commit somebody twice over.
    val individualPlans =
      individual.flatMap { pseudo =>
        IndividualClass.unapplySeq(pseudo.code).map(parts => parts.head -> pseudo)
      }
      .filter((classCode, _) => knownClasses.contains(classCode))
      .groupBy((classCode, _) => classCode)
      .toList.sortBy(_._1)
      .flatMap { (classCode, pseudos) =>
        // Numbered after the empty ones are dropped, so a class with one plan
        // calls it the first rather than the second.
        pseudos
          .map((_, pseudo) => doc.assignments
            .filter(a => a.classCode.contains(pseudo.code))
            .flatMap(a => Option.when(a.hours > 0)(
              PlannedIndividualLesson(a.subjectCode, a.teacherCode, List.fill(a.hours)(1)))))
          .filter(_.nonEmpty)
          .zipWithIndex
          .map((lessons, index) => PlannedIndividualPlan(classCode, index + 1, lessons))
      }

    val skipped = individual.size - individualPlans.size
    if individualPlans.nonEmpty then
      info(s"Wczytano ${individualPlans.size} planów nauczania indywidualnego " +
        s"(${individualPlans.map(_.lessons.map(_.blocks.sum).sum).sum} godz.) — " +
        "bez danych ucznia, zapisany jest tylko oddział i liczba godzin")
    if skipped > 0 then
      info(s"Pominięto $skipped planów nauczania indywidualnego bez przydzielonych godzin")

    ImportPlan(
      schoolName = doc.school.name,
      teachers = doc.teachers.map(t => PlannedTeacher(t.code, t.firstName, t.lastName, t.pensum)),
      subjects = subjects,
      classes = classes,
      lessonLines = fittedLines,
      crossClassUnits = crossClassUnits,
      individualPlans = individualPlans,
      notes = notes.result()
    )

  /**
   * belFER splits a class into two halves. The arkusz mostly agrees, but its
   * own WF rows sometimes carry three or four groups within one class — which
   * the model cannot hold, so those are reported rather than squeezed in.
   */
  /** How much of the imported stretch an allocation actually applies to. */
  private def overlapWeeks(line: PlannedLessonLine, weeks: WeekRange): Int =
    (math.min(line.weekTo, weeks.to) - math.max(line.weekFrom, weeks.from) + 1).max(0)

  private def normaliseName(name: String): String =
    name.trim.toLowerCase.replaceAll("\\s+", " ")

  private def withoutExtendedAdjective(name: String): String =
    name.replaceAll("(?i)\\s+rozszerzon\\w*", "").trim

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
    // Religia and etyka carry a group number because only some students
    // attend, but the class's slot is occupied either way — which is exactly
    // why they are placed first or last rather than split. Reading that number
    // as a half would leave the other half with nothing to do.
    if a.groupScheme.exists(isOptionalScheme) then Right(LessonAudience.WHOLE_CLASS)
    else a.groupNumber match
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

      // Every group of a unit runs at the same hour, so one person cannot have
      // two of them. Where the arkusz lists two under one teacher it is
      // describing one group twice — the same ethics class written once for
      // the second years and once again with a fourth year added — so they
      // are folded together rather than taken at face value, which would make
      // the unit impossible before anyone scheduled it.
      val folded = component
        .groupBy(_.teacherCode)
        .toList.sortBy(_._1.getOrElse(""))
        .flatMap { (teacher, sharing) =>
          if teacher.isEmpty || sharing.sizeIs == 1 then
            sharing.map(c => PlannedCrossClassGroup(c.label, c.teacherCode, c.classCodes))
          else
            warn(s"${component.head.label}: ${sharing.size} grup tego zespołu prowadzi " +
              s"ta sama osoba, a odbywają się jednocześnie — połączono je w jedną")
            List(PlannedCrossClassGroup(
              sharing.map(_.label).min, teacher,
              sharing.flatMap(_.classCodes).distinct.sorted))
        }

      PlannedCrossClassUnit(
        subjectCode = component.head.subject,
        name = s"${subjectLabel(doc, component.head.subject)} — ${classes.mkString(", ")}",
        blocks = List.fill(hours.min.max(1))(1),
        groups = folded
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
