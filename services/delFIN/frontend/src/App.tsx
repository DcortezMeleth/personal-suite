import { Route, Routes, useLocation } from "react-router-dom";
import { PageLayout } from "@delfin/ui";
import { SpendingDashboard } from "./pages/SpendingDashboard";
import { InvestmentDashboard } from "./pages/InvestmentDashboard";
import { TransactionsPage } from "./pages/TransactionsPage";
import { RulesPage } from "./pages/RulesPage";

const NAV_ITEMS = [
  { label: "Spending", href: "/" },
  { label: "Transactions", href: "/transactions" },
  { label: "Rules", href: "/rules" },
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
        <Route path="/transactions" element={<TransactionsPage />} />
        <Route path="/rules" element={<RulesPage />} />
        <Route path="/investments" element={<InvestmentDashboard />} />
      </Routes>
    </PageLayout>
  );
}
