import { DataCard } from "@delfin/ui";

/**
 * What a page shows when the school has not been set up yet.
 *
 * Reached only once the fetch has succeeded and come back empty — a failure
 * that leaves us without a school is a different thing to say, and useSchool
 * says it before this is ever reached.
 */
export function NoSchool({ title }: { title: string }) {
  return (
    <DataCard title={title}>
      <p className="text-neutral-700">Najpierw skonfiguruj szkołę na zakładce „Szkoła”.</p>
    </DataCard>
  );
}
