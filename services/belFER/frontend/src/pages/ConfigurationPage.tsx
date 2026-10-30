import { useEffect, useState } from "react";
import { AlertBanner, DataCard } from "@delfin/ui";
import { api, type Optimisation, type School } from "../api/client";
import { useSchool } from "../hooks/useSchool";
import { NoSchool } from "../components/NoSchool";

/**
 * What a good timetable is, as distinct from a legal one.
 *
 * The rules in Kontrola say whether a week is allowed. These say which of the
 * allowed weeks is wanted — the thing the previous software never let anybody
 * state, and the reason belFER exists (REQUIREMENTS §1.1).
 *
 * Split in two, and the split is not cosmetic. A bound is absolute: FET must
 * satisfy it, and one set too tight means no timetable at all. A preference is
 * weighted: FET tries and gives up when it must, and no setting of it can stop
 * a week being made. Which rule falls on which side is not our choice — FET
 * refuses a weight below 100 on the gap rules and on the shortest day, and
 * accepts one elsewhere (ADR 0005). Mixing them into one list of sliders would
 * hide the only question that matters here: can this stop a plan being made?
 */

const field = "w-24 rounded border border-neutral-300 px-2 py-1";

/** Null means "not asked for", and an empty box is how that is said. */
function Optional(props: {
  value: number | null;
  onChange: (value: number | null) => void;
  min?: number;
}) {
  return (
    <input
      type="number"
      min={props.min ?? 0}
      className={field}
      value={props.value ?? ""}
      placeholder="bez limitu"
      onChange={(e) => {
        const raw = e.target.value.trim();
        props.onChange(raw === "" ? null : Number(raw));
      }}
    />
  );
}

function Weight(props: { value: number; onChange: (value: number) => void }) {
  return (
    <span className="flex items-center gap-3">
      <input
        type="range"
        min={0}
        max={100}
        step={5}
        value={props.value}
        onChange={(e) => props.onChange(Number(e.target.value))}
        className="w-48"
      />
      <span className="w-20 text-sm text-neutral-600">
        {props.value === 0 ? "wyłączone" : `${props.value}%`}
      </span>
    </span>
  );
}

function Row(props: { label: string; help: string; children: React.ReactNode }) {
  return (
    <div className="border-t border-neutral-200 py-3 first:border-t-0">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <span className="font-medium text-neutral-800">{props.label}</span>
        {props.children}
      </div>
      <p className="mt-1 max-w-3xl text-sm text-neutral-500">{props.help}</p>
    </div>
  );
}

export function ConfigurationPage() {
  const [draft, setDraft] = useState<Optimisation | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  const { school, setSchool, error, setError, gate } = useSchool({
    title: "Konfiguracja",
  });

  useEffect(() => {
    if (school) setDraft(school.settings.optimisation);
  }, [school]);

  if (gate) return gate;
  if (!school) return <NoSchool title="Konfiguracja" />;
  if (!draft) return null;

  const dirty = JSON.stringify(draft) !== JSON.stringify(school.settings.optimisation);
  const patch = (change: Partial<Optimisation>) => setDraft({ ...draft, ...change });

  async function save(current: School, body: Optimisation) {
    try {
      const updated = await api.updateOptimisation(current.id, body);
      setSchool(updated);
      setDraft(updated.settings.optimisation);
      setError(null);
      setSaved("Zapisano. Będzie brane pod uwagę przy następnym układaniu planu.");
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      setSaved(null);
    }
  }

  return (
    <div className="space-y-4">
      {error && <AlertBanner level="danger" message={error} onDismiss={() => setError(null)} />}
      {saved && !dirty && (
        <AlertBanner level="success" message={saved} onDismiss={() => setSaved(null)} />
      )}

      <DataCard
        title="Twarde ograniczenia"
        actions={
          <button
            type="button"
            disabled={!dirty}
            onClick={() => save(school, draft)}
            className="rounded bg-primary-500 px-4 py-2 text-white hover:bg-primary-600 disabled:opacity-50"
          >
            Zapisz
          </button>
        }
      >
        <p className="mb-3 text-sm text-neutral-600">
          Muszą zostać spełnione. Ustawione zbyt ostro sprawią, że planu nie da się
          ułożyć — wtedy zostanie to zgłoszone, a dotychczasowy plan zostanie bez zmian.
        </p>

        <Row
          label="Maks. okienek nauczyciela w tygodniu"
          help="Największa dźwignia na okienka — limit dzienny poniżej pozwala na pięć razy
                tyle w tygodniu. Pusto oznacza brak limitu. Uwaga: FET ostrzega, że ta reguła
                bywa kosztowna — układanie może trwać znacznie dłużej."
        >
          <Optional
            value={draft.maxTeacherGapsPerWeek}
            onChange={(maxTeacherGapsPerWeek) => patch({ maxTeacherGapsPerWeek })}
          />
        </Row>

        <Row
          label="Najkrótszy dzień oddziału (godz.)"
          help="Razem z najdłuższym dniem niżej zastępuje regułę „najdłuższy minus najkrótszy
                dzień”, bo FET nie potrafi mówić o różnicy — tylko o obu końcach osobno."
        >
          <Optional
            value={draft.minClassHoursDaily}
            onChange={(minClassHoursDaily) => patch({ minClassHoursDaily })}
          />
        </Row>
      </DataCard>

      <DataCard title="Preferencje">
        <p className="mb-3 text-sm text-neutral-600">
          Układane w miarę możliwości. Im wyższa waga, tym mocniej FET się stara — ale
          żadna z nich nie może sprawić, że planu nie da się ułożyć. Zero wyłącza regułę.
        </p>

        <Row
          label="Najdłuższy dzień oddziału (godz.)"
          help="FET ostrzega, że dla tej reguły waga poniżej 100% działa „niedoskonale, choć
                zwykle wystarczająco dobrze”. Przy 100% jest to w praktyce twardy limit."
        >
          <span className="flex items-center gap-3">
            <Optional
              value={draft.maxClassHoursDaily}
              onChange={(maxClassHoursDaily) => patch({ maxClassHoursDaily })}
            />
            <Weight
              value={draft.classDayWeight}
              onChange={(classDayWeight) => patch({ classDayWeight })}
            />
          </span>
        </Row>

        <Row
          label="Lekcja w sali własnej nauczyciela"
          help="Sala własna oznacza pierwszeństwo, a nie wyłączność — kilku nauczycieli może
                dzielić jedną salę, a nie każdy ma własną."
        >
          <Weight
            value={draft.homeRoomWeight}
            onChange={(homeRoomWeight) => patch({ homeRoomWeight })}
          />
        </Row>

        <Row
          label="Przedmiot w sali swojego typu"
          help="Dotyczy tylko przedmiotów, przy których wymóg sali zaznaczono jako miękki.
                Tam, gdzie jest twardy, obowiązuje bezwzględnie i ta waga go nie dotyczy."
        >
          <Weight
            value={draft.softRoomWeight}
            onChange={(softRoomWeight) => patch({ softRoomWeight })}
          />
        </Row>
      </DataCard>

      <p className="text-xs text-neutral-500">
        Zmiany dotyczą następnego układania planu — obecny plan zostaje taki, jaki jest.
      </p>
    </div>
  );
}
