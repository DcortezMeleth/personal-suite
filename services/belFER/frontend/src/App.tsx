import { Route, Routes, useLocation } from "react-router-dom";
import { PageLayout } from "@delfin/ui";
import { AllocationPage } from "./pages/AllocationPage";
import { ClassesPage } from "./pages/ClassesPage";
import { ImportPage } from "./pages/ImportPage";
import { CrossClassPage } from "./pages/CrossClassPage";
import { ResourcesPage } from "./pages/ResourcesPage";
import { SchoolPage } from "./pages/SchoolPage";
import { ValidationPage } from "./pages/ValidationPage";
import { TeachersPage } from "./pages/TeachersPage";

// The interface is Polish, because the people using it are.
const NAV_ITEMS = [
  { label: "Szkoła", href: "/" },
  { label: "Przedmioty i sale", href: "/zasoby" },
  { label: "Nauczyciele", href: "/nauczyciele" },
  { label: "Oddziały", href: "/oddzialy" },
  { label: "Przydziały", href: "/przydzialy" },
  { label: "Zajęcia międzyoddziałowe", href: "/miedzyoddzialowe" },
  { label: "Import", href: "/import" },
  { label: "Kontrola", href: "/kontrola" },
];

export default function App() {
  const { pathname } = useLocation();

  const navItems = NAV_ITEMS.map((item) => ({
    ...item,
    active: item.href === pathname,
  }));

  return (
    <PageLayout title="belFER" navItems={navItems}>
      <Routes>
        <Route path="/" element={<SchoolPage />} />
        <Route path="/zasoby" element={<ResourcesPage />} />
        <Route path="/nauczyciele" element={<TeachersPage />} />
        <Route path="/oddzialy" element={<ClassesPage />} />
        <Route path="/przydzialy" element={<AllocationPage />} />
        <Route path="/miedzyoddzialowe" element={<CrossClassPage />} />
        <Route path="/import" element={<ImportPage />} />
        <Route path="/kontrola" element={<ValidationPage />} />
      </Routes>
    </PageLayout>
  );
}
