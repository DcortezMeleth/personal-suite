// Hand-rolled fetch wrapper, matching delFIN's. Errors come back from the
// backend as {"error": "..."} and are unwrapped into a thrown Error so callers
// only ever deal with one failure shape.
const BASE = "/api";

async function unwrap<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      if (body?.error) message = body.error;
    } catch {
      // Body was not JSON; the status line is the best we have.
    }
    throw new Error(message);
  }
  return response.json() as Promise<T>;
}

export interface Health {
  status: string;
  service: string;
}

export const api = {
  get: <T>(path: string) => fetch(`${BASE}${path}`).then(unwrap<T>),

  // /health sits at the root rather than under /api, so that a readiness probe
  // does not depend on the API routes being wired up.
  health: () => fetch("/health").then(unwrap<Health>),
};
