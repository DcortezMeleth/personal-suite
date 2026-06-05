import { Route, Routes, useLocation } from "react-router-dom";
import { PageLayout } from "@delfin/ui";
import { SpendingDashboard } from "./pages/SpendingDashboard";
import { InvestmentDashboard } from "./pages/InvestmentDashboard";

const NAV_ITEMS = [
  { label: "Spending", href: "/" },
  { label: "Investments", href: "/investments" },
];

export default function App() {
  const { pathname } = useLocation();

  const navItems = NAV_ITEMS.map((item) => ({
    ...item,
    active: item.href === pathname,
  }));

  return (
    <PageLayout title="delFIN" navItems={navItems}>
      <Routes>
        <Route path="/" element={<SpendingDashboard />} />
        <Route path="/investments" element={<InvestmentDashboard />} />
      </Routes>
    </PageLayout>
  );
}
