import { useCallback, useEffect, useMemo, useState } from "react";
import { AlertBanner, DataCard } from "@delfin/ui";
import { api, type Generated, type PlanEntry, type Timetable } from "../api/client";
import { useSchool } from "../hooks/useSchool";
import { NoSchool } from "../components/NoSchool";
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
  const [plan, setPlan] = useState<Timetable | null>(null);
  const [view, setView] = useState<View>("oddzial");
  const [who, setWho] = useState<string>("");
  const [missing, setMissing] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [outcome, setOutcome] = useState<Generated | null>(null);

  const { school, error, setError, reload, gate } = useSchool({
    title: "Plan lekcji",
    load: async (school) => {
      try {
        setPlan(await api.currentPlan(school.id));
        setMissing(false);
      } catch {
        // No plan yet is an ordinary state, not a failure: the school has
        // simply not generated one.
        setPlan(null);
        setMissing(true);
      }
    },
  });

  // This waits rather than polling a job. Laying out the real school has
  // taken between thirty-five seconds and two minutes, so the wait is long
  // enough to need saying out loud — hence the button's own busy state — and
  // the proxy in front of this has a matching timeout. Asking FET to optimise
  // as well as satisfy is what will make polling unavoidable.
  const generate = useCallback(async () => {
    if (!school) return;
    setGenerating(true);
    setOutcome(null);
    try {
      const result = await api.generatePlan(school.id);
      setOutcome(result);
      setError(null);
      // A saved plan is the current one, so the grid below has to catch up.
      if (result.status === "saved") await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setGenerating(false);
    }
  }, [school, reload, setError]);

  const generateButton = (
    <button
      type="button"
      onClick={generate}
      disabled={generating}
      className="rounded bg-primary-500 px-4 py-2 text-white hover:bg-primary-600 disabled:opacity-60"
    >
      {generating ? "Układanie…" : plan ? "Ułóż plan na nowo" : "Ułóż plan"}
    </button>
  );

  const outcomeBanner = outcome && (
    outcome.status === "saved" ? (
      <AlertBanner
        level="success"
        message={`Ułożono w ${(outcome.tookMillis / 1000).toFixed(1)} s — ${outcome.placed} zajęć, ` +
          `${outcome.rulesKept} z ${outcome.rulesTotal} reguł spełnionych.`}
        onDismiss={() => setOutcome(null)}
      />
    ) : (
      <AlertBanner
        level="danger"
        message={`Ułożony plan łamie reguły, więc go nie zapisano: ` +
          outcome.broken.map((r) => `${r.code} ${r.what}${r.detail ? ` (${r.detail})` : ""}`).join("; ") +
          (outcome.unplaced > 0 ? ` — ${outcome.unplaced} zajęć bez godziny.` : ".")}
        onDismiss={() => setOutcome(null)}
      />
    )
  );

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

  if (gate) return gate;
  if (!school) return <NoSchool title="Plan lekcji" />;

  if (missing)
    return (
      <div className="space-y-4">
        {error && <AlertBanner level="danger" message={error} onDismiss={() => setError(null)} />}
        {outcomeBanner}
        <DataCard title="Plan lekcji">
          <p className="mb-4 text-neutral-700">
            Nie ma jeszcze ułożonego planu.
          </p>
          {generateButton}
          <p className="mt-3 text-xs text-neutral-500">
            Plan jest sprawdzany względem reguł, zanim zostanie zapisany. Taki,
            który je łamie, zostanie pokazany, ale nie stanie się planem szkoły.
          </p>
        </DataCard>
      </div>
    );

  if (!plan) return null;

  const kept = plan.rulesKept ?? 0;
  const total = plan.rulesTotal ?? 0;

  return (
    <div className="space-y-4">
      {error && <AlertBanner level="danger" message={error} onDismiss={() => setError(null)} />}

      {plan.unresolved > 0 && (
        <AlertBanner
          level="warning"
          message={`${plan.unresolved} zajęć z tego planu odnosi się do przydziałów, których już nie ma — nie widać ich poniżej. Ułóż plan ponownie.`}
        />
      )}

      {outcomeBanner}

      <DataCard title={plan.name} actions={generateButton}>
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
