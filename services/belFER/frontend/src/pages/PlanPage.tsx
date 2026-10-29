import { useCallback, useEffect, useMemo, useState } from "react";
import { AlertBanner, DataCard } from "@delfin/ui";
import { api, type Generation, type PlanEntry, type Timetable } from "../api/client";
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
  const [run, setRun] = useState<Generation | null>(null);
  const [elapsed, setElapsed] = useState(0);

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

  const running = run?.status === "running";

  // Ask once, so a page opened — or reloaded — while a run is going finds it
  // rather than losing it.
  useEffect(() => {
    if (!school) return;
    let live = true;
    api
      .generationStatus(school.id)
      .then((latest) => {
        if (live) setRun(latest);
      })
      // No run has ever been started for this school, which is not news.
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [school]);

  // Poll only while something is actually running. Keyed on the status rather
  // than started and stopped by hand, so there is no state in which a timer
  // outlives what it was watching — a school that has never generated must
  // not end up asking after a run that does not exist, every two seconds,
  // forever.
  useEffect(() => {
    if (!school || run?.status !== "running") return;
    let live = true;
    const timer = window.setInterval(async () => {
      try {
        const latest = await api.generationStatus(school.id);
        if (live) setRun(latest);
      } catch {
        // A blip while it works is not worth abandoning the run over.
      }
    }, 2000);
    return () => {
      live = false;
      window.clearInterval(timer);
    };
  }, [school, run?.status]);

  // A plain second counter while it works, because a minute of nothing
  // happening looks the same as a minute of being broken.
  useEffect(() => {
    if (!running || !run) return;
    const started = new Date(run.startedAt).getTime();
    const tick = () => setElapsed(Math.max(0, Math.round((Date.now() - started) / 1000)));
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, [running, run]);

  // A finished run that saved a plan is the current one, so the grid below
  // has to catch up with it.
  const settled = run && run.status !== "running" ? run.finishedAt : null;
  useEffect(() => {
    if (settled && run?.status === "saved") void reload();
  }, [settled, run?.status, reload]);

  const generate = useCallback(async () => {
    if (!school) return;
    try {
      // The answer is the run, already "running", which is what starts the
      // polling effect above.
      setRun(await api.startGeneration(school.id));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [school, setError]);

  const generateButton = (
    <button
      type="button"
      onClick={generate}
      disabled={running}
      className="rounded bg-primary-500 px-4 py-2 text-white hover:bg-primary-600 disabled:opacity-60"
    >
      {running ? `Układanie… ${elapsed} s` : plan ? "Ułóż plan na nowo" : "Ułóż plan"}
    </button>
  );

  const outcomeBanner = (() => {
    if (!run || run.status === "running") return null;
    const seconds = ((run.tookMillis ?? 0) / 1000).toFixed(1);
    if (run.status === "saved")
      return (
        <AlertBanner
          level="success"
          message={`Ułożono w ${seconds} s — ${run.placed} zajęć, ` +
            `${run.rulesKept} z ${run.rulesTotal} reguł spełnionych.`}
          onDismiss={() => setRun(null)}
        />
      );
    if (run.status === "failed")
      return (
        <AlertBanner
          level="danger"
          message={`Nie udało się ułożyć planu: ${run.message ?? "nieznany błąd"}`}
          onDismiss={() => setRun(null)}
        />
      );
    return (
      <AlertBanner
        level="danger"
        message={
          `Ułożony plan łamie reguły, więc go nie zapisano: ` +
          run.broken.map((r) => `${r.code} ${r.what}${r.detail ? ` (${r.detail})` : ""}`).join("; ") +
          ((run.unplaced ?? 0) > 0 ? ` — ${run.unplaced} zajęć bez godziny.` : ".")
        }
        onDismiss={() => setRun(null)}
      />
    );
  })();

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
