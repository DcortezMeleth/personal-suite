import { useState } from "react";
import { AlertBanner, DataCard } from "@delfin/ui";
import { api, type TeacherGaps, type ValidationReport } from "../api/client";
import { useSchool } from "../hooks/useSchool";
import { NoSchool } from "../components/NoSchool";

export function ValidationPage() {
  const [report, setReport] = useState<ValidationReport | null>(null);
  // Windows only exist once a week has been laid out, so this is empty until
  // there is a plan — which is an ordinary state rather than a failure.
  const [gaps, setGaps] = useState<TeacherGaps[]>([]);

  const { school, error, setError, gate } = useSchool({
    title: "Kontrola danych",
    busy: "Sprawdzanie…",
    load: async (school) => {
      setReport(await api.validate(school.id));
      try {
        setGaps(await api.teacherGaps(school.id));
      } catch {
        setGaps([]);
      }
    },
  });

  if (gate) return gate;
  if (!school) return <NoSchool title="Kontrola danych" />;
  if (!report) return null;

  const clean = report.blocking.length === 0 && report.warnings.length === 0;

  return (
    <div className="space-y-4">
      {error && <AlertBanner level="danger" message={error} onDismiss={() => setError(null)} />}

      {clean && <AlertBanner level="success" message="Nic nie stoi na przeszkodzie ułożeniu planu." />}

      {/* Blocking first and loudest: these say no arrangement of these hours
          can work, so there is no point generating until they are gone. */}
      {report.blocking.length > 0 && (
        <DataCard title={`Blokady (${report.blocking.length})`}>
          <p className="mb-3 text-sm text-neutral-600">
            Przy tych danych nie da się ułożyć planu — żadne rozmieszczenie godzin ich nie spełni.
          </p>
          <ul className="space-y-1">
            {report.blocking.map((f, i) => (
              <li key={i} className="text-sm text-danger-600">
                <span className="font-medium">{f.subject}</span> — {f.message}
              </li>
            ))}
          </ul>
        </DataCard>
      )}

      {report.warnings.length > 0 && (
        <DataCard title={`Do sprawdzenia (${report.warnings.length})`}>
          <p className="mb-3 text-sm text-neutral-600">
            Plan da się ułożyć, ale te rzeczy wyglądają nietypowo.
          </p>
          <ul className="space-y-1">
            {report.warnings.map((f, i) => (
              <li key={i} className="text-sm text-warning-600">
                <span className="font-medium">{f.subject}</span> — {f.message}
              </li>
            ))}
          </ul>
        </DataCard>
      )}

      <DataCard title="Obciążenie oddziałów">
        <div className="grid gap-x-8 gap-y-1 sm:grid-cols-2 lg:grid-cols-3">
          {report.classes.map((cls) => {
            const share = Math.min(1, cls.occupied / Math.max(1, cls.capacity));
            const tight = cls.occupied >= cls.capacity;
            return (
              <div key={cls.name} className="flex items-center gap-3 py-1">
                <span className="w-10 font-medium text-neutral-700">{cls.name}</span>
                <div className="h-2 flex-1 rounded bg-neutral-200">
                  <div
                    className={`h-2 rounded ${tight ? "bg-danger-500" : "bg-primary-500"}`}
                    style={{ width: `${share * 100}%` }}
                  />
                </div>
                <span className="w-16 text-right text-sm text-neutral-500">
                  {cls.occupied}/{cls.capacity}
                </span>
              </div>
            );
          })}
        </div>
        <p className="mt-3 text-sm text-neutral-500">
          Godziny zajęte przez oddział wobec miejsc w tygodniu. Lekcja z podziałem na grupy liczy
          się raz, zajęcia międzyoddziałowe są wliczone.
        </p>
      </DataCard>

      {gaps.length > 0 && (
        <DataCard title={`Okienka nauczycieli (${gaps.length})`}>
          <p className="mb-3 text-sm text-neutral-600">
            Wolne godziny w środku dnia, w ułożonym planie. Nauczyciele bez okienek nie są tu
            wymienieni.
          </p>
          <table className="w-full text-left text-sm">
            <thead className="text-neutral-500">
              <tr>
                <th className="pb-2">Nauczyciel</th>
                <th className="pb-2">Okienka</th>
                <th className="pb-2">Najdłuższe</th>
                <th className="pb-2">Dni z okienkami</th>
              </tr>
            </thead>
            <tbody>
              {gaps.map((t) => (
                <tr key={t.teacher} className="border-t border-neutral-200">
                  <td className="py-1 text-neutral-700">{t.teacher}</td>
                  <td className="py-1">{t.gaps}</td>
                  {/* The rule bounds the longest run, not the total, so that
                      is the column worth colouring. */}
                  <td className={`py-1 ${t.longestRun > 2 ? "text-warning-600" : ""}`}>
                    {t.longestRun}
                  </td>
                  <td className="py-1 text-neutral-500">{t.days}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="mt-3 text-sm text-neutral-500">
            Razem {gaps.reduce((sum, t) => sum + t.gaps, 0)} okienek w tygodniu.
          </p>
        </DataCard>
      )}

      <DataCard title="Obciążenie nauczycieli">
        <table className="w-full text-left text-sm">
          <thead className="text-neutral-500">
            <tr>
              <th className="pb-2">Nauczyciel</th>
              <th className="pb-2">Przydział</th>
              <th className="pb-2">Pensum</th>
              <th className="pb-2">Różnica</th>
            </tr>
          </thead>
          <tbody>
            {[...report.teachers]
              .sort((a, b) => b.allocated - a.allocated)
              .map((t) => {
                const diff = t.pensum === null ? null : t.allocated - t.pensum;
                return (
                  <tr key={t.name} className="border-t border-neutral-200">
                    <td className="py-1 text-neutral-700">{t.name}</td>
                    <td className="py-1">{t.allocated}</td>
                    <td className="py-1 text-neutral-500">{t.pensum ?? "—"}</td>
                    <td
                      className={`py-1 ${
                        diff === null ? "" : diff > 0 ? "text-warning-600" : "text-neutral-400"
                      }`}
                    >
                      {diff === null ? "" : diff > 0 ? `+${diff}` : diff}
                    </td>
                  </tr>
                );
              })}
          </tbody>
        </table>
      </DataCard>
    </div>
  );
}
