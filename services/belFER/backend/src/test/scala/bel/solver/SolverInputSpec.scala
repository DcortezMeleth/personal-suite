package bel.solver

import bel.domain.*
import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters.*

/**
 * The school's own data, through the solver and back.
 *
 * The POC already proved CP-SAT can express these rules; what is untested here
 * is the wiring — that a lesson line becomes the right number of blocks, that a
 * merged cross-class group is taught once rather than once per class, and that
 * what comes back can be matched to what went in.
 */
class SolverInputSpec extends AnyFunSuite:

  private val settings = SchedulingSettings(2, 5, 8)
  private val maths = Subject(UUID.randomUUID, "mat", "Matematyka", false, None, false)
  private val wf = Subject(UUID.randomUUID, "wf", "WF", false, None, false)
  private val religia = Subject(UUID.randomUUID, "rel", "Religia", true, None, false)

  private def teacher(name: String) =
    Teacher(UUID.randomUUID, name.take(2).toUpperCase, name, name, None, Nil, None, None, None, Nil)

  private def cls(name: String) =
    SchoolClass(UUID.randomUUID, name.head.asDigit, name.tail, None, None, None, None)

  private def line(classId: UUID, subject: UUID, blocks: List[Int], t: Teacher,
                   audience: LessonAudience = LessonAudience.WHOLE_CLASS,
                   support: Option[UUID] = None) =
    LessonLine(UUID.randomUUID, classId, subject, audience, LessonKind.BASE,
      Some(t.id), support, blocks)

  private def snapshotOf(classes: List[SchoolClass], teachers: List[Teacher],
                         lines: List[LessonLine], units: List[CrossClassUnit]) =
    SchoolSnapshot(settings, slotsPerDay = 8, teachingDays = 5, classes, teachers,
      List(maths, wf, religia), lines, units, Nil, Map.empty, Map.empty, totalRooms = 8)

  private def rooms(n: Int) =
    (1 to n).map(i => Room(UUID.randomUUID, i.toString, None, true, Nil)).toList

  test("a lesson line becomes one thing to place per block") {
    val a = cls("1A")
    val kowalski = teacher("Kowalski")
    val snapshot = snapshotOf(List(a), List(kowalski),
      List(line(a.id, maths.id, List(1, 2, 1), kowalski)), Nil)

    val problem = SolverInput.build(snapshot, rooms(2), timeLimitSeconds = 10)
    assert(problem.lessons.size == 3)
    assert(problem.lessons.asScala.map(_.length).toList.sorted == List(1, 1, 2))
  }

  // A merged group is taught once however many classes it draws from. Getting
  // this wrong books one teacher into two rooms at the same moment, and the
  // instance quietly becomes unsolvable.
  test("a cross-class group covering two classes is still one taught group") {
    val a = cls("1A")
    val b = cls("1B")
    val nowak = teacher("Nowak")
    val unit = CrossClassUnit(UUID.randomUUID, wf.id, "WF 1AB", None, List(1),
      List(CrossClassGroup(UUID.randomUUID, "chłopcy", Some(nowak.id), List(a.id, b.id))))
    val snapshot = snapshotOf(List(a, b), List(nowak), Nil, List(unit))

    val problem = SolverInput.build(snapshot, rooms(2), timeLimitSeconds = 10)
    val all = problem.lessons.asScala.toList
    // One lesson for the group — who teaches it, and where — carrying no class,
    // and one per class to say that class is occupied. Emitting a lesson per
    // (group, class) instead books a class into its own slot once per group it
    // belongs to, which is a contradiction the school's data hits constantly:
    // fourteen of its seventeen units have a class spread over several groups.
    val group = all.filter(_.classId == null)
    val occupancy = all.filter(_.classId != null)
    assert(group.sizeIs == 1, all.toString)
    assert(occupancy.sizeIs == 2, all.toString)
    assert(group.head.teacherId == nowak.id.toString)
    // Only the group books anything; the occupancy lessons share its id so the
    // solver knows the teacher and room are consumed once.
    assert(occupancy.forall(_.teacherId == null))
    assert(all.map(_.crossClassGroupId).toSet.sizeIs == 1)
  }

  test("a solved plan can be matched back to what went in") {
    val a = cls("1A")
    val kowalski = teacher("Kowalski")
    val lines = List(line(a.id, maths.id, List(1, 1), kowalski))
    val snapshot = snapshotOf(List(a), List(kowalski), lines, Nil)

    val problem = SolverInput.build(snapshot, rooms(2), timeLimitSeconds = 30)
    val solution = TimetableSolver.solve(problem)

    assert(solution.solved, solution.detail)
    assert(solution.placements.size == 2)
    val refs = solution.placements.asScala.toList.flatMap(p => SolverInput.LessonRef.parse(p.lessonId))
    assert(refs.sizeIs == 2)
    assert(refs.forall {
      case SolverInput.LessonRef.Line(id, _) => id == lines.head.id
      case _                                 => false
    })
  }

  // The rules themselves are covered by the POC against the real arkusz; this
  // only checks that they survive the trip through the real domain types.
  //
  // Two blocks each, not three: with gaps disallowed a class whose day holds a
  // single hour must hold it in the first slot, so two classes of three such
  // days would need six days between them and there are five. That is the
  // rules working, but it makes a poor fixture for testing the teacher clash.
  test("two classes sharing a teacher are not scheduled on top of each other") {
    val a = cls("1A")
    val b = cls("1B")
    val shared = teacher("Wspolny")
    val snapshot = snapshotOf(List(a, b), List(shared),
      List(line(a.id, maths.id, List(1, 1), shared),
           line(b.id, maths.id, List(1, 1), shared)), Nil)

    val problem = SolverInput.build(snapshot, rooms(4), timeLimitSeconds = 60)
    val solution = TimetableSolver.solve(problem)
    assert(solution.solved, solution.detail)

    val slots = solution.placements.asScala.map(_.startSlot).toList
    assert(slots.distinct.sizeIs == slots.size, s"overlapping slots: $slots")
  }

  test("an optional subject is placed at an end of its day") {
    val a = cls("1A")
    val katecheta = teacher("Katecheta")
    val matematyk = teacher("Matematyk")
    val rel = line(a.id, religia.id, List(1), katecheta)
    // Two hours of maths on the same day as religia, so there is a day long
    // enough for "at an end" to mean something.
    val mat = line(a.id, maths.id, List(2), matematyk)
    val snapshot = snapshotOf(List(a), List(katecheta, matematyk), List(rel, mat), Nil)

    val problem = SolverInput.build(snapshot, rooms(2), timeLimitSeconds = 60)
    val solution = TimetableSolver.solve(problem)
    assert(solution.solved, solution.detail)

    val placed = solution.placements.asScala.map(p => p.lessonId -> p.startSlot).toMap
    val religiaSlot = placed(s"line|${rel.id}|0")
    val mathsSlot = placed(s"line|${mat.id}|0")
    val slotsPerDay = problem.slotsPerDay

    if religiaSlot / slotsPerDay == mathsSlot / slotsPerDay then
      // Same day: religia is either the first hour or the one after maths ends.
      val position = religiaSlot % slotsPerDay
      assert(position == 0 || religiaSlot == mathsSlot + 2,
        s"religia at $religiaSlot, maths at $mathsSlot")
    else
      // Its own day, where it is both first and last.
      assert(religiaSlot % slotsPerDay == 0, s"religia at $religiaSlot")
  }
