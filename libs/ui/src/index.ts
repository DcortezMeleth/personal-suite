export { AppHeader } from "./components/AppHeader";
export { PageLayout } from "./components/PageLayout";
export { DataCard } from "./components/DataCard";
export { AlertBanner } from "./components/AlertBanner";
export { ConfirmDialog } from "./components/ConfirmDialog";
export { DatePicker } from "./components/DatePicker";
export { MonthPicker } from "./components/MonthPicker";
export { useBackdropDismiss } from "./hooks/useBackdropDismiss";
export { colors, typography } from "./theme/tokens";

// A consumer that only ever writes props inline can infer these, but one that
// holds a value in state or passes it through its own props needs to name the
// type. Until the library was type-checked at all there was no way to notice
// that none of them were reachable.
export type { AppHeaderProps, NavItem } from "./components/AppHeader";
export type { PageLayoutProps } from "./components/PageLayout";
export type { DataCardProps } from "./components/DataCard";
export type { AlertBannerProps, AlertLevel } from "./components/AlertBanner";
export type { ConfirmDialogProps } from "./components/ConfirmDialog";
export type { DatePickerProps } from "./components/DatePicker";
export type { MonthPickerProps } from "./components/MonthPicker";
export type { BackdropDismissProps } from "./hooks/useBackdropDismiss";
// CalendarValue and singleDate stay internal on purpose. The type resolves into
// react-calendar's dist/shared/types.js, which is not a portable reference, and
// both pickers deliberately speak "YYYY-MM-DD" strings at their edges — nothing
// outside this library has a calendar value to narrow.
export type { Colors, Typography } from "./theme/tokens";
