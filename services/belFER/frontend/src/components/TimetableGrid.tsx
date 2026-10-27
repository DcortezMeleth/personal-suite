import type { PlanEntry } from "../api/client";

/**
 * One week, as a grid: hours down the side, days across.
 *
 * All four views the school asked for are this same table over a different
 * slice of the same lessons — a class's week, a teacher's, a room's. What
 * differs is only which lessons reach it and what each cell needs to say, so
 * that is what the props are.
 */
export interface TimetableGridProps {
  days: string[];
  slotsPerDay: number;
  entries: PlanEntry[];
  /** What to show under the subject. The room, usually; the class, sometimes. */
  caption: (entry: PlanEntry) => string;
  /** Highlight a lesson, when one is worth pointing at. */
  isHighlighted?: (entry: PlanEntry) => boolean;
}

// A lesson that only half a class attends is marked rather than described: the
// planner knows what /1 and /2 mean, and a colour down the left edge is read
// faster than a word.
const HALF_EDGE: Record<number, string> = {
  1: "border-l-4 border-l-emerald-400",
  2: "border-l-4 border-l-violet-400",
};

export function TimetableGrid({
  days,
  slotsPerDay,
  entries,
  caption,
  isHighlighted,
}: TimetableGridProps) {
  const at = new Map<string, PlanEntry[]>();
  for (const entry of entries) {
    // A two-hour block fills both of its rows, so it is listed in each.
    for (let k = 0; k < Math.max(1, entry.length); k += 1) {
      const key = `${entry.day}:${entry.position + k}`;
      const cell = at.get(key);
      if (cell) cell.push(entry);
      else at.set(key, [entry]);
    }
  }

  const hours = Array.from({ length: slotsPerDay }, (_, i) => i);

  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr>
            <th className="w-10 border border-neutral-200 bg-neutral-50 p-1" />
            {days.map((day) => (
              <th
                key={day}
                className="border border-neutral-200 bg-neutral-50 p-2 text-left font-medium text-neutral-700"
              >
                {day}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {hours.map((hour) => (
            <tr key={hour}>
              <td className="border border-neutral-200 bg-neutral-50 p-1 text-center align-middle text-xs font-medium text-neutral-500">
                {hour + 1}
              </td>
              {days.map((day, dayIndex) => {
                const here = at.get(`${dayIndex}:${hour}`) ?? [];
                return (
                  <td
                    key={day}
                    className="h-12 border border-neutral-200 p-0 align-top"
                  >
                    {here.map((entry, i) => (
                      <div
                        key={`${entry.subject}-${i}`}
                        className={[
                          "px-2 py-1",
                          entry.half ? HALF_EDGE[entry.half] : "",
                          isHighlighted?.(entry) ? "bg-amber-50" : "",
                          entry.individual ? "italic text-neutral-600" : "",
                        ]
                          .filter(Boolean)
                          .join(" ")}
                      >
                        <div className="font-medium leading-tight text-neutral-900">
                          {entry.subject}
                          {entry.half ? (
                            <span className="text-neutral-500">/{entry.half}</span>
                          ) : null}
                        </div>
                        {caption(entry) && (
                          <div className="text-xs leading-tight text-neutral-500">
                            {caption(entry)}
                          </div>
                        )}
                      </div>
                    ))}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
