import { useCallback, useEffect, useMemo, useState } from "react";
import { AlertBanner, DataCard } from "@delfin/ui";
import { api, type PlanEntry, type School, type Timetable } from "../api/client";
import { TimetableGrid } from "../components/TimetableGrid";

/**
 * The plan, to be looked at.
 *
 * Four ways of reading one week — by class, by teacher, by room, and all of it
 * at once. The school asked for every one of them, and they are the same
 * lessons sliced differently, so the page is a picker and a grid rather than
 * four pages.
 */

type View = "oddzial" | "nauczyciel" | "sala" | "szkola";

const VIEWS: { id: View; label: string }[] = [
  { id: "oddzial", label: "Oddziały" },
  { id: "nauczyciel", label: "Nauczyciele" },
  { id: "sala", label: "Sale" },
  { id: "szkola", label: "Cała szkoła" },
];

export function PlanPage() {
  const [school, setSchool] = useState<School | null>(null);
  const [plan, setPlan] = useState<Timetable | null>(null);
  const [view, setView] = useState<View>("oddzial");
  const [who, setWho] = useState<string>("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [missing, setMissing] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const current = (await api.listSchools())[0] ?? null;
      setSchool(current);
      if (current) {
        try {
          setPlan(await api.currentPlan(current.id));
          setMissing(false);
        } catch {
          // No plan yet is an ordinary state, not a failure: the school has
          // simply not generated one.
          setPlan(null);
          setMissing(true);
        }
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

  // Whoever the view is about: a class, a teacher, a room. Kept valid as the
  // view changes, so switching never lands on an empty grid.
  const subjects = useMemo(() => {
    if (!plan) return [];
    if (view === "oddzial") return plan.classes;
    if (view === "nauczyciel") return plan.teachers;
    if (view === "sala") return plan.rooms;
    return [];
  }, [plan, view]);

  useEffect(() => {
    if (subjects.length > 0 && !subjects.includes(who)) setWho(subjects[0]);
  }, [subjects, who]);

  const shown = useMemo<PlanEntry[]>(() => {
    if (!plan) return [];
    if (view === "oddzial") return plan.entries.filter((e) => e.classes.includes(who));
    if (view === "nauczyciel") return plan.entries.filter((e) => e.teachers.includes(who));
    if (view === "sala") return plan.entries.filter((e) => e.room === who);
    return plan.entries;
  }, [plan, view, who]);

  const caption = useCallback(
    (entry: PlanEntry) => {
      if (view === "oddzial")
        return [entry.teachers.join(", "), entry.room].filter(Boolean).join(" · ");
      if (view === "nauczyciel")
        return [entry.individual ? "nauczanie indywidualne" : entry.classes.join(", "), entry.room]
          .filter(Boolean)
          .join(" · ");
      if (view === "sala")
        return [entry.classes.join(", "), entry.teachers.join(", ")].filter(Boolean).join(" · ");
      return [entry.classes.join(", "), entry.room].filter(Boolean).join(" · ");
    },
    [view],
  );

  if (loading) return <p className="text-neutral-500">Wczytywanie…</p>;

  if (!school)
    return (
      <DataCard title="Plan lekcji">
        <p className="text-neutral-700">Najpierw skonfiguruj szkołę na zakładce „Szkoła”.</p>
      </DataCard>
    );

  if (missing)
    return (
      <DataCard title="Plan lekcji">
        <p className="text-neutral-700">
          Nie ma jeszcze ułożonego planu. Gdy powstanie, pojawi się tutaj.
        </p>
      </DataCard>
    );

  if (!plan) return null;

  const kept = plan.rulesKept ?? 0;
  const total = plan.rulesTotal ?? 0;

  return (
    <div className="space-y-4">
      {error && <AlertBanner level="danger" message={error} onDismiss={() => setError(null)} />}

      <DataCard title={plan.name}>
        <div className="flex flex-wrap items-center gap-x-6 gap-y-2 text-sm text-neutral-600">
          <span>{plan.entries.length} zajęć</span>
          {total > 0 && (
            <span className={kept === total ? "text-emerald-700" : "text-danger-600"}>
              {kept === total ? "spełnia wszystkie reguły" : `reguły: ${kept}/${total}`}
            </span>
          )}
          <span>ułożony {new Date(plan.createdAt).toLocaleString("pl-PL")}</span>
        </div>
      </DataCard>

      <div className="flex flex-wrap items-center gap-3">
        {/* The four views, as tabs: switching between them is the main thing
            anybody does on this page. */}
        <div className="flex gap-1 rounded-md bg-neutral-100 p-1">
          {VIEWS.map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={() => setView(item.id)}
              className={[
                "rounded px-3 py-1 text-sm",
                view === item.id
                  ? "bg-white font-medium text-neutral-900 shadow-sm"
                  : "text-neutral-600 hover:text-neutral-900",
              ].join(" ")}
            >
              {item.label}
            </button>
          ))}
        </div>

        {subjects.length > 0 && (
          <select
            value={who}
            onChange={(event) => setWho(event.target.value)}
            className="rounded-md border border-neutral-300 px-3 py-1.5 text-sm"
          >
            {subjects.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        )}
      </div>

      <DataCard title={view === "szkola" ? "Cała szkoła" : who}>
        {shown.length === 0 ? (
          <p className="text-sm text-neutral-500">Nic tu nie ma w tym tygodniu.</p>
        ) : (
          <TimetableGrid
            days={plan.days}
            slotsPerDay={plan.slotsPerDay}
            entries={shown}
            caption={caption}
          />
        )}
      </DataCard>

      <p className="text-xs text-neutral-500">
        Zielony pasek po lewej to pierwsza grupa, fioletowy — druga. Kursywą zaznaczone jest
        nauczanie indywidualne, które nie zajmuje oddziału.
      </p>
    </div>
  );
}
