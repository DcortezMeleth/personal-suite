import { useEffect, useRef, useState } from "react";
import type { Finding } from "../api/client";

/**
 * A marker beside a row, opening its findings on a click.
 *
 * Deliberately small: these sit in dense tables, and a banner per row would
 * make a screen of eighty teachers unreadable. Blocking is red because the
 * plan cannot be built until it is dealt with; a warning is amber because it
 * can.
 *
 * It used to carry the text in `title`, which is a tooltip — and a tooltip is
 * a poor place for something a person needs to read and think about. It waits
 * a second before appearing, vanishes if the pointer drifts, cannot be
 * selected or copied, never appears at all on a touchscreen, and gives no sign
 * that there is anything to hover over in the first place. A click is the
 * obvious gesture for "tell me more", so that is the gesture.
 */
export function FindingMark({ findings }: { findings: Finding[] }) {
  const [open, setOpen] = useState(false);
  const holder = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    if (!open) return;
    // Mousedown rather than click: a click fires on the common ancestor of
    // press and release, so selecting the text inside the panel and releasing
    // outside it would otherwise shut the panel mid-selection.
    const away = (event: MouseEvent) => {
      if (!holder.current?.contains(event.target as Node)) setOpen(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", away);
    document.addEventListener("keydown", escape);
    return () => {
      document.removeEventListener("mousedown", away);
      document.removeEventListener("keydown", escape);
    };
  }, [open]);

  if (findings.length === 0) return null;
  const blocking = findings.some((f) => f.severity === "Blocking");

  return (
    <span ref={holder} className="relative inline-block">
      <button
        type="button"
        onClick={() => setOpen((shown) => !shown)}
        aria-expanded={open}
        aria-label={`${findings.length} ${
          blocking ? "blokad" : "ostrzeżeń"
        } — pokaż szczegóły`}
        className={`ml-1 rounded px-1 leading-none select-none hover:bg-neutral-100 ${
          blocking ? "text-danger-500" : "text-warning-600"
        }`}
      >
        {blocking ? "✕" : "⚠"}
      </button>

      {open && (
        <div
          role="dialog"
          aria-label="Szczegóły"
          // Left-aligned to the mark, which sits in the first column, so the
          // panel opens into the table rather than off the edge of it.
          className="absolute left-0 top-full z-20 mt-1 w-80 rounded border border-neutral-300 bg-white p-3 text-left shadow-lg"
        >
          <ul className="space-y-2">
            {findings.map((finding, i) => (
              <li key={i} className="text-sm leading-snug text-neutral-800">
                <span
                  className={
                    finding.severity === "Blocking"
                      ? "mr-1 font-medium text-danger-600"
                      : "mr-1 font-medium text-warning-700"
                  }
                >
                  {finding.severity === "Blocking" ? "Blokada:" : "Uwaga:"}
                </span>
                {finding.message}
              </li>
            ))}
          </ul>
          <button
            type="button"
            onClick={() => setOpen(false)}
            className="mt-3 text-xs text-neutral-500 hover:text-neutral-800"
          >
            Zamknij
          </button>
        </div>
      )}
    </span>
  );
}
