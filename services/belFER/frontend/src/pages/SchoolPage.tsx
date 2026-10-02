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

export function SchoolPage() {
  const [school, setSchool] = useState<School | null>(null);
  const [slots, setSlots] = useState<TimeSlotInput[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const schools = await api.listSchools();
      const current = schools[0] ?? null;
      setSchool(current);
      setSlots(current ? await api.listTimeSlots(current.id) : []);
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

  const report = (err: unknown) =>
    setError(err instanceof Error ? err.message : String(err));

  const announce = (message: string) => {
    setSaved(message);
    setError(null);
  };

  async function createSchool() {
    try {
      announce("");
      setSchool(await api.createSchool("Nowa szkoła", 4));
      await load();
    } catch (err) {
      report(err);
    }
  }

  async function saveSchool() {
    if (!school) return;
    try {
      const updated = await api.updateSchool(school.id, {
        name: school.name,
        years: school.years,
        settings: school.settings,
      });
      setSchool(updated);
      announce("Zapisano ustawienia szkoły");
    } catch (err) {
      report(err);
    }
  }

  async function saveSlots() {
    if (!school) return;
    try {
      // Positions are renumbered from the row order, so the user never has to
      // keep them consistent by hand — the backend rejects holes outright.
      const renumbered = slots.map((slot, index) => ({
        position: index + 1,
        startsAt: toApi(slot.startsAt),
        endsAt: toApi(slot.endsAt),
      }));
      const fresh: TimeSlot[] = await api.replaceTimeSlots(school.id, renumbered);
      setSlots(fresh);
      announce("Zapisano plan dzwonków");
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

  const field = "w-full rounded border border-neutral-300 px-3 py-2";
  const label = "block text-sm font-medium text-neutral-700";

  return (
    <div className="space-y-4">
      {error && <AlertBanner level="danger" message={error} onDismiss={() => setError(null)} />}
      {saved && <AlertBanner level="success" message={saved} onDismiss={() => setSaved(null)} />}

      <DataCard
        title="Szkoła"
        actions={
          <button
            onClick={saveSchool}
            className="rounded bg-primary-500 px-3 py-1.5 text-sm text-white hover:bg-primary-600"
          >
            Zapisz
          </button>
        }
      >
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
          <div className="flex gap-2">
            <button
              onClick={() => setSlots([...slots, { ...NEW_SLOT }])}
              className="rounded border border-neutral-300 px-3 py-1.5 text-sm hover:bg-neutral-50"
            >
              Dodaj lekcję
            </button>
            <button
              onClick={saveSlots}
              className="rounded bg-primary-500 px-3 py-1.5 text-sm text-white hover:bg-primary-600"
            >
              Zapisz
            </button>
          </div>
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
