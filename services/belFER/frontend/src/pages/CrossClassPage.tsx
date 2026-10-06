import { useCallback, useEffect, useState } from "react";
import { AlertBanner, DataCard } from "@delfin/ui";
import { useConfirm } from "../hooks/useConfirm";
import {
  api,
  type CrossClassGroupInput,
  type CrossClassUnit,
  type CrossClassUnitInput,
  type Room,
  type RoomKind,
  type School,
  type SchoolClass,
  type Subject,
  type Teacher,
} from "../api/client";

const field = "w-full rounded border border-neutral-300 px-2 py-1";
const plainButton = "rounded border border-neutral-300 px-3 py-1.5 text-sm hover:bg-neutral-50";

const parseBlocks = (text: string): number[] =>
  text.split(/[,\s]+/).map(Number).filter((n) => Number.isFinite(n) && n > 0);

export function CrossClassPage() {
  const [school, setSchool] = useState<School | null>(null);
  const [units, setUnits] = useState<CrossClassUnit[]>([]);
  const [classes, setClasses] = useState<SchoolClass[]>([]);
  const [subjects, setSubjects] = useState<Subject[]>([]);
  const [teachers, setTeachers] = useState<Teacher[]>([]);
  const [kinds, setKinds] = useState<RoomKind[]>([]);
  const [rooms, setRooms] = useState<Room[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [confirmDialog, confirm] = useConfirm();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const current = (await api.listSchools())[0] ?? null;
      setSchool(current);
      if (current) {
        const [u, c, s, t, k, r] = await Promise.all([
          api.listCrossClassUnits(current.id),
          api.listClasses(current.id),
          api.listSubjects(current.id),
          api.listTeachers(current.id),
          api.listRoomKinds(current.id),
          api.listRooms(current.id),
        ]);
        setUnits(u);
        setClasses(c);
        setSubjects(s);
        setTeachers(t);
        setKinds(k);
        setRooms(r);
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

  if (loading) return <p className="text-neutral-500">Wczytywanie…</p>;
  if (!school)
    return (
      <DataCard title="Zajęcia międzyoddziałowe">
        <p className="text-neutral-700">Najpierw skonfiguruj szkołę na zakładce „Szkoła”.</p>
      </DataCard>
    );
  if (classes.length === 0 || subjects.length === 0)
    return (
      <DataCard title="Zajęcia międzyoddziałowe">
        <p className="text-neutral-700">
          Potrzebne są oddziały i przedmioty — dodaj je na zakładkach „Oddziały” i „Przedmioty i sale”.
        </p>
      </DataCard>
    );

  const roomsPerKind = new Map(
    kinds.map((kind) => [kind.id, rooms.filter((r) => r.kinds.some((k) => k.id === kind.id)).length]),
  );

  return (
    <div className="space-y-4">
      {confirmDialog}
      {error && <AlertBanner level="danger" message={error} onDismiss={() => setError(null)} />}

      <DataCard
        title={`Zajęcia międzyoddziałowe (${units.length})`}
        actions={
          <button
            className={plainButton}
            onClick={() =>
              run(() =>
                api.createCrossClassUnit(school.id, {
                  subjectId: subjects[0].id,
                  name: `Zespół ${units.length + 1}`,
                  requiredRoomKindId: null,
                  blocks: [2],
                  groups: [{ label: "grupa 1", teacherId: null, classIds: [classes[0].id] }],
                }),
              )
            }
          >
            Dodaj zespół
          </button>
        }
      >
        <p className="text-sm text-neutral-500">
          Grupy jednego zespołu odbywają się w tym samym czasie, a wszystkie oddziały, z których
          pochodzą uczniowie, są wtedy zajęte. Dzięki temu nie trzeba wiedzieć, który uczeń jest w
          której grupie — wystarczą oddziały i liczba grup.
        </p>
      </DataCard>

      {units.map((unit) => (
        <CrossClassUnitCard
          key={unit.id}
          unit={unit}
          classes={classes}
          subjects={subjects}
          teachers={teachers}
          kinds={kinds}
          roomsPerKind={roomsPerKind}
          onSave={(input) => run(() => api.updateCrossClassUnit(school.id, unit.id, input))}
          onDelete={() =>
            confirm(`Usunąć zespół „${unit.name}”?`, () => run(() => api.deleteCrossClassUnit(unit.id)))
          }
        />
      ))}
    </div>
  );
}

function CrossClassUnitCard(props: {
  unit: CrossClassUnit;
  classes: SchoolClass[];
  subjects: Subject[];
  teachers: Teacher[];
  kinds: RoomKind[];
  roomsPerKind: Map<string, number>;
  onSave: (input: CrossClassUnitInput) => void;
  onDelete: () => void;
}) {
  const initial: CrossClassUnitInput = {
    subjectId: props.unit.subjectId,
    name: props.unit.name,
    requiredRoomKindId: props.unit.requiredRoomKindId,
    blocks: props.unit.blocks,
    groups: props.unit.groups.map((g) => ({
      label: g.label,
      teacherId: g.teacherId,
      classIds: g.classIds,
    })),
  };
  const [draft, setDraft] = useState<CrossClassUnitInput>(initial);
  const [blockText, setBlockText] = useState(props.unit.blocks.join(", "));
  const dirty = JSON.stringify(draft) !== JSON.stringify(initial);

  const available = draft.requiredRoomKindId
    ? props.roomsPerKind.get(draft.requiredRoomKindId)
    : undefined;
  const tooManyGroups = available !== undefined && draft.groups.length > available;

  const patchGroup = (index: number, patch: Partial<CrossClassGroupInput>) =>
    setDraft({
      ...draft,
      groups: draft.groups.map((g, i) => (i === index ? { ...g, ...patch } : g)),
    });

  const toggleClass = (index: number, classId: string) => {
    const group = draft.groups[index];
    patchGroup(index, {
      classIds: group.classIds.includes(classId)
        ? group.classIds.filter((c) => c !== classId)
        : [...group.classIds, classId],
    });
  };

  return (
    <DataCard
      title={props.unit.name}
      actions={
        <div className="flex gap-2">
          {dirty && (
            <button
              className="rounded bg-primary-500 px-3 py-1.5 text-sm text-white hover:bg-primary-600"
              onClick={() => props.onSave(draft)}
            >
              Zapisz
            </button>
          )}
          <button className="text-sm text-danger-500 hover:text-danger-600" onClick={props.onDelete}>
            Usuń zespół
          </button>
        </div>
      }
    >
      <div className="grid gap-3 sm:grid-cols-4">
        <div>
          <label className="block text-sm text-neutral-500">Nazwa</label>
          <input className={field} value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
        </div>
        <div>
          <label className="block text-sm text-neutral-500">Przedmiot</label>
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
        </div>
        <div>
          <label className="block text-sm text-neutral-500">Wymagany typ sali</label>
          <select
            className={field}
            value={draft.requiredRoomKindId ?? ""}
            onChange={(e) => setDraft({ ...draft, requiredRoomKindId: e.target.value || null })}
          >
            <option value="">—</option>
            {props.kinds.map((k) => (
              <option key={k.id} value={k.id}>
                {k.name} ({props.roomsPerKind.get(k.id) ?? 0})
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="block text-sm text-neutral-500">Bloki</label>
          <input
            className={field}
            value={blockText}
            placeholder="1, 2"
            onChange={(e) => {
              setBlockText(e.target.value);
              setDraft({ ...draft, blocks: parseBlocks(e.target.value) });
            }}
          />
        </div>
      </div>

      {tooManyGroups && (
        <p className="mt-3 text-sm text-warning-600">
          ⚠ Grup jest {draft.groups.length}, a sal tego typu tylko {available} — wszystkie grupy
          odbywają się jednocześnie.
        </p>
      )}

      <div className="mt-4 space-y-3">
        {draft.groups.map((group, index) => (
          <div key={index} className="rounded border border-neutral-200 p-3">
            <div className="flex flex-wrap items-end gap-3">
              <div className="min-w-[12rem] flex-1">
                <label className="block text-sm text-neutral-500">Nazwa grupy</label>
                <input
                  className={field}
                  value={group.label}
                  onChange={(e) => patchGroup(index, { label: e.target.value })}
                />
              </div>
              <div className="min-w-[12rem] flex-1">
                <label className="block text-sm text-neutral-500">Nauczyciel</label>
                <select
                  className={field}
                  value={group.teacherId ?? ""}
                  onChange={(e) => patchGroup(index, { teacherId: e.target.value || null })}
                >
                  <option value="">—</option>
                  {props.teachers.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.lastName} {t.firstName}
                    </option>
                  ))}
                </select>
              </div>
              <button
                className="text-sm text-danger-500 hover:text-danger-600"
                onClick={() =>
                  setDraft({ ...draft, groups: draft.groups.filter((_, i) => i !== index) })
                }
              >
                Usuń grupę
              </button>
            </div>
            <div className="mt-2">
              <span className="text-sm text-neutral-500">Uczniowie z oddziałów:</span>
              <div className="mt-1 flex flex-wrap gap-2">
                {props.classes.map((cls) => (
                  <label key={cls.id} className="flex items-center gap-1 text-sm">
                    <input
                      type="checkbox"
                      checked={group.classIds.includes(cls.id)}
                      onChange={() => toggleClass(index, cls.id)}
                    />
                    <span>{cls.name}</span>
                  </label>
                ))}
              </div>
            </div>
          </div>
        ))}

        <button
          className={plainButton}
          onClick={() =>
            setDraft({
              ...draft,
              groups: [
                ...draft.groups,
                { label: `grupa ${draft.groups.length + 1}`, teacherId: null, classIds: [] },
              ],
            })
          }
        >
          Dodaj grupę
        </button>
      </div>
    </DataCard>
  );
}
