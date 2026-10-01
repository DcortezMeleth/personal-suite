import { Route, Routes, useLocation } from "react-router-dom";
import { PageLayout } from "@delfin/ui";
import { PlanPage } from "./pages/PlanPage";

// The interface is Polish, because the people using it are.
const NAV_ITEMS = [
  { label: "Plan", href: "/" },
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
        <Route path="/" element={<PlanPage />} />
      </Routes>
    </PageLayout>
  );
}
