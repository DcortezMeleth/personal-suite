import { useCallback, useEffect, useState } from "react";
import { api, type Finding } from "../api/client";

/**
 * The checks that the Kontrola page reports, indexed by what they are about.
 *
 * A page of problems somewhere else is a page someone has to remember to go
 * and read. Showing the same finding beside the row it concerns means it is
 * seen while the data that caused it is on screen.
 */
export function useFindings(schoolId: string | undefined) {
  const [byEntity, setByEntity] = useState<Map<string, Finding[]>>(new Map());

  const load = useCallback(async () => {
    if (!schoolId) return;
    try {
      const report = await api.validate(schoolId);
      const map = new Map<string, Finding[]>();
      const add = (finding: Finding) => {
        if (!finding.entityId) return;
        map.set(finding.entityId, [...(map.get(finding.entityId) ?? []), finding]);
      };
      for (const finding of [...report.blocking, ...report.warnings]) add(finding);

      // Pensum overruns reach the Kontrola page as a single aggregate line,
      // because 37 of them at full length buried the warnings that mattered.
      // Beside the teacher's own row there is no such crowd, so the per-teacher
      // figure is reconstructed here from the load table the aggregate points at.
      for (const teacher of report.teachers) {
        if (teacher.pensum === null || teacher.pensum <= 0) continue;
        if (teacher.allocated <= teacher.pensum) continue;
        add({
          severity: "Warning",
          subject: teacher.name,
          message: `${teacher.allocated} godz. przy pensum ${teacher.pensum} — ${teacher.allocated - teacher.pensum} nadgodzin`,
          entityId: teacher.id,
        });
      }
      for (const cls of report.classes) {
        if (cls.occupied <= cls.capacity) continue;
        add({
          severity: "Blocking",
          subject: cls.name,
          message: `${cls.occupied} godz. przy ${cls.capacity} dostępnych w tygodniu`,
          entityId: cls.id,
        });
      }
      setByEntity(map);
    } catch {
      // Findings are an aid, not the point of the screen: if they cannot be
      // fetched the page still works without them.
      setByEntity(new Map());
    }
  }, [schoolId]);

  useEffect(() => {
    load();
  }, [load]);

  return { findingsFor: (id: string) => byEntity.get(id) ?? [], reloadFindings: load };
}
