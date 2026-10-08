package bel.importer

/**
 * Where the arkusz changes shape.
 *
 * The school issues a new plan when the allocation changes — history drops from
 * two hours to one at week 12, and from week 13 the classes get a fresh
 * timetable. So the useful question when importing is not "which semester" but
 * "which stretch of weeks has a constant allocation", and the file answers it:
 * every allocation carries the weeks it applies to, and the boundaries are
 * wherever one starts or stops.
 *
 * Offering these rather than asking for two numbers means the planner picks a
 * stretch that is actually constant, instead of discovering half way through
 * that the plan she built covers two different allocations.
 */
case class WeekSegment(from: Int, to: Int, allocations: Int, hoursPerWeek: Int):
  def weeks: Int = to - from + 1

object ArkuszSegments:

  /** Stretches of weeks over which the set of active allocations does not change. */
  def detect(doc: ArkuszDocument): List[WeekSegment] =
    val relevant = doc.assignments.filter(a => a.isOrdinary || a.isCrossClassGroup)
    if relevant.isEmpty then Nil
    else
      val boundaries =
        (relevant.map(_.weekFrom) ++ relevant.map(_.weekTo + 1)).distinct.sorted
      boundaries
        .sliding(2)
        .collect { case List(from, next) if next > from =>
          val active = relevant.filter(a => a.weekFrom <= from && a.weekTo >= next - 1)
          WeekSegment(from, next - 1, active.size, active.map(_.hours).sum)
        }
        .filter(_.allocations > 0)
        .toList

  /**
   * Segments worth offering as a choice. A single week carrying a handful of
   * extra allocations is a one-off the school deals with by hand, not a plan
   * anyone would print — so only stretches long enough to be worth a timetable
   * are suggested.
   */
  def suggested(doc: ArkuszDocument, minimumWeeks: Int = 3): List[WeekSegment] =
    detect(doc).filter(_.weeks >= minimumWeeks)
