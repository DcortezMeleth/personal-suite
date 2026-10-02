import { useCallback, useEffect, useState } from "react";
import { AlertBanner, DataCard } from "@delfin/ui";
import {
  api,
  type School,
  type SchedulingSettings,
  type TimeSlot,
  type TimeSlotInput,
} from "../api/client";

// The backend speaks LocalTime, which serialises with seconds; <input type="time">
// wants HH:MM. Converting at the edge keeps the rest of the component unaware.
const toInput = (time: string) => time.slice(0, 5);
const toApi = (time: string) => (time.length === 5 ? `${time}:00` : time);

const NEW_SLOT: TimeSlotInput = { position: 0, startsAt: "08:00:00", endsAt: "08:45:00" };

// Positions come from row order, so a reordered or deleted row shows up as a
// change even though no individual field was touched.
const renumber = (slots: TimeSlotInput[]): TimeSlotInput[] =>
  slots.map((slot, index) => ({
    position: index + 1,
    startsAt: toApi(slot.startsAt),
    endsAt: toApi(slot.endsAt),
  }));

const fromServer = (slots: TimeSlot[]): TimeSlotInput[] =>
  slots.map((s) => ({ position: s.position, startsAt: s.startsAt, endsAt: s.endsAt }));

const field = "w-full rounded border border-neutral-300 px-3 py-2";
const label = "block text-sm font-medium text-neutral-700";

export function SchoolPage() {
  const [school, setSchool] = useState<School | null>(null);
  const [slots, setSlots] = useState<TimeSlotInput[]>([]);
  // What the server last told us. Everything dirty is measured against this,
  // so one button can know whether there is anything to send — and send only
  // the half that actually changed.
  const [savedSchool, setSavedSchool] = useState<School | null>(null);
  const [savedSlots, setSavedSlots] = useState<TimeSlotInput[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const current = (await api.listSchools())[0] ?? null;
      const currentSlots = current ? fromServer(await api.listTimeSlots(current.id)) : [];
      setSchool(current);
      setSavedSchool(current);
      setSlots(currentSlots);
      setSavedSlots(currentSlots);
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
    setSaved(null);
  }

  const schoolDirty = JSON.stringify(school) !== JSON.stringify(savedSchool);
  const slotsDirty = JSON.stringify(renumber(slots)) !== JSON.stringify(renumber(savedSlots));
  const dirty = schoolDirty || slotsDirty;

  async function save() {
    if (!school) return;
    try {
      // The bell schedule goes first because it is the half that can be
      // rejected. If it fails, nothing has been written — rather than leaving
      // the settings saved against a schedule that was not.
      if (slotsDirty) {
        const fresh = fromServer(await api.replaceTimeSlots(school.id, renumber(slots)));
        setSlots(fresh);
        setSavedSlots(fresh);
      }
      if (schoolDirty) {
        const updated = await api.updateSchool(school.id, {
          name: school.name,
          years: school.years,
          settings: school.settings,
        });
        setSchool(updated);
        setSavedSchool(updated);
      }
      setError(null);
      setSaved("Zapisano zmiany");
    } catch (err) {
      report(err);
    }
  }

  async function createSchool() {
    try {
      await api.createSchool("Nowa szkoła", 4);
      await load();
    } catch (err) {
      report(err);
    }
  }

  const patchSettings = (patch: Partial<SchedulingSettings>) =>
    setSchool((prev) => (prev ? { ...prev, settings: { ...prev.settings, ...patch } } : prev));

  const patchSlot = (index: number, patch: Partial<TimeSlotInput>) =>
    setSlots((prev) => prev.map((slot, i) => (i === index ? { ...slot, ...patch } : slot)));

  if (loading) return <p className="text-neutral-500">Wczytywanie…</p>;

  if (!school) {
    return (
      <div className="space-y-4">
        {error && <AlertBanner level="danger" message={error} />}
        <DataCard title="Szkoła">
          <p className="mb-4 text-neutral-700">Nie skonfigurowano jeszcze żadnej szkoły.</p>
          <button
            onClick={createSchool}
            className="rounded bg-primary-500 px-4 py-2 text-white hover:bg-primary-600"
          >
            Utwórz szkołę
          </button>
        </DataCard>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {error && <AlertBanner level="danger" message={error} onDismiss={() => setError(null)} />}
      {saved && !dirty && (
        <AlertBanner level="success" message={saved} onDismiss={() => setSaved(null)} />
      )}

      {/* One save for the page. The settings below belong to the same record as
          the name above, so a button per card would have meant two buttons
          writing the same thing. */}
      <div className="flex items-center justify-end gap-3">
        {dirty && <span className="text-sm text-neutral-500">Niezapisane zmiany</span>}
        <button
          onClick={save}
          disabled={!dirty}
          className="rounded bg-primary-500 px-4 py-2 text-white hover:bg-primary-600 disabled:cursor-not-allowed disabled:bg-neutral-300"
        >
          Zapisz zmiany
        </button>
      </div>

      <DataCard title="Szkoła">
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <label className={label}>Nazwa</label>
            <input
              className={field}
              value={school.name}
              onChange={(e) => setSchool({ ...school, name: e.target.value })}
            />
          </div>
          <div>
            <label className={label}>Liczba roczników</label>
            <input
              type="number"
              min={1}
              max={12}
              className={field}
              value={school.years}
              onChange={(e) => setSchool({ ...school, years: Number(e.target.value) })}
            />
          </div>
        </div>
      </DataCard>

      <DataCard title="Zasady układania planu">
        <div className="space-y-4">
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={school.settings.allowClassGaps}
              onChange={(e) => patchSettings({ allowClassGaps: e.target.checked })}
            />
            <span className="text-neutral-700">Pozwól na okienka w planie klasy</span>
          </label>

          <div className="grid gap-4 sm:grid-cols-3">
            <div>
              <label className={label}>Maks. okienek pod rząd (nauczyciel)</label>
              <input
                type="number"
                min={0}
                className={field}
                value={school.settings.maxConsecutiveTeacherGaps}
                onChange={(e) => patchSettings({ maxConsecutiveTeacherGaps: Number(e.target.value) })}
              />
            </div>
            <div>
              <label className={label}>Domyślne dni pracy</label>
              <input
                type="number"
                min={1}
                max={7}
                className={field}
                value={school.settings.defaultTeacherMaxWorkingDays}
                onChange={(e) =>
                  patchSettings({ defaultTeacherMaxWorkingDays: Number(e.target.value) })
                }
              />
            </div>
            <div>
              <label className={label}>Domyślny limit lekcji dziennie</label>
              <input
                type="number"
                min={1}
                className={field}
                value={school.settings.defaultTeacherMaxLessonsPerDay}
                onChange={(e) =>
                  patchSettings({ defaultTeacherMaxLessonsPerDay: Number(e.target.value) })
                }
              />
            </div>
          </div>
          <p className="text-sm text-neutral-500">
            Maksymalna liczba lekcji dziennie dla klasy wynika z liczby lekcji w planie dzwonków
            ({slots.length}) — nie ustawia się jej osobno.
          </p>
        </div>
      </DataCard>

      <DataCard
        title="Plan dzwonków"
        actions={
          <button
            onClick={() => setSlots([...slots, { ...NEW_SLOT }])}
            className="rounded border border-neutral-300 px-3 py-1.5 text-sm hover:bg-neutral-50"
          >
            Dodaj lekcję
          </button>
        }
      >
        {slots.length === 0 ? (
          <p className="text-neutral-500">Nie dodano jeszcze żadnej lekcji.</p>
        ) : (
          <table className="w-full text-left">
            <thead>
              <tr className="text-sm text-neutral-500">
                <th className="pb-2">Lekcja</th>
                <th className="pb-2">Od</th>
                <th className="pb-2">Do</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {slots.map((slot, index) => (
                <tr key={index} className="border-t border-neutral-200">
                  <td className="py-2 pr-4 text-neutral-700">{index + 1}</td>
                  <td className="py-2 pr-4">
                    <input
                      type="time"
                      className="rounded border border-neutral-300 px-2 py-1"
                      value={toInput(slot.startsAt)}
                      onChange={(e) => patchSlot(index, { startsAt: toApi(e.target.value) })}
                    />
                  </td>
                  <td className="py-2 pr-4">
                    <input
                      type="time"
                      className="rounded border border-neutral-300 px-2 py-1"
                      value={toInput(slot.endsAt)}
                      onChange={(e) => patchSlot(index, { endsAt: toApi(e.target.value) })}
                    />
                  </td>
                  <td className="py-2 text-right">
                    <button
                      onClick={() => setSlots(slots.filter((_, i) => i !== index))}
                      className="text-sm text-danger-500 hover:text-danger-600"
                    >
                      Usuń
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </DataCard>
    </div>
  );
}
