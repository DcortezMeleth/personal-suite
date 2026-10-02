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

// 204 responses have no body, so they cannot go through unwrap's json().
async function sendNoContent(method: string, path: string): Promise<void> {
  const response = await fetch(`${BASE}${path}`, { method });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      if (body?.error) message = body.error;
    } catch {
      // No JSON body; the status line is the best we have.
    }
    throw new Error(message);
  }
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

export interface RoomKind {
  id: string;
  name: string;
}

export interface Room {
  id: string;
  number: string;
  name: string | null;
  fitsWholeClass: boolean;
  kinds: RoomKind[];
}

export interface RoomInput {
  number: string;
  name: string | null;
  fitsWholeClass: boolean;
  kindIds: string[];
}

export interface Subject {
  id: string;
  code: string;
  name: string;
  optional: boolean;
  requiredRoomKind: RoomKind | null;
  roomRequirementHard: boolean;
}

export interface SubjectInput {
  code: string;
  name: string;
  optional: boolean;
  requiredRoomKindId: string | null;
  roomRequirementHard: boolean;
}

export interface UnavailabilityBlock {
  dayOfWeek: number;
  fromPosition: number;
  toPosition: number;
}

export interface Teacher {
  id: string;
  code: string;
  firstName: string;
  lastName: string;
  homeRoomId: string | null;
  subjectIds: string[];
  // null means "inherit the school default", not "no limit".
  maxWorkingDays: number | null;
  maxLessonsPerDay: number | null;
  pensum: number | null;
  unavailability: UnavailabilityBlock[];
}

export type TeacherInput = Omit<Teacher, "id">;

export interface SchoolClass {
  id: string;
  year: number;
  letter: string;
  specialisation: string | null;
  homeroomTeacherId: string | null;
  studentCount: number | null;
  girlCount: number | null;
  name: string;
}

export type SchoolClassInput = Omit<SchoolClass, "id" | "name">;

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

  listRoomKinds: (schoolId: string) => send<RoomKind[]>("GET", `/schools/${schoolId}/room-kinds`),
  createRoomKind: (schoolId: string, name: string) =>
    send<RoomKind>("POST", `/schools/${schoolId}/room-kinds`, { name }),
  deleteRoomKind: (id: string) => sendNoContent("DELETE", `/room-kinds/${id}`),

  listRooms: (schoolId: string) => send<Room[]>("GET", `/schools/${schoolId}/rooms`),
  createRoom: (schoolId: string, input: RoomInput) =>
    send<Room>("POST", `/schools/${schoolId}/rooms`, input),
  updateRoom: (id: string, input: RoomInput) => send<Room>("PUT", `/rooms/${id}`, input),
  deleteRoom: (id: string) => sendNoContent("DELETE", `/rooms/${id}`),

  listSubjects: (schoolId: string) => send<Subject[]>("GET", `/schools/${schoolId}/subjects`),
  createSubject: (schoolId: string, input: SubjectInput) =>
    send<Subject>("POST", `/schools/${schoolId}/subjects`, input),
  updateSubject: (id: string, input: SubjectInput) => send<Subject>("PUT", `/subjects/${id}`, input),
  deleteSubject: (id: string) => sendNoContent("DELETE", `/subjects/${id}`),

  listTeachers: (schoolId: string) => send<Teacher[]>("GET", `/schools/${schoolId}/teachers`),
  createTeacher: (schoolId: string, input: TeacherInput) =>
    send<Teacher>("POST", `/schools/${schoolId}/teachers`, input),
  updateTeacher: (schoolId: string, id: string, input: TeacherInput) =>
    send<Teacher>("PUT", `/schools/${schoolId}/teachers/${id}`, input),
  deleteTeacher: (id: string) => sendNoContent("DELETE", `/teachers/${id}`),

  listClasses: (schoolId: string) => send<SchoolClass[]>("GET", `/schools/${schoolId}/classes`),
  createClass: (schoolId: string, input: SchoolClassInput) =>
    send<SchoolClass>("POST", `/schools/${schoolId}/classes`, input),
  updateClass: (schoolId: string, id: string, input: SchoolClassInput) =>
    send<SchoolClass>("PUT", `/schools/${schoolId}/classes/${id}`, input),
  deleteClass: (id: string) => sendNoContent("DELETE", `/classes/${id}`),
  generateClasses: (schoolId: string, year: number, letters: string[]) =>
    send<SchoolClass[]>("POST", `/schools/${schoolId}/classes/generate`, { year, letters }),
};
