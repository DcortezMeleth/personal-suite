import { useEffect, useRef, useState } from "react";
import Calendar from "react-calendar";
import { singleDate } from "./calendarValue";
import "./calendar.css";

function parseLocalDate(value: string): Date | undefined {
  if (!value) return undefined;
  const [y, m, d] = value.split("-").map(Number);
  return new Date(y, m - 1, d);
}

function formatLocalDate(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, "0");
  const d = String(date.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

function displayDate(value: string): string {
  const date = parseLocalDate(value);
  if (!date) return "";
  return date.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

export interface DatePickerProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  className?: string;
  align?: "left" | "right";
}

// Replaces the native <input type="date">, whose calendar popup looks like
// the OS default and can't be restyled. Value/onChange use the same
// "YYYY-MM-DD" string format the native input did, so it's a drop-in swap.
export function DatePicker({ value, onChange, placeholder = "Select date…", className, align = "left" }: DatePickerProps) {
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
          "w-full rounded-md border border-neutral-300 bg-white px-3 py-1.5 text-left text-sm shadow-sm focus:outline-none focus:ring-2 focus:ring-primary-500"
        }
      >
        {value ? <span className="text-neutral-900">{displayDate(value)}</span> : <span className="text-neutral-400">{placeholder}</span>}
      </button>
      {open && (
        <div
          className={`absolute top-full z-30 mt-2 rounded-lg border border-neutral-200 bg-white p-2 shadow-lg ${
            align === "right" ? "right-0" : "left-0"
          }`}
        >
          <Calendar
            className="rc-cal"
            value={parseLocalDate(value) ?? null}
            onChange={(v) => {
              const picked = singleDate(v);
              if (!picked) return;
              onChange(formatLocalDate(picked));
              setOpen(false);
            }}
          />
        </div>
      )}
    </div>
  );
}
