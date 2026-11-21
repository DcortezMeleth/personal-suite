package bel.solver

/**
 * How uneven a class's week may be (RULES P2), in the only terms FET has.
 *
 * The rule is "longest day minus shortest at most N". FET cannot say that: it
 * knows a set's shortest day and its longest day, each a fixed number, and
 * nothing about the difference between them. So the difference is said as a
 * fixed range of day lengths, chosen per class from its own weekly load. Any
 * range N wide keeps the rule; which one is chosen decides how much else it
 * gives up.
 *
 * For a 32-hour week at N = 2, days of 5–7 allow 30 of the 35 layouts the rule
 * allows. The five lost are 8/6/6/6/6 in its five orders, which sit in 6–8
 * instead. No single range holds both, which is the price of FET having no
 * way to say "or". The range kept is the one that loses least.
 *
 * Measured on the real school before this was built (RUNLOG R8–R10), with a
 * script doing exactly what this does.
 */
object ClassDays:

  /** How many ways `days` day lengths, each in `lo`..`hi`, add up to `load`. */
  def layouts(load: Int, days: Int, lo: Int, hi: Int): Long =
    var ways = Array.fill(load + 1)(0L)
    ways(0) = 1
    for _ <- 1 to days do
      val next = Array.fill(load + 1)(0L)
      for n <- 0 to load if ways(n) > 0; h <- lo to hi if n + h <= load do
        next(n + h) += ways(n)
      ways = next
    ways(load)

  /**
   * The range of day lengths, at most `spread` apart, that allows the most
   * layouts of `load` hours over `days` days of at most `slots` lessons.
   *
   * Ties go to the range centred nearest the class's average day, then to the
   * lower one. Every day has at least one lesson. None when no range can
   * hold the load at all, which is a question about the data rather than
   * about the layout, and is left to whatever else the week says about it.
   */
  def range(load: Int, days: Int, slots: Int, spread: Int): Option[(Int, Int)] =
    (1 to slots)
      .map(lo => (lo, (lo + spread).min(slots)))
      .map((lo, hi) => (lo, hi, layouts(load, days, lo, hi)))
      .filter(_._3 > 0)
      .sortBy((lo, hi, n) => (-n, math.abs(lo + hi - 2.0 * load / days), lo))
      .headOption
      .map((lo, hi, _) => (lo, hi))
