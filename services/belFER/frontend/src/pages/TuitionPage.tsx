import { Fragment, useMemo, useState } from "react";
import { AlertBanner, DataCard } from "@delfin/ui";
import { api, type TuitionKind, type TuitionPlan, type TuitionSummary } from "../api/client";
import { useSchool } from "../hooks/useSchool";
import { NoSchool } from "../components/NoSchool";

/**
 * Nauczanie indywidualne, as a list somebody can check.
 *
 * These hours were imported, planned around and verified from the beginning
 * with nowhere to look at them: they reached a screen only as italic entries
 * on a teacher's week, and only once a plan existed. A school that has just
 * imported a hundred and twenty-one of them needs to hold that against the
 * arkusz, and that is what this is for — grouped the way the arkusz groups
 * them, so the two can be read side by side.
 *
 * The lessons are read-only on purpose. A plan is a class and an ordinal
 * within it, never a name: the hours are the school's business and the
 * student is not. Adding one here would mean deciding what it means to record
 * a plan for a student we decline to record, and the arkusz is where that
 * decision belongs.
 *
 * Two things are set here, because the arkusz does not carry them and the
 * week cannot be laid out without them (RULES S9): the kind of pupil, and for
 * a mixed pupil the half of the class they are in. Saved as soon as chosen.
 */

const KINDS: { value: TuitionKind; label: string }[] = [
  { value: "ON_SITE", label: "stacjonarnie" },
  { value: "REMOTE", label: "zdalnie" },
  { value: "MIXED", label: "mieszane — część lekcji z oddziałem" },
];

const select = "rounded border border-neutral-300 px-2 py-1 text-sm font-normal";

/** The kind and half of one pupil, as two selects. */
function KindPicker(props: {
  plan: TuitionPlan;
  onChange: (kind: TuitionKind | null, half: 1 | 2 | null) => void;
}) {
  const { plan } = props;
  const label = `${plan.className} / ${plan.ordinal}`;
  return (
    <span className="ml-4 inline-flex flex-wrap items-center gap-2">
      <select
        aria-label={`Rodzaj nauczania: ${label}`}
        className={`${select} ${plan.kind === null ? "border-danger-500 text-danger-700" : ""}`}
        value={plan.kind ?? ""}
        onChange={(e) => {
          const kind = (e.target.value || null) as TuitionKind | null;
          // A half belongs only to a mixed pupil; changing away drops it.
          props.onChange(kind, kind === "MIXED" ? plan.half : null);
        }}
      >
        <option value="">— wybierz rodzaj —</option>
        {KINDS.map((k) => (
          <option key={k.value} value={k.value}>
            {k.label}
          </option>
        ))}
      </select>
      {plan.kind === "MIXED" && (
        <select
          aria-label={`Grupa w oddziale: ${label}`}
          className={`${select} ${plan.half === null && plan.classSplit ? "border-danger-500 text-danger-700" : ""}`}
          value={plan.half ?? ""}
          onChange={(e) =>
            props.onChange("MIXED", e.target.value === "" ? null : (Number(e.target.value) as 1 | 2))
          }
        >
          <option value="">— grupa —</option>
          <option value="1">grupa 1</option>
          <option value="2">grupa 2</option>
        </select>
      )}
    </span>
  );
}
export function TuitionPage() {
  const [tuition, setTuition] = useState<TuitionSummary | null>(null);
  const [filter, setFilter] = useState("");

  const { school, error, setError, reload, gate } = useSchool({
    title: "Nauczanie indywidualne",
    load: async (school) => {
      setTuition(await api.individualPlans(school.id));
    },
  });

  // Matches a plan if anything in it matches: a class, a subject or a
  // teacher. Checking an arkusz means looking things up by all three.
  const visible = useMemo(() => {
    if (!tuition) return [];
    const needle = filter.trim().toLowerCase();
    if (!needle) return tuition.byClass;
    return tuition.byClass.filter((plan) =>
      `${plan.className} ${plan.lessons
        .map((l) => `${l.subject} ${l.teacher ?? ""}`)
        .join(" ")}`
        .toLowerCase()
        .includes(needle),
    );
  }, [tuition, filter]);

  if (gate) return gate;
  if (!school) return <NoSchool title="Nauczanie indywidualne" />;
  if (!tuition) return null;

  const current = school;
  async function setKind(plan: TuitionPlan, kind: TuitionKind | null, half: 1 | 2 | null) {
    try {
      await api.setTuitionKind(current.id, plan.id, { kind, half });
      setError(null);
      await reload();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }

  const unset = tuition.byClass.filter(
    (p) => p.kind === null || (p.kind === "MIXED" && p.half === null && p.classSplit),
  ).length;

  const withoutTeacher = tuition.byClass
    .flatMap((p) => p.lessons)
    .filter((l) => l.teacher === null).length;

  return (
    <div className="space-y-4">
      {error && <AlertBanner level="danger" message={error} onDismiss={() => setError(null)} />}

      {withoutTeacher > 0 && (
        <AlertBanner
          level="warning"
          message={`${withoutTeacher} godz. nauczania indywidualnego nie ma przypisanego nauczyciela —
                    te godziny nie zablokują nikomu czasu przy układaniu planu.`}
        />
      )}

      {unset > 0 && (
        <AlertBanner
          level="warning"
          message={`U ${unset} uczniów nie wybrano rodzaju nauczania (albo grupy ucznia mieszanego).
                    Do tego czasu taki uczeń jest planowany jak stacjonarny — bez lekcji z oddziałem.`}
        />
      )}

      <DataCard
        title={`Nauczanie indywidualne (${tuition.plans})`}
        actions={
          <input
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder="Szukaj: oddział, przedmiot, nauczyciel"
            className="w-72 rounded border border-neutral-300 px-2 py-1 text-sm"
          />
        }
      >
        <div className="mb-3 flex flex-wrap gap-x-6 gap-y-1 text-sm text-neutral-600">
          <span>
            <strong>{tuition.hours}</strong> godz. tygodniowo
          </span>
          <span>
            u <strong>{tuition.teachers}</strong> nauczycieli
          </span>
        </div>

        <p className="mb-4 max-w-3xl text-sm text-neutral-500">
          Jeden uczeń uczony osobno. Uczeń nie jest tu zapisany — plan rozpoznajemy po
          oddziale i kolejnym numerze w tym oddziale, bo godziny są sprawą szkoły, a uczeń
          nie. Te godziny zajmują nauczyciela tak samo jak każda inna lekcja, więc ktoś
          z pięcioma godzinami nauczania indywidualnego ma o pięć mniej na wszystko inne.
          Oddział nie jest w tym czasie zajęty.
        </p>

        {visible.length === 0 ? (
          <p className="text-neutral-500">
            {tuition.plans === 0
              ? "Arkusz nie zawierał nauczania indywidualnego."
              : "Nic nie pasuje do wyszukiwania."}
          </p>
        ) : (
          <table className="w-full text-left text-sm">
            <thead className="text-neutral-500">
              <tr>
                <th className="pb-2">Przedmiot</th>
                <th className="pb-2">Nauczyciel</th>
                <th className="pb-2 text-right">Bloki</th>
                <th className="pb-2 text-right">Godz.</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((plan) => (
                // A heading row per plan rather than a card each: twenty-four
                // cards is a page of scrolling, and the arkusz reads as a list.
                // The key belongs on the fragment, not the first row inside
                // it: React counts the fragment as the list item.
                <Fragment key={plan.id}>
                  <tr className="border-t border-neutral-300">
                    <td colSpan={3} className="pt-3 pb-1 font-medium text-neutral-800">
                      {plan.className}
                      <span className="ml-1 text-neutral-400">/ {plan.ordinal}</span>
                      <KindPicker
                        plan={plan}
                        onChange={(kind, half) => setKind(plan, kind, half)}
                      />
                    </td>
                    <td className="pt-3 pb-1 text-right font-medium text-neutral-800">
                      {plan.hours}
                    </td>
                  </tr>
                  {plan.lessons.map((lesson, i) => (
                    <tr key={`${plan.id}-${i}`} className="text-neutral-700">
                      <td className="py-1 pl-4">{lesson.subject}</td>
                      <td className="py-1">
                        {lesson.teacher ?? (
                          <span className="text-warning-700">brak nauczyciela</span>
                        )}
                      </td>
                      <td className="py-1 text-right text-neutral-500">
                        {lesson.blocks.join(", ")}
                      </td>
                      <td className="py-1 text-right">{lesson.hours}</td>
                    </tr>
                  ))}
                </Fragment>
              ))}
            </tbody>
          </table>
        )}
      </DataCard>

      <p className="text-xs text-neutral-500">
        Lekcje pochodzą z arkusza i nie da się ich tu zmienić — poprawki nanieś w arkuszu
        i zaimportuj go ponownie. Rodzaj nauczania i grupę ucznia ustawia się tutaj, bo arkusz
        ich nie zawiera; ponowny import je wyczyści.
      </p>
    </div>
  );
}
