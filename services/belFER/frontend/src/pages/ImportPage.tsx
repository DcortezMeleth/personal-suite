import { useCallback, useEffect, useState } from "react";
import { AlertBanner, DataCard } from "@delfin/ui";
import { useConfirm } from "../hooks/useConfirm";
import {
  api,
  type ImportCounts,
  type ImportPreview,
  type School,
  type WeekSegment,
} from "../api/client";

const plainButton = "rounded border border-neutral-300 px-3 py-1.5 text-sm hover:bg-neutral-50";

const LABELS: { key: keyof ImportCounts; label: string }[] = [
  { key: "teachers", label: "Nauczyciele" },
  { key: "subjects", label: "Przedmioty" },
  { key: "classes", label: "Oddziały" },
  { key: "lessonLines", label: "Przydziały" },
  { key: "crossClassUnits", label: "Zajęcia międzyoddziałowe" },
];

export function ImportPage() {
  const [school, setSchool] = useState<School | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [range, setRange] = useState<{ from: number; to: number }>({ from: 1, to: 38 });
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [confirmDialog, confirm] = useConfirm();

  useEffect(() => {
    api.listSchools().then(s => setSchool(s[0] ?? null)).catch(report);
  }, []);

  function report(err: unknown) {
    setError(err instanceof Error ? err.message : String(err));
  }

  const runPreview = useCallback(
    async (chosen: File, from: number, to: number) => {
      if (!school) return;
      setBusy(true);
      try {
        // Defaulted rather than assumed: a response without them should show an
        // empty list, not blow up with "cannot read properties of undefined".
        const result = await api.previewImport(school.id, chosen, from, to);
        setPreview({ ...result, segments: result.segments ?? [], notes: result.notes ?? [] });
        setError(null);
        // The file decides which stretches exist, so the first useful range can
        // only be chosen once it has been read.
        if (result.segments.length > 0 && from === 1 && to === 38) {
          // The earliest, not the longest: landing in the second semester
          // because it happens to run a week longer is a surprise, and any
          // other stretch is one click away.
          const first = result.segments.reduce((a, b) => (b.from < a.from ? b : a));
          setRange({ from: first.from, to: first.to });
          const narrowed = await api.previewImport(school.id, chosen, first.from, first.to);
          setPreview({ ...narrowed, segments: narrowed.segments ?? [], notes: narrowed.notes ?? [] });
        }
      } catch (err) {
        report(err);
        setPreview(null);
      } finally {
        setBusy(false);
      }
    },
    [school],
  );

  function chooseFile(chosen: File | null) {
    setFile(chosen);
    setDone(null);
    setPreview(null);
    if (chosen) runPreview(chosen, 1, 38);
  }

  function chooseSegment(segment: WeekSegment) {
    setRange({ from: segment.from, to: segment.to });
    setDone(null);
    if (file) runPreview(file, segment.from, segment.to);
  }

  async function doImport() {
    if (!school || !file) return;
    setBusy(true);
    try {
      const result = await api.applyImport(school.id, file, range.from, range.to);
      setDone(
        `Zaimportowano: ${result.teachers} nauczycieli, ${result.classes} oddziałów, ` +
          `${result.lessonLines} przydziałów, ${result.crossClassUnits} zajęć międzyoddziałowych.`,
      );
      setError(null);
    } catch (err) {
      report(err);
    } finally {
      setBusy(false);
    }
  }

  if (!school)
    return (
      <DataCard title="Import arkusza">
        <p className="text-neutral-700">Najpierw skonfiguruj szkołę na zakładce „Szkoła”.</p>
      </DataCard>
    );

  const replaces = preview?.replaces;
  const replacingAnything =
    replaces !== undefined && LABELS.some(({ key }) => replaces[key] > 0);

  return (
    <div className="space-y-4">
      {confirmDialog}
      {error && <AlertBanner level="danger" message={error} onDismiss={() => setError(null)} />}
      {done && <AlertBanner level="success" message={done} onDismiss={() => setDone(null)} />}

      <DataCard title="Import arkusza organizacyjnego">
        <input
          type="file"
          accept=".xml,text/xml"
          onChange={(e) => chooseFile(e.target.files?.[0] ?? null)}
          className="block text-sm"
        />
        <p className="mt-2 text-sm text-neutral-500">
          Plik XML z arkusza organizacyjnego. Nic nie zostanie zapisane, dopóki nie potwierdzisz —
          najpierw zobaczysz, co powstanie.
        </p>
      </DataCard>

      {busy && <p className="text-neutral-500">Wczytywanie pliku…</p>}

      {preview && (
        <>
          <DataCard title="Okres planu">
            {/* The arkusz covers a whole year, but a plan covers a stretch over
                which the allocation does not change — and the file says where
                those are, so they are offered rather than guessed. */}
            <div className="flex flex-wrap gap-2">
              {preview.segments.map((segment) => {
                const active = segment.from === range.from && segment.to === range.to;
                return (
                  <button
                    key={`${segment.from}-${segment.to}`}
                    onClick={() => chooseSegment(segment)}
                    className={`rounded px-3 py-2 text-left text-sm ${
                      active ? "bg-primary-500 text-white" : "border border-neutral-300 hover:bg-neutral-50"
                    }`}
                  >
                    <span className="font-medium">tyg. {segment.from}–{segment.to}</span>
                    <span className={`ml-2 ${active ? "text-primary-50" : "text-neutral-500"}`}>
                      {segment.weeks} tyg., {segment.hoursPerWeek} godz./tydz.
                    </span>
                  </button>
                );
              })}
            </div>
            <p className="mt-2 text-sm text-neutral-500">
              W każdym z tych okresów przydział godzin jest stały. Gdy się zmienia, szkoła wydaje
              nowy plan — dlatego plan obejmuje jeden okres, a nie cały rok.
            </p>
          </DataCard>

          <DataCard title={`Co powstanie — ${preview.schoolName}`}>
            <table className="text-sm">
              <tbody>
                {LABELS.map(({ key, label }) => (
                  <tr key={key}>
                    <td className="py-1 pr-6 text-neutral-600">{label}</td>
                    <td className="py-1 pr-6 font-medium text-neutral-900">{preview.counts[key]}</td>
                    <td className="py-1 text-neutral-500">
                      {replaces && replaces[key] > 0 ? `zastąpi ${replaces[key]}` : ""}
                    </td>
                  </tr>
                ))}
                <tr>
                  <td className="py-1 pr-6 text-neutral-600">w tym grup</td>
                  <td className="py-1 pr-6 font-medium text-neutral-900">
                    {preview.counts.crossClassGroups}
                  </td>
                  <td />
                </tr>
              </tbody>
            </table>

            {preview.notes.length > 0 && (
              <ul className="mt-4 space-y-1">
                {preview.notes.map((note) => (
                  <li
                    key={note.message}
                    className={`text-sm ${
                      note.level === "Warning" ? "text-warning-600" : "text-neutral-500"
                    }`}
                  >
                    {note.level === "Warning" ? "⚠" : "·"} {note.message}
                  </li>
                ))}
              </ul>
            )}

            <div className="mt-5 flex items-center gap-3">
              <button
                disabled={busy}
                className="rounded bg-primary-500 px-4 py-2 text-white hover:bg-primary-600 disabled:bg-neutral-300"
                onClick={() =>
                  replacingAnything
                    ? confirm(
                        "Import zastąpi obecnych nauczycieli, przedmioty, oddziały i przydziały. " +
                          "Sale i typy sal zostaną nienaruszone.",
                        doImport,
                        { title: "Potwierdź import", confirmLabel: "Importuj" },
                      )
                    : doImport()
                }
              >
                Importuj
              </button>
              <button className={plainButton} onClick={() => chooseFile(null)}>
                Anuluj
              </button>
            </div>
          </DataCard>
        </>
      )}
    </div>
  );
}
