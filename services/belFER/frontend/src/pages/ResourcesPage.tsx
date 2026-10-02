import { useCallback, useEffect, useState } from "react";
import { AlertBanner, DataCard } from "@delfin/ui";
import {
  api,
  type Room,
  type RoomInput,
  type RoomKind,
  type School,
  type Subject,
  type SubjectInput,
} from "../api/client";

const field = "w-full rounded border border-neutral-300 px-2 py-1";
const primaryButton = "rounded bg-primary-500 px-3 py-1.5 text-sm text-white hover:bg-primary-600";
const plainButton = "rounded border border-neutral-300 px-3 py-1.5 text-sm hover:bg-neutral-50";

export function ResourcesPage() {
  const [school, setSchool] = useState<School | null>(null);
  const [kinds, setKinds] = useState<RoomKind[]>([]);
  const [rooms, setRooms] = useState<Room[]>([]);
  const [subjects, setSubjects] = useState<Subject[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [newKind, setNewKind] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const current = (await api.listSchools())[0] ?? null;
      setSchool(current);
      if (current) {
        const [k, r, s] = await Promise.all([
          api.listRoomKinds(current.id),
          api.listRooms(current.id),
          api.listSubjects(current.id),
        ]);
        setKinds(k);
        setRooms(r);
        setSubjects(s);
      }
      setError(null);
    } catch (err) {
      report(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  function report(err: unknown) {
    setError(err instanceof Error ? err.message : String(err));
  }

  // Every mutation reloads rather than patching local state. At this scale the
  // extra request costs nothing, and it keeps the screen honest about what the
  // server actually stored — which matters when a write can be rejected.
  async function run(action: () => Promise<unknown>) {
    try {
      await action();
      setError(null);
      await load();
    } catch (err) {
      report(err);
    }
  }

  if (loading) return <p className="text-neutral-500">Wczytywanie…</p>;
  if (!school)
    return (
      <DataCard title="Przedmioty i sale">
        <p className="text-neutral-700">Najpierw skonfiguruj szkołę na zakładce „Szkoła”.</p>
      </DataCard>
    );

  const toInput = (room: Room): RoomInput => ({
    number: room.number,
    name: room.name,
    fitsWholeClass: room.fitsWholeClass,
    kindIds: room.kinds.map((k) => k.id),
  });

  const subjectToInput = (subject: Subject): SubjectInput => ({
    code: subject.code,
    name: subject.name,
    optional: subject.optional,
    requiredRoomKindId: subject.requiredRoomKind?.id ?? null,
    roomRequirementHard: subject.roomRequirementHard,
  });

  return (
    <div className="space-y-4">
      {error && <AlertBanner level="danger" message={error} onDismiss={() => setError(null)} />}

      <DataCard title="Typy sal">
        <div className="mb-3 flex gap-2">
          <input
            className={`${field} max-w-xs`}
            placeholder="np. Pracownia chemiczna"
            value={newKind}
            onChange={(e) => setNewKind(e.target.value)}
          />
          <button
            className={primaryButton}
            onClick={() =>
              run(async () => {
                await api.createRoomKind(school.id, newKind);
                setNewKind("");
              })
            }
          >
            Dodaj
          </button>
        </div>
        {kinds.length === 0 ? (
          <p className="text-neutral-500">Brak typów sal.</p>
        ) : (
          <ul className="divide-y divide-neutral-200">
            {kinds.map((kind) => (
              <li key={kind.id} className="flex items-center justify-between py-2">
                <span className="text-neutral-700">{kind.name}</span>
                <button
                  className="text-sm text-danger-500 hover:text-danger-600"
                  onClick={() => run(() => api.deleteRoomKind(kind.id))}
                >
                  Usuń
                </button>
              </li>
            ))}
          </ul>
        )}
      </DataCard>

      <DataCard
        title="Sale"
        actions={
          <button
            className={plainButton}
            onClick={() =>
              run(() =>
                api.createRoom(school.id, {
                  number: `nowa-${rooms.length + 1}`,
                  name: null,
                  fitsWholeClass: true,
                  kindIds: [],
                }),
              )
            }
          >
            Dodaj salę
          </button>
        }
      >
        {rooms.length === 0 ? (
          <p className="text-neutral-500">Brak sal.</p>
        ) : (
          <table className="w-full text-left text-sm">
            <thead className="text-neutral-500">
              <tr>
                <th className="pb-2">Numer</th>
                <th className="pb-2">Nazwa</th>
                <th className="pb-2">Typy</th>
                <th className="pb-2">Mieści klasę</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rooms.map((room) => (
                <RoomRow
                  key={room.id}
                  room={room}
                  kinds={kinds}
                  onSave={(input) => run(() => api.updateRoom(room.id, input))}
                  onDelete={() => run(() => api.deleteRoom(room.id))}
                  initial={toInput(room)}
                />
              ))}
            </tbody>
          </table>
        )}
      </DataCard>

      <DataCard
        title="Przedmioty"
        actions={
          <button
            className={plainButton}
            onClick={() =>
              run(() =>
                api.createSubject(school.id, {
                  code: `nowy-${subjects.length + 1}`,
                  name: "Nowy przedmiot",
                  optional: false,
                  requiredRoomKindId: null,
                  roomRequirementHard: false,
                }),
              )
            }
          >
            Dodaj przedmiot
          </button>
        }
      >
        {subjects.length === 0 ? (
          <p className="text-neutral-500">Brak przedmiotów.</p>
        ) : (
          <table className="w-full text-left text-sm">
            <thead className="text-neutral-500">
              <tr>
                <th className="pb-2">Kod</th>
                <th className="pb-2">Nazwa</th>
                <th className="pb-2">Nieobowiązkowy</th>
                <th className="pb-2">Wymagana sala</th>
                <th className="pb-2">Wymóg twardy</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {subjects.map((subject) => (
                <SubjectRow
                  key={subject.id}
                  kinds={kinds}
                  initial={subjectToInput(subject)}
                  onSave={(input) => run(() => api.updateSubject(subject.id, input))}
                  onDelete={() => run(() => api.deleteSubject(subject.id))}
                />
              ))}
            </tbody>
          </table>
        )}
        <p className="mt-3 text-sm text-neutral-500">
          Przedmiot nieobowiązkowy (religia, etyka) trafia na pierwszą lub ostatnią lekcję dnia.
          Wymóg sali jest domyślnie miękki — gdy brakuje pracowni, lekcja odbędzie się w zwykłej sali.
        </p>
      </DataCard>
    </div>
  );
}

function RoomRow(props: {
  room: Room;
  kinds: RoomKind[];
  initial: RoomInput;
  onSave: (input: RoomInput) => void;
  onDelete: () => void;
}) {
  const [draft, setDraft] = useState<RoomInput>(props.initial);
  const dirty = JSON.stringify(draft) !== JSON.stringify(props.initial);

  const toggleKind = (id: string) =>
    setDraft({
      ...draft,
      kindIds: draft.kindIds.includes(id)
        ? draft.kindIds.filter((k) => k !== id)
        : [...draft.kindIds, id],
    });

  return (
    <tr className="border-t border-neutral-200 align-top">
      <td className="py-2 pr-2">
        <input className={field} value={draft.number} onChange={(e) => setDraft({ ...draft, number: e.target.value })} />
      </td>
      <td className="py-2 pr-2">
        <input
          className={field}
          value={draft.name ?? ""}
          onChange={(e) => setDraft({ ...draft, name: e.target.value || null })}
        />
      </td>
      <td className="py-2 pr-2">
        <div className="flex flex-wrap gap-2">
          {props.kinds.length === 0 && <span className="text-neutral-400">—</span>}
          {props.kinds.map((kind) => (
            <label key={kind.id} className="flex items-center gap-1">
              <input
                type="checkbox"
                checked={draft.kindIds.includes(kind.id)}
                onChange={() => toggleKind(kind.id)}
              />
              <span>{kind.name}</span>
            </label>
          ))}
        </div>
      </td>
      <td className="py-2 pr-2">
        <input
          type="checkbox"
          checked={draft.fitsWholeClass}
          onChange={(e) => setDraft({ ...draft, fitsWholeClass: e.target.checked })}
        />
      </td>
      <td className="py-2 text-right whitespace-nowrap">
        {dirty && (
          <button className="mr-3 text-sm text-primary-500 hover:text-primary-600" onClick={() => props.onSave(draft)}>
            Zapisz
          </button>
        )}
        <button className="text-sm text-danger-500 hover:text-danger-600" onClick={props.onDelete}>
          Usuń
        </button>
      </td>
    </tr>
  );
}

function SubjectRow(props: {
  kinds: RoomKind[];
  initial: SubjectInput;
  onSave: (input: SubjectInput) => void;
  onDelete: () => void;
}) {
  const [draft, setDraft] = useState<SubjectInput>(props.initial);
  const dirty = JSON.stringify(draft) !== JSON.stringify(props.initial);

  return (
    <tr className="border-t border-neutral-200">
      <td className="py-2 pr-2">
        <input className={field} value={draft.code} onChange={(e) => setDraft({ ...draft, code: e.target.value })} />
      </td>
      <td className="py-2 pr-2">
        <input className={field} value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
      </td>
      <td className="py-2 pr-2">
        <input
          type="checkbox"
          checked={draft.optional}
          onChange={(e) => setDraft({ ...draft, optional: e.target.checked })}
        />
      </td>
      <td className="py-2 pr-2">
        <select
          className={field}
          value={draft.requiredRoomKindId ?? ""}
          onChange={(e) =>
            setDraft({
              ...draft,
              requiredRoomKindId: e.target.value || null,
              // A hard requirement with no kind cannot be satisfied, and the
              // backend rejects it — so clear it here rather than let the user
              // discover that on save.
              roomRequirementHard: e.target.value ? draft.roomRequirementHard : false,
            })
          }
        >
          <option value="">—</option>
          {props.kinds.map((kind) => (
            <option key={kind.id} value={kind.id}>
              {kind.name}
            </option>
          ))}
        </select>
      </td>
      <td className="py-2 pr-2">
        <input
          type="checkbox"
          disabled={!draft.requiredRoomKindId}
          checked={draft.roomRequirementHard}
          onChange={(e) => setDraft({ ...draft, roomRequirementHard: e.target.checked })}
        />
      </td>
      <td className="py-2 text-right whitespace-nowrap">
        {dirty && (
          <button className="mr-3 text-sm text-primary-500 hover:text-primary-600" onClick={() => props.onSave(draft)}>
            Zapisz
          </button>
        )}
        <button className="text-sm text-danger-500 hover:text-danger-600" onClick={props.onDelete}>
          Usuń
        </button>
      </td>
    </tr>
  );
}
