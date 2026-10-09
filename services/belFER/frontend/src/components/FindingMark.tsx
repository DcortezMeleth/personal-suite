import type { Finding } from "../api/client";

/**
 * A marker beside a row, carrying its findings in the tooltip.
 *
 * Deliberately small: these sit in dense tables, and a banner per row would
 * make a screen of eighty teachers unreadable. Blocking is red because the plan
 * cannot be built until it is dealt with; a warning is amber because it can.
 */
export function FindingMark({ findings }: { findings: Finding[] }) {
  if (findings.length === 0) return null;
  const blocking = findings.some((f) => f.severity === "Blocking");
  return (
    <span
      // Without a role the glyph is read as loose text next to the row rather
      // than as something said about it, and aria-label is ignored on a bare
      // span.
      role="img"
      title={findings.map((f) => f.message).join("\n")}
      className={`ml-1 cursor-help select-none ${
        blocking ? "text-danger-500" : "text-warning-600"
      }`}
      aria-label={findings.map((f) => f.message).join("; ")}
    >
      {blocking ? "✕" : "⚠"}
    </span>
  );
}
