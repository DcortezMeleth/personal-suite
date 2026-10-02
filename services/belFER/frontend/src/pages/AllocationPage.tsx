import { useCallback, useEffect, useState } from "react";
import { AlertBanner, DataCard } from "@delfin/ui";
import { useConfirm } from "../hooks/useConfirm";
import {
  api,
  type AllocationSummary,
  type LessonAudience,
  type LessonKind,
  type LessonLine,
  type LessonLineInput,
  type School,
  type SchoolClass,
  type Subject,
  type Teacher,
} from "../api/client";

const AUDIENCES: { value: LessonAudience; label: string }[] = [
  { value: "WHOLE_CLASS", label: "cała klasa" },
  { value: "GROUP_1", label: "grupa 1" },
  { value: "GROUP_2", label: "grupa 2" },
];

const KINDS: { value: LessonKind; label: string }[] = [
  { value: "BASE", label: "podstawa" },
  { value: "EXTENSION", label: "rozszerzenie" },
];

const field = "w-full rounded border border-neutral-300 px-2 py-1";
const plainButton = "rounded border border-neutral-300 px-3 py-1.5 text-sm hover:bg-neutral-50";

// The school states hours as a shape — "1, 1, 2" is four hours as two singles
// and a double — so that is what the field takes.
const parseBlocks = (text: string): number[] =>
  text.split(/[,\s]+/).map((p) => Number(p)).filter((n) => Number.isFinite(n) && n > 0);

const formatBlocks = (blocks: number[]) => blocks.join(", ");

export function AllocationPage() {
  const [school, setSchool] = useState<School | null>(null);
  const [classes, setClasses] = useState<SchoolClass[]>([]);
  const [subjects, setSubjects] = useState<Subject[]>([]);
  const [teachers, setTeachers] = useState<Teacher[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [lines, setLines] = useState<LessonLine[]>([]);
  const [summary, setSummary] = useState<AllocationSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [confirmDialog, confirm] = useConfirm();

  const loadBase = useCallback(async () => {
    setLoading(true);
    try {
      const current = (await api.listSchools())[0] ?? null;
      setSchool(current);
      if (current) {
        const [c, s, t] = await Promise.all([
          api.listClasses(current.id),
          api.listSubjects(current.id),
          api.listTeachers(current.id),
        ]);
        setClasses(c);
        setSubjects(s);
        setTeachers(t);
        setSelected((prev) => prev ?? c[0]?.id ?? null);
      }
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, []);

  const loadLines = useCallback(async () => {
    if (!school || !selected) {
      setLines([]);
      setSummary(null);
      return;
    }
    try {
      const [l, s] = await Promise.all([
        api.listLessonLines(selected),
        api.allocationSummary(school.id, selected),
      ]);
      setLines(l);
      setSummary(s);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [school, selected]);

  useEffect(() => {
    loadBase();
  }, [loadBase]);
  useEffect(() => {
    loadLines();
  }, [loadLines]);

  async function run(action: () => Promise<unknown>) {
    try {
      await action();
      setError(null);
      await loadLines();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }

  if (loading) return <p className="text-neutral-500">Wczytywanie…</p>;
  if (!school)
    return (
      <DataCard title="Przydziały">
        <p className="text-neutral-700">Najpierw skonfiguruj szkołę na zakładce „Szkoła”.</p>
      </DataCard>
    );
  if (classes.length === 0 || subjects.length === 0)
    return (
      <DataCard title="Przydziały">
        <p className="text-neutral-700">
          Potrzebne są oddziały i przedmioty — dodaj je na zakładkach „Oddziały” i „Przedmioty i sale”.
        </p>
      </DataCard>
    );

  const subjectName = new Map(subjects.map((s) => [s.id, s.name]));
  const current = classes.find((c) => c.id === selected) ?? null;

  return (
    <div className="space-y-4">
      {confirmDialog}
      {error && <AlertBanner level="danger" message={error} onDismiss={() => setError(null)} />}

      <DataCard title="Oddział">
        <div className="flex flex-wrap gap-2">
          {classes.map((cls) => (
            <button
              key={cls.id}
              onClick={() => setSelected(cls.id)}
              className={`rounded px-3 py-1.5 text-sm ${
                cls.id === selected
                  ? "bg-primary-500 text-white"
                  : "border border-neutral-300 hover:bg-neutral-50"
              }`}
            >
              {cls.name}
            </button>
          ))}
        </div>
      </DataCard>

      {summary && (
        <DataCard title={`Podsumowanie — ${current?.name ?? ""}`}>
          <p className="text-neutral-700">
            Godzin zajętych przez oddział: <strong>{summary.occupiedHours}</strong>
            <span className="ml-2 text-sm text-neutral-500">
              (lekcja z podziałem na grupy liczy się raz)
            </span>
          </p>
          {summary.problems.length > 0 && (
            <ul className="mt-3 space-y-1">
              {summary.problems.map((problem) => (
                <li key={problem} className="text-sm text-warning-600">
                  ⚠ {problem}
                </li>
              ))}
            </ul>
          )}
        </DataCard>
      )}

      <DataCard
        title={`Przydziały — ${current?.name ?? ""}`}
        actions={
          <button
            className={plainButton}
            onClick={() =>
              run(() =>
                api.createLessonLine(school.id, {
                  classId: selected!,
                  subjectId: subjects[0].id,
                  audience: "WHOLE_CLASS",
                  kind: "BASE",
                  teacherId: null,
                  supportTeacherId: null,
                  blocks: [1],
                }),
              )
            }
          >
            Dodaj przydział
          </button>
        }
      >
        {lines.length === 0 ? (
          <p className="text-neutral-500">Ten oddział nie ma jeszcze przydziałów.</p>
        ) : (
          <table className="w-full text-left text-sm">
            <thead className="text-neutral-500">
              <tr>
                <th className="pb-2">Przedmiot</th>
                <th className="pb-2">Odbiorca</th>
                <th className="pb-2">Rodzaj</th>
                <th className="pb-2">Nauczyciel</th>
                <th className="pb-2">Wspomagający</th>
                <th className="pb-2">Bloki</th>
                <th className="pb-2">Godz.</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {lines.map((line) => (
                <LineRow
                  key={line.id}
                  line={line}
                  subjects={subjects}
                  teachers={teachers}
                  onSave={(input) => run(() => api.updateLessonLine(school.id, line.id, input))}
                  onDelete={() =>
                    confirm(
                      `Usunąć przydział „${subjectName.get(line.subjectId) ?? ""}”?`,
                      () => run(() => api.deleteLessonLine(line.id)),
                    )
                  }
                />
              ))}
            </tbody>
          </table>
        )}
        <p className="mt-3 text-sm text-neutral-500">
          Ten sam przedmiot może mieć jednocześnie lekcje z całą klasą i z podziałem na grupy —
          dodaj po prostu kolejny przydział. Godziny rozszerzone to drugi przydział tego samego
          przedmiotu, a nie osobny przedmiot.
        </p>
      </DataCard>
    </div>
  );
}

function LineRow(props: {
  line: LessonLine;
  subjects: Subject[];
  teachers: Teacher[];
  onSave: (input: LessonLineInput) => void;
  onDelete: () => void;
}) {
  const initial: LessonLineInput = {
    classId: props.line.classId,
    subjectId: props.line.subjectId,
    audience: props.line.audience,
    kind: props.line.kind,
    teacherId: props.line.teacherId,
    supportTeacherId: props.line.supportTeacherId,
    blocks: props.line.blocks,
  };
  const [draft, setDraft] = useState<LessonLineInput>(initial);
  const [blockText, setBlockText] = useState(formatBlocks(props.line.blocks));
  const dirty = JSON.stringify(draft) !== JSON.stringify(initial);

  const teacherOptions = (
    <>
      <option value="">—</option>
      {props.teachers.map((t) => (
        <option key={t.id} value={t.id}>
          {t.lastName} {t.firstName}
        </option>
      ))}
    </>
  );

  return (
    <tr className="border-t border-neutral-200">
      <td className="py-2 pr-2">
        <select
          className={field}
          value={draft.subjectId}
          onChange={(e) => setDraft({ ...draft, subjectId: e.target.value })}
        >
          {props.subjects.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
      </td>
      <td className="py-2 pr-2">
        <select
          className={field}
          value={draft.audience}
          onChange={(e) => setDraft({ ...draft, audience: e.target.value as LessonAudience })}
        >
          {AUDIENCES.map((a) => (
            <option key={a.value} value={a.value}>
              {a.label}
            </option>
          ))}
        </select>
      </td>
      <td className="py-2 pr-2">
        <select
          className={field}
          value={draft.kind}
          onChange={(e) => setDraft({ ...draft, kind: e.target.value as LessonKind })}
        >
          {KINDS.map((k) => (
            <option key={k.value} value={k.value}>
              {k.label}
            </option>
          ))}
        </select>
      </td>
      <td className="py-2 pr-2">
        <select
          className={field}
          value={draft.teacherId ?? ""}
          onChange={(e) => setDraft({ ...draft, teacherId: e.target.value || null })}
        >
          {teacherOptions}
        </select>
      </td>
      <td className="py-2 pr-2">
        <select
          className={field}
          value={draft.supportTeacherId ?? ""}
          onChange={(e) => setDraft({ ...draft, supportTeacherId: e.target.value || null })}
        >
          {teacherOptions}
        </select>
      </td>
      <td className="py-2 pr-2 w-28">
        <input
          className={field}
          value={blockText}
          placeholder="1, 1, 2"
          onChange={(e) => {
            setBlockText(e.target.value);
            setDraft({ ...draft, blocks: parseBlocks(e.target.value) });
          }}
        />
      </td>
      <td className="py-2 pr-2 text-neutral-700">{draft.blocks.reduce((a, b) => a + b, 0)}</td>
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
