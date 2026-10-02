import { useCallback, useEffect, useMemo, useState } from "react";
import { AlertBanner, DataCard } from "@delfin/ui";
import { useConfirm } from "../hooks/useConfirm";
import {
  api,
  type Room,
  type School,
  type Subject,
  type Teacher,
  type TeacherInput,
  type UnavailabilityBlock,
} from "../api/client";

const DAYS = ["Pn", "Wt", "Śr", "Cz", "Pt"];

const field = "w-full rounded border border-neutral-300 px-2 py-1";
const plainButton = "rounded border border-neutral-300 px-3 py-1.5 text-sm hover:bg-neutral-50";

// The grid the user clicks and the ranges the backend stores are two views of
// the same thing. Converting at the edges means neither side has to know about
// the other's shape.
const cellKey = (day: number, position: number) => `${day}:${position}`;

function blocksToCells(blocks: UnavailabilityBlock[]): Set<string> {
  const cells = new Set<string>();
  for (const block of blocks) {
    for (let p = block.fromPosition; p <= block.toPosition; p++) {
      cells.add(cellKey(block.dayOfWeek, p));
    }
  }
  return cells;
}

function cellsToBlocks(cells: Set<string>, slotCount: number): UnavailabilityBlock[] {
  const blocks: UnavailabilityBlock[] = [];
  for (let day = 1; day <= DAYS.length; day++) {
    let start: number | null = null;
    // One past the end, so a run reaching the last lesson still gets closed.
    for (let p = 1; p <= slotCount + 1; p++) {
      const blocked = p <= slotCount && cells.has(cellKey(day, p));
      if (blocked && start === null) start = p;
      if (!blocked && start !== null) {
        blocks.push({ dayOfWeek: day, fromPosition: start, toPosition: p - 1 });
        start = null;
      }
    }
  }
  return blocks;
}

export function TeachersPage() {
  const [school, setSchool] = useState<School | null>(null);
  const [teachers, setTeachers] = useState<Teacher[]>([]);
  const [subjects, setSubjects] = useState<Subject[]>([]);
  const [rooms, setRooms] = useState<Room[]>([]);
  const [slotCount, setSlotCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState("");
  const [expanded, setExpanded] = useState<string | null>(null);
  const [confirmDialog, confirm] = useConfirm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const current = (await api.listSchools())[0] ?? null;
      setSchool(current);
      if (current) {
        const [t, s, r, slots] = await Promise.all([
          api.listTeachers(current.id),
          api.listSubjects(current.id),
          api.listRooms(current.id),
          api.listTimeSlots(current.id),
        ]);
        setTeachers(t);
        setSubjects(s);
        setRooms(r);
        setSlotCount(slots.length);
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
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }

  const visible = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) return teachers;
    return teachers.filter((t) =>
      `${t.code} ${t.firstName} ${t.lastName}`.toLowerCase().includes(needle),
    );
  }, [teachers, filter]);

  if (loading) return <p className="text-neutral-500">Wczytywanie…</p>;
  if (!school)
    return (
      <DataCard title="Nauczyciele">
        <p className="text-neutral-700">Najpierw skonfiguruj szkołę na zakładce „Szkoła”.</p>
      </DataCard>
    );

  return (
    <div className="space-y-4">
      {confirmDialog}
      {error && <AlertBanner level="danger" message={error} onDismiss={() => setError(null)} />}

      <DataCard
        title={`Nauczyciele (${teachers.length})`}
        actions={
          <div className="flex items-center gap-2">
            <input
              className="rounded border border-neutral-300 px-2 py-1 text-sm"
              placeholder="Szukaj…"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            />
            <button
              className={plainButton}
              onClick={() =>
                run(() =>
                  api.createTeacher(school.id, {
                    code: `N${teachers.length + 1}`,
                    firstName: "Imię",
                    lastName: "Nazwisko",
                    homeRoomId: null,
                    subjectIds: [],
                    maxWorkingDays: null,
                    maxLessonsPerDay: null,
                    pensum: null,
                    unavailability: [],
                  }),
                )
              }
            >
              Dodaj nauczyciela
            </button>
          </div>
        }
      >
        {visible.length === 0 ? (
          <p className="text-neutral-500">
            {teachers.length === 0 ? "Brak nauczycieli." : "Nic nie pasuje do wyszukiwania."}
          </p>
        ) : (
          <table className="w-full text-left text-sm">
            <thead className="text-neutral-500">
              <tr>
                <th className="pb-2">Kod</th>
                <th className="pb-2">Imię</th>
                <th className="pb-2">Nazwisko</th>
                <th className="pb-2">Sala własna</th>
                <th className="pb-2">Dni</th>
                <th className="pb-2">Lekcji/dzień</th>
                <th className="pb-2">Pensum</th>
                <th className="pb-2">Przedmioty</th>
                <th className="pb-2">Niedostępność</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {visible.map((teacher) => (
                <TeacherRow
                  key={teacher.id}
                  teacher={teacher}
                  rooms={rooms}
                  subjects={subjects}
                  slotCount={slotCount}
                  school={school}
                  expanded={expanded === teacher.id}
                  onToggleExpand={() => setExpanded(expanded === teacher.id ? null : teacher.id)}
                  onSave={(input) => run(() => api.updateTeacher(school.id, teacher.id, input))}
                  onDelete={() =>
                    confirm(
                      `Usunąć nauczyciela ${teacher.firstName} ${teacher.lastName}? ` +
                        "Zablokowane godziny i przypisane przedmioty zostaną usunięte razem z nim.",
                      () => run(() => api.deleteTeacher(teacher.id)),
                    )
                  }
                />
              ))}
            </tbody>
          </table>
        )}
        <p className="mt-3 text-sm text-neutral-500">
          Puste pola „Dni” i „Lekcji/dzień” oznaczają wartość domyślną szkoły
          ({school.settings.defaultTeacherMaxWorkingDays} i {school.settings.defaultTeacherMaxLessonsPerDay}).
        </p>
      </DataCard>
    </div>
  );
}

function TeacherRow(props: {
  teacher: Teacher;
  rooms: Room[];
  subjects: Subject[];
  slotCount: number;
  school: School;
  expanded: boolean;
  onToggleExpand: () => void;
  onSave: (input: TeacherInput) => void;
  onDelete: () => void;
}) {
  const initial: TeacherInput = {
    code: props.teacher.code,
    firstName: props.teacher.firstName,
    lastName: props.teacher.lastName,
    homeRoomId: props.teacher.homeRoomId,
    subjectIds: props.teacher.subjectIds,
    maxWorkingDays: props.teacher.maxWorkingDays,
    maxLessonsPerDay: props.teacher.maxLessonsPerDay,
    pensum: props.teacher.pensum,
    unavailability: props.teacher.unavailability,
  };
  const [draft, setDraft] = useState<TeacherInput>(initial);
  const cells = blocksToCells(draft.unavailability);
  const dirty = JSON.stringify(draft) !== JSON.stringify(initial);
  const blockedCount = cells.size;

  const toggleCell = (day: number, position: number) => {
    const next = new Set(cells);
    const key = cellKey(day, position);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    setDraft({ ...draft, unavailability: cellsToBlocks(next, props.slotCount) });
  };

  const toggleSubject = (id: string) =>
    setDraft({
      ...draft,
      subjectIds: draft.subjectIds.includes(id)
        ? draft.subjectIds.filter((s) => s !== id)
        : [...draft.subjectIds, id],
    });

  const num = (value: string): number | null => (value === "" ? null : Number(value));

  return (
    <>
      <tr className="border-t border-neutral-200">
        <td className="py-2 pr-2 w-20">
          <input className={field} value={draft.code} onChange={(e) => setDraft({ ...draft, code: e.target.value })} />
        </td>
        <td className="py-2 pr-2">
          <input className={field} value={draft.firstName} onChange={(e) => setDraft({ ...draft, firstName: e.target.value })} />
        </td>
        <td className="py-2 pr-2">
          <input className={field} value={draft.lastName} onChange={(e) => setDraft({ ...draft, lastName: e.target.value })} />
        </td>
        <td className="py-2 pr-2">
          <select
            className={field}
            value={draft.homeRoomId ?? ""}
            onChange={(e) => setDraft({ ...draft, homeRoomId: e.target.value || null })}
          >
            <option value="">—</option>
            {props.rooms.map((room) => (
              <option key={room.id} value={room.id}>
                {room.number}
              </option>
            ))}
          </select>
        </td>
        <td className="py-2 pr-2 w-20">
          <input
            type="number"
            min={1}
            max={7}
            className={field}
            placeholder={String(props.school.settings.defaultTeacherMaxWorkingDays)}
            value={draft.maxWorkingDays ?? ""}
            onChange={(e) => setDraft({ ...draft, maxWorkingDays: num(e.target.value) })}
          />
        </td>
        <td className="py-2 pr-2 w-24">
          <input
            type="number"
            min={1}
            className={field}
            placeholder={String(props.school.settings.defaultTeacherMaxLessonsPerDay)}
            value={draft.maxLessonsPerDay ?? ""}
            onChange={(e) => setDraft({ ...draft, maxLessonsPerDay: num(e.target.value) })}
          />
        </td>
        <td className="py-2 pr-2 w-20">
          <input
            type="number"
            min={0}
            className={field}
            value={draft.pensum ?? ""}
            onChange={(e) => setDraft({ ...draft, pensum: num(e.target.value) })}
          />
        </td>
        <td className="py-2 pr-2">
          <button className="text-primary-500 hover:text-primary-600" onClick={props.onToggleExpand}>
            {draft.subjectIds.length} wybrano
          </button>
        </td>
        <td className="py-2 pr-2">
          <button className="text-primary-500 hover:text-primary-600" onClick={props.onToggleExpand}>
            {blockedCount === 0 ? "pełna dostępność" : `${blockedCount} godz.`}
          </button>
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

      {props.expanded && (
        <tr className="border-t border-neutral-100 bg-neutral-50">
          <td colSpan={10} className="px-2 py-4">
            <div className="grid gap-6 lg:grid-cols-2">
              <div>
                <h4 className="mb-2 font-medium text-neutral-700">Uczy przedmiotów</h4>
                {props.subjects.length === 0 ? (
                  <p className="text-neutral-500">Brak przedmiotów — dodaj je na zakładce „Przedmioty i sale”.</p>
                ) : (
                  <div className="flex flex-wrap gap-x-4 gap-y-1">
                    {props.subjects.map((subject) => (
                      <label key={subject.id} className="flex items-center gap-1">
                        <input
                          type="checkbox"
                          checked={draft.subjectIds.includes(subject.id)}
                          onChange={() => toggleSubject(subject.id)}
                        />
                        <span>{subject.name}</span>
                      </label>
                    ))}
                  </div>
                )}
              </div>

              <div>
                <h4 className="mb-2 font-medium text-neutral-700">Godziny niedostępności</h4>
                {props.slotCount === 0 ? (
                  <p className="text-neutral-500">Najpierw ustaw plan dzwonków na zakładce „Szkoła”.</p>
                ) : (
                  <>
                    {/* Clicking a cell beats typing ranges: this is the thing the
                        school's current software makes hardest. */}
                    <table className="border-collapse">
                      <thead>
                        <tr>
                          <th className="w-8" />
                          {DAYS.map((day) => (
                            <th key={day} className="px-2 pb-1 text-xs font-medium text-neutral-500">
                              {day}
                            </th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {Array.from({ length: props.slotCount }, (_, i) => i + 1).map((position) => (
                          <tr key={position}>
                            <td className="pr-2 text-right text-xs text-neutral-500">{position}</td>
                            {DAYS.map((_, dayIndex) => {
                              const day = dayIndex + 1;
                              const blocked = cells.has(cellKey(day, position));
                              return (
                                <td key={day} className="p-0.5">
                                  <button
                                    aria-label={`${DAYS[dayIndex]} lekcja ${position}`}
                                    onClick={() => toggleCell(day, position)}
                                    className={`h-7 w-12 rounded border text-xs ${
                                      blocked
                                        ? "border-danger-500 bg-danger-50 text-danger-600"
                                        : "border-neutral-200 bg-white hover:bg-neutral-100"
                                    }`}
                                  >
                                    {blocked ? "✕" : ""}
                                  </button>
                                </td>
                              );
                            })}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    <p className="mt-2 text-xs text-neutral-500">
                      Zaznaczone godziny są wyłączone z planowania — np. gdy nauczyciel uczy wtedy w innej szkole.
                    </p>
                  </>
                )}
              </div>
            </div>
          </td>
        </tr>
      )}
    </>
  );
}
