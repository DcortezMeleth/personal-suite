// TypeScript 7 rejects a side-effect import it has no declaration for
// (TS2882), and a stylesheet is exactly that: MonthPicker and DatePicker pull
// in calendar.css for Vite to bundle, with nothing to import from it.
declare module "*.css";
