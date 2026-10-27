package bel.domain

/**
 * Which days the school teaches on, and what they are called.
 *
 * Said once because it is said to three audiences that have to agree: the
 * screens, the printed sheet, and the file FET is given — which answers with
 * the day *names* it was handed, so a name we write and do not recognise
 * coming back loses the lesson silently.
 *
 * The teaching week is a constant rather than a column. Every Polish liceum
 * this is built for teaches Monday to Friday, and REQUIREMENTS §2.3 fixes it;
 * carrying it in the snapshot as if it were configurable, while a loader
 * filled it in with a literal, only made it look like a question that had
 * been answered. When a school needs Saturday it becomes a school setting,
 * and this is the one place that has to change.
 */
object SchoolWeek:

  /** Every day a timetable could name, in order. */
  val DayNames =
    List("Poniedziałek", "Wtorek", "Środa", "Czwartek", "Piątek", "Sobota", "Niedziela")

  /** Monday to Friday — REQUIREMENTS §2.3. */
  val TeachingDays = 5

  /**
   * The first `teachingDays` of them.
   *
   * Refuses to name fewer days than it was asked for, rather than quietly
   * returning a short list: a FET file that says it has six days and names
   * five is accepted by nobody, and the answer comes back unreadable.
   */
  def names(teachingDays: Int): List[String] =
    require(teachingDays >= 0 && teachingDays <= DayNames.size,
      s"a week of $teachingDays days has no names")
    DayNames.take(teachingDays)
