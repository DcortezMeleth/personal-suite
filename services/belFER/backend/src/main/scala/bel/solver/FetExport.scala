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
 * One rule does not survive the crossing. FET can say an activity ends a
 * class's day and it can say an activity sits at a given hour, but it has no
 * way to say "first or last, whichever suits" — so optional subjects are
 * exported as ending the day, which is stricter than the school is. Worth
 * knowing when reading the answer: a timetable FET cannot find under that
 * reading might still exist under ours.
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
  case class Written(xml: String, means: Map[Int, SolverInput.LessonRef])

  private val Days = List("Poniedziałek", "Wtorek", "Środa", "Czwartek", "Piątek")

  /** The day names this file will use, which is how FET answers back. */
  def dayNames(teachingDays: Int): List[String] = Days.take(teachingDays)

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
    means: Option[SolverInput.LessonRef],
    /** The kind of room it needs, when it needs a particular one. */
    roomKind: Option[UUID],
    /** Whether a whole class has to fit in the room. */
    wholeClass: Boolean
  )

  def apply(snapshot: SchoolSnapshot, rooms: List[Room]): Written =
    val subjects = snapshot.subjects.map(s => s.id -> s.name).toMap
    val teachers = snapshot.teachers.map(t => t.id -> s"${t.lastName} ${t.firstName} (${t.code})").toMap
    val classes = snapshot.classes.map(c => c.id -> c.name).toMap

    val acts = activities(snapshot, subjects, teachers, classes)
    val means = acts.flatMap(a => a.means.map(a.id -> _)).toMap
    val b = StringBuilder()

    b ++= "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
    b ++= "<fet version=\"7.12.0\">\n\n"
    b ++= s"<Institution_Name>${esc("I LO")}</Institution_Name>\n"
    b ++= "<Comments>Wyeksportowane z belFER</Comments>\n\n"

    b ++= "<Days_List>\n"
    b ++= s"\t<Number_of_Days>${snapshot.teachingDays}</Number_of_Days>\n"
    for d <- Days.take(snapshot.teachingDays) do
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
    b ++= "<Students_List>\n"
    for cls <- snapshot.classes.sortBy(_.name) do
      val size = cls.studentCount.getOrElse(30)
      b ++= s"\t<Year>\n\t\t<Name>${esc(cls.name)}</Name>\n"
      b ++= s"\t\t<Number_of_Students>$size</Number_of_Students>\n\t\t<Comments></Comments>\n"
      for half <- List(1, 2) do
        b ++= s"\t\t<Group>\n\t\t\t<Name>${esc(cls.name)}/$half</Name>\n"
        b ++= s"\t\t\t<Number_of_Students>${size / 2}</Number_of_Students>\n"
        b ++= "\t\t\t<Comments></Comments>\n"
        b ++= s"\t\t\t<Subgroup>\n\t\t\t\t<Name>${esc(cls.name)}/${half}s</Name>\n"
        b ++= s"\t\t\t\t<Number_of_Students>${size / 2}</Number_of_Students>\n"
        b ++= "\t\t\t\t<Comments></Comments>\n\t\t\t</Subgroup>\n"
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

    // A student has nowhere to be in a free period.
    for cls <- snapshot.classes.sortBy(_.name) do
      b ++= constraint("ConstraintStudentsSetMaxGapsPerDay",
        List("Max_Gaps" -> "0", "Students" -> cls.name))
      b ++= constraint("ConstraintStudentsSetMaxHoursDaily",
        List("Maximum_Hours_Daily" -> snapshot.slotsPerDay.toString, "Students" -> cls.name))

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
    for (_, group) <- acts.filter(a => a.groupId > 0 && a.sameTimeAs.isEmpty)
                          .groupBy(_.groupId) if group.size > 1 do
      b ++= constraint("ConstraintMinDaysBetweenActivities",
        ("Number_of_Activities" -> group.size.toString)
          :: group.map(a => "Activity_Id" -> a.id.toString)
          ::: List("MinDays" -> "1", "Consecutive_If_Same_Day" -> "true"))

    // Religia and etyka sit at the edge of a day. FET can say "ends the day";
    // it has no way to say "or begins it", so this is the stricter reading.
    for a <- acts if a.optional do
      b ++= constraint("ConstraintActivityEndsStudentsDay", List("Activity_Id" -> a.id.toString))

    // What a teacher's week may look like. None of this was exported the first
    // time, so FET had no reason to respect any of it — and duly did not:
    // thirty-two teacher-days with somebody sitting through three free hours.
    // Not its answer being poor, our question being incomplete.
    //
    // "At most two free hours in a row" has no counterpart here; FET counts a
    // day's gaps rather than its longest run of them. At most two in the day
    // is the nearest thing and is stricter, which is the safe direction.
    b ++= constraint("ConstraintTeachersMaxGapsPerDay",
      List("Max_Gaps" -> snapshot.settings.maxConsecutiveTeacherGaps.toString))
    b ++= constraint("ConstraintTeachersMaxHoursDaily",
      List("Maximum_Hours_Daily" -> snapshot.settings.defaultTeacherMaxLessonsPerDay.toString))

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
        b ++= s"\t\t\t<Day>${esc(Days(day - 1))}</Day>\n\t\t\t<Hour>$hour</Hour>\n"
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
      val allowed = a.roomKind match
        case Some(kind) =>
          val fitting = rooms.filter(_.kinds.exists(_.id == kind)).map(roomName)
          // A requirement no room satisfies is a data fault. Saying "nowhere"
          // here turns it into an unsolvable week with no explanation;
          // validation is the place to complain about it.
          if fitting.nonEmpty then fitting else ordinary.map(roomName)
        case None =>
          // A whole class does not fit in a room meant for half of one. The
          // school keeps three such rooms and they are worth having, but only
          // for the lessons a split puts in them.
          val fitting = if a.wholeClass then ordinary.filter(_.fitsWholeClass) else ordinary
          fitting.map(roomName)
      if allowed.nonEmpty then
        b ++= constraint("ConstraintActivityPreferredRooms",
          ("Activity_Id" -> a.id.toString)
            :: ("Number_of_Preferred_Rooms" -> allowed.size.toString)
            :: allowed.map("Preferred_Room" -> _))

    b ++= "</Space_Constraints_List>\n\n"
    b ++= "</fet>\n"
    Written(b.result(), means)

  /** Every block of every lesson line, and every class's share of every unit. */
  private def activities(snapshot: SchoolSnapshot, subjects: Map[UUID, String],
                         teachers: Map[UUID, String], classes: Map[UUID, String]): List[Act] =
    var next = 1
    var group = 1
    val out = List.newBuilder[Act]
    // One group per unit rather than per block, so that two blocks of a unit
    // land on different days the way two blocks of a subject do.
    val unitGroups = scala.collection.mutable.Map.empty[UUID, Int]
    val lineGroups =
      scala.collection.mutable.Map.empty[(UUID, UUID, LessonAudience), Int]

    for line <- snapshot.lessonLines do
      val who = line.audience match
        case LessonAudience.WHOLE_CLASS => classes.getOrElse(line.classId, "?")
        case LessonAudience.GROUP_1     => classes.getOrElse(line.classId, "?") + "/1"
        case LessonAudience.GROUP_2     => classes.getOrElse(line.classId, "?") + "/2"
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
          means = Some(SolverInput.LessonRef.Line(line.id, block)),
          roomKind = snapshot.subjects.find(_.id == line.subjectId)
            .flatMap(_.requiredRoomKind).map(_.id),
          wholeClass = line.audience == LessonAudience.WHOLE_CLASS)
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
      out += Act(anchor, Nil, subject, unit.classIds.toList.flatMap(classes.get).distinct.sorted,
        length, mine, optional, sameTimeAs = None, means = None, roomKind = None,
        wholeClass = false)
      next += 1

      for g <- unit.groups do
        out += Act(next, g.teacherId.toList.flatMap(teachers.get), subject, Nil, length, mine,
          optional = false, sameTimeAs = Some(anchor),
          means = Some(SolverInput.LessonRef.CrossClassGroupRef(unit.id, g.id, block)),
          roomKind = unit.requiredRoomKindId,
          // A cross-class group is part of a class, never a whole one.
          wholeClass = false)
        next += 1

    out.result()

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
