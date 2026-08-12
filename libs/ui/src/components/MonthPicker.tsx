import { useEffect, useRef, useState } from "react";
import Calendar from "react-calendar";
import "./calendar.css";

function parseMonth(value: string): Date {
  const [y, m] = value.split("-").map(Number);
  return new Date(y, (m || 1) - 1, 1);
}

function formatMonth(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}`;
}

function displayMonth(value: string): string {
  return parseMonth(value).toLocaleDateString(undefined, { year: "numeric", month: "long" });
}

interface Props {
  value: string;
  onChange: (value: string) => void;
  className?: string;
}

// Replaces the native <input type="month">. Locks react-calendar to its
// year view (minDetail = maxDetail = "year") so clicking a tile picks a
// month directly instead of drilling into individual days.
export function MonthPicker({ value, onChange, className }: Props) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function onOutside(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("mousedown", onOutside);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onOutside);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className={
          className ??
          "rounded-md border border-neutral-300 bg-white px-3 py-1.5 text-sm shadow-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
        }
      >
        {displayMonth(value)}
      </button>
      {open && (
        <div className="absolute right-0 top-full z-30 mt-2 rounded-lg border border-neutral-200 bg-white p-2 shadow-lg">
          <Calendar
            className="rc-cal"
            minDetail="year"
            maxDetail="year"
            value={parseMonth(value)}
            onChange={(v) => {
              onChange(formatMonth(v as Date));
              setOpen(false);
            }}
          />
        </div>
      )}
    </div>
  );
}
