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

function send<T>(method: string, path: string, body?: unknown): Promise<T> {
  return fetch(`${BASE}${path}`, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  }).then(unwrap<T>);
}

export interface Health {
  status: string;
  service: string;
}

export interface SchedulingSettings {
  allowClassGaps: boolean;
  maxConsecutiveTeacherGaps: number;
  defaultTeacherMaxWorkingDays: number;
  defaultTeacherMaxLessonsPerDay: number;
}

export interface School {
  id: string;
  name: string;
  years: number;
  settings: SchedulingSettings;
  createdAt: string;
}

export interface TimeSlot {
  id: string;
  position: number;
  startsAt: string;
  endsAt: string;
}

export interface TimeSlotInput {
  position: number;
  startsAt: string;
  endsAt: string;
}

export const api = {
  // /health sits at the root rather than under /api, so that a readiness probe
  // does not depend on the API routes being wired up.
  health: () => fetch("/health").then(unwrap<Health>),

  listSchools: () => send<School[]>("GET", "/schools"),
  createSchool: (name: string, years: number) =>
    send<School>("POST", "/schools", { name, years }),
  updateSchool: (id: string, body: { name: string; years: number; settings: SchedulingSettings }) =>
    send<School>("PUT", `/schools/${id}`, body),

  listTimeSlots: (schoolId: string) => send<TimeSlot[]>("GET", `/schools/${schoolId}/time-slots`),
  replaceTimeSlots: (schoolId: string, slots: TimeSlotInput[]) =>
    send<TimeSlot[]>("PUT", `/schools/${schoolId}/time-slots`, slots),
};
