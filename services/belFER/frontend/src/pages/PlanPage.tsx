import { useCallback, useEffect, useState } from "react";
import { DataCard } from "@delfin/ui";
import { api } from "../api/client";

// A placeholder that does one useful thing: proves the Vite proxy reaches the
// backend. Phase 1 replaces it with the real configuration screens.
export function PlanPage() {
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const health = await api.health();
      setStatus(health.status);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <DataCard title="Stan usługi">
      {error && <p className="text-danger-500">Brak połączenia z serwerem: {error}</p>}
      {!error && <p className="text-neutral-700">Backend: {status ?? "sprawdzanie…"}</p>}
    </DataCard>
  );
}
