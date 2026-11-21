package bel.solver

import bel.domain.*
import java.util.UUID

/**
 * The school as a FET file.
 *
 * FET is a timetabling program that has been in use in real schools for twenty
 * years and solves instances this size in five to twenty minutes. Writing our
 * school out in its format buys three things that nothing we build can: an
 * answer about this instance rather than a recollection about another one, a
 * reference timetable to hold ours against rule by rule, and — if the rules
 * turn out not to be satisfiable — the knowledge that the fault is in what we
 * believe rather than in how we search.
 *
 * The format is plain XML and maps onto ours more closely than it had any
 * obligation to. An activity may name several teachers, which is a supporting
 * teacher; it may name several student sets, which is a cross-class block; and
 * the student structure is years containing groups, which is a class
 * containing its two halves.
 *
 * The rules being translated are docs/RULES.md, which also says, rule by rule,
 * how each one is said here and where FET is told less than the rule.
 *
 * Religia and etyka are "first or last, whichever suits". For years of FET
 * that could not be said — 6.8.5 has "ends the day" and "begins the day" but
 * no "or" — and exporting "ends" alone placed 432 activities of 1331 and then
 * nothing for fifteen hours. FET 7 says it directly, per subgroup, which is
 * one reason the image is built from upstream source rather than taken from
 * Ubuntu (services/belFER/fet/Dockerfile).
 */
object FetExport:

  /**
   * The file, and what each activity in it stands for.
   *
   * FET numbers activities with integers and hands those integers back in its
   * result, so without this the answer is a thousand placements of things we
   * can no longer name. Keeping the mapping is what makes the round trip a
   * round trip rather than a one-way export.
   */
  case class Written(xml: String, means: Map[Int, LessonRef],
                     /**
                      * How many activities the file asks FET to place.
                      *
                      * More than `means.size`: a cross-class unit also gets an
                      * occupancy activity standing for every participating class,
                      * which means nothing on its own and so is not read back. The
                      * count here is FET's, because it is what FET's own progress is
                      * measured against.
                      */
                     activities: Int)

  /**
   * The names of the attendance cohorts in a class, with their sizes.
   *
   * Empty unless the school has said who attends what, because "nobody
   * attends" and "nobody has said" are different answers and only the second
   * should leave the blunter rule in place. A cohort nobody is in is dropped:
   * an empty subgroup is a subgroup FET has to carry for nothing.
   *
   * The remainder — those attending none of the optional subjects — is the
   * cohort that matters, because they are the ones who would otherwise be left
   * waiting while the rest of the class is in a lesson.
   */
  private def attendanceCohorts(snapshot: SchoolSnapshot, cls: SchoolClass,
                                size: Int): List[(String, Int)] =
    val named = optionalCohortLabels(snapshot, cls)
    if named.isEmpty then Nil
    else
      val taken = named.map(_._3).sum
      // Past the class size means somebody has been counted twice — a pupil
      // attending two of them, which this school says does not happen. Kontrola
      // reports it; here it simply cannot go negative.
      val rest = (size - taken).max(0)
      named.map((_, label, n) => (label, n)) ++ (if rest > 0 then List(NoneLabel -> rest) else Nil)

  /** Optional subjects this class attends, as (subject, label, how many). */
  private def optionalCohortLabels(snapshot: SchoolSnapshot,
                                   cls: SchoolClass): List[(UUID, String, Int)] =
    snapshot.subjects.filter(_.optional).sortBy(_.name).zipWithIndex.flatMap { (subject, i) =>
      snapshot.optionalAttendance.get((cls.id, subject.id))
        .filter(_ > 0)
        .map(n => (subject.id, s"o$i", n))
    }

  /** Those attending none of them, who must not be made to wait. */
  private val NoneLabel = "ox"

  /** What the group naming a subject's attenders in a class is called. */
  private def cohortGroup(cls: SchoolClass, subjectId: UUID): String =
    s"${cls.name}~${subjectId.toString.take(8)}"

  /**
   * Every student set of a class that an activity can name, with the
   * subgroups (the pupils who share one timetable) it stands for. The names
   * are the ones the student structure below writes. Kept beside it rather
   * than derived from it, so a change to one has to be made to the other;
   * FetClassDaysSpec fails if they drift apart.
   */
  private def subgroups(snapshot: SchoolSnapshot, cls: SchoolClass): Map[String, List[String]] =
    val cohorts = attendanceCohorts(snapshot, cls, cls.studentCount.getOrElse(30))
    val halves = List(1, 2).map { half =>
      val halfName = s"${cls.name}/$half"
      halfName -> (if cohorts.isEmpty then List(s"${halfName}s")
                   else cohorts.map((label, _) => s"${halfName}s-$label"))
    }
    val optional = optionalCohortLabels(snapshot, cls).map((subjectId, label, _) =>
      cohortGroup(cls, subjectId) -> List(1, 2).map(half => s"${cls.name}/${half}s-$label"))
    val all = halves.flatMap(_._2)
    ((cls.name -> all) :: (halves ++ optional ++ all.map(s => s -> List(s)))).toMap

  /** Each subgroup's hours a week: every activity any set of it is named in. */
  private def subgroupLoads(snapshot: SchoolSnapshot, acts: List[Act]): Map[String, Int] =
    val sets = snapshot.classes.flatMap(subgroups(snapshot, _)).toMap
    acts.flatMap(a => a.students.flatMap(s => sets.getOrElse(s,
        throw IllegalStateException(s"student set $s is named by an activity and never declared"))
      ).distinct.map(_ -> a.duration))
      .groupMapReduce(_._1)(_._2)(_ + _)

  /** The day names this file will use, which is how FET answers back. */
  def dayNames(teachingDays: Int): List[String] = SchoolWeek.names(teachingDays)

  /** An activity, after names have been resolved and blocks split out. */
  private case class Act(
    id: Int,
    teachers: List[String],
    subject: String,
    students: List[String],
    duration: Int,
    groupId: Int,
    optional: Boolean,
    /** Activities that must begin at the same hour as this one. */
    sameTimeAs: Option[Int],
    /** What this activity is, in our own terms. */
    means: Option[LessonRef],
    /** The kind of room it needs, when it needs a particular one. */
    roomKind: Option[UUID],
    /**
     * Whether that kind is required or merely wanted. Subjects carry the
     * flag and the interface has always promised it means something; until
     * ADR 0005 the exporter wrote every requirement as absolute.
     */
    roomHard: Boolean,
    /** Whether a whole class has to fit in the room. */
    wholeClass: Boolean,
    /**
     * Whether the school has said who attends this, for every class in it.
     *
     * When it has, the activity is addressed to those pupils and the rest of
     * the class is visibly free, so FET's own no-gaps rule keeps them from
     * waiting and the blunter "ends the day" rule is not written at all. When
     * it has not — or has for only some of a cross-class unit's classes — the
     * fallback stays, because a pupil nobody has accounted for must not be
     * quietly allowed a window.
     */
    cohortKnown: Boolean = false,
    /** The class's own lesson line this block belongs to, if it is one. */
    line: Option[LineBlock] = None
  )

  /** Where a block of a class's own lesson line came from. */
  private case class LineBlock(classId: UUID, subjectId: UUID, audience: LessonAudience,
                               kind: LessonKind, lineId: UUID, block: Int)

  /** One class's lessons of one subject, as whole-class and per half. */
  private case class Split(whole: List[Act], first: List[Act], second: List[Act])

  def apply(snapshot: SchoolSnapshot, rooms: List[Room]): Written =
    val kindNames = rooms.flatMap(_.kinds).map(k => k.id -> k.name).toMap
    val subjects = snapshot.subjects.map(s => s.id -> s.name).toMap
    val teachers = snapshot.teachers.map(t => t.id -> s"${t.lastName} ${t.firstName} (${t.code})").toMap
    val classes = snapshot.classes.map(c => c.id -> c.name).toMap

    val acts = activities(snapshot, subjects, teachers, classes, kindNames)
    val means = acts.flatMap(a => a.means.map(a.id -> _)).toMap
    val b = StringBuilder()

    b ++= "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
    // The version of the engine that reads it (services/belFER/fet/Dockerfile).
    b ++= "<fet version=\"7.12.1\">\n\n"
    b ++= s"<Institution_Name>${esc("I LO")}</Institution_Name>\n"
    b ++= "<Comments>Wyeksportowane z belFER</Comments>\n\n"

    b ++= "<Days_List>\n"
    b ++= s"\t<Number_of_Days>${snapshot.teachingDays}</Number_of_Days>\n"
    for d <- dayNames(snapshot.teachingDays) do
      b ++= s"\t<Day>\n\t\t<Name>${esc(d)}</Name>\n\t</Day>\n"
    b ++= "</Days_List>\n\n"

    b ++= "<Hours_List>\n"
    b ++= s"\t<Number_of_Hours>${snapshot.slotsPerDay}</Number_of_Hours>\n"
    for h <- 1 to snapshot.slotsPerDay do
      b ++= s"\t<Hour>\n\t\t<Name>$h</Name>\n\t</Hour>\n"
    b ++= "</Hours_List>\n\n"

    b ++= "<Subjects_List>\n"
    for name <- subjects.values.toList.distinct.sorted do
      b ++= s"\t<Subject>\n\t\t<Name>${esc(name)}</Name>\n\t\t<Comments></Comments>\n\t</Subject>\n"
    b ++= "</Subjects_List>\n\n"
    b ++= "<Activity_Tags_List>\n</Activity_Tags_List>\n\n"

    b ++= "<Teachers_List>\n"
    for name <- teachers.values.toList.distinct.sorted do
      b ++= s"\t<Teacher>\n\t\t<Name>${esc(name)}</Name>\n"
      b ++= "\t\t<Target_Number_of_Hours>0</Target_Number_of_Hours>\n"
      b ++= "\t\t<Qualified_Subjects></Qualified_Subjects>\n\t\t<Comments></Comments>\n\t</Teacher>\n"
    b ++= "</Teachers_List>\n\n"

    // A class is a year and its two halves are groups, which is how FET says
    // "these two cannot both be somewhere else at once, and either of them
    //  clashes with anything the whole class does".
    //
    // Where the school has said who attends religia and who attends etyka,
    // each half is divided again by that, and a group is written per optional
    // subject naming the pupils who sit in it. This is the whole point of the
    // exercise: FET then sees the pupils who are *not* in the lesson, applies
    // the no-gaps rule it already has to them, and will not leave them waiting
    // — which is the school's actual rule. Proved against FET directly before
    // this was written: pin such a lesson to the middle of a day and FET calls
    // the timetable impossible. See ADR 0006.
    b ++= "<Students_List>\n"
    for cls <- snapshot.classes.sortBy(_.name) do
      val size = cls.studentCount.getOrElse(30)
      val cohorts = attendanceCohorts(snapshot, cls, size)
      b ++= s"\t<Year>\n\t\t<Name>${esc(cls.name)}</Name>\n"
      b ++= s"\t\t<Number_of_Students>$size</Number_of_Students>\n\t\t<Comments></Comments>\n"

      def subgroup(name: String, n: Int, indent: String): Unit =
        b ++= s"$indent<Subgroup>\n$indent\t<Name>${esc(name)}</Name>\n"
        b ++= s"$indent\t<Number_of_Students>$n</Number_of_Students>\n"
        b ++= s"$indent\t<Comments></Comments>\n$indent</Subgroup>\n"

      for half <- List(1, 2) do
        val halfName = s"${cls.name}/$half"
        b ++= s"\t\t<Group>\n\t\t\t<Name>${esc(halfName)}</Name>\n"
        b ++= s"\t\t\t<Number_of_Students>${size / 2}</Number_of_Students>\n"
        b ++= "\t\t\t<Comments></Comments>\n"
        if cohorts.isEmpty then subgroup(s"${halfName}s", size / 2, "\t\t\t")
        else
          for (label, whole) <- cohorts do
            // Halved, because the division by attendance cuts across the
            // division by language group rather than replacing it.
            subgroup(s"${halfName}s-$label", (whole / 2).max(1), "\t\t\t")
        b ++= "\t\t</Group>\n"

      // One group per optional subject, naming only those who attend it.
      for (subjectId, label, _) <- optionalCohortLabels(snapshot, cls) do
        val members = List(1, 2).map(half => s"${cls.name}/${half}s-$label")
        b ++= s"\t\t<Group>\n\t\t\t<Name>${esc(cohortGroup(cls, subjectId))}</Name>\n"
        b ++= s"\t\t\t<Number_of_Students>${members.size}</Number_of_Students>\n"
        b ++= "\t\t\t<Comments></Comments>\n"
        for m <- members do subgroup(m, 1, "\t\t\t")
        b ++= "\t\t</Group>\n"
      b ++= "\t</Year>\n"
    b ++= "</Students_List>\n\n"

    b ++= "<Activities_List>\n"
    for a <- acts do
      b ++= "\t<Activity>\n"
      for t <- a.teachers do b ++= s"\t\t<Teacher>${esc(t)}</Teacher>\n"
      b ++= s"\t\t<Subject>${esc(a.subject)}</Subject>\n"
      for s <- a.students do b ++= s"\t\t<Students>${esc(s)}</Students>\n"
      b ++= s"\t\t<Duration>${a.duration}</Duration>\n"
      b ++= s"\t\t<Total_Duration>${a.duration}</Total_Duration>\n"
      b ++= s"\t\t<Id>${a.id}</Id>\n"
      b ++= s"\t\t<Activity_Group_Id>${a.groupId}</Activity_Group_Id>\n"
      b ++= "\t\t<Active>true</Active>\n\t\t<Comments></Comments>\n\t</Activity>\n"
    b ++= "</Activities_List>\n\n"

    b ++= "<Buildings_List>\n</Buildings_List>\n\n"
    b ++= "<Rooms_List>\n"
    for room <- rooms do
      b ++= s"\t<Room>\n\t\t<Name>${esc(roomName(room))}</Name>\n"
      // A capacity that never binds, because we do not have capacities. The
      // model keeps a "fits a whole class" flag and nothing numeric, so any
      // number here would be invented — and inventing thirty is how the first
      // attempt at room constraints came to be refused outright: a lesson
      // standing for seven classes at once counts as two hundred students,
      // and no room holds two hundred. Those students are in seven rooms, and
      // the lesson that says so is bookkeeping rather than a place anybody
      // sits. Until rooms carry a real size, the honest thing is to say that
      // size is not something we know.
      b ++= "\t\t<Building></Building>\n\t\t<Capacity>10000</Capacity>\n"
      b ++= "\t\t<Virtual>false</Virtual>\n\t\t<Comments></Comments>\n\t</Room>\n"
    b ++= "</Rooms_List>\n\n"

    b ++= "<Time_Constraints_List>\n"
    b ++= constraint("ConstraintBasicCompulsoryTime", Nil)

    // How uneven a class's week may be (RULES P2), as a range of day lengths
    // per class: see ClassDays. Worked out per subgroup, because a subgroup is
    // a set of pupils with one timetable between them. Where every subgroup of
    // a class carries the same load, which is every class until attendance is
    // entered, one range is written for the class, and FET applies it to each
    // subgroup. Where they differ, each subgroup gets its own.
    val dayRanges: Map[String, (Int, Int)] =
      snapshot.settings.optimisation.maxClassDaySpread match
        case None => Map.empty
        case Some(spread) =>
          val load = subgroupLoads(snapshot, acts)
          def rangeFor(l: Int) = ClassDays.range(l, snapshot.teachingDays, snapshot.slotsPerDay, spread)
          snapshot.classes.flatMap { cls =>
            val subs = subgroups(snapshot, cls)(cls.name)
            val loads = subs.map(load.getOrElse(_, 0)).distinct
            if loads.size == 1 then rangeFor(loads.head).map(cls.name -> _).toList
            else subs.flatMap(s => rangeFor(load.getOrElse(s, 0)).map(s -> _))
          }.toMap

    def dayRange(set: String, longest: Int): Unit =
      for (shortest, _) <- dayRanges.get(set) do
        b ++= constraint("ConstraintStudentsSetMinHoursDaily",
          List("Minimum_Hours_Daily" -> shortest.toString, "Students" -> set,
            "Allow_Empty_Days" -> "false"))
      b ++= constraint("ConstraintStudentsSetMaxHoursDaily",
        List("Maximum_Hours_Daily" -> longest.toString, "Students" -> set))

    // A student has nowhere to be in a free period.
    for cls <- snapshot.classes.sortBy(_.name) do
      b ++= constraint("ConstraintStudentsSetMaxGapsPerDay",
        List("Max_Gaps" -> "0", "Students" -> cls.name))
      dayRange(cls.name, dayRanges.get(cls.name).fold(snapshot.slotsPerDay)(_._2))
      if !dayRanges.contains(cls.name) then
        for s <- subgroups(snapshot, cls)(cls.name) if dayRanges.contains(s) do
          dayRange(s, dayRanges(s)._2)

    // Both halves of a split, and every class's share of a cross-class block.
    for (_, group) <- acts.flatMap(a => a.sameTimeAs.map(_ -> a)).groupBy(_._1) do
      val ids = group.map(_._2.id).sorted
      val anchor = group.head._1
      val all = (anchor :: ids).distinct.sorted
      if all.size > 1 then
        b ++= constraint("ConstraintActivitiesSameStartingTime",
          ("Number_of_Activities" -> all.size.toString) :: all.map(i => "Activity_Id" -> i.toString))

    // Two blocks of a subject never share a day — but the several activities
    // standing for one cross-class block are one block, and are already tied
    // to the same hour. Asking for those to be a day apart as well is a
    // contradiction, and FET takes it exactly as seriously as it deserves:
    // half an hour of searching placed one activity out of eleven hundred.
    //
    // A subject a class has both whole-class and in halves is spread as one:
    // the whole-class blocks with each half's, so a half never meets the
    // subject twice in a day (RULES S4). The halves are not spread against
    // each other — both may have it on the same day.
    val split = acts.flatMap(a => a.line.map(l => (l.classId, l.subjectId) -> a))
      .groupBy(_._1).view.mapValues(_.map(_._2)).toMap
      .map((key, as) => key -> Split(
        as.filter(_.line.exists(_.audience == LessonAudience.WHOLE_CLASS)),
        as.filter(_.line.exists(_.audience == LessonAudience.GROUP_1)),
        as.filter(_.line.exists(_.audience == LessonAudience.GROUP_2))))
      .filter((_, s) => s.whole.nonEmpty && (s.first.nonEmpty || s.second.nonEmpty))
    val linked = split.values.flatMap(s => s.whole ++ s.first ++ s.second).map(_.id).toSet
    val spreading =
      acts.filter(a => a.groupId > 0 && a.sameTimeAs.isEmpty && !linked(a.id))
        .groupBy(_.groupId).values.toList ++
      split.values.toList.flatMap(s =>
        List(s.first, s.second).filter(_.nonEmpty).map(half => s.whole ++ half))
    for group <- spreading.map(_.sortBy(_.id)).sortBy(_.head.id) if group.size > 1 do
      b ++= constraint("ConstraintMinDaysBetweenActivities",
        ("Number_of_Activities" -> group.size.toString)
          :: group.map(a => "Activity_Id" -> a.id.toString)
          ::: List("MinDays" -> "1", "Consecutive_If_Same_Day" -> "true"))

    // The halves stay level (RULES S5): when the whole class meets, neither
    // half is a lesson ahead. FET cannot say that exactly — where the halves
    // must be level depends on where the whole-class lesson lands — so it is
    // said as something stronger that FET can: each lesson of half 1 and its
    // counterpart in half 2 at most a day apart. With the spreading above
    // keeping the whole class off the halves' days, no whole-class lesson can
    // then fall between a pair. It keeps 73–100% of the valid layouts; RULES
    // S5 has the count, and PlanCheck R16 checks the rule itself.
    //
    // Counterparts are matched like for like — basic with basic, extended
    // with extended — then in block order.
    def matched(half: List[Act]) =
      half.sortBy(a => a.line.map(l => (l.kind.ordinal, l.lineId.toString, l.block)))
    for
      s <- split.values.toList.sortBy(_.whole.head.id)
      (one, other) <- matched(s.first).zip(matched(s.second))
    do
      b ++= constraint("ConstraintMaxDaysBetweenActivities",
        List("Number_of_Activities" -> "2", "Activity_Id" -> one.id.toString,
          "Activity_Id" -> other.id.toString, "MaxDays" -> "1"))

    // Religia and etyka sit at the edge of a day, first or last, for every
    // class they are taught to — RULES S2 and G1. FET checks this per
    // subgroup, so a block shared by seven classes sits at the edge of each of
    // their days, not merely of one.
    //
    // Only where nobody has said who attends. Where the attendance is known
    // the rule is not needed at all, because the cohorts say who is free and
    // FET's own no-gaps promise covers them — so it is dropped there rather
    // than piled on top, which would forbid the mid-day hour that suits
    // everybody.
    //
    // This used to be "ends the day", the stricter half of the rule, because
    // FET 6.8.5 had no "or": on the real school it placed 432 activities of
    // 1331 and never moved again.
    for a <- acts if a.optional && !a.cohortKnown do
      b ++= constraint("ConstraintActivityBeginsOrEndsStudentsDay",
        List("Activity_Id" -> a.id.toString))

    // What a teacher's week may look like. None of this was exported the first
    // time, so FET had no reason to respect any of it — and duly did not:
    // thirty-two teacher-days with somebody sitting through three free hours.
    // Not its answer being poor, our question being incomplete.
    //
    // Two different rules, and only one of them is FET's.
    //
    // The school's rule is a run length — at most N free hours in a row — and
    // FET has no way to say that: it counts a day's gaps, never the longest
    // stretch of them. So unless the planner says otherwise, the run length is
    // sent as a daily count, which is stricter and therefore never produces a
    // week that breaks the real rule.
    //
    // Stricter is not free. At one, "no more than one gap in the day" forbids
    // a teacher teaching first, third and fifth period — two separate single
    // gaps, adjacent to nothing, entirely legal by the rule being stood in
    // for. Tightening the run length that far asks for a week no human
    // timetable satisfies either, so the daily count can now be set on its
    // own and the run length left to PlanCheck R15, which can check it
    // properly because it reads a finished week.
    b ++= constraint("ConstraintTeachersMaxGapsPerDay",
      List("Max_Gaps" -> snapshot.settings.optimisation.maxTeacherGapsPerDay
        .getOrElse(snapshot.settings.maxConsecutiveTeacherGaps).toString))

    b ++= constraint("ConstraintTeachersMaxHoursDaily",
      List("Maximum_Hours_Daily" -> snapshot.settings.defaultTeacherMaxLessonsPerDay.toString))

    // ── What the planner has asked for beyond legality — ADR 0005 ─────────
    //
    // The weekly gap total is a bound rather than a preference because FET
    // will not have it any other way, and it is the one with leverage: the
    // per-day bound above permits five times itself across a week.
    val tuning = snapshot.settings.optimisation
    for most <- tuning.maxTeacherGapsPerWeek do
      b ++= constraint("ConstraintTeachersMaxGapsPerWeek", List("Max_Gaps" -> most.toString))

    // The two ends of every class's day, one pair for the whole school, and
    // only one end will take a weight. The spread itself is said per class
    // above, which these cannot do (maxClassDaySpread).
    for shortest <- tuning.minClassHoursDaily do
      b ++= constraint("ConstraintStudentsMinHoursDaily",
        List("Minimum_Hours_Daily" -> shortest.toString, "Allow_Empty_Days" -> "false"))
    if tuning.wantsClassDay then
      for longest <- tuning.maxClassHoursDaily do
        b ++= preference("ConstraintStudentsMaxHoursDaily", tuning.classDayWeight,
          List("Maximum_Hours_Daily" -> longest.toString))

    for t <- snapshot.teachers do
      val name = teachers.getOrElse(t.id, t.code)
      for days <- t.maxWorkingDays if days < snapshot.teachingDays do
        b ++= constraint("ConstraintTeacherMaxDaysPerWeek",
          List("Max_Days_Per_Week" -> days.toString, "Teacher_Name" -> name))
      for cap <- t.maxLessonsPerDay if cap < snapshot.slotsPerDay do
        b ++= constraint("ConstraintTeacherMaxHoursDaily",
          List("Maximum_Hours_Daily" -> cap.toString, "Teacher_Name" -> name))

    for t <- snapshot.teachers if t.unavailability.nonEmpty do
      val slots = t.unavailability.flatMap(u =>
        (u.fromPosition to u.toPosition).map(p => (u.dayOfWeek, p)))
      b ++= s"\t<ConstraintTeacherNotAvailableTimes>\n"
      b ++= "\t\t<Weight_Percentage>100</Weight_Percentage>\n"
      b ++= s"\t\t<Teacher>${esc(teachers.getOrElse(t.id, t.code))}</Teacher>\n"
      b ++= s"\t\t<Number_of_Not_Available_Times>${slots.size}</Number_of_Not_Available_Times>\n"
      for (day, hour) <- slots do
        b ++= "\t\t<Not_Available_Time>\n"
        b ++= s"\t\t\t<Day>${esc(SchoolWeek.DayNames(day - 1))}</Day>\n\t\t\t<Hour>$hour</Hour>\n"
        b ++= "\t\t</Not_Available_Time>\n"
      b ++= "\t\t<Active>true</Active>\n\t\t<Comments></Comments>\n"
      b ++= "\t</ConstraintTeacherNotAvailableTimes>\n"

    b ++= "</Time_Constraints_List>\n\n"
    b ++= "<Space_Constraints_List>\n"
    b ++= constraint("ConstraintBasicCompulsorySpace", Nil)

    // Which rooms a subject may use.
    //
    // Without this nothing in the file says a lesson needs a room at all, so
    // FET assigns none and has no reason to keep two lessons out of the same
    // one — which is how the first week we generated came to put thirty-six
    // lessons into one hour in a building with thirty-two rooms. The rooms
    // were listed; nobody had said they were needed.
    //
    // Stated per subject rather than per lesson: a dozen constraints instead
    // of eleven hundred, saying the same thing. A subject wanting a particular
    // kind of room gets the rooms of that kind; everything else gets whatever
    // the school has.
    // Said of the lesson rather than of the subject, because the lessons
    // standing for a class's share of a cross-class block are bookkeeping and
    // need no room at all — the groups being taught have the rooms. Stating it
    // per subject catches those too, and a week where the same hour is wanted
    // by five activities and three of them are fictional is a week FET refuses
    // before it starts.
    // An ordinary room is one that carries no speciality: offering the sports
    // hall to a maths lesson is how sixty-odd ordinary lessons came to be
    // timetabled in the gym, and it takes the hall away from the PE that
    // needs it.
    val ordinary = rooms.filter(_.kinds.isEmpty)
    for a <- acts if a.means.isDefined do
      val specialist = a.roomKind
        .map(kind => rooms.filter(_.kinds.exists(_.id == kind)).map(roomName))
        .getOrElse(Nil)
      // A whole class does not fit in a room meant for half of one. The school
      // keeps three such rooms and they are worth having, but only for the
      // lessons a split puts in them.
      val plain = (if a.wholeClass then ordinary.filter(_.fitsWholeClass) else ordinary)
        .map(roomName)

      // Two lists, because the allowed set does two jobs at once: it says
      // which rooms suit the subject and which are big enough for the class.
      // Weakening it wholesale would weaken both, and a soft chemistry
      // requirement would let a whole class into a room for fifteen. So the
      // hard list stays a hard list — widened to include ordinary rooms when
      // the requirement is soft — and the preference is stated separately.
      val (mustBeIn, preferIn) = a.roomKind match
        // A requirement no room satisfies is a data fault. Saying "nowhere"
        // here turns it into an unsolvable week with no explanation;
        // validation is the place to complain about it.
        case Some(_) if specialist.isEmpty => (plain, Nil)
        case Some(_) if a.roomHard         => (specialist, Nil)
        case Some(_)                       => ((specialist ++ plain).distinct, specialist)
        case None                          => (plain, Nil)

      def rooms_(field: String, names: List[String]) =
        ("Activity_Id" -> a.id.toString)
          :: ("Number_of_Preferred_Rooms" -> names.size.toString)
          :: names.map(field -> _)

      if mustBeIn.nonEmpty then
        b ++= constraint("ConstraintActivityPreferredRooms",
          rooms_("Preferred_Room", mustBeIn))
      if preferIn.nonEmpty && tuning.wantsSoftRooms then
        b ++= preference("ConstraintActivityPreferredRooms", tuning.softRoomWeight,
          rooms_("Preferred_Room", preferIn))

    // A teacher's own room is a preference and never more than one: §5 says a
    // room means priority rather than possession, and several teachers share.
    if tuning.wantsHomeRooms then
      for
        t <- snapshot.teachers
        home <- t.homeRoomId
        room <- rooms.find(_.id == home)
      do
        b ++= preference("ConstraintTeacherHomeRoom", tuning.homeRoomWeight,
          List("Teacher" -> teachers.getOrElse(t.id, t.code), "Room" -> roomName(room)))

    b ++= "</Space_Constraints_List>\n\n"
    b ++= "</fet>\n"
    Written(b.result(), means, acts.size)

  /** Every block of every lesson line, and every class's share of every unit. */
  private def activities(snapshot: SchoolSnapshot, subjects: Map[UUID, String],
                         teachers: Map[UUID, String], classes: Map[UUID, String],
                         kindNames: Map[UUID, String]): List[Act] =
    var next = 1
    var group = 1
    val out = List.newBuilder[Act]
    // One group per unit rather than per block, so that two blocks of a unit
    // land on different days the way two blocks of a subject do.
    val unitGroups = scala.collection.mutable.Map.empty[UUID, Int]
    val lineGroups =
      scala.collection.mutable.Map.empty[(UUID, UUID, LessonAudience), Int]

    for line <- snapshot.lessonLines do
      // A lesson only part of the class attends is addressed to the pupils who
      // attend it, where the school has said who they are. That is what lets
      // FET see the others as free and keep them out of a mid-day wait — and
      // it is strictly better than ordering the lesson to the end of the day,
      // because an hour that suits everybody stays available.
      val cohort = snapshot.classes.find(_.id == line.classId)
        .filter(_ => line.audience == LessonAudience.WHOLE_CLASS)
        .filter(c => snapshot.optionalAttendance.get((c.id, line.subjectId)).exists(_ > 0))
        .map(c => cohortGroup(c, line.subjectId))
      val who = cohort.getOrElse(line.audience match
        case LessonAudience.WHOLE_CLASS => classes.getOrElse(line.classId, "?")
        case LessonAudience.GROUP_1     => classes.getOrElse(line.classId, "?") + "/1"
        case LessonAudience.GROUP_2     => classes.getOrElse(line.classId, "?") + "/2")
      val staff = (line.teacherId.toList ++ line.supportTeacherId.toList).flatMap(teachers.get)
      val subject = subjects.getOrElse(line.subjectId, "?")
      // Spreading is per subject and audience, not per line. A class's
      // standard and extended hours are two lines of one subject, and the rule
      // is that a class sees that subject once a day — so they share a group
      // and get spread together. Grouping by line lets a base block and an
      // extension block land on the same day, which our own checker caught in
      // sixty-three places.
      val key = (line.classId, line.subjectId, line.audience)
      val mine = lineGroups.getOrElseUpdate(key, { group += 1; group })
      for (length, block) <- line.blocks.zipWithIndex do
        out += Act(next, staff, subject, List(who), length, mine,
          optional = snapshot.subjects.find(_.id == line.subjectId).exists(_.optional),
          sameTimeAs = None,
          means = Some(LessonRef.Line(line.id, block)),
          roomKind = snapshot.subjects.find(_.id == line.subjectId)
            .flatMap(_.requiredRoomKind).map(_.id),
          // Absent subject means absent requirement; firm is the safe reading.
          roomHard = snapshot.subjects.find(_.id == line.subjectId)
            .forall(_.roomRequirementHard),
          wholeClass = line.audience == LessonAudience.WHOLE_CLASS,
          cohortKnown = cohort.isDefined,
          line = Some(LineBlock(line.classId, line.subjectId, line.audience, line.kind,
            line.id, block)))
        next += 1

    // A cross-class block is two different things at once and has to be said
    // twice. One activity holds the classes and no teacher: that is the hour
    // their students are busy, counted once however many groups they are split
    // across. One activity per group holds a teacher and no class: that is the
    // group being taught, and it needs a room of its own.
    //
    // Saying it as one activity per group naming its classes is the obvious
    // thing and it is wrong — a class whose students go to three different
    // groups of one unit is then charged the hour three times. FET noticed
    // before we did, which is the whole reason for putting it through FET.
    for
      unit <- snapshot.crossClassUnits
      (length, block) <- unit.blocks.zipWithIndex
    do
      val mine = unitGroups.getOrElseUpdate(unit.id, { group += 1; group })
      val anchor = next
      val subject = subjects.getOrElse(unit.subjectId, "?")
      val optional = snapshot.subjects.find(_.id == unit.subjectId).exists(_.optional)

      // The occupancy activity stands for every participating class at once,
      // so it has no single lesson to name; the classes learn their hour from
      // the group activities, which do.
      // Addressed to the pupils who attend, class by class, where the school
      // has said who they are — so that the rest of each class is visibly free
      // and FET will not strand them mid-day.
      val busy = unit.classIds.toList.flatMap(id => snapshot.classes.find(_.id == id)).map(c =>
        if snapshot.optionalAttendance.get((c.id, unit.subjectId)).exists(_ > 0)
        then cohortGroup(c, unit.subjectId) else c.name)
      out += Act(anchor, Nil, subject, busy.distinct.sorted,
        length, mine, optional, sameTimeAs = None, means = None, roomKind = None,
        roomHard = true, wholeClass = false,
        // Every class in the unit, or none of them: one activity carries one
        // rule, and a class nobody has accounted for would lose its fallback.
        cohortKnown = unit.classIds.nonEmpty && unit.classIds.forall(id =>
          snapshot.optionalAttendance.get((id, unit.subjectId)).exists(_ > 0)))
      next += 1

      for g <- unit.groups do
        out += Act(next, g.teacherId.toList.flatMap(teachers.get), subject, Nil, length, mine,
          optional = false, sameTimeAs = Some(anchor),
          means = Some(LessonRef.CrossClassGroupRef(unit.id, g.id, block)),
          roomKind = unit.requiredRoomKindId,
          roomHard = snapshot.subjects.find(_.id == unit.subjectId)
            .forall(_.roomRequirementHard),
          // A cross-class group is part of a class, never a whole one.
          wholeClass = false)
        next += 1

    // ── Nauczanie indywidualne ────────────────────────────────────────────
    //
    // One student, so no student set: the class is not busy, because the
    // student is not in it. What it does book is the teacher — which is the
    // whole reason for carrying these at all — and one of the rooms the school
    // keeps for it.
    val individualKind = snapshot.roomsOfKind.keys.find(id =>
      kindNames.get(id).exists(_.equalsIgnoreCase(IndividualTuition.RoomKindName)))
    for
      plan <- snapshot.individualPlans
      lesson <- plan.lessons
      (length, block) <- lesson.blocks.zipWithIndex
    do
      // A group of its own, allocated like every other: taking the current
      // number before moving on handed the first tuition lesson the last
      // unit's group, and with it that unit's "different days" rule.
      group += 1
      val mine = group
      out += Act(next, lesson.teacherId.toList.flatMap(teachers.get),
        subjects.getOrElse(lesson.subjectId, "?"), Nil, length, mine,
        optional = false, sameTimeAs = None,
        means = Some(LessonRef.Individual(lesson.id, block)),
        roomKind = individualKind, roomHard = true, wholeClass = false)
      next += 1

    out.result()

  /**
   * A constraint FET is asked to prefer rather than obey.
   *
   * Only some will take this. FET refuses a weight below 100 on the gap
   * constraints and on the daily minimum, and aborts rather than warning —
   * see ADR 0005 for which is which and what it says when it declines.
   */
  private def preference(name: String, weight: Int, fields: List[(String, String)]): String =
    val b = StringBuilder()
    b ++= s"\t<$name>\n\t\t<Weight_Percentage>$weight</Weight_Percentage>\n"
    for (k, v) <- fields do b ++= s"\t\t<$k>${esc(v)}</$k>\n"
    b ++= "\t\t<Active>true</Active>\n\t\t<Comments></Comments>\n"
    b ++= s"\t</$name>\n"
    b.result()

  private def constraint(name: String, fields: List[(String, String)]): String =
    val b = StringBuilder()
    b ++= s"\t<$name>\n\t\t<Weight_Percentage>100</Weight_Percentage>\n"
    for (k, v) <- fields do b ++= s"\t\t<$k>${esc(v)}</$k>\n"
    b ++= "\t\t<Active>true</Active>\n\t\t<Comments></Comments>\n"
    b ++= s"\t</$name>\n"
    b.result()

  /** How a room is named in the file. The number, because it is unique. */
  private def roomName(room: Room): String = room.number

  private def esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
     .replace("\"", "&quot;").replace("'", "&apos;")
