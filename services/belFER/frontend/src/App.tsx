import { Route, Routes, useLocation } from "react-router-dom";
import { PageLayout } from "@delfin/ui";
import { ResourcesPage } from "./pages/ResourcesPage";
import { SchoolPage } from "./pages/SchoolPage";

// The interface is Polish, because the people using it are.
const NAV_ITEMS = [
  { label: "Szkoła", href: "/" },
  { label: "Przedmioty i sale", href: "/zasoby" },
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
      </Routes>
    </PageLayout>
  );
}
