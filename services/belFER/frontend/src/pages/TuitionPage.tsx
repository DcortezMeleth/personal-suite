import { Fragment, useMemo, useState } from "react";
import { AlertBanner, DataCard } from "@delfin/ui";
import { api, type TuitionSummary } from "../api/client";
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
 * Read-only on purpose. A plan is a class and an ordinal within it, never a
 * name: the hours are the school's business and the student is not. Adding
 * one here would mean deciding what it means to record a plan for a student
 * we decline to record, and the arkusz is where that decision belongs.
 */
export function TuitionPage() {
  const [tuition, setTuition] = useState<TuitionSummary | null>(null);
  const [filter, setFilter] = useState("");

  const { school, error, setError, gate } = useSchool({
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
        Lista pochodzi z arkusza i nie da się jej tu zmienić. Poprawki nanieś w arkuszu
        i zaimportuj go ponownie.
      </p>
    </div>
  );
}
