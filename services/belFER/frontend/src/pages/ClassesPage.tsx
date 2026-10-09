import { useCallback, useEffect, useState } from "react";
import { AlertBanner, DataCard } from "@delfin/ui";
import { useConfirm } from "../hooks/useConfirm";
import { useFindings } from "../hooks/useFindings";
import { FindingMark } from "../components/FindingMark";
import {
  api,
  type School,
  type SchoolClass,
  type SchoolClassInput,
  type Finding,
  type Teacher,
} from "../api/client";

const field = "w-full rounded border border-neutral-300 px-2 py-1";
const plainButton = "rounded border border-neutral-300 px-3 py-1.5 text-sm hover:bg-neutral-50";

export function ClassesPage() {
  const [school, setSchool] = useState<School | null>(null);
  const [classes, setClasses] = useState<SchoolClass[]>([]);
  const [teachers, setTeachers] = useState<Teacher[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [genYear, setGenYear] = useState(1);
  const [genLetters, setGenLetters] = useState("A, B, C, D");
  const [confirmDialog, confirm] = useConfirm();
  const { findingsFor, reloadFindings } = useFindings(school?.id);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const current = (await api.listSchools())[0] ?? null;
      setSchool(current);
      if (current) {
        const [c, t] = await Promise.all([api.listClasses(current.id), api.listTeachers(current.id)]);
        setClasses(c);
        setTeachers(t);
      }
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

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

  if (loading) return <p className="text-neutral-500">Wczytywanie…</p>;
  if (!school)
    return (
      <DataCard title="Oddziały">
        <p className="text-neutral-700">Najpierw skonfiguruj szkołę na zakładce „Szkoła”.</p>
      </DataCard>
    );

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
                <th className="pb-2">Oddział</th>
                <th className="pb-2">Profil</th>
                <th className="pb-2">Wychowawca</th>
                <th className="pb-2">Uczniów</th>
                <th className="pb-2">Dziewcząt</th>
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
                  findingsFor={findingsFor}
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
  findingsFor: (id: string) => Finding[];
  onSave: (id: string, input: SchoolClassInput) => void;
  onDelete: (id: string, name: string) => void;
}) {
  return (
    <>
      <tr>
        <td colSpan={6} className="pt-4 pb-1 text-xs font-semibold uppercase tracking-wide text-neutral-400">
          Rocznik {props.year}
        </td>
      </tr>
      {props.classes.map((cls) => (
        <ClassRow
          key={cls.id}
          cls={cls}
          teachers={props.teachers}
          findings={props.findingsFor(cls.id)}
          onSave={(input) => props.onSave(cls.id, input)}
          onDelete={() => props.onDelete(cls.id, cls.name)}
        />
      ))}
    </>
  );
}

function ClassRow(props: {
  cls: SchoolClass;
  teachers: Teacher[];
  findings: Finding[];
  onSave: (input: SchoolClassInput) => void;
  onDelete: () => void;
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
  const num = (value: string): number | null => (value === "" ? null : Number(value));

  return (
    <tr className="border-t border-neutral-200">
      <td className="py-2 pr-2 font-medium text-neutral-700">
        {draft.year}
        <FindingMark findings={props.findings} />
        <input
          className={`${field} ml-1 inline-block w-14`}
          value={draft.letter}
          onChange={(e) => setDraft({ ...draft, letter: e.target.value })}
        />
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
          {props.teachers.map((teacher) => (
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
