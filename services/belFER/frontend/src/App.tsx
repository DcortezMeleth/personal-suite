import { Route, Routes, useLocation } from "react-router-dom";
import { PageLayout } from "@delfin/ui";
import { AllocationPage } from "./pages/AllocationPage";
import { ClassesPage } from "./pages/ClassesPage";
import { ConfigurationPage } from "./pages/ConfigurationPage";
import { ImportPage } from "./pages/ImportPage";
import { PlanPage } from "./pages/PlanPage";
import { TuitionPage } from "./pages/TuitionPage";
import { CrossClassPage } from "./pages/CrossClassPage";
import { ResourcesPage } from "./pages/ResourcesPage";
import { SchoolPage } from "./pages/SchoolPage";
import { ValidationPage } from "./pages/ValidationPage";
import { TeachersPage } from "./pages/TeachersPage";

// The interface is Polish, because the people using it are.
//
// Grouped and down the side rather than across the top: eleven pages, several
// of them phrases, outgrew a row. The order is the order of the work — data
// arrives, gets assigned, becomes a plan — so the sidebar reads as the job
// rather than as an alphabet.
const NAV_GROUPS = [
  {
    label: "Dane szkoły",
    items: [
      { label: "Import", href: "/import" },
      { label: "Szkoła", href: "/" },
      { label: "Przedmioty i sale", href: "/zasoby" },
      { label: "Nauczyciele", href: "/nauczyciele" },
      { label: "Oddziały", href: "/oddzialy" },
    ],
  },
  {
    label: "Przydziały",
    items: [
      { label: "Przydziały", href: "/przydzialy" },
      { label: "Zajęcia międzyoddziałowe", href: "/miedzyoddzialowe" },
      { label: "Nauczanie indywidualne", href: "/indywidualne" },
    ],
  },
  {
    label: "Plan",
    items: [
      { label: "Kontrola", href: "/kontrola" },
      { label: "Konfiguracja", href: "/konfiguracja" },
      { label: "Plan lekcji", href: "/plan" },
    ],
  },
];

export default function App() {
  const { pathname } = useLocation();

  const navGroups = NAV_GROUPS.map((group) => ({
    ...group,
    items: group.items.map((item) => ({ ...item, active: item.href === pathname })),
  }));

  return (
    <PageLayout title="belFER" navGroups={navGroups}>
      <Routes>
        <Route path="/" element={<SchoolPage />} />
        <Route path="/zasoby" element={<ResourcesPage />} />
        <Route path="/nauczyciele" element={<TeachersPage />} />
        <Route path="/oddzialy" element={<ClassesPage />} />
        <Route path="/przydzialy" element={<AllocationPage />} />
        <Route path="/miedzyoddzialowe" element={<CrossClassPage />} />
        <Route path="/indywidualne" element={<TuitionPage />} />
        <Route path="/plan" element={<PlanPage />} />
        <Route path="/import" element={<ImportPage />} />
        <Route path="/konfiguracja" element={<ConfigurationPage />} />
        <Route path="/kontrola" element={<ValidationPage />} />
      </Routes>
    </PageLayout>
  );
}
