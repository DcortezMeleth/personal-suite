import { useCallback, useEffect, useRef, useState } from "react";
import { AlertBanner, DataCard } from "@delfin/ui";
import { api, type School } from "../api/client";

/**
 * The school every page works against, and the states before it arrives.
 *
 * There is one school, and every page began by fetching it the same way. What
 * differed was only what each did afterwards — and all of them got the order
 * of the answers wrong in the same place. `school` is null both when the fetch
 * found none and when the fetch *failed*, and the "configure a school first"
 * card was returned before the error was ever rendered. So with the backend
 * down, eight pages told the planner to go and create a school she had already
 * created, and never mentioned that anything was wrong.
 *
 * The states therefore come back already ordered, as `gate`: something to show
 * instead of the page, or null when the page itself can be shown. A page
 * cannot put them in the wrong order because it no longer puts them in any
 * order.
 *
 * Only a failure that leaves nothing to show takes over the page. Once there
 * is a school, a later error — a save that was refused, a reload that did not
 * answer — belongs beside the data it concerns, so `error` is handed back for
 * the page to band across the top as before.
 */
export function useSchool(options: {
  /** Heading for the card shown while there is no page to show. */
  title: string;
  /** What to say while waiting, where a page says something of its own. */
  busy?: string;
  /** The rest of the page's data, once there is a school to load it for. */
  load?: (school: School) => Promise<void>;
}) {
  const { title, busy = "Wczytywanie…" } = options;

  const [school, setSchool] = useState<School | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Kept in a ref so that a page may pass a fresh closure on every render —
  // which every one of them does — without that restarting the fetch.
  const loadFor = useRef(options.load);
  loadFor.current = options.load;

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      const current = (await api.listSchools())[0] ?? null;
      setSchool(current);
      if (current) await loadFor.current?.(current);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  const gate = loading ? (
    <p className="text-neutral-500">{busy}</p>
  ) : error && !school ? (
    // Nothing was loaded, so there is no page to put a banner on top of. Say
    // what went wrong and offer the only useful action, which is to try again.
    <DataCard title={title}>
      <AlertBanner level="danger" message={error} />
      <button
        type="button"
        onClick={reload}
        className="mt-4 rounded bg-primary-500 px-4 py-2 text-white hover:bg-primary-600"
      >
        Spróbuj ponownie
      </button>
    </DataCard>
  ) : null;

  return { school, setSchool, loading, error, setError, reload, gate };
}
