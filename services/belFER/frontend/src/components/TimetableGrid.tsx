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

/**
 * One hour of one lesson, and how far into it we are.
 *
 * A double block fills two rows and has to appear in both, or the second hour
 * looks free. Printing it twice identically is what it used to do, and that
 * makes a double indistinguishable from two single lessons back to back —
 * which matters, because they are different things: a subject may be taught
 * once a day per group, and a double is that one time.
 */
interface Hour {
  entry: PlanEntry;
  /** 0 for the hour the lesson starts, 1 for the next, and so on. */
  into: number;
}

export function TimetableGrid({
  days,
  slotsPerDay,
  entries,
  caption,
  isHighlighted,
}: TimetableGridProps) {
  const at = new Map<string, Hour[]>();
  for (const entry of entries) {
    for (let into = 0; into < Math.max(1, entry.length); into += 1) {
      const key = `${entry.day}:${entry.position + into}`;
      const cell = at.get(key);
      if (cell) cell.push({ entry, into });
      else at.set(key, [{ entry, into }]);
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
                    {here.map(({ entry, into }, i) => (
                      <div
                        key={`${entry.subject}-${i}`}
                        className={[
                          "px-2 py-1",
                          entry.half ? HALF_EDGE[entry.half] : "",
                          isHighlighted?.(entry) ? "bg-amber-50" : "",
                          entry.individual ? "italic text-neutral-600" : "",
                          // A continued hour is drawn as the tail of the one
                          // above rather than as a lesson of its own.
                          into > 0 ? "border-t border-dashed border-neutral-200" : "",
                        ]
                          .filter(Boolean)
                          .join(" ")}
                      >
                        <div
                          className={
                            into > 0
                              ? "leading-tight text-neutral-400"
                              : "font-medium leading-tight text-neutral-900"
                          }
                        >
                          {entry.subject}
                          {entry.half ? (
                            <span className="text-neutral-500">/{entry.half}</span>
                          ) : null}
                        </div>
                        {/* The room and the names belong to the block, not to
                            each of its hours, so they are said once. */}
                        {into === 0 && caption(entry) && (
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
