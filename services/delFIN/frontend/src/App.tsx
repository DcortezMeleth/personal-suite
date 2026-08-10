import { Route, Routes, useLocation } from "react-router-dom";
import { PageLayout } from "@delfin/ui";
import { SpendingDashboard } from "./pages/SpendingDashboard";
import { InvestmentDashboard } from "./pages/InvestmentDashboard";
import { TransactionsPage } from "./pages/TransactionsPage";
import { RulesPage } from "./pages/RulesPage";
import { CategoriesPage } from "./pages/CategoriesPage";
import { TagsPage } from "./pages/TagsPage";

const NAV_ITEMS = [
  { label: "Dashboard", href: "/" },
  { label: "Transactions", href: "/transactions" },
  { label: "Rules", href: "/rules" },
  { label: "Categories", href: "/categories" },
  { label: "Tags", href: "/tags" },
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
        <Route path="/categories" element={<CategoriesPage />} />
        <Route path="/tags" element={<TagsPage />} />
        <Route path="/investments" element={<InvestmentDashboard />} />
      </Routes>
    </PageLayout>
  );
}
