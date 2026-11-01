import { useState } from "react";
import { AlertBanner, DataCard } from "@delfin/ui";
import { useSchool } from "../hooks/useSchool";
import { useConfirm } from "../hooks/useConfirm";
import { useFindings } from "../hooks/useFindings";
import { FindingMark } from "../components/FindingMark";
import { NoSchool } from "../components/NoSchool";
import {
  api,
  type Attendance,
  type SchoolClass,
  type SchoolClassInput,
  type Finding,
  type Subject,
  type Teacher,
} from "../api/client";

const field = "w-full rounded border border-neutral-300 px-2 py-1";
const plainButton = "rounded border border-neutral-300 px-3 py-1.5 text-sm hover:bg-neutral-50";

export function ClassesPage() {
  const [classes, setClasses] = useState<SchoolClass[]>([]);
  const [teachers, setTeachers] = useState<Teacher[]>([]);
  // Only the optional subjects get a column: they are the ones part of a class
  // attends, and so the ones whose absentees must not be left waiting.
  const [optional, setOptional] = useState<Subject[]>([]);
  const [attending, setAttending] = useState<Map<string, number>>(new Map());
  const [genYear, setGenYear] = useState(1);
  const [genLetters, setGenLetters] = useState("A, B, C, D");
  const [confirmDialog, confirm] = useConfirm();

  const { school, error, setError, reload: load, gate } = useSchool({
    title: "Oddziały",
    load: async (school) => {
      const [c, t, s, a] = await Promise.all([
        api.listClasses(school.id),
        api.listTeachers(school.id),
        api.listSubjects(school.id),
        api.listAttendance(school.id),
      ]);
      setClasses(c);
      setTeachers(t);
      setOptional(s.filter((x) => x.optional));
      setAttending(new Map(a.flatMap((e: Attendance) =>
        e.attending === null ? [] : [[`${e.classId}:${e.subjectId}`, e.attending] as const])));
    },
  });

  const { findingsFor, hoursFor, reloadFindings } = useFindings(school?.id);

  async function run(action: () => Promise<unknown>) {
    try {
      await action();
      setError(null);
      await load();
      // Fixing a class can resolve or create a finding, so they are refreshed
      // with the data rather than going stale until a reload.
      await reloadFindings();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }

  if (gate) return gate;
  if (!school) return <NoSchool title="Oddziały" />;

  const byYear = new Map<number, SchoolClass[]>();
  for (const cls of classes) {
    byYear.set(cls.year, [...(byYear.get(cls.year) ?? []), cls]);
  }

  return (
    <div className="space-y-4">
      {confirmDialog}
      {error && <AlertBanner level="danger" message={error} onDismiss={() => setError(null)} />}

      <DataCard title="Utwórz oddziały">
        {/* Letters are typed rather than counted: this school has no 1B, and
            generating A..H only to delete one is worse than naming the seven
            that exist. Re-running adds only what is missing. */}
        <div className="flex flex-wrap items-end gap-3">
          <div>
            <label className="block text-sm font-medium text-neutral-700">Rocznik</label>
            <select
              className={`${field} w-24`}
              value={genYear}
              onChange={(e) => setGenYear(Number(e.target.value))}
            >
              {Array.from({ length: school.years }, (_, i) => i + 1).map((year) => (
                <option key={year} value={year}>
                  {year}
                </option>
              ))}
            </select>
          </div>
          <div className="flex-1 min-w-[16rem]">
            <label className="block text-sm font-medium text-neutral-700">Litery oddziałów</label>
            <input
              className={field}
              value={genLetters}
              onChange={(e) => setGenLetters(e.target.value)}
              placeholder="A, C, D, E, F, G, H"
            />
          </div>
          <button
            className={plainButton}
            onClick={() =>
              run(() =>
                api.generateClasses(
                  school.id,
                  genYear,
                  genLetters.split(",").map((l) => l.trim()).filter(Boolean),
                ),
              )
            }
          >
            Utwórz
          </button>
        </div>
        <p className="mt-2 text-sm text-neutral-500">
          Litery nie muszą być kolejne. Ponowne uruchomienie doda tylko brakujące oddziały.
        </p>
      </DataCard>

      <DataCard title={`Oddziały (${classes.length})`}>
        {classes.length === 0 ? (
          <p className="text-neutral-500">Brak oddziałów.</p>
        ) : (
          <table className="w-full text-left text-sm">
            <thead className="text-neutral-500">
              <tr>
                <th className="w-28 pb-2">Oddział</th>
                <th className="pb-2">Profil</th>
                <th className="pb-2">Wychowawca</th>
                <th className="pb-2">Uczniów</th>
                <th className="pb-2">Dziewcząt</th>
                {/* One column per optional subject. These are what make the
                    week legal: a pupil attending none of them must not be left
                    waiting mid-day, and until the school says how many that is
                    the solver has to force every such lesson to the end of the
                    day — which on this school made the timetable impossible. */}
                {optional.map((s) => (
                  <th key={s.id} className="pb-2">{s.name}</th>
                ))}
                <th className="pb-2">Żadnych</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {[...byYear.keys()].sort((a, b) => a - b).map((year) => (
                <ClassYear
                  key={year}
                  year={year}
                  classes={byYear.get(year) ?? []}
                  teachers={teachers}
                  optional={optional}
                  attending={attending}
                  teaches={(id) => (hoursFor(id) ?? 0) > 0}
                  findingsFor={findingsFor}
                  onAttendance={(classId, subjectId, n) =>
                    run(() => api.setAttendance(school.id, {
                      classId, subjectId, attending: n,
                    }))}
                  onSave={(id, input) => run(() => api.updateClass(school.id, id, input))}
                  onDelete={(id, name) =>
                    confirm(`Usunąć oddział ${name}?`, () => run(() => api.deleteClass(id)))
                  }
                />
              ))}
            </tbody>
          </table>
        )}
      </DataCard>
    </div>
  );
}

function ClassYear(props: {
  year: number;
  classes: SchoolClass[];
  teachers: Teacher[];
  optional: Subject[];
  attending: Map<string, number>;
  teaches: (id: string) => boolean;
  findingsFor: (id: string) => Finding[];
  onSave: (id: string, input: SchoolClassInput) => void;
  onDelete: (id: string, name: string) => void;
  onAttendance: (classId: string, subjectId: string, n: number | null) => void;
}) {
  return (
    <>
      {/* A banded header rather than a line of grey text: with four roczniki
          and thirty-odd oddziały the eye needs somewhere to stop, and the
          count saves scrolling a block to find out how long it is. */}
      <tr>
        <td
          colSpan={7 + props.optional.length}
          className="bg-neutral-100 px-2 py-1.5 text-xs font-semibold uppercase tracking-wide text-neutral-500"
        >
          Rocznik {props.year}
          <span className="ml-2 font-normal normal-case tracking-normal text-neutral-400">
            {props.classes.length}
          </span>
        </td>
      </tr>
      {props.classes.map((cls) => (
        <ClassRow
          key={cls.id}
          cls={cls}
          teachers={props.teachers}
          optional={props.optional}
          attending={props.attending}
          teaches={props.teaches}
          findings={props.findingsFor(cls.id)}
          onSave={(input) => props.onSave(cls.id, input)}
          onDelete={() => props.onDelete(cls.id, cls.name)}
          onAttendance={props.onAttendance}
        />
      ))}
    </>
  );
}

function ClassRow(props: {
  cls: SchoolClass;
  teachers: Teacher[];
  optional: Subject[];
  attending: Map<string, number>;
  teaches: (id: string) => boolean;
  findings: Finding[];
  onSave: (input: SchoolClassInput) => void;
  onDelete: () => void;
  onAttendance: (classId: string, subjectId: string, n: number | null) => void;
}) {
  const initial: SchoolClassInput = {
    year: props.cls.year,
    letter: props.cls.letter,
    specialisation: props.cls.specialisation,
    homeroomTeacherId: props.cls.homeroomTeacherId,
    studentCount: props.cls.studentCount,
    girlCount: props.cls.girlCount,
  };
  const [draft, setDraft] = useState<SchoolClassInput>(initial);
  const dirty = JSON.stringify(draft) !== JSON.stringify(initial);

  const counted = props.optional.reduce(
    (sum, s) => sum + (props.attending.get(`${props.cls.id}:${s.id}`) ?? 0), 0);
  const size = props.cls.studentCount ?? 0;
  // Over the class size means somebody has been counted twice — a pupil in two
  // of them, which this school says does not happen — so it is shown rather
  // than clamped away.
  const neither = size === 0 || counted === 0 ? "—"
    : counted > size ? <span className="text-danger-600">{size - counted}</span>
    : size - counted;
  const num = (value: string): number | null => (value === "" ? null : Number(value));

  return (
    <tr className="border-t border-neutral-200">
      {/* "1A" is one name, so the year and the letter sit on one line. They
          used to be inline content in a cell with no width of its own, and
          with five full-width inputs competing for the table the column was
          squeezed until the year wrapped above the letter. */}
      <td className="py-2 pr-2">
        <div className="flex items-center gap-1 whitespace-nowrap">
          <span className="font-medium text-neutral-700">{draft.year}</span>
          <input
            className={`${field} w-14 shrink-0`}
            value={draft.letter}
            onChange={(e) => setDraft({ ...draft, letter: e.target.value })}
          />
          <FindingMark findings={props.findings} />
        </div>
      </td>
      <td className="py-2 pr-2">
        <input
          className={field}
          placeholder="np. MAT-FIZ-INF"
          value={draft.specialisation ?? ""}
          onChange={(e) => setDraft({ ...draft, specialisation: e.target.value || null })}
        />
      </td>
      <td className="py-2 pr-2">
        <select
          className={field}
          value={draft.homeroomTeacherId ?? ""}
          onChange={(e) => setDraft({ ...draft, homeroomTeacherId: e.target.value || null })}
        >
          <option value="">—</option>
          {/* Staff with no assignments are not candidates: a good number of
              the arkusz's people teach nothing at all — psychologists, the
              librarian — and they outnumber the plausible answers.
              Whoever is already chosen stays listed regardless, or the
              select would show a blank for a value that is set. */}
          {props.teachers
            .filter((t) => props.teaches(t.id) || t.id === draft.homeroomTeacherId)
            .map((teacher) => (
            <option key={teacher.id} value={teacher.id}>
              {teacher.lastName} {teacher.firstName}
            </option>
          ))}
        </select>
      </td>
      <td className="py-2 pr-2 w-24">
        <input
          type="number"
          min={0}
          className={field}
          value={draft.studentCount ?? ""}
          onChange={(e) => setDraft({ ...draft, studentCount: num(e.target.value) })}
        />
      </td>
      <td className="py-2 pr-2 w-24">
        <input
          type="number"
          min={0}
          className={field}
          value={draft.girlCount ?? ""}
          onChange={(e) => setDraft({ ...draft, girlCount: num(e.target.value) })}
        />
      </td>
      {/* Saved on blur rather than behind the row's Zapisz: these go to a
          route of their own, so that saving a class cannot overwrite them and
          editing them cannot overwrite the class. The school's optimisation
          settings were silently wiped once by exactly that coupling. */}
      {props.optional.map((subject) => {
        const at = props.attending.get(`${props.cls.id}:${subject.id}`);
        return (
          <td key={subject.id} className="w-20 py-2 pr-2">
            <input
              type="number"
              min={0}
              className={field}
              defaultValue={at ?? ""}
              onBlur={(e) => {
                const next = e.target.value === "" ? null : Number(e.target.value);
                if (next !== (at ?? null)) {
                  props.onAttendance(props.cls.id, subject.id, next);
                }
              }}
            />
          </td>
        );
      })}
      {/* Derived, never typed: the class size minus those accounted for. These
          are the pupils the rule exists for, so showing the number is how the
          planner sees whether it looks right. */}
      <td className="w-20 py-2 pr-2 text-sm tabular-nums">{neither}</td>

      <td className="py-2 text-right whitespace-nowrap">
        {dirty && (
          <button className="mr-3 text-primary-500 hover:text-primary-600" onClick={() => props.onSave(draft)}>
            Zapisz
          </button>
        )}
        <button className="text-danger-500 hover:text-danger-600" onClick={props.onDelete}>
          Usuń
        </button>
      </td>
    </tr>
  );
}
